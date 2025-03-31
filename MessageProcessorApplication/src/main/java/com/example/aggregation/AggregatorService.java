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
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * AggregatorService is the core service for processing incoming messages from multiple sources
 * (REST endpoint, folder, or ZIP file), consolidating them by a common load identifier, and sending
 * the merged message to a target REST URL. It supports several key features:
 *
 * <ul>
 *   <li><b>Multiple Incoming Sources:</b> The service accepts messages via a REST endpoint ("port"),
 *       a folder, or a ZIP file. The source is tagged as "port" or "file".</li>
 *   <li><b>Source Prioritization:</b> When the property {@code incoming.priority} is set to "port",
 *       file/ZIP processing is delayed for a configurable period after a port message is received.
 *       This is controlled via the property {@code incoming.file.delay.seconds}.</li>
 *   <li><b>Outgoing Message Control and Throttling:</b> Outgoing messages are only sent if
 *       {@code target.rest.enabled} is true. Throttling is applied (using a simple counter-based
 *       approach) to limit the number of messages sent per second as configured by
 *       {@code throttling.enabled} and {@code throttling.limit}.</li>
 *   <li><b>Dead Letter Queue:</b> If message delivery fails, the merged message is moved to a dead
 *       letter queue, preserving the same folder hierarchy (YYYYMMDD/HH/mm) as used in archiving.</li>
 *   <li><b>Reporting:</b> A CSV report (named as {@code report_YYYYMMDD.csv}) is generated to record
 *       details such as the minute, load ID, number of entries in the merged message, and status
 *       (SENT, FAILED, or SKIPPED).</li>
 *   <li><b>Input Source Selection:</b> The service processes file-based messages either from a ZIP file
 *       (if {@code input.mode} is set to "zip") or directly from a folder (if set to "folder").</li>
 * </ul>
 *
 * <p>The properties for configuration are dynamically read from a properties file (external-config.properties)
 * and include settings for archiving, throttling, input source details, and more.</p>
 *
 * @author JKR3
 */

/**
 * Detailed description of implementation.
 *
 * Multiple Incoming Sources & Prioritization:
 * Incoming messages can come from a REST endpoint (port), a folder, or a ZIP file. A new property (incoming.priority) allows prioritizing port messages over file‐based inputs. When the priority is set to "port", the service delays processing of file/ZIP inputs by a configurable delay (via incoming.file.delay.seconds) after receiving a port message.
 *
 * Outgoing Message Control & Throttling:
 * The service uses a switch (target.rest.enabled) to enable or disable outgoing message delivery. A throttling feature (controlled via throttling.enabled and throttling.limit) limits the number of merged messages sent per second. If sending fails, the message is moved to a dead letter queue whose location is defined in the properties file.
 *
 * Dynamic Input Source (ZIP vs Folder):
 * Depending on the property input.mode (which can be "zip" or "folder"), the service processes messages either by unzipping a given ZIP file (property: input.zip.file) or by reading files from a designated folder (property: input.folder.path). The folder structure is assumed to follow a hierarchy of YYYYMMDD/HH/mm, and files are processed in ascending order.
 *
 * Reporting:
 * After each merged message is processed (whether sent, skipped, or failed), a record is appended to a CSV file (named using a prefix and the current date, e.g. report_YYYYMMDD.csv). The report includes details such as the minute (from the timestamp), load ID, number of entries in the merged message, and the delivery status.
 *
 */

@Service
public class AggregatorService {

    // JSON mapper to parse and generate JSON messages.
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Buckets to group messages by loadId.
    private final Map<String, MessageBucket> buckets = new ConcurrentHashMap<>();

    // Cache for created directories to avoid repeated I/O.
    private final Set<String> createdDirs = ConcurrentHashMap.newKeySet();

    // Executor for asynchronous operations such as archiving.
    private final ExecutorService archiveExecutor = Executors.newFixedThreadPool(4);

    // REST endpoint URL to send the merged message.
    @Value("${target.rest.url}")
    private String targetRestUrl;

    // Switch to enable or disable sending merged messages to the REST endpoint.
    @Value("${target.rest.enabled:true}")
    private boolean targetRestEnabled;

    // Root directories for archiving incoming and merged messages.
    @Value("${archive.incoming.root}")
    private String incomingArchiveRoot;

    @Value("${archive.merged.root}")
    private String mergedArchiveRoot;

    // Root directory for dead letter queue.
    @Value("${deadletterqueue}")
    private String deadLetterQueue;

    // Prefix for the CSV report file.
    @Value("${report.file.prefix:report_}")
    private String reportFilePrefix;

    // Timeframe (in seconds) to consolidate messages.
    @Value("${consolidation.timeframe}")
    private long consolidationTimeFrame;

    // Threshold for immediate flush based on bucket size (0 to disable).
    @Value("${bucket.flush.size:0}")
    private int bucketFlushSize;

    // Flag to enable or disable archiving.
    @Value("${archiving.enabled:true}")
    private boolean archivingEnabled;

    // Throttling configuration.
    @Value("${throttling.enabled:true}")
    private boolean throttlingEnabled;

    // Maximum allowed merged messages per second.
    @Value("${throttling.limit:5}")
    private int throttlingLimit;

    // Input mode for file-based messages: "zip" or "folder".
    @Value("${input.mode:folder}")
    private String inputMode;

    // Full path to the ZIP file if input.mode is "zip".
    @Value("${input.zip.file:}")
    private String inputZipFile;

    // Folder path if input.mode is "folder".
    @Value("${input.folder.path:input}")
    private String inputFolderPath;

    // Prioritization property for incoming messages: "port" or "file".
    @Value("${incoming.priority:port}")
    private String incomingPriority;

    // Delay (in seconds) to postpone processing file/ZIP messages when port messages have priority.
    @Value("${incoming.file.delay.seconds:10}")
    private int fileDelaySeconds;

    // Tracks the timestamp of the last received port message.
    private volatile long lastPortMessageTime = 0;

    // Lock and counters for throttling.
    private final Object throttleLock = new Object();
    private long lastThrottleReset = System.currentTimeMillis();
    private int throttleCount = 0;

    // REST client for sending outgoing messages.
    private final RestTemplate restTemplate;

    /**
     * Constructor that injects the RestTemplate.
     *
     * @param restTemplate the RestTemplate bean for HTTP communication.
     */
    public AggregatorService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Processes an incoming message with a given source.
     *
     * @param message the JSON message content.
     * @param source  the source of the message (e.g., "port" for REST, "file" for file-based).
     */
    public void processIncomingMessage(String message, String source) {
        // If the message comes from the REST endpoint ("port"), record the timestamp.
        if ("port".equalsIgnoreCase(source)) {
            lastPortMessageTime = System.currentTimeMillis();
        }
        // Delegate to the common processing logic.
        processMessageInternal(message);
    }

    /**
     * Overloaded method for processing an incoming message without specifying a source.
     * Defaults to "unknown".
     *
     * @param message the JSON message content.
     */
    public void processIncomingMessage(String message) {
        processIncomingMessage(message, "unknown");
    }

    /**
     * Internal method to process the message content. It performs the following steps:
     * <ol>
     *   <li>Archives the raw incoming message (if archiving is enabled).</li>
     *   <li>Parses the JSON and extracts the "LoadPipeline" array.</li>
     *   <li>Extracts the "LoadID" from the first element and groups messages by this ID.</li>
     *   <li>If the bucket reaches a configured threshold, it triggers a flush immediately.</li>
     * </ol>
     *
     * @param message the JSON message content.
     */
    private void processMessageInternal(String message) {
        // Archive the raw incoming message asynchronously.
        if (archivingEnabled) {
            archiveExecutor.submit(() -> archiveMessage(message, "incoming"));
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode loadPipelineNode = root.get("LoadPipeline");
            if (loadPipelineNode == null || !loadPipelineNode.isArray() || loadPipelineNode.size() == 0) {
                System.err.println("Invalid message: 'LoadPipeline' array is missing or empty.");
                return;
            }
            // Extract LoadID from the first element.
            JsonNode firstElement = loadPipelineNode.get(0);
            JsonNode loadIdNode = firstElement.get("LoadID");
            if (loadIdNode == null) {
                System.err.println("Invalid message: 'LoadID' is missing in the first element.");
                return;
            }
            String loadId = loadIdNode.asText();

            // Group the message by LoadID.
            buckets.compute(loadId, (id, bucket) -> {
                if (bucket == null) {
                    bucket = new MessageBucket();
                }
                bucket.addMessage(loadPipelineNode);
                // If bucket flush size threshold is reached, flush immediately.
                if (bucketFlushSize > 0 && bucket.getMessages().size() >= bucketFlushSize) {
                    flushBucket(id, bucket);
                    return null;
                }
                return bucket;
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Scheduled task to flush message buckets based on consolidation timeframe.
     * Runs every second.
     */
    @Scheduled(fixedDelay = 1000)
    public void flushBuckets() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, MessageBucket> entry : buckets.entrySet()) {
            String loadId = entry.getKey();
            MessageBucket bucket = entry.getValue();
            if (now - bucket.getStartTime() >= consolidationTimeFrame * 1000) {
                flushBucket(loadId, bucket);
            }
        }
    }

    /**
     * Flushes a specific bucket of messages by consolidating them into a single JSON payload,
     * archiving the merged message, applying throttling, and then sending to the target REST URL.
     * If sending fails, the message is moved to the dead letter queue.
     * A CSV report record is generated for each merged message.
     *
     * @param loadId the load identifier used as the bucket key.
     * @param bucket the MessageBucket containing grouped messages.
     */
    private void flushBucket(String loadId, MessageBucket bucket) {
        // Build consolidated payload.
        ObjectNode aggregated = objectMapper.createObjectNode();
        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (JsonNode node : bucket.getMessages()) {
            if (node.isArray()) {
                node.forEach(messagesArray::add);
            } else {
                messagesArray.add(node);
            }
        }
        aggregated.set("LoadPipeline", messagesArray);
        String aggregatedStr = aggregated.toString();

        // Archive the merged message if archiving is enabled.
        if (archivingEnabled) {
            archiveExecutor.submit(() -> archiveMessage(aggregatedStr, "merged"));
        }

        // Prepare reporting details.
        String minuteDetail = LocalDateTime.now().format(DateTimeFormatter.ofPattern("mm"));
        int messageCount = bucket.getMessages().size();

        // Check if outgoing messages are enabled.
        if (targetRestEnabled) {
            // Apply throttling if enabled.
            if (throttlingEnabled) {
                throttleIfNeeded();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept-Encoding", "gzip,deflate");
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> requestEntity = new HttpEntity<>(aggregatedStr, headers);
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        targetRestUrl,
                        HttpMethod.POST,
                        requestEntity,
                        String.class
                );
                // Log/report success.
                writeReport(loadId, messageCount, minuteDetail, "SENT");
            } catch (Exception ex) {
                ex.printStackTrace();
                // On failure, move the merged message to the dead letter queue.
                moveToDeadLetter(aggregatedStr);
                writeReport(loadId, messageCount, minuteDetail, "FAILED");
            }
        } else {
            // If sending is disabled, record the status as SKIPPED.
            writeReport(loadId, messageCount, minuteDetail, "SKIPPED");
        }
        buckets.remove(loadId);
    }

    /**
     * Implements a simple throttling mechanism. It limits the number of outgoing messages per second
     * to the configured {@code throttling.limit}. If the limit is reached, the thread sleeps until
     * the start of the next second.
     */
    private void throttleIfNeeded() {
        synchronized (throttleLock) {
            long currentTime = System.currentTimeMillis();
            // Reset the throttle counter if a second has passed.
            if (currentTime - lastThrottleReset >= 1000) {
                lastThrottleReset = currentTime;
                throttleCount = 0;
            }
            // If the limit has been reached, sleep for the remaining time in the current second.
            if (throttleCount >= throttlingLimit) {
                long sleepTime = 1000 - (currentTime - lastThrottleReset);
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lastThrottleReset = System.currentTimeMillis();
                throttleCount = 0;
            }
            throttleCount++;
        }
    }

    /**
     * Moves a failed merged message to the dead letter queue. The message is stored in a folder
     * structure based on the current date and time (YYYYMMDD/HH/mm).
     *
     * @param message the JSON merged message that failed to be delivered.
     */
    private void moveToDeadLetter(String message) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String folderPath = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("HH"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("mm"));
            archiveToFolder(message, deadLetterQueue, folderPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Archives a message by storing it in a dynamically built folder path under the specified archive type.
     * The folder hierarchy is YYYYMMDD/HH/mm.
     *
     * @param message the JSON message content to archive.
     * @param type    the type of archive ("incoming" or "merged").
     */
    private void archiveMessage(String message, String type) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String folderPath = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("HH"))
                    + File.separator + now.format(DateTimeFormatter.ofPattern("mm"));
            String rootDir = "incoming".equals(type) ? incomingArchiveRoot : mergedArchiveRoot;
            archiveToFolder(message, rootDir, folderPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Writes the given message content into a file under the specified root directory and folder path.
     * Creates directories as needed and caches created directory paths.
     *
     * @param message  the message content to write.
     * @param rootDir  the root directory under which the file is stored.
     * @param folderPath the folder hierarchy (e.g. YYYYMMDD/HH/mm).
     */
    private void archiveToFolder(String message, String rootDir, String folderPath) {
        try {
            String baseDir = rootDir + File.separator + folderPath;
            if (!createdDirs.contains(baseDir)) {
                Files.createDirectories(Paths.get(baseDir));
                createdDirs.add(baseDir);
            }
            String fileName = baseDir + File.separator + System.currentTimeMillis() + "_" + UUID.randomUUID() + ".json";
            try (FileWriter writer = new FileWriter(fileName)) {
                writer.write(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Appends a record to the CSV report file (named as report_YYYYMMDD.csv) with details including the
     * minute, load ID, entry count, and message status.
     *
     * @param loadId the LoadID associated with the merged message.
     * @param count the number of entries in the merged message.
     * @param minute the minute detail from the current timestamp.
     * @param status the status of the message (SENT, FAILED, or SKIPPED).
     */
    private void writeReport(String loadId, int count, String minute, String status) {
        try {
            String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String reportFile = reportFilePrefix + dateStr + ".csv";
            File file = new File(reportFile);
            boolean writeHeader = !file.exists();
            try (FileWriter fw = new FileWriter(file, true);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                if (writeHeader) {
                    bw.write("Minute,LoadID,EntryCount,Status");
                    bw.newLine();
                }
                bw.write(String.format("%s,%s,%d,%s", minute, loadId, count, status));
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Scheduled task that processes file-based incoming messages (either from a ZIP file or a folder).
     * If the {@code incoming.priority} property is set to "port", then file-based processing is skipped
     * if a port message was received within the delay specified by {@code incoming.file.delay.seconds}.
     * This method runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60000)
    public void processInputMessages() {
        // If port messages are prioritized, check the delay.
        if ("port".equalsIgnoreCase(incomingPriority)) {
            long timeSincePort = System.currentTimeMillis() - lastPortMessageTime;
            if (timeSincePort < fileDelaySeconds * 1000) {
                System.out.println("Port messages are prioritized. Skipping file/zip input processing.");
                return;
            }
        }

        // Process according to input mode.
        if ("zip".equalsIgnoreCase(inputMode) && inputZipFile != null && !inputZipFile.isEmpty()) {
            processZipInput();
        } else if ("folder".equalsIgnoreCase(inputMode)) {
            processFolderInput();
        }
    }

    /**
     * Processes messages from a ZIP file. The ZIP file is unzipped to a temporary directory,
     * and then the folder structure is traversed (expecting hierarchy YYYYMMDD/HH/mm) to process
     * each JSON file.
     */
    private void processZipInput() {
        File zipFile = new File(inputZipFile);
        if (!zipFile.exists()) {
            System.err.println("Zip file not found: " + inputZipFile);
            return;
        }
        // Create a temporary directory for unzipping.
        File tempDir = new File("temp_unzip");
        tempDir.mkdirs();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                File outFile = new File(tempDir, entry.getName());
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (InputStream in = zf.getInputStream(entry);
                         OutputStream out = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Process the unzipped directory structure.
        processMessagesFromDirectory(tempDir);
        // Optionally, delete the temporary directory after processing.
        deleteDirectory(tempDir);
    }

    /**
     * Processes messages from a folder defined by {@code input.folder.path}. It expects the folder
     * structure to follow the hierarchy YYYYMMDD/HH/mm.
     */
    private void processFolderInput() {
        File folder = new File(inputFolderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.err.println("Input folder not found: " + inputFolderPath);
            return;
        }
        processMessagesFromDirectory(folder);
    }

    /**
     * Recursively traverses the given root directory following the expected hierarchy (YYYYMMDD/HH/mm)
     * and processes each JSON file found by reading its content and delegating to processIncomingMessage.
     *
     * @param root the root directory to traverse.
     */
    private void processMessagesFromDirectory(File root) {
        // List date directories (expected to be in the format YYYYMMDD) and sort them.
        File[] dateDirs = root.listFiles(File::isDirectory);
        if (dateDirs == null) return;
        Arrays.sort(dateDirs, Comparator.comparing(File::getName));
        for (File dateDir : dateDirs) {
            File[] hourDirs = dateDir.listFiles(File::isDirectory);
            if (hourDirs == null) continue;
            Arrays.sort(hourDirs, Comparator.comparing(File::getName));
            for (File hourDir : hourDirs) {
                File[] minuteDirs = hourDir.listFiles(File::isDirectory);
                if (minuteDirs == null) continue;
                Arrays.sort(minuteDirs, Comparator.comparing(File::getName));
                for (File minuteDir : minuteDirs) {
                    File[] messageFiles = minuteDir.listFiles((dir, name) -> name.endsWith(".json"));
                    if (messageFiles == null) continue;
                    Arrays.sort(messageFiles, Comparator.comparing(File::getName));
                    for (File msgFile : messageFiles) {
                        try {
                            String content = new String(Files.readAllBytes(msgFile.toPath()));
                            // Process the file-based message with source "file".
                            processIncomingMessage(content, "file");
                            // Optionally, delete or archive the file after processing.
                            // Files.delete(msgFile.toPath());
                        } catch (Exception e) {
                            e.printStackTrace();
                            // On error, move the file to the dead letter queue preserving the hierarchy.
                            moveFileToDeadLetter(msgFile, dateDir.getName(), hourDir.getName(), minuteDir.getName());
                        }
                    }
                }
            }
        }
    }

    /**
     * Moves a file that failed processing to the dead letter queue, preserving the directory hierarchy.
     *
     * @param file   the file to move.
     * @param date   the date folder (YYYYMMDD).
     * @param hour   the hour folder (HH).
     * @param minute the minute folder (mm).
     */
    private void moveFileToDeadLetter(File file, String date, String hour, String minute) {
        try {
            String targetDir = deadLetterQueue + File.separator + date + File.separator + hour + File.separator + minute;
            Files.createDirectories(Paths.get(targetDir));
            Files.move(file.toPath(), Paths.get(targetDir, file.getName()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively deletes a directory.
     *
     * @param dir the directory to delete.
     */
    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            for (File sub : Objects.requireNonNull(dir.listFiles())) {
                deleteDirectory(sub);
            }
        }
        dir.delete();
    }

    /**
     * Shuts down the archive executor service when the application is about to close.
     */
    @PreDestroy
    public void shutdown() {
        archiveExecutor.shutdown();
    }

    /**
     * Inner class representing a bucket that groups messages by their LoadID.
     * It maintains the start time (for consolidation purposes) and a queue of messages.
     */
    private static class MessageBucket {
        private final long startTime;
        private final Queue<JsonNode> messages = new ConcurrentLinkedQueue<>();

        public MessageBucket() {
            this.startTime = System.currentTimeMillis();
        }

        /**
         * Adds a message node to the bucket.
         *
         * @param message the JSON message node to add.
         */
        public void addMessage(JsonNode message) {
            messages.add(message);
        }

        /**
         * Returns the creation time of the bucket.
         *
         * @return the start time in milliseconds.
         */
        public long getStartTime() {
            return startTime;
        }

        /**
         * Returns the queue of message nodes.
         *
         * @return the queue of messages.
         */
        public Queue<JsonNode> getMessages() {
            return messages;
        }
    }
}
