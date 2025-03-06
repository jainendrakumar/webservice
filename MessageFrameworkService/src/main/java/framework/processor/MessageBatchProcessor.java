package framework.processor;

import framework.config.ConfigurationManager;
import framework.sender.MessageSender;
import framework.archiver.MessageArchiver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;

/**
 * Handles batching of incoming messages.
 * Batches are flushed based on a configured time window or batch size.
 *
 * @author JKR3
 */
@Component
public class MessageBatchProcessor {

    // Map storing batches per message type (thread-safe)
    private final Map<String, List<String>> messageBatches = new ConcurrentHashMap<>();

    @Autowired
    private MessageSender messageSender;

    @Autowired
    private MessageArchiver messageArchiver;

    @Autowired
    private ConfigurationManager configManager;

    private ScheduledExecutorService scheduler;

    /**
     * Initializes the batch processor and schedules periodic flush.
     */
    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Schedule flush of all batches at a fixed rate based on the configured time window
        int timeWindowSec = configManager.getBatchTimeWindowSec();
        scheduler.scheduleAtFixedRate(this::flushAllBatches, timeWindowSec, timeWindowSec, TimeUnit.SECONDS);
    }

    /**
     * Adds a message to the batch for the specified type.
     *
     * @param messageType the type of message.
     * @param message     the message content.
     */
    public void addMessage(String messageType, String message) {
        messageBatches.computeIfAbsent(messageType, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(message);
        // Check if batch size limit is reached, then flush immediately
        int batchSize = configManager.getBatchSize(messageType);
        if (messageBatches.get(messageType).size() >= batchSize) {
            flushBatch(messageType);
        }
    }

    /**
     * Flushes all message batches.
     */
    public void flushAllBatches() {
        for (String messageType : messageBatches.keySet()) {
            flushBatch(messageType);
        }
    }

    /**
     * Flushes the batch for the specified message type.
     *
     * @param messageType the message type.
     */
    public void flushBatch(String messageType) {
        List<String> batch = messageBatches.get(messageType);
        if (batch != null && !batch.isEmpty()) {
            // Merge messages by joining with newline (for demonstration)
            String mergedMessage = String.join("\n", batch);
            // Send the merged message using the MessageSender
            messageSender.sendMessage(messageType, mergedMessage);
            // Archive the merged message
            messageArchiver.archiveMerged(messageType, mergedMessage);
            // Clear the batch after flushing
            batch.clear();
        }
    }
}
