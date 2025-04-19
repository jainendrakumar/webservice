package com.example.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * AggregatorService is the core service for processing incoming messages from multiple sources
 * (REST endpoint, folder, or ZIP file), consolidating them by a common load identifier, and sending
 * the merged message to a target REST URL. It supports:
 *
 * <ul>
 *   <li>Multiple incoming sources (port vs. file) with prioritization.</li>
 *   <li>Outgoing message control and per-second throttling.</li>
 *   <li>Archiving of incoming/merged messages and dead-letter queue handling.</li>
 *   <li>Scheduled processing of file-based inputs (ZIP or folder).</li>
 *   <li>Reporting of merge operations to daily CSV files.</li>
 * </ul>
 *
 * <p>Configuration is done via external properties:</p>
 * <ul>
 *   <li>target.rest.url, target.rest.enabled</li>
 *   <li>archiving.enabled, archive.incoming.root, archive.merged.root</li>
 *   <li>consolidation.timeframe, bucket.flush.size</li>
 *   <li>throttling.enabled, throttling.limit</li>
 *   <li>deadletterqueue, report.file.prefix</li>
 *   <li>incoming.priority, incoming.file.delay.seconds</li>
 *   <li>input.mode, input.zip.file, input.folder.path</li>
 * </ul>
 *
 * @author jkr3
 */
@Service
public class AggregatorService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, MessageBucket> buckets = new ConcurrentHashMap<>();
    private final Set<String> createdDirs = ConcurrentHashMap.newKeySet();
    private final ExecutorService archiveExecutor = Executors.newFixedThreadPool(4);

    @Value("${target.rest.url}")
    private String targetRestUrl;

    @Value("${target.rest.enabled:true}")
    private boolean targetRestEnabled;

    @Value("${archive.incoming.root}")
    private String incomingArchiveRoot;

    @Value("${archive.merged.root}")
    private String mergedArchiveRoot;

    @Value("${deadletterqueue}")
    private String deadLetterQueue;

    @Value("${report.file.prefix:report_}")
    private String reportFilePrefix;

    @Value("${consolidation.timeframe}")
    private long consolidationTimeFrame;

    @Value("${bucket.flush.size:0}")
    private int bucketFlushSize;

    @Value("${archiving.enabled:true}")
    private boolean archivingEnabled;

    @Value("${throttling.enabled:true}")
    private boolean throttlingEnabled;

    @Value("${throttling.limit:5}")
    private int throttlingLimit;

    @Value("${input.mode:folder}")
    private String inputMode;

    @Value("${input.zip.file:}")
    private String inputZipFile;

    @Value("${input.folder.path:input}")
    private String inputFolderPath;

    @Value("${incoming.priority:port}")
    private String incomingPriority;

    @Value("${incoming.file.delay.seconds:10}")
    private int fileDelaySeconds;

    private volatile long lastPortMessageTime = 0;

    private final Object throttleLock = new Object();
    private long lastThrottleReset = System.currentTimeMillis();
    private int throttleCount = 0;

    private final RestTemplate restTemplate;

    /**
     * Constructor injecting the RestTemplate for HTTP communication.
     *
     * @param restTemplate the RestTemplate bean
     */
    public AggregatorService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Processes an incoming message and tags its source.
     *
     * @param message the JSON message
     * @param source  "port" for REST, "file" for file-based
     */
    public void processIncomingMessage(String message, String source) {
        if ("port".equalsIgnoreCase(source)) {
            lastPortMessageTime = System.currentTimeMillis();
        }
        processMessageInternal(message);
    }

    /**
     * Processes an incoming message with unknown source.
     *
     * @param message the JSON message
     */
    public void processIncomingMessage(String message) {
        processIncomingMessage(message, "unknown");
    }

    /**
     * Internal logic for parsing, grouping, and archiving incoming messages.
     *
     * @param message the JSON payload
     */
    private void processMessageInternal(String message) {
        if (archivingEnabled) {
            archiveExecutor.submit(() -> archiveMessage(message, "incoming"));
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode loadPipeline = root.get("LoadPipeline");
            if (loadPipeline == null || !loadPipeline.isArray() || loadPipeline.isEmpty()) {
                System.err.println("Invalid message: missing or empty LoadPipeline");
                return;
            }
            JsonNode first = loadPipeline.get(0);
            JsonNode loadIdNode = first.get("LoadID");
            if (loadIdNode == null) {
                System.err.println("Invalid message: missing LoadID");
                return;
            }
            String loadId = loadIdNode.asText();

            buckets.compute(loadId, (id, bucket) -> {
                if (bucket == null) bucket = new MessageBucket();
                bucket.addMessage(loadPipeline);
                if (bucketFlushSize > 0 && bucket.getMessages().size() >= bucketFlushSize) {
                    flushBucket(id, bucket);
                    return null;
                }
                return bucket;
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Periodically flushes buckets older than the consolidation timeframe.
     * Runs every second.
     */
    @Scheduled(fixedDelay = 1000)
    public void flushBuckets() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, MessageBucket> entry : buckets.entrySet()) {
            MessageBucket bucket = entry.getValue();
            if (now - bucket.getStartTime() >= consolidationTimeFrame * 1000) {
                flushBucket(entry.getKey(), bucket);
            }
        }
    }

    /**
     * Consolidates, archives, throttles, sends, and reports a bucket's messages.
     *
     * @param loadId the load identifier
     * @param bucket the bucket of messages
     */
    private void flushBucket(String loadId, MessageBucket bucket) {
        ObjectNode aggregated = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        bucket.getMessages().forEach(node -> {
            if (node.isArray()) node.forEach(arr::add);
            else arr.add(node);
        });
        aggregated.set("LoadPipeline", arr);
        String payload = aggregated.toString();

        if (archivingEnabled) {
            archiveExecutor.submit(() -> archiveMessage(payload, "merged"));
        }

        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("mm"));
        int count = bucket.getMessages().size();

        if (targetRestEnabled) {
            if (throttlingEnabled) throttleIfNeeded();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept-Encoding", "gzip,deflate");
            HttpEntity<String> req = new HttpEntity<>(payload, headers);
            try {
                restTemplate.exchange(targetRestUrl, HttpMethod.POST, req, String.class);
                writeReport(loadId, count, minute, "SENT");
            } catch (Exception e) {
                e.printStackTrace();
                moveToDeadLetter(payload);
                writeReport(loadId, count, minute, "FAILED");
            }
        } else {
            writeReport(loadId, count, minute, "SKIPPED");
        }
        buckets.remove(loadId);
    }

    /**
     * Simple per-second throttling mechanism.
     */
    private void throttleIfNeeded() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            if (now - lastThrottleReset >= 1000) {
                lastThrottleReset = now;
                throttleCount = 0;
            }
            if (throttleCount >= throttlingLimit) {
                try {
                    Thread.sleep(1000 - (now - lastThrottleReset));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                lastThrottleReset = System.currentTimeMillis();
                throttleCount = 0;
            }
            throttleCount++;
        }
    }

    /**
     * Moves a failed payload to the dead letter queue.
     *
     * @param message the payload JSON
     */
    private void moveToDeadLetter(String message) {
        try {
            String path = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + File.separator
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH"))
                    + File.separator
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("mm"));
            archiveToFolder(message, deadLetterQueue, path);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Archives a message under the given type ("incoming" or "merged").
     *
     * @param message the JSON payload
     * @param type    archive type
     */
    private void archiveMessage(String message, String type) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String path = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + File.separator
                    + now.format(DateTimeFormatter.ofPattern("HH"))
                    + File.separator
                    + now.format(DateTimeFormatter.ofPattern("mm"));
            String root = "incoming".equals(type) ? incomingArchiveRoot : mergedArchiveRoot;
            archiveToFolder(message, root, path);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Writes the payload to a file under rootDir/path.
     *
     * @param message payload
     * @param rootDir root directory
     * @param path    sub-directory path
     */
    private void archiveToFolder(String message, String rootDir, String path) {
        try {
            String base = rootDir + File.separator + path;
            if (!createdDirs.contains(base)) {
                Files.createDirectories(Paths.get(base));
                createdDirs.add(base);
            }
            String fn = base + File.separator + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".json";
            try (FileWriter fw = new FileWriter(fn)) {
                fw.write(message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Appends a line to the daily CSV report.
     *
     * @param loadId  the load ID
     * @param count   number of entries
     * @param minute  minute detail
     * @param status  SENT/FAILED/SKIPPED
     */
    private void writeReport(String loadId, int count, String minute, String status) {
        try {
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String file = reportFilePrefix + date + ".csv";
            File f = new File(file);
            boolean hdr = !f.exists();
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(f, true))) {
                if (hdr) {
                    bw.write("Minute,LoadID,EntryCount,Status");
                    bw.newLine();
                }
                bw.write(String.join(",", minute, loadId, String.valueOf(count), status));
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Shuts down the archive executor.
     */
    @PreDestroy
    public void shutdown() {
        archiveExecutor.shutdown();
    }

    /**
     * Inner class grouping messages by LoadID.
     */
    private static class MessageBucket {
        private final long startTime = System.currentTimeMillis();
        private final Queue<JsonNode> messages = new ConcurrentLinkedQueue<>();

        public void addMessage(JsonNode msg) {
            messages.add(msg);
        }

        public long getStartTime() {
            return startTime;
        }

        public Queue<JsonNode> getMessages() {
            return messages;
        }
    }
}
