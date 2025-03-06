package framework.retry;

import framework.config.ConfigurationManager;
import framework.sender.MessageSender;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Processes messages that failed to send by scanning retry folders and reattempting delivery.
 *
 * @author JKR3
 */
@Component
public class RetryProcessor {

    @Autowired
    private ConfigurationManager configManager;

    @Autowired
    private MessageSender messageSender;

    private ScheduledExecutorService scheduler;

    /**
     * Initializes the retry processor to scan for retry files every 30 seconds.
     */
    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Schedule scanning of retry folders every 30 seconds
        scheduler.scheduleAtFixedRate(this::processRetryFolders, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Scans the retry base folder, attempts to resend messages, and deletes files on success.
     */
    private void processRetryFolders() {
        String retryBase = configManager.getRetryBasePath();
        File baseDir = new File(retryBase);
        if (!baseDir.exists()) return;
        Collection<File> files = FileUtils.listFiles(baseDir, new String[]{"json"}, true);
        for (File file : files) {
            try {
                String message = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                // Assuming filename pattern: <messageType>_retry_*.json
                String fileName = file.getName();
                String messageType = fileName.split("_")[0];
                boolean sent = messageSender.sendMessage(messageType, message);
                if (sent) {
                    FileUtils.forceDelete(file);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
