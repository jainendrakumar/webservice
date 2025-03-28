package framework.queue;

import framework.config.ConfigurationManager;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The {@code OutgoingMessageQueue} class manages the outgoing messages that are to be processed
 * asynchronously by the Message Framework Service. It uses a {@link PriorityBlockingQueue} to store
 * {@link OutgoingMessage} objects, ensuring that messages are processed in order based on their priority
 * (and by timestamp for FIFO ordering if priorities are equal).
 * <p>
 * The queue also supports persistence by periodically writing its current state to disk. This ensures that
 * in the event of a system failure, messages in the queue are not lost.
 * </p>
 *
 * <h3>Configuration:</h3>
 * The base folder for persistence is defined by the property {@code queue.persistence.folder} (default: {@code data/queue}).
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * // Inject the OutgoingMessageQueue via Spring
 * &#64;Autowired
 * private OutgoingMessageQueue outgoingQueue;
 *
 * // Enqueue a message
 * OutgoingMessage message = new OutgoingMessage("LoadAttribute", "{\"key\": \"value\"}", 1);
 * outgoingQueue.enqueue(message);
 *
 * // Poll a message with a timeout
 * OutgoingMessage msg = outgoingQueue.poll(10, TimeUnit.SECONDS);
 * </pre>
 *
 * @see OutgoingMessage
 * @see ConfigurationManager
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class OutgoingMessageQueue {

    /**
     * The configuration manager for loading properties.
     */
    @Autowired
    private ConfigurationManager configManager;

    /**
     * The priority queue used to store outgoing messages.
     */
    private PriorityBlockingQueue<OutgoingMessage> queue;

    /**
     * Initializes the outgoing message queue by creating the priority queue and loading any
     * persisted messages from disk. Also starts a background task to persist the queue state periodically.
     */
    @PostConstruct
    public void init() {
        queue = new PriorityBlockingQueue<>();
        loadPersistedQueue();
        startPersistenceTask();
    }

    /**
     * Enqueues an {@link OutgoingMessage} into the priority queue.
     *
     * @param message the outgoing message to enqueue.
     */
    public void enqueue(OutgoingMessage message) {
        queue.put(message);
    }

    /**
     * Polls the queue for an outgoing message, waiting up to the specified timeout if necessary.
     *
     * @param timeout the maximum time to wait.
     * @param unit    the time unit of the timeout.
     * @return the head of the queue, or {@code null} if the specified waiting time elapses before an element is available.
     * @throws InterruptedException if interrupted while waiting.
     */
    public OutgoingMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * Returns the current size of the queue.
     *
     * @return the number of messages in the queue.
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Persists the current state of the queue to disk.
     * <p>
     * The queue is persisted to a file in the folder specified by the property {@code queue.persistence.folder}.
     * Each message is written as a line in the file with fields separated by a pipe character.
     * </p>
     */
    private void persistQueue() {
        try {
            String folderPath = configManager.getProperty("queue.persistence.folder", "data/queue");
            File folder = new File(folderPath);
            if (!folder.exists()) {
                FileUtils.forceMkdir(folder);
            }
            File file = new File(folder, "outgoing_queue.txt");
            StringBuilder sb = new StringBuilder();
            for (OutgoingMessage msg : queue) {
                sb.append(msg.getMessageType())
                        .append("|").append(msg.getPriority())
                        .append("|").append(msg.getTimestamp())
                        .append("|").append(msg.getContent().replaceAll("\\n", " "))
                        .append(System.lineSeparator());
            }
            FileUtils.writeStringToFile(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads persisted messages from disk into the queue.
     * <p>
     * The method reads the persisted file and recreates {@link OutgoingMessage} objects which are then enqueued.
     * </p>
     */
    private void loadPersistedQueue() {
        try {
            String folderPath = configManager.getProperty("queue.persistence.folder", "data/queue");
            File file = new File(folderPath, "outgoing_queue.txt");
            if (file.exists()) {
                String content = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                String[] lines = content.split(System.lineSeparator());
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        String[] parts = line.split("\\|", 4);
                        String type = parts[0];
                        int priority = Integer.parseInt(parts[1]);
                        String msgContent = parts[3];
                        queue.put(new OutgoingMessage(type, msgContent, priority));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts a background task to persist the queue state to disk periodically.
     * <p>
     * The task runs in a separate thread and calls {@link #persistQueue()} every 60 seconds.
     * </p>
     */
    private void startPersistenceTask() {
        new Thread(() -> {
            while (true) {
                try {
                    persistQueue();
                    TimeUnit.SECONDS.sleep(60);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "QueuePersistenceThread").start();
    }
}
