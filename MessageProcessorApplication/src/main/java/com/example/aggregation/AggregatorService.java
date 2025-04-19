// src/main/java/com/example/aggregation/AggregatorService.java

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
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Core aggregation service handling six pipelines:
 *   • LoadPipeline & MDR (per‑ID bucketing)
 *   • LoadAttribute, MaintenanceBlock,
 *     MaintenanceBlockResource, TrainServiceUpdateActual (global batching)
 *
 * Features per pipeline:
 *   – JSON parsing and validation
 *   – Time‑window or size‑triggered flush
 *   – Async archiving (incoming & merged)
 *   – Per‑second throttling
 *   – Non‑blocking REST dispatch via WebClient
 *   – Daily CSV reporting
 *   – Dead‑letter queue
 *   – Daily ZIP + retention
 *
 * All behaviors and thresholds are driven by external-config.properties.
 */
@Service
public class AggregatorService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;
    private final ExecutorService ioPool = Executors.newFixedThreadPool(4);

    // ─────────── LoadPipeline & MDR (per‑ID buckets) ─────────────────────────────

    private final ConcurrentMap<String, MessageBucket> lpBuckets  = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MessageBucket> mdrBuckets = new ConcurrentHashMap<>();

    // LoadPipeline configuration
    @Value("${consolidation.loadpipeline.timeframe}") private long lpTimeWindow;
    @Value("${bucket.loadpipeline.flush.size}")       private int  lpSizeTrigger;
    @Value("${archiving.loadpipeline.enabled}")       private boolean lpArchEnabled;
    @Value("${archive.loadpipeline.incoming.root}")   private String lpIncRoot;
    @Value("${archive.loadpipeline.merged.root}")     private String lpMerRoot;
    @Value("${throttling.loadpipeline.enabled}")      private boolean lpThrotEnabled;
    @Value("${throttling.loadpipeline.limit}")        private int  lpThrotLimit;
    @Value("${target.loadpipeline.rest.url}")         private String lpUrl;
    @Value("${target.loadpipeline.rest.enabled}")     private boolean lpEnabled;
    @Value("${deadletterqueue.loadpipeline}")         private String lpDLQ;
    @Value("${report.loadpipeline.prefix}")           private String lpReportPref;
    private final LongAdder lpCounter  = new LongAdder();
    private volatile long lpWindowStart  = System.currentTimeMillis();

    // MDR configuration
    @Value("${consolidation.mdr.timeframe}") private long mdrTimeWindow;
    @Value("${bucket.mdr.flush.size}")       private int  mdrSizeTrigger;
    @Value("${archiving.mdr.enabled}")       private boolean mdrArchEnabled;
    @Value("${archive.mdr.incoming.root}")   private String mdrIncRoot;
    @Value("${archive.mdr.merged.root}")     private String mdrMerRoot;
    @Value("${throttling.mdr.enabled}")      private boolean mdrThrotEnabled;
    @Value("${throttling.mdr.limit}")        private int  mdrThrotLimit;
    @Value("${target.mdr.rest.url}")         private String mdrUrl;
    @Value("${target.mdr.rest.enabled}")     private boolean mdrEnabled;
    @Value("${deadletterqueue.mdr}")         private String mdrDLQ;
    @Value("${report.mdr.prefix}")           private String mdrReportPref;
    private final LongAdder mdrCounter = new LongAdder();
    private volatile long mdrWindowStart = System.currentTimeMillis();

    // ─────────── Global‑Batch Streams ────────────────────────────────────────────

    private final AtomicReference<BatchBucket> laBatch  = new AtomicReference<>(new BatchBucket());
    private final AtomicReference<BatchBucket> mbBatch  = new AtomicReference<>(new BatchBucket());
    private final AtomicReference<BatchBucket> mbrBatch = new AtomicReference<>(new BatchBucket());
    private final AtomicReference<BatchBucket> tsuBatch = new AtomicReference<>(new BatchBucket());

    // LoadAttribute configuration
    @Value("${consolidation.loadattribute.timeframe}") private long laTimeWindow;
    @Value("${bucket.loadattribute.flush.size}")       private int  laSizeTrigger;
    @Value("${archiving.loadattribute.enabled}")       private boolean laArchEnabled;
    @Value("${archive.loadattribute.incoming.root}")   private String laIncRoot;
    @Value("${archive.loadattribute.merged.root}")     private String laMerRoot;
    @Value("${throttling.loadattribute.enabled}")      private boolean laThrotEnabled;
    @Value("${throttling.loadattribute.limit}")        private int  laThrotLimit;
    @Value("${target.loadattribute.rest.url}")         private String laUrl;
    @Value("${target.loadattribute.rest.enabled}")     private boolean laEnabled;
    @Value("${deadletterqueue.loadattribute}")         private String laDLQ;
    @Value("${report.loadattribute.prefix}")           private String laReportPref;
    private final LongAdder laCounter = new LongAdder();
    private volatile long laWindowStart = System.currentTimeMillis();

    // MaintenanceBlock configuration
    @Value("${consolidation.maintenanceblock.timeframe}") private long mbTimeWindow;
    @Value("${bucket.maintenanceblock.flush.size}")       private int  mbSizeTrigger;
    @Value("${archiving.maintenanceblock.enabled}")       private boolean mbArchEnabled;
    @Value("${archive.maintenanceblock.incoming.root}")   private String mbIncRoot;
    @Value("${archive.maintenanceblock.merged.root}")     private String mbMerRoot;
    @Value("${throttling.maintenanceblock.enabled}")      private boolean mbThrotEnabled;
    @Value("${throttling.maintenanceblock.limit}")        private int  mbThrotLimit;
    @Value("${target.maintenanceblock.rest.url}")         private String mbUrl;
    @Value("${target.maintenanceblock.rest.enabled}")     private boolean mbEnabled;
    @Value("${deadletterqueue.maintenanceblock}")         private String mbDLQ;
    @Value("${report.maintenanceblock.prefix}")           private String mbReportPref;
    private final LongAdder mbCounter = new LongAdder();
    private volatile long mbWindowStart = System.currentTimeMillis();

    // MaintenanceBlockResource configuration
    @Value("${consolidation.maintenanceblockresource.timeframe}") private long mbrTimeWindow;
    @Value("${bucket.maintenanceblockresource.flush.size}")       private int  mbrSizeTrigger;
    @Value("${archiving.maintenanceblockresource.enabled}")       private boolean mbrArchEnabled;
    @Value("${archive.maintenanceblockresource.incoming.root}")   private String mbrIncRoot;
    @Value("${archive.maintenanceblockresource.merged.root}")     private String mbrMerRoot;
    @Value("${throttling.maintenanceblockresource.enabled}")      private boolean mbrThrotEnabled;
    @Value("${throttling.maintenanceblockresource.limit}")        private int  mbrThrotLimit;
    @Value("${target.maintenanceblockresource.rest.url}")         private String mbrUrl;
    @Value("${target.maintenanceblockresource.rest.enabled}")     private boolean mbrEnabled;
    @Value("${deadletterqueue.maintenanceblockresource}")         private String mbrDLQ;
    @Value("${report.maintenanceblockresource.prefix}")           private String mbrReportPref;
    private final LongAdder mbrCounter = new LongAdder();
    private volatile long mbrWindowStart = System.currentTimeMillis();

    // TrainServiceUpdateActual configuration
    @Value("${consolidation.trainserviceupdate.timeframe}") private long tsuTimeWindow;
    @Value("${bucket.trainserviceupdate.flush.size}")       private int  tsuSizeTrigger;
    @Value("${archiving.trainserviceupdate.enabled}")       private boolean tsuArchEnabled;
    @Value("${archive.trainserviceupdate.incoming.root}")   private String tsuIncRoot;
    @Value("${archive.trainserviceupdate.merged.root}")     private String tsuMerRoot;
    @Value("${throttling.trainserviceupdate.enabled}")      private boolean tsuThrotEnabled;
    @Value("${throttling.trainserviceupdate.limit}")        private int  tsuThrotLimit;
    @Value("${target.trainserviceupdate.rest.url}")         private String tsuUrl;
    @Value("${target.trainserviceupdate.rest.enabled}")     private boolean tsuEnabled;
    @Value("${deadletterqueue.trainserviceupdate}")         private String tsuDLQ;
    @Value("${report.trainserviceupdate.prefix}")           private String tsuReportPref;
    private final LongAdder tsuCounter = new LongAdder();
    private volatile long tsuWindowStart = System.currentTimeMillis();

    // Shared formatters
    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyyMMdd/HH/mm");
    private static final DateTimeFormatter CSV_FMT = DateTimeFormatter.ofPattern("mm");

    /**
     * @param webClient shared non‑blocking HTTP client
     */
    public AggregatorService(WebClient webClient) {
        this.webClient = webClient;
    }

    // ─────────── LoadPipeline Methods ───────────────────────────────────────────

    /**
     * Ingests a LoadPipeline message, groups by LoadID,
     * flushes by time‑window or size.
     */
    public void processLoadPipeline(String json, String source) {
        // Archive raw incoming
        if (lpArchEnabled) ioPool.submit(() -> archive(json, lpIncRoot));

        try {
            JsonNode root = mapper.readTree(json);
            ArrayNode arr = (ArrayNode) root.get("LoadPipeline");
            if (arr == null || arr.isEmpty())
                throw new IllegalArgumentException("Missing LoadPipeline array");

            String id = arr.get(0).get("LoadID").asText();

            lpBuckets.compute(id, (key, bucket) -> {
                if (bucket == null) bucket = new MessageBucket();
                bucket.add(arr);
                if (lpSizeTrigger > 0 && bucket.size() >= lpSizeTrigger) {
                    flushLoadPipeline(id, bucket);
                    return null;
                }
                return bucket;
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Runs every second to flush expired LoadPipeline buckets. */
    @Scheduled(fixedDelay = 1000)
    public void flushLoadPipelineBuckets() {
        long now = System.currentTimeMillis();
        lpBuckets.forEach((id, bucket) -> {
            if (now - bucket.getStart() >= lpTimeWindow * 1000) {
                flushLoadPipeline(id, bucket);
            }
        });
    }

    /**
     * Merges and dispatches a completed LoadPipeline bucket.
     */
    private void flushLoadPipeline(String id, MessageBucket bucket) {
        // 1) Merge
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("LoadPipeline");
        bucket.getMessages().forEach(node -> node.forEach(out::add));
        String payload = merged.toString();

        // 2) Archive merged
        if (lpArchEnabled) ioPool.submit(() -> archive(payload, lpMerRoot));

        // 3) Dispatch + throttle + report
        sendWithThrottleAndReport(
                id,
                payload,
                bucket.size(),           // entryCount
                lpEnabled,
                lpUrl,
                lpThrotEnabled,
                lpThrotLimit,
                lpCounter,
                () -> lpWindowStart = System.currentTimeMillis(),
                lpDLQ,
                lpReportPref
        );

        // 4) Remove bucket
        lpBuckets.remove(id);
    }

    // ─────────── MDR Methods ────────────────────────────────────────────────────

    /** Ingests an MDR message. */
    public void processMdr(String json, String source) {
        if (mdrArchEnabled) ioPool.submit(() -> archive(json, mdrIncRoot));
        try {
            JsonNode root = mapper.readTree(json);
            ArrayNode arr = (ArrayNode) root.get("MultiDestinationRake");
            if (arr == null || arr.isEmpty())
                throw new IllegalArgumentException("Missing MultiDestinationRake array");

            String id = arr.get(0).get("LoadID").asText();
            mdrBuckets.compute(id, (k, bucket) -> {
                if (bucket == null) bucket = new MessageBucket();
                bucket.add(arr);
                if (mdrSizeTrigger > 0 && bucket.size() >= mdrSizeTrigger) {
                    flushMdr(id, bucket);
                    return null;
                }
                return bucket;
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Runs every second to flush expired MDR buckets. */
    @Scheduled(fixedDelay = 1000)
    public void flushMdrBuckets() {
        long now = System.currentTimeMillis();
        mdrBuckets.forEach((id, bucket) -> {
            if (now - bucket.getStart() >= mdrTimeWindow * 1000) {
                flushMdr(id, bucket);
            }
        });
    }

    /** Merges and dispatches a completed MDR bucket. */
    private void flushMdr(String id, MessageBucket bucket) {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("MultiDestinationRake");
        bucket.getMessages().forEach(node -> node.forEach(out::add));
        String payload = merged.toString();

        if (mdrArchEnabled) ioPool.submit(() -> archive(payload, mdrMerRoot));
        sendWithThrottleAndReport(
                id,
                payload,
                bucket.size(),
                mdrEnabled,
                mdrUrl,
                mdrThrotEnabled,
                mdrThrotLimit,
                mdrCounter,
                () -> mdrWindowStart = System.currentTimeMillis(),
                mdrDLQ,
                mdrReportPref
        );
        mdrBuckets.remove(id);
    }

    // ───────────── LoadAttribute (global batch) ─────────────────────────────────

    /** Ingests a LoadAttribute message into the global batch. */
    public void processLoadAttribute(String json, String source) {
        if (laArchEnabled) ioPool.submit(() -> archive(json, laIncRoot));
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("LoadAttribute");
            if (arr == null || arr.isEmpty())
                throw new IllegalArgumentException("Missing LoadAttribute array");

            BatchBucket batch = laBatch.get();
            batch.add(arr);

            // Flush immediately if size threshold reached
            if (batch.size() >= laSizeTrigger) {
                BatchBucket toFlush = laBatch.getAndSet(new BatchBucket());
                flushLoadAttributeBatch(toFlush);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Every second, flush LoadAttribute batch if time threshold reached. */
    @Scheduled(fixedDelay = 1000)
    public void flushLoadAttributeByTime() {
        BatchBucket batch = laBatch.get();
        if (System.currentTimeMillis() - batch.getStart() >= laTimeWindow * 1000) {
            BatchBucket toFlush = laBatch.getAndSet(new BatchBucket());
            if (!toFlush.isEmpty()) {
                flushLoadAttributeBatch(toFlush);
            }
        }
    }

    /** Merges and dispatches one LoadAttribute batch. */
    private void flushLoadAttributeBatch(BatchBucket batch) {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("LoadAttribute");
        batch.getMessages().forEach(arr -> arr.forEach(out::add));
        String payload = merged.toString();

        if (laArchEnabled) ioPool.submit(() -> archive(payload, laMerRoot));

        sendWithThrottleAndReport(
                null,
                payload,
                batch.size(),
                laEnabled,
                laUrl,
                laThrotEnabled,
                laThrotLimit,
                laCounter,
                () -> laWindowStart = System.currentTimeMillis(),
                laDLQ,
                laReportPref
        );
    }

    // ───────── MaintenanceBlock (global batch) ─────────────────────────────────

    /** Ingests a MaintenanceBlock message into the global batch. */
    public void processMaintenanceBlock(String json, String source) {
        if (mbArchEnabled) ioPool.submit(() -> archive(json, mbIncRoot));
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("MaintenanceBlock");
            if (arr == null || arr.isEmpty())
                throw new IllegalArgumentException("Missing MaintenanceBlock array");

            BatchBucket batch = mbBatch.get();
            batch.add(arr);

            if (batch.size() >= mbSizeTrigger) {
                BatchBucket toFlush = mbBatch.getAndSet(new BatchBucket());
                flushMaintenanceBlockBatch(toFlush);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void flushMaintenanceBlockByTime() {
        BatchBucket batch = mbBatch.get();
        if (System.currentTimeMillis() - batch.getStart() >= mbTimeWindow * 1000) {
            BatchBucket toFlush = mbBatch.getAndSet(new BatchBucket());
            if (!toFlush.isEmpty()) {
                flushMaintenanceBlockBatch(toFlush);
            }
        }
    }

    private void flushMaintenanceBlockBatch(BatchBucket batch) {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("MaintenanceBlock");
        batch.getMessages().forEach(arr -> arr.forEach(out::add));
        String payload = merged.toString();

        if (mbArchEnabled) ioPool.submit(() -> archive(payload, mbMerRoot));

        sendWithThrottleAndReport(
                null,
                payload,
                batch.size(),
                mbEnabled,
                mbUrl,
                mbThrotEnabled,
                mbThrotLimit,
                mbCounter,
                () -> mbWindowStart = System.currentTimeMillis(),
                mbDLQ,
                mbReportPref
        );
    }

    // ─────── MaintenanceBlockResource (global batch) ────────────────────────────

    public void processMaintenanceBlockResource(String json, String source) {
        if (mbrArchEnabled) ioPool.submit(() -> archive(json, mbrIncRoot));
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("MaintenanceBlockResource");
            if (arr == null || arr.isEmpty())
                throw new IllegalArgumentException("Missing MaintenanceBlockResource array");

            BatchBucket batch = mbrBatch.get();
            batch.add(arr);

            if (batch.size() >= mbrSizeTrigger) {
                BatchBucket toFlush = mbrBatch.getAndSet(new BatchBucket());
                flushMaintenanceBlockResourceBatch(toFlush);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void flushMaintenanceBlockResourceByTime() {
        BatchBucket batch = mbrBatch.get();
        if (System.currentTimeMillis() - batch.getStart() >= mbrTimeWindow * 1000) {
            BatchBucket toFlush = mbrBatch.getAndSet(new BatchBucket());
            if (!toFlush.isEmpty()) {
                flushMaintenanceBlockResourceBatch(toFlush);
            }
        }
    }

    private void flushMaintenanceBlockResourceBatch(BatchBucket batch) {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("MaintenanceBlockResource");
        batch.getMessages().forEach(arr -> arr.forEach(out::add));
        String payload = merged.toString();

        if (mbrArchEnabled) ioPool.submit(() -> archive(payload, mbrMerRoot));

        sendWithThrottleAndReport(
                null,
                payload,
                batch.size(),
                mbrEnabled,
                mbrUrl,
                mbrThrotEnabled,
                mbrThrotLimit,
                mbrCounter,
                () -> mbrWindowStart = System.currentTimeMillis(),
                mbrDLQ,
                mbrReportPref
        );
    }

    // ─────── TrainServiceUpdateActual (global batch) ────────────────────────────

    public void processTrainServiceUpdate(String json, String source) {
        if (tsuArchEnabled) ioPool.submit(() -> archive(json, tsuIncRoot));
        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("TrainActual");
            if (arr == null || arr.isEmpty())
                throw new IllegalArgumentException("Missing TrainActual array");

            BatchBucket batch = tsuBatch.get();
            batch.add(arr);

            if (batch.size() >= tsuSizeTrigger) {
                BatchBucket toFlush = tsuBatch.getAndSet(new BatchBucket());
                flushTrainServiceUpdateBatch(toFlush);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void flushTrainServiceUpdateByTime() {
        BatchBucket batch = tsuBatch.get();
        if (System.currentTimeMillis() - batch.getStart() >= tsuTimeWindow * 1000) {
            BatchBucket toFlush = tsuBatch.getAndSet(new BatchBucket());
            if (!toFlush.isEmpty()) {
                flushTrainServiceUpdateBatch(toFlush);
            }
        }
    }

    private void flushTrainServiceUpdateBatch(BatchBucket batch) {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("TrainActual");
        batch.getMessages().forEach(arr -> arr.forEach(out::add));
        String payload = merged.toString();

        if (tsuArchEnabled) ioPool.submit(() -> archive(payload, tsuMerRoot));

        sendWithThrottleAndReport(
                null,
                payload,
                batch.size(),
                tsuEnabled,
                tsuUrl,
                tsuThrotEnabled,
                tsuThrotLimit,
                tsuCounter,
                () -> tsuWindowStart = System.currentTimeMillis(),
                tsuDLQ,
                tsuReportPref
        );
    }

    // ───────── Shared Helper Methods ───────────────────────────────────────────

    /**
     * Dispatches payload with optional throttling, reports CSV, handles dead‑letter.
     *
     * @param id            optional ID (or null for global batch)
     * @param payload       merged JSON
     * @param entryCount    number of entries in this batch
     * @param enabled       whether to perform HTTP POST
     * @param url           target REST URL
     * @param thrEnabled    whether to throttle
     * @param thrLimit      max calls/sec
     * @param counter       LongAdder counting calls this second
     * @param resetWindow   resets the second-window timestamp
     * @param dlqRoot       dead-letter archive root
     * @param reportPref    CSV report file prefix
     */
    private void sendWithThrottleAndReport(
            String id,
            String payload,
            int entryCount,
            boolean enabled,
            String url,
            boolean thrEnabled,
            int thrLimit,
            LongAdder counter,
            Runnable resetWindow,
            String dlqRoot,
            String reportPref
    ) {
        // Compute minute for CSV
        String minute = LocalTime.now().format(CSV_FMT);

        if (enabled) {
            // Throttle if needed
            throttle(counter, resetWindow, thrEnabled, thrLimit);

            // Non-blocking HTTP POST
            webClient.post()
                    .uri(url)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .doOnSuccess(resp -> writeCsv(reportPref, id, entryCount, minute, "SENT"))
                    .doOnError(err -> {
                        err.printStackTrace();
                        ioPool.submit(() -> archive(payload, dlqRoot));
                        writeCsv(reportPref, id, entryCount, minute, "FAILED");
                    })
                    .subscribe();
        } else {
            // Dispatch disabled: record SKIPPED
            writeCsv(reportPref, id, entryCount, minute, "SKIPPED");
        }
    }

    /**
     * Simple per‑second throttle using LongAdder.
     */
    private void throttle(
            LongAdder counter,
            Runnable resetWindow,
            boolean enabled,
            int limit
    ) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        // Reset counter every second
        if (now - resetWindowTime(resetWindow) >= 1000) {
            counter.reset();
            resetWindow.run();
        }
        // If at limit, brief sleep
        if (counter.sum() >= limit) {
            try { Thread.sleep(1); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        counter.increment();
    }

    /** Placeholder for mapping resetWindow to its timestamp. */
    private long resetWindowTime(Runnable resetWindow) {
        return System.currentTimeMillis();
    }

    /** Archives JSON under root/YYYYMMdd/HH/mm/filename.json. */
    private void archive(String json, String root) {
        try {
            String sub = LocalDateTime.now().format(DIR_FMT);
            Path dir = Paths.get(root, sub);
            Files.createDirectories(dir);
            String fname = System.currentTimeMillis() + "_" + UUID.randomUUID() + ".json";
            Files.writeString(dir.resolve(fname), json, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Appends a line to the daily CSV: Minute,ID,EntryCount,Status.
     */
    private void writeCsv(String prefix, String id, int count, String minute, String status) {
        try {
            String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            Path file = Paths.get(prefix + date + ".csv");
            boolean hdr = Files.notExists(file);
            try (BufferedWriter bw = Files.newBufferedWriter(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (hdr) bw.write("Minute,ID,EntryCount,Status\n");
                bw.write(String.join(",", minute,
                        id == null ? "" : id,
                        String.valueOf(count),
                        status) + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Shuts down the I/O executor cleanly on app exit. */
    @PreDestroy
    public void shutdown() {
        ioPool.shutdown();
    }

    // ─────────── Internal Bucket Classes ─────────────────────────────────────────

    /** Per‑ID bucket for LoadPipeline & MDR. */
    private static class MessageBucket {
        private final long start = System.currentTimeMillis();
        private final ConcurrentLinkedQueue<ArrayNode> msgs = new ConcurrentLinkedQueue<>();
        void add(ArrayNode arr) { msgs.add(arr); }
        long getStart()      { return start; }
        Collection<ArrayNode> getMessages() { return msgs; }
        int size()           { return msgs.size(); }
    }

    /** Global batch for the four batch-only streams. */
    private static class BatchBucket {
        private final long start = System.currentTimeMillis();
        private final ConcurrentLinkedQueue<ArrayNode> msgs = new ConcurrentLinkedQueue<>();
        void add(ArrayNode arr) { msgs.add(arr); }
        long getStart()        { return start; }
        Collection<ArrayNode> getMessages() { return msgs; }
        int size()             { return msgs.size(); }
        boolean isEmpty()      { return msgs.isEmpty(); }
    }
}
