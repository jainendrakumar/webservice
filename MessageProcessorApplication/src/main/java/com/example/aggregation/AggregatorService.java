// src/main/java/com/example/aggregation/AggregatorService.java
package com.example.aggregation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * Core service that now supports BOTH LoadPipeline (on port 9021)
 * and MultiDestinationRake (on port 9022) with identical
 * bucketing/archiving/throttling/reporting semantics.
 */
@Service
public class AggregatorService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // buckets for LoadPipeline and for MultiDestinationRake
    private final Map<String, MessageBucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, MessageBucket> mdrBuckets = new ConcurrentHashMap<>();

    private final Set<String> createdDirs = ConcurrentHashMap.newKeySet();
    private final ExecutorService archiveExecutor = Executors.newFixedThreadPool(4);

    @Value("${target.rest.url}")
    private String targetRestUrl;
    @Value("${target.rest.enabled:true}")
    private boolean targetRestEnabled;

    @Value("${target.mdr.rest.url}")
    private String targetMdrRestUrl;
    @Value("${target.mdr.rest.enabled:true}")
    private boolean targetMdrRestEnabled;

    @Value("${archive.incoming.root}")
    private String incomingArchiveRoot;
    @Value("${archive.merged.root}")
    private String mergedArchiveRoot;
    @Value("${deadletterqueue}")
    private String deadLetterQueue;

    @Value("${report.file.prefix:report_}")
    private String reportFilePrefix;
    @Value("${report.mdr.file.prefix:report_mdr_}")
    private String mdrReportFilePrefix;

    @Value("${consolidation.timeframe}")
    private long consolidationTimeFrame;
    @Value("${mdr.consolidation.timeframe:5}")
    private long mdrConsolidationTimeFrame;

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

    public AggregatorService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    //── LoadPipeline ────────────────────────────────────────────────────────────

    public void processIncomingMessage(String message, String source) {
        if ("port".equalsIgnoreCase(source)) {
            lastPortMessageTime = System.currentTimeMillis();
        }
        processMessageInternal(message);
    }

    public void processIncomingMessage(String message) {
        processIncomingMessage(message, "unknown");
    }

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
            String loadId = loadPipeline.get(0).get("LoadID").asText();

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

    @Scheduled(fixedDelay = 1000)
    public void flushBuckets() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, MessageBucket> e : buckets.entrySet()) {
            MessageBucket bucket = e.getValue();
            if (now - bucket.getStartTime() >= consolidationTimeFrame * 1000) {
                flushBucket(e.getKey(), bucket);
            }
        }
    }

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
            } catch (Exception ex) {
                ex.printStackTrace();
                moveToDeadLetter(payload);
                writeReport(loadId, count, minute, "FAILED");
            }
        } else {
            writeReport(loadId, count, minute, "SKIPPED");
        }
        buckets.remove(loadId);
    }

    //── MultiDestinationRake ────────────────────────────────────────────────────

    public void processIncomingMdrMessage(String message, String source) {
        if ("port".equalsIgnoreCase(source)) {
            lastPortMessageTime = System.currentTimeMillis();
        }
        processMdrMessageInternal(message);
    }

    private void processMdrMessageInternal(String message) {
        if (archivingEnabled) {
            archiveExecutor.submit(() -> archiveMessage(message, "incoming"));
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode arr = root.get("MultiDestinationRake");
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                System.err.println("Invalid message: missing or empty MultiDestinationRake");
                return;
            }
            String loadId = arr.get(0).get("LoadID").asText();

            mdrBuckets.compute(loadId, (id, bucket) -> {
                if (bucket == null) bucket = new MessageBucket();
                bucket.addMessage(arr);
                return bucket;
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void flushMdrBuckets() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, MessageBucket> e : mdrBuckets.entrySet()) {
            MessageBucket bucket = e.getValue();
            if (now - bucket.getStartTime() >= mdrConsolidationTimeFrame * 1000) {
                flushMdrBucket(e.getKey(), bucket);
            }
        }
    }

    private void flushMdrBucket(String loadId, MessageBucket bucket) {
        ObjectNode aggregated = objectMapper.createObjectNode();
        ArrayNode arr = objectMapper.createArrayNode();
        bucket.getMessages().forEach(node -> {
            if (node.isArray()) node.forEach(arr::add);
            else arr.add(node);
        });
        aggregated.set("MultiDestinationRake", arr);
        String payload = aggregated.toString();

        if (archivingEnabled) {
            archiveExecutor.submit(() -> archiveMessage(payload, "merged"));
        }

        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("mm"));
        int count = bucket.getMessages().size();

        if (targetMdrRestEnabled) {
            if (throttlingEnabled) throttleIfNeeded();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept-Encoding", "gzip,deflate");
            HttpEntity<String> req = new HttpEntity<>(payload, headers);
            try {
                restTemplate.exchange(targetMdrRestUrl, HttpMethod.POST, req, String.class);
                writeMdrReport(loadId, count, minute, "SENT");
            } catch (Exception ex) {
                ex.printStackTrace();
                moveToDeadLetter(payload);
                writeMdrReport(loadId, count, minute, "FAILED");
            }
        } else {
            writeMdrReport(loadId, count, minute, "SKIPPED");
        }
        mdrBuckets.remove(loadId);
    }

    //── Common helpers ─────────────────────────────────────────────────────────

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

    private void moveToDeadLetter(String message) {
        try {
            String path = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + File.separator + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH"))
                    + File.separator + LocalDateTime.now().format(DateTimeFormatter.ofPattern("mm"));
            archiveToFolder(message, deadLetterQueue, path);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void archiveMessage(String message, String type) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String path = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("HH"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("mm"));
            String root = "incoming".equals(type) ? incomingArchiveRoot : mergedArchiveRoot;
            archiveToFolder(message, root, path);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void archiveToFolder(String message, String rootDir, String path) {
        try {
            String base = rootDir + File.separator + path;
            if (!createdDirs.contains(base)) {
                Files.createDirectories(Paths.get(base));
                createdDirs.add(base);
            }
            String fn = base + File.separator + System.currentTimeMillis()
                    + "_" + UUID.randomUUID() + ".json";
            try (FileWriter fw = new FileWriter(fn)) {
                fw.write(message);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

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
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void writeMdrReport(String loadId, int count, String minute, String status) {
        try {
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String file = mdrReportFilePrefix + date + ".csv";
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
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    @PreDestroy
    public void shutdown() {
        archiveExecutor.shutdown();
    }

    private static class MessageBucket {
        private final long startTime = System.currentTimeMillis();
        private final Queue<JsonNode> messages = new ConcurrentLinkedQueue<>();
        public void addMessage(JsonNode msg) { messages.add(msg); }
        public long getStartTime() { return startTime; }
        public Queue<JsonNode> getMessages() { return messages; }
    }
}
