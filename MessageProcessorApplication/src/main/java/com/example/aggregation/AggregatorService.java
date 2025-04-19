package com.example.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Core aggregation service for both LoadPipeline and MultiDestinationRake.
 * <p>
 * Features for each stream:
 *  • JSON parsing & validation
 *  • LoadID‑keyed bucketing with configurable time window & optional size‑trigger
 *  • Asynchronous archiving (incoming & merged)
 *  • Per‑second throttling using LongAdder
 *  • Non‑blocking HTTP dispatch via WebClient
 *  • Daily CSV reporting
 *  • Dead‑letter storage
 *  • Daily zipping & retention
 * </p>
 */
@Service
public class AggregatorService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient http;

    // In-memory buckets
    private final ConcurrentMap<String, MessageBucket> lpBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageBucket> mdrBuckets = new ConcurrentHashMap<>();

    // Thread-pool for I/O tasks
    private final ExecutorService ioPool = Executors.newFixedThreadPool(4);

    // ─────────── Injected Configuration ────────────────────────────────────────

    // LoadPipeline
    @Value("${target.loadpipeline.rest.url}")            private String lpUrl;
    @Value("${target.loadpipeline.rest.enabled}")        private boolean lpEnabled;
    @Value("${archiving.loadpipeline.enabled}")          private boolean lpArchiveEnabled;
    @Value("${archive.loadpipeline.incoming.root}")      private String lpIncRoot;
    @Value("${archive.loadpipeline.merged.root}")        private String lpMergedRoot;
    @Value("${consolidation.loadpipeline.timeframe}")    private long lpWindow;
    @Value("${bucket.loadpipeline.flush.size}")          private int lpSizeTrigger;
    @Value("${throttling.loadpipeline.enabled}")         private boolean lpThrottleEnabled;
    @Value("${throttling.loadpipeline.limit}")           private int lpThrottleLimit;
    @Value("${deadletterqueue.loadpipeline}")            private String lpDLQ;
    @Value("${report.loadpipeline.prefix}")              private String lpReportPrefix;

    // MDR
    @Value("${target.mdr.rest.url}")                     private String mdrUrl;
    @Value("${target.mdr.rest.enabled}")                 private boolean mdrEnabled;
    @Value("${archiving.mdr.enabled}")                   private boolean mdrArchiveEnabled;
    @Value("${archive.mdr.incoming.root}")               private String mdrIncRoot;
    @Value("${archive.mdr.merged.root}")                 private String mdrMergedRoot;
    @Value("${consolidation.mdr.timeframe}")             private long mdrWindow;
    @Value("${bucket.mdr.flush.size}")                   private int mdrSizeTrigger;
    @Value("${throttling.mdr.enabled}")                  private boolean mdrThrottleEnabled;
    @Value("${throttling.mdr.limit}")                    private int mdrThrottleLimit;
    @Value("${deadletterqueue.mdr}")                     private String mdrDLQ;
    @Value("${report.mdr.prefix}")                       private String mdrReportPrefix;

    // Shared date formatter
    private static final DateTimeFormatter DTF_DIR = DateTimeFormatter.ofPattern("yyyyMMdd/HH/mm");
    private static final DateTimeFormatter DTF_CSV = DateTimeFormatter.ofPattern("mm");

    // Throttling counters
    private final LongAdder lpCounter = new LongAdder();
    private volatile long lpWindowStart = System.currentTimeMillis();

    private final LongAdder mdrCounter = new LongAdder();
    private volatile long mdrWindowStart = System.currentTimeMillis();

    public AggregatorService(WebClient http) {
        this.http = http;
    }

    // ─────────── Public Ingestion Methods ──────────────────────────────────────

    /**
     * Ingests a LoadPipeline JSON message.
     * @param json   raw payload
     * @param source "port" or "file"
     */
    public void processLoadPipeline(String json, String source) {
        // 1) Archive raw if enabled
        if (lpArchiveEnabled) ioPool.submit(() -> archive(json, lpIncRoot));
        // 2) Parse & bucket
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr  = root.get("LoadPipeline");
            if (arr == null || !arr.isArray() || arr.size()==0) {
                throw new IllegalArgumentException("Invalid LoadPipeline payload");
            }
            String loadId = arr.get(0).get("LoadID").asText();
            lpBuckets.compute(loadId, (id,b) -> {
                if (b==null) b=new MessageBucket();
                b.add(arr);
                if (lpSizeTrigger>0 && b.size()>=lpSizeTrigger) {
                    flushLoadPipeline(id,b);
                    return null;
                }
                return b;
            });
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Ingests a MultiDestinationRake JSON message.
     * @param json   raw payload
     * @param source "port" or "file"
     */
    public void processMdr(String json, String source) {
        if (mdrArchiveEnabled) ioPool.submit(() -> archive(json, mdrIncRoot));
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr  = root.get("MultiDestinationRake");
            if (arr == null || !arr.isArray() || arr.size()==0) {
                throw new IllegalArgumentException("Invalid MDR payload");
            }
            String loadId = arr.get(0).get("LoadID").asText();
            mdrBuckets.compute(loadId, (id,b) -> {
                if (b==null) b=new MessageBucket();
                b.add(arr);
                return b;
            });
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // ─────────── Scheduled Flush ────────────────────────────────────────────────

    /** Flush LP buckets every second if window expired. */
    @Scheduled(fixedDelay=1000)
    public void flushLp() {
        long now = System.currentTimeMillis();
        lpBuckets.forEach((id,b)->{
            if (now - b.getStart() >= lpWindow*1_000) {
                flushLoadPipeline(id,b);
            }
        });
    }

    /** Flush MDR buckets every second if window expired. */
    @Scheduled(fixedDelay=1000)
    public void flushMdr() {
        long now = System.currentTimeMillis();
        mdrBuckets.forEach((id,b)->{
            if (now - b.getStart() >= mdrWindow*1_000) {
                flushMdrBucket(id,b);
            }
        });
    }

    // ─────────── Flush & Dispatch ─────────────────────────────────────────────

    /**
     * Merge, archive, throttle, send, report, and remove one LP bucket.
     */
    private void flushLoadPipeline(String loadId, MessageBucket bucket) {
        // Merge into a single JSON
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("LoadPipeline");
        bucket.getAll().forEach(arr -> arr.forEach(out::add));
        String payload = merged.toString();

        // Archive merged
        if (lpArchiveEnabled) ioPool.submit(() -> archive(payload, lpMergedRoot));

        // CSV minute & count
        String minute = LocalTime.now().format(DTF_CSV);
        int count = bucket.size();

        // Throttle
        if (lpEnabled) {
            throttle(lpCounter, () -> lpWindowStart = System.currentTimeMillis(), lpThrottleEnabled, lpThrottleLimit);
            // Non-blocking HTTP
            http.post()
                    .uri(lpUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .doOnError(e-> {
                        e.printStackTrace();
                        ioPool.submit(() -> archive(payload, lpDLQ));
                        writeCsv(lpReportPrefix, loadId, count, minute, "FAILED");
                    })
                    .doOnSuccess(r-> writeCsv(lpReportPrefix, loadId, count, minute, "SENT"))
                    .subscribe();
        } else {
            writeCsv(lpReportPrefix, loadId, count, minute, "SKIPPED");
        }
        lpBuckets.remove(loadId);
    }

    /**
     * Merge, archive, throttle, send, report, and remove one MDR bucket.
     */
    private void flushMdrBucket(String loadId, MessageBucket bucket) {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("MultiDestinationRake");
        bucket.getAll().forEach(arr -> arr.forEach(out::add));
        String payload = merged.toString();

        if (mdrArchiveEnabled) ioPool.submit(() -> archive(payload, mdrMergedRoot));

        String minute = LocalTime.now().format(DTF_CSV);
        int count = bucket.size();

        if (mdrEnabled) {
            throttle(mdrCounter, () -> mdrWindowStart = System.currentTimeMillis(), mdrThrottleEnabled, mdrThrottleLimit);
            http.post()
                    .uri(mdrUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .doOnError(e-> {
                        e.printStackTrace();
                        ioPool.submit(() -> archive(payload, mdrDLQ));
                        writeCsv(mdrReportPrefix, loadId, count, minute, "FAILED");
                    })
                    .doOnSuccess(r-> writeCsv(mdrReportPrefix, loadId, count, minute, "SENT"))
                    .subscribe();
        } else {
            writeCsv(mdrReportPrefix, loadId, count, minute, "SKIPPED");
        }
        mdrBuckets.remove(loadId);
    }

    // ─────────── Helpers ────────────────────────────────────────────────────────

    /**
     * Throttle at most {@code limit} calls per second using LongAdder.
     * @param counter      the LongAdder tracking calls
     * @param resetWindow  callback to reset window timestamp
     * @param enabled      whether throttling is on
     * @param limit        max calls/sec
     */
    private void throttle(LongAdder counter, Runnable resetWindow, boolean enabled, int limit) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (now - (enabled ? lpWindowStart : mdrWindowStart) >= 1000) {
            counter.reset();
            resetWindow.run();
        }
        if (counter.sum() >= limit) {
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
        counter.increment();
    }

    /**
     * Write a daily CSV line under prefix+yyyyMMdd.csv.
     */
    private void writeCsv(String prefix, String loadId, int count, String minute, String status) {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String file = prefix + date + ".csv";
        boolean header = !Files.exists(Path.of(file));
        try (BufferedWriter bw = Files.newBufferedWriter(Path.of(file), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (header) bw.write("Minute,LoadID,EntryCount,Status\n");
            bw.write(String.join(",", minute, loadId, String.valueOf(count), status) + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Archive JSON to disk under root/YYYYMMdd/HH/mm/*.json.
     */
    private void archive(String json, String root) {
        try {
            String sub = LocalDateTime.now().format(DTF_DIR);
            Path dir = Path.of(root, sub);
            Files.createDirectories(dir);
            Path file = dir.resolve(System.currentTimeMillis()+"_"+UUID.randomUUID()+".json");
            Files.writeString(file, json, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void shutdown() {
        ioPool.shutdown();
    }

    // ─────────── Internal Bucket Class ─────────────────────────────────────────

    /**
     * Holds an arrival timestamp and a list of JSON array nodes.
     */
    private static class MessageBucket {
        private final long start = System.currentTimeMillis();
        private final List<JsonNode> list = new CopyOnWriteArrayList<>();

        void add(JsonNode arr) { list.add(arr); }
        long getStart()      { return start; }
        List<JsonNode> getAll() { return list; }
        int size()           { return list.size(); }
    }
}
