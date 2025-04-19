package com.example.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core service that:
 *  • Parses incoming JSON (LoadPipeline & MDR)
 *  • Buckets by LoadID over time windows
 *  • Aggregates & flushes windows every second
 *  • Archives raw & merged JSON
 *  • Throttles outbound HTTP
 *  • Dispatches to configured REST endpoints
 *  • Reports to daily CSVs
 *  • Dead‑letters failures
 */
@Service
public class AggregatorService {

    // JSON parser
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Buckets keyed by LoadID
    private final Map<String, MessageBucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, MessageBucket> mdrBuckets = new ConcurrentHashMap<>();

    // Prevents redundant mkdirs
    private final Set<String> createdDirs = ConcurrentHashMap.newKeySet();

    // Thread‑pool for async archiving
    private final ExecutorService archiveExecutor = Executors.newFixedThreadPool(4);

    // ─── Configuration (injected from external-config.properties) ─────────────

    // LoadPipeline dispatch settings
    @Value("${target.loadpipeline.rest.url}")              private String lpRestUrl;
    @Value("${target.loadpipeline.rest.enabled:true}")     private boolean lpRestEnabled;

    // MDR dispatch settings
    @Value("${target.mdr.rest.url}")                       private String mdrRestUrl;
    @Value("${target.mdr.rest.enabled:true}")              private boolean mdrRestEnabled;

    // Archiving roots
    @Value("${archive.incoming.root}")                     private String incomingArchiveRoot;
    @Value("${archive.merged.root}")                       private String mergedArchiveRoot;

    // Dead‑letter directory
    @Value("${deadletterqueue}")                           private String deadLetterQueue;

    // CSV report prefixes
    @Value("${report.file.prefix:report_}")                private String lpReportPrefix;
    @Value("${report.mdr.file.prefix:report_mdr_}")        private String mdrReportPrefix;

    // Time windows (seconds)
    @Value("${consolidation.timeframe}")                   private long lpWindowSec;
    @Value("${mdr.consolidation.timeframe}")               private long mdrWindowSec;

    // Immediate size‑based flush
    @Value("${bucket.flush.size:0}")                       private int bucketFlushSize;

    // Archiving toggle
    @Value("${archiving.enabled:true}")                    private boolean archivingEnabled;

    // Throttling settings
    @Value("${throttling.enabled:true}")                   private boolean throttlingEnabled;
    @Value("${throttling.limit:5}")                        private int throttlingLimit;

    // (File‑based ingestion props omitted for brevity)

    // For simple per‑second throttling
    private final Object throttleLock = new Object();
    private long lastThrottleReset = System.currentTimeMillis();
    private int throttleCount = 0;

    private final RestTemplate restTemplate;

    public AggregatorService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ─── LoadPipeline Flow ─────────────────────────────────────────────────────

    /**
     * Handle an incoming LoadPipeline JSON message.
     *
     * @param message raw JSON
     * @param source  "port" or "file"
     */
    public void processIncomingMessage(String message, String source) {
        if ("port".equalsIgnoreCase(source)) {
            // could track last‑port time if needed
        }
        enqueueLoadPipeline(message);
    }

    /**
     * Parses and enqueues a LoadPipeline payload into its bucket.
     */
    private void enqueueLoadPipeline(String message) {
        // Archive raw if enabled
        if (archivingEnabled) {
            archiveExecutor.submit(() -> archive(message, "incoming"));
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode arr  = root.get("LoadPipeline");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                System.err.println("Invalid LoadPipeline payload");
                return;
            }
            String loadId = arr.get(0).get("LoadID").asText();

            buckets.compute(loadId, (id, bucket) -> {
                if (bucket == null) bucket = new MessageBucket();
                bucket.add(arr);
                // optional size‑based flush
                if (bucketFlushSize > 0 && bucket.size() >= bucketFlushSize) {
                    flushLoadPipeline(id, bucket);
                    return null;
                }
                return bucket;
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Scheduled every second to flush any LoadPipeline bucket
     * whose window has expired.
     */
    @Scheduled(fixedDelay = 1000)
    public void flushLpBuckets() {
        long now = System.currentTimeMillis();
        buckets.forEach((loadId, bucket) -> {
            if (now - bucket.getStart() >= lpWindowSec * 1000) {
                flushLoadPipeline(loadId, bucket);
            }
        });
    }

    /**
     * Flushes, aggregates, archives, throttles, dispatches, reports,
     * and dead‑letters one LoadPipeline bucket.
     */
    private void flushLoadPipeline(String loadId, MessageBucket bucket) {
        // 1) Merge JSON
        ObjectNode merged = objectMapper.createObjectNode();
        ArrayNode outArr = merged.putArray("LoadPipeline");
        bucket.getAll().forEach(node -> node.forEach(outArr::add));
        String payload = merged.toString();

        // 2) Archive merged
        if (archivingEnabled) {
            archiveExecutor.submit(() -> archive(payload, "merged"));
        }

        // 3) Throttle & dispatch
        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("mm"));
        int count   = bucket.size();
        if (lpRestEnabled) {
            if (throttlingEnabled) throttle();
            doHttpPost(lpRestUrl, payload);
            writeCsv(lpReportPrefix, loadId, count, minute, "SENT");
        } else {
            writeCsv(lpReportPrefix, loadId, count, minute, "SKIPPED");
        }

        // 4) Remove bucket
        buckets.remove(loadId);
    }

    // ─── MDR Flow ────────────────────────────────────────────────────────────────

    public void processIncomingMdrMessage(String message, String source) {
        enqueueMdr(message);
    }

    private void enqueueMdr(String message) {
        if (archivingEnabled) {
            archiveExecutor.submit(() -> archive(message, "incoming"));
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode arr  = root.get("MultiDestinationRake");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                System.err.println("Invalid MDR payload");
                return;
            }
            String loadId = arr.get(0).get("LoadID").asText();

            mdrBuckets.compute(loadId, (id, bucket) -> {
                if (bucket == null) bucket = new MessageBucket();
                bucket.add(arr);
                return bucket;
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void flushMdrBuckets() {
        long now = System.currentTimeMillis();
        mdrBuckets.forEach((loadId, bucket) -> {
            if (now - bucket.getStart() >= mdrWindowSec * 1000) {
                flushMdr(loadId, bucket);
            }
        });
    }

    private void flushMdr(String loadId, MessageBucket bucket) {
        ObjectNode merged = objectMapper.createObjectNode();
        ArrayNode outArr = merged.putArray("MultiDestinationRake");
        bucket.getAll().forEach(node -> node.forEach(outArr::add));
        String payload = merged.toString();

        if (archivingEnabled) {
            archiveExecutor.submit(() -> archive(payload, "merged"));
        }

        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("mm"));
        int count   = bucket.size();
        if (mdrRestEnabled) {
            if (throttlingEnabled) throttle();
            doHttpPost(mdrRestUrl, payload);
            writeCsv(mdrReportPrefix, loadId, count, minute, "SENT");
        } else {
            writeCsv(mdrReportPrefix, loadId, count, minute, "SKIPPED");
        }

        mdrBuckets.remove(loadId);
    }

    // ─── Common Helpers ────────────────────────────────────────────────────────

    /** Simple per‑second throttling. */
    private void throttle() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            if (now - lastThrottleReset >= 1000) {
                lastThrottleReset = now;
                throttleCount = 0;
            }
            if (throttleCount >= throttlingLimit) {
                try { Thread.sleep(1000 - (now - lastThrottleReset)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                lastThrottleReset = System.currentTimeMillis();
                throttleCount = 0;
            }
            throttleCount++;
        }
    }

    /** Performs the HTTP POST and dead‑letters on exception. */
    private void doHttpPost(String url, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Accept-Encoding", "gzip,deflate");
        HttpEntity<String> req = new HttpEntity<>(json, h);
        try {
            restTemplate.exchange(url, HttpMethod.POST, req, String.class);
        } catch (Exception ex) {
            ex.printStackTrace();
            archive(json, deadLetterQueue);
        }
    }

    /**
     * Archives a JSON string under the given type or queue.
     * type = "incoming", "merged", or deadLetterQueue name.
     */
    private void archive(String message, String type) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String sub  = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("HH"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("mm"));
            String root = "incoming".equals(type) || "merged".equals(type)
                    ? ("incoming".equals(type) ? incomingArchiveRoot : mergedArchiveRoot)
                    : type;
            String base = root + File.separator + sub;
            if (createdDirs.add(base)) {
                Files.createDirectories(Paths.get(base));
            }
            String fn = base + File.separator
                    + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".json";
            try (FileWriter fw = new FileWriter(fn)) {
                fw.write(message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Appends a line to the daily CSV (prefix + yyyyMMdd + .csv).
     */
    private void writeCsv(String prefix, String loadId, int count, String minute, String status) {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String file = prefix + date + ".csv";
        boolean needHeader = !new File(file).exists();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
            if (needHeader) {
                bw.write("Minute,LoadID,EntryCount,Status");
                bw.newLine();
            }
            bw.write(String.join(",", minute, loadId, String.valueOf(count), status));
            bw.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Gracefully shuts down the archive executor. */
    @PreDestroy
    public void shutdown() {
        archiveExecutor.shutdown();
    }

    // ─── Bucket Data Class ─────────────────────────────────────────────────────

    /**
     * Holds messages for one LoadID along with first‑arrival timestamp.
     */
    private static class MessageBucket {
        private final long startTime = System.currentTimeMillis();
        private final Queue<JsonNode> queue = new ConcurrentLinkedQueue<>();

        /** Adds one JSON array node to this bucket. */
        void add(JsonNode arr) {
            queue.add(arr);
        }
        /** @return timestamp when first message arrived */
        long getStart() {
            return startTime;
        }
        /** @return all queued JSON array nodes */
        List<JsonNode> getAll() {
            return new ArrayList<>(queue);
        }
        /** @return number of messages in this bucket */
        int size() {
            return queue.size();
        }
    }
}
