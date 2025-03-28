package framework.processor;

import framework.archiver.MessageArchiver;
import framework.config.ConfigurationManager;
import framework.sender.MessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@code MessageBatchProcessor} class is responsible for collecting incoming messages,
 * grouping them by message type, and flushing them as a merged batch based on either a configurable
 * time window or a batch size threshold.
 * <p>
 * When a batch is flushed, the merged message is sent to the outgoing queue for asynchronous processing
 * and also archived using the {@link MessageArchiver}.
 * </p>
 *
 * <p>
 * The batching parameters (such as batch size and time window) are configured via the external configuration
 * properties loaded by the {@link ConfigurationManager}. This allows the behavior of the batch processor to be
 * dynamically controlled without changing the code.
 * </p>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * &#64;Autowired
 * private MessageBatchProcessor batchProcessor;
 *
 * // Add a new message for type "LoadAttribute"
 * batchProcessor.addMessage("LoadAttribute", "{\"key\": \"value\"}");
 * </pre>
 *
 * @see MessageSender
 * @see MessageArchiver
 * @see ConfigurationManager
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class MessageBatchProcessor {

    /**
     * A thread-safe map that stores lists of messages for each message type.
     */
    private final Map<String, List<String>> messageBatches = new ConcurrentHashMap<>();

    @Autowired
    private MessageSender messageSender;

    @Autowired
    private MessageArchiver messageArchiver;

    @Autowired
    private ConfigurationManager configManager;

    /**
     * A scheduled executor to periodically flush all message batches.
     */
    private ScheduledExecutorService scheduler;

    /**
     * Initializes the batch processor by starting a scheduled task that flushes all batches
     * at fixed intervals. The flush interval is configured via the property {@code batch.LoadAttribute.timeWindowSec}.
     */
    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        int timeWindowSec = configManager.getBatchTimeWindowSec();
        scheduler.scheduleAtFixedRate(this::flushAllBatches, timeWindowSec, timeWindowSec, TimeUnit.SECONDS);
    }

    /**
     * Adds an incoming message to the batch for the specified message type.
     * If the batch size for that type reaches the configured threshold, the batch is flushed immediately.
     *
     * @param messageType the type of the message (e.g., "LoadAttribute")
     * @param message     the JSON message content
     */
    public void addMessage(String messageType, String message) {
        messageBatches.computeIfAbsent(messageType, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(message);
        int batchSize = configManager.getBatchSize(messageType);
        if (messageBatches.get(messageType).size() >= batchSize) {
            flushBatch(messageType);
        }
    }

    /**
     * Flushes all message batches by iterating over all message types and flushing each batch.
     */
    public void flushAllBatches() {
        for (String messageType : messageBatches.keySet()) {
            flushBatch(messageType);
        }
    }

    /**
     * Flushes the batch for the specified message type.
     * <p>
     * This method merges the messages into a single payload (using newline as a delimiter),
     * enqueues the merged message for sending via the {@link MessageSender}, archives the merged
     * message using the {@link MessageArchiver}, and clears the batch.
     * </p>
     *
     * @param messageType the type of the messages to flush.
     */
    public void flushBatch(String messageType) {
        List<String> batch = messageBatches.get(messageType);
        if (batch != null && !batch.isEmpty()) {
            String mergedMessage = String.join("\n", batch);
            // Enqueue merged message for asynchronous sending
            messageSender.enqueueOutgoingMessage(messageType, mergedMessage);
            // Archive the merged message
            messageArchiver.archiveMerged(messageType, mergedMessage);
            // Clear the batch after flushing
            batch.clear();
        }
    }
}
