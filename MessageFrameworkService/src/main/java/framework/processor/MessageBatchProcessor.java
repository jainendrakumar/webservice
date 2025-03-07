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
 * Batches incoming messages by type.
 * Flushes batches based on a time window or when a batch size threshold is reached.
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

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        int timeWindowSec = configManager.getBatchTimeWindowSec();
        scheduler.scheduleAtFixedRate(this::flushAllBatches, timeWindowSec, timeWindowSec, TimeUnit.SECONDS);
    }

    public void addMessage(String messageType, String message) {
        messageBatches.computeIfAbsent(messageType, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(message);
        int batchSize = configManager.getBatchSize(messageType);
        if (messageBatches.get(messageType).size() >= batchSize) {
            flushBatch(messageType);
        }
    }

    public void flushAllBatches() {
        for (String messageType : messageBatches.keySet()) {
            flushBatch(messageType);
        }
    }

    public void flushBatch(String messageType) {
        List<String> batch = messageBatches.get(messageType);
        if (batch != null && !batch.isEmpty()) {
            String mergedMessage = String.join("\n", batch);
            // Use the correct method to enqueue the outgoing message
            messageSender.enqueueOutgoingMessage(messageType, mergedMessage);
            messageArchiver.archiveMerged(messageType, mergedMessage);
            batch.clear();
        }
    }
}
