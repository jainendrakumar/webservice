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
 * Manages the outgoing message queue using a priority-based blocking queue.
 * Supports persistence to disk to ensure messages are not lost.
 *
 * @author JKR3
 */
@Component
public class OutgoingMessageQueue {

    @Autowired
    private ConfigurationManager configManager;

    private PriorityBlockingQueue<OutgoingMessage> queue;

    @PostConstruct
    public void init() {
        queue = new PriorityBlockingQueue<>();
        loadPersistedQueue();
        startPersistenceTask();
    }

    public void enqueue(OutgoingMessage message) {
        queue.put(message);
    }

    public OutgoingMessage poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    public int getQueueSize() {
        return queue.size();
    }

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
