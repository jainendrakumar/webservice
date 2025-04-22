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
 * AggregatorService is the main processing unit of the ingestion system.
 * <p>
 * It handles six types of ingestion pipelines:
 * <ul>
 *     <li>LoadPipeline</li>
 *     <li>MultiDestinationRake (MDR)</li>
 *     <li>LoadAttribute</li>
 *     <li>MaintenanceBlock</li>
 *     <li>MaintenanceBlockResource</li>
 *     <li>TrainServiceUpdateActual</li>
 * </ul>
 * <p>
 * Features for each pipeline include:
 * <ul>
 *     <li>Ingestion and JSON parsing</li>
 *     <li>Batching (by ID or globally)</li>
 *     <li>Time/size-based flushing</li>
 *     <li>Throttling requests per second</li>
 *     <li>Archiving (incoming and merged)</li>
 *     <li>Dispatching via non-blocking WebClient</li>
 *     <li>CSV reporting and dead-letter queue handling</li>
 * </ul>
 *
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

    // JSON parser
    private final ObjectMapper mapper = new ObjectMapper();

    // Shared HTTP client for dispatch
    private final WebClient webClient;

    // Thread pool for file I/O tasks (archiving, logging)
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

    // Metrics and time tracking
    private final LongAdder lpCounter = new LongAdder();
    private volatile long lpWindowStart = System.currentTimeMillis();

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

    // ───────────────────── Global Batch Buckets (Atomic references) ─────────────────────

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
     * Constructs the AggregatorService with injected WebClient.
     *
     * @param webClient Shared reactive HTTP client used for all dispatches
     */
    public AggregatorService(WebClient webClient) {
        this.webClient = webClient;
    }

    // ─────────── LoadPipeline Methods ───────────────────────────────────────────

    /**
     * Ingests a LoadPipeline message, groups by LoadID,
     * flushes by time‑window or size.
     * <p>
     * Behavior:
     * <ul>
     *     <li>Archives the incoming raw message (optional)</li>
     *     <li>Parses the "LoadPipeline" array</li>
     *     <li>Groups messages by LoadID into per-ID buckets</li>
     *     <li>Flushes the bucket immediately if size threshold is reached</li>
     * </ul>
     *
     * @param json   Incoming JSON string with "LoadPipeline" array
     * @param source Metadata tag for the message source (e.g., "port")
     */
    public void processLoadPipeline(String json, String source) {
        // Asynchronously archive incoming message
        if (lpArchEnabled) {
            ioPool.submit(() -> archive(json, lpIncRoot));
        }

        try {
            // Parse JSON string
            JsonNode root = mapper.readTree(json);
            ArrayNode arr = (ArrayNode) root.get("LoadPipeline");

            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("Missing LoadPipeline array");
            }

            // Use first LoadID as grouping key
            String id = arr.get(0).get("LoadID").asText();

            // Put messages into bucket, flush if size exceeds threshold
            lpBuckets.compute(id, (key, bucket) -> {
                if (bucket == null) bucket = new MessageBucket();
                bucket.add(arr);

                if (lpSizeTrigger > 0 && bucket.size() >= lpSizeTrigger) {
                    flushLoadPipeline(id, bucket);
                    return null; // Remove bucket after flush
                }

                return bucket; // Retain for further accumulation
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *  Periodic check to flush LoadPipeline buckets based on time threshold.
     *  Runs every second to flush expired LoadPipeline buckets.
     */
    @Scheduled(fixedDelay = 1000)
    public void flushLoadPipelineBuckets() {
        long now = System.currentTimeMillis();

        // Check each bucket’s age
        lpBuckets.forEach((id, bucket) -> {
            if (now - bucket.getStart() >= lpTimeWindow * 1000) {
                flushLoadPipeline(id, bucket);
            }
        });
    }

    /**
     * Merges and dispatches a completed LoadPipeline bucket.
     * Flushes a LoadPipeline message bucket:
     * <ol>
     *     <li>Merges all JSON arrays into one</li>
     *     <li>Archives the merged payload</li>
     *     <li>Dispatches to the REST endpoint (with throttling)</li>
     *     <li>Writes CSV report and handles errors</li>
     *     <li>Deletes the bucket from memory</li>
     * </ol>
     *
     * @param id     the LoadID for this batch
     * @param bucket the associated bucket containing messages
     *
     * @author jkr3 (Jainendra.kumar@3ds.com)
     * @version 1.0.0
     * @since 2025-04-20
     */
    private void flushLoadPipeline(String id, MessageBucket bucket) {
        // Merge into a single JSON array
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("LoadPipeline");
        bucket.getMessages().forEach(node -> node.forEach(out::add));
        String payload = merged.toString();

        // Archive the merged payload
        if (lpArchEnabled) {
            ioPool.submit(() -> archive(payload, lpMerRoot));
        }

        // Send and report
        sendWithThrottleAndReport(
                id,
                payload,
                bucket.size(),
                lpEnabled,
                lpUrl,
                lpThrotEnabled,
                lpThrotLimit,
                lpCounter,
                () -> lpWindowStart = System.currentTimeMillis(),
                lpDLQ,
                lpReportPref
        );

        // Remove from active buckets
        lpBuckets.remove(id);
    }

    // ─────────── MDR Methods ────────────────────────────────────────────────────

    /**
     * Ingests a MultiDestinationRake (MDR) JSON payload.
     * <p>
     * Logic:
     * <ul>
     *     <li>Archives the incoming JSON message</li>
     *     <li>Parses "MultiDestinationRake" array</li>
     *     <li>Groups by LoadID into per-ID buckets</li>
     *     <li>Flushes the bucket if size threshold reached</li>
     * </ul>
     *
     * @param json   Raw MDR JSON
     * @param source Metadata tag (e.g. "port")
     */
    public void processMdr(String json, String source) {
        if (mdrArchEnabled) {
            ioPool.submit(() -> archive(json, mdrIncRoot));
        }

        try {
            JsonNode root = mapper.readTree(json);
            ArrayNode arr = (ArrayNode) root.get("MultiDestinationRake");

            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("Missing MultiDestinationRake array");
            }

            String id = arr.get(0).get("LoadID").asText();

            mdrBuckets.compute(id, (key, bucket) -> {
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

    /**
     * Scheduled method to flush expired MDR buckets based on time threshold.
     * Executes every second.
     */
    @Scheduled(fixedDelay = 1000)
    public void flushMdrBuckets() {
        long now = System.currentTimeMillis();

        mdrBuckets.forEach((id, bucket) -> {
            if (now - bucket.getStart() >= mdrTimeWindow * 1000) {
                flushMdr(id, bucket);
            }
        });
    }

    /**
     * Merges, dispatches and flushes a completed MDR bucket.
     *
     * @param id     LoadID used for grouping
     * @param bucket Buffered messages for this LoadID
     */
    private void flushMdr(String id, MessageBucket bucket) {
        // Combine messages into a single array
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("MultiDestinationRake");
        bucket.getMessages().forEach(node -> node.forEach(out::add));
        String payload = merged.toString();

        if (mdrArchEnabled) {
            ioPool.submit(() -> archive(payload, mdrMerRoot));
        }

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

    /**
     * Processes a LoadAttribute message batch (global batching).
     *
     * @param json   Raw JSON input with "LoadAttribute" array
     * @param source Metadata tag (unused in this case)
     */
    public void processLoadAttribute(String json, String source) {
        if (laArchEnabled) {
            ioPool.submit(() -> archive(json, laIncRoot));
        }

        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("LoadAttribute");

            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("Missing LoadAttribute array");
            }

            // Add to current batch
            BatchBucket batch = laBatch.get();
            batch.add(arr);

            // Flush immediately if batch is full
            if (batch.size() >= laSizeTrigger) {
                BatchBucket toFlush = laBatch.getAndSet(new BatchBucket());
                flushLoadAttributeBatch(toFlush);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Scheduled flush of LoadAttribute messages if time window exceeded.
     */
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

    /** Flush and dispatch the LoadAttribute batch */
    private void flushLoadAttributeBatch(BatchBucket batch) {
        ObjectNode merged = mapper.createObjectNode();
        ArrayNode out = merged.putArray("LoadAttribute");
        batch.getMessages().forEach(arr -> arr.forEach(out::add));
        String payload = merged.toString();

        if (laArchEnabled) {
            ioPool.submit(() -> archive(payload, laMerRoot));
        }

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

    /** Process incoming MaintenanceBlock batch (global batching). */
    public void processMaintenanceBlock(String json, String source) {
        if (mbArchEnabled) ioPool.submit(() -> archive(json, mbIncRoot));

        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("MaintenanceBlock");

            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("Missing MaintenanceBlock array");
            }

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

        if (mbArchEnabled) {
            ioPool.submit(() -> archive(payload, mbMerRoot));
        }

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

    /** Processes MaintenanceBlockResource messages in global batch mode. */
    public void processMaintenanceBlockResource(String json, String source) {
        if (mbrArchEnabled) ioPool.submit(() -> archive(json, mbrIncRoot));

        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("MaintenanceBlockResource");

            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("Missing MaintenanceBlockResource array");
            }

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

        if (mbrArchEnabled) {
            ioPool.submit(() -> archive(payload, mbrMerRoot));
        }

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

    /** Processes TrainServiceUpdateActual (TrainActual array) via global batching. */
    public void processTrainServiceUpdate(String json, String source) {
        if (tsuArchEnabled) ioPool.submit(() -> archive(json, tsuIncRoot));

        try {
            ArrayNode arr = (ArrayNode) mapper.readTree(json).get("TrainActual");

            if (arr == null || arr.isEmpty()) {
                throw new IllegalArgumentException("Missing TrainActual array");
            }

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

        if (tsuArchEnabled) {
            ioPool.submit(() -> archive(payload, tsuMerRoot));
        }

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

    // ───────────────────── Shared Helper Methods ─────────────────────

    /**
     * Dispatches the payload via HTTP POST with optional throttling,
     * logs status to CSV, and sends failed messages to a dead-letter queue.
     *
     * @param id           Optional ID (used for per-ID pipelines; null for global batch)
     * @param payload      Final merged JSON message
     * @param entryCount   Total number of entries in this message
     * @param enabled      Whether HTTP dispatch is enabled
     * @param url          Target REST endpoint URL
     * @param thrEnabled   Whether throttling is active
     * @param thrLimit     Max requests per second if throttling is enabled
     * @param counter      LongAdder to count dispatches in current second
     * @param resetWindow  Function to reset the timestamp window
     * @param dlqRoot      Path to dead-letter archive folder
     * @param reportPref   Prefix for the CSV report filename
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
        // Format minute for CSV logging (e.g. "05", "43")
        String minute = LocalTime.now().format(CSV_FMT);

        if (enabled) {
            // Apply throttling logic (if enabled)
            throttle(counter, resetWindow, thrEnabled, thrLimit);

            // Non-blocking async HTTP POST using WebClient
            webClient.post()
                    .uri(url)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .doOnSuccess(resp -> {
                        // Successful POST — log to CSV as SENT
                        writeCsv(reportPref, id, entryCount, minute, "SENT");
                    })
                    .doOnError(err -> {
                        // Log error, archive to DLQ, and mark as FAILED
                        err.printStackTrace();
                        ioPool.submit(() -> archive(payload, dlqRoot));
                        writeCsv(reportPref, id, entryCount, minute, "FAILED");
                    })
                    .subscribe();
        } else {
            // Dispatch is disabled — log as SKIPPED
            writeCsv(reportPref, id, entryCount, minute, "SKIPPED");
        }
    }

    /**
     * Simple throttle mechanism using LongAdder for per-second control.
     *
     * @param counter      Accumulator for number of dispatches
     * @param resetWindow  Timestamp reset hook
     * @param enabled      Whether throttling is active
     * @param limit        Max allowed dispatches per second
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

        // If limit reached, pause briefly
        if (counter.sum() >= limit) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        counter.increment();
    }

    /**
     * Dummy method for throttle window reset timestamp.
     * (Could be improved to use actual window tracking per pipeline.)
     */
    private long resetWindowTime(Runnable resetWindow) {
        return System.currentTimeMillis();
    }

    /**
     * Writes a JSON payload to the archive directory using a structured timestamped path:
     * root/yyyyMMdd/HH/mm/UUID.json
     *
     * @param json JSON string to write
     * @param root Base folder under which timestamped subfolders will be created
     */
    private void archive(String json, String root) {
        try {
            // Create timestamped folder structure like 20240420/14/23/
            String sub = LocalDateTime.now().format(DIR_FMT);
            Path dir = Paths.get(root, sub);
            Files.createDirectories(dir);

            // Write to a uniquely named file using UUID
            String fname = System.currentTimeMillis() + "_" + UUID.randomUUID() + ".json";
            Files.writeString(dir.resolve(fname), json, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Appends an entry to a daily CSV file for tracking dispatches.
     * CSV format: Minute,ID,EntryCount,Status
     *
     * @param prefix     CSV file prefix (e.g. report_loadpipeline_)
     * @param id         Optional ID for the message batch
     * @param count      Number of messages
     * @param minute     Minute of the hour (e.g. "04")
     * @param status     One of: SENT, FAILED, SKIPPED
     */
    private void writeCsv(String prefix, String id, int count, String minute, String status) {
        try {
            // Generate file name based on today's date
            String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            Path file = Paths.get(prefix + date + ".csv");

            boolean newFile = Files.notExists(file);

            // Append new entry (and header if file is new)
            try (BufferedWriter bw = Files.newBufferedWriter(
                    file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) {
                    bw.write("Minute,ID,EntryCount,Status\n");
                }
                bw.write(String.join(",", minute,
                        id == null ? "" : id,
                        String.valueOf(count),
                        status) + "\n");
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Gracefully shuts down the I/O thread pool when the application is closing.
     */
    @PreDestroy
    public void shutdown() {
        ioPool.shutdown();
    }

    // ───────────────────── Internal Helper Classes ─────────────────────

    /**
     * Represents a per-ID message bucket for LoadPipeline and MDR.
     * Holds a queue of JSON arrays and a timestamp.
     */
    private static class MessageBucket {
        private final long start = System.currentTimeMillis();
        private final ConcurrentLinkedQueue<ArrayNode> msgs = new ConcurrentLinkedQueue<>();

        void add(ArrayNode arr) { msgs.add(arr); }

        long getStart() { return start; }

        Collection<ArrayNode> getMessages() { return msgs; }

        int size() { return msgs.size(); }
    }

    /**
     * Represents a global batch bucket for LoadAttribute, MB, MBR, TSU.
     */
    private static class BatchBucket {
        private final long start = System.currentTimeMillis();
        private final ConcurrentLinkedQueue<ArrayNode> msgs = new ConcurrentLinkedQueue<>();

        void add(ArrayNode arr) { msgs.add(arr); }

        long getStart() { return start; }

        Collection<ArrayNode> getMessages() { return msgs; }

        int size() { return msgs.size(); }

        boolean isEmpty() { return msgs.isEmpty(); }
    }
}

