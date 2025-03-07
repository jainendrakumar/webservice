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
 * Processes messages that previously failed to send.
 * Scans the retry folder periodically and reattempts delivery.
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

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::processRetryFolders, 30, 30, TimeUnit.SECONDS);
    }

    private void processRetryFolders() {
        String retryBase = configManager.getProperty("retry.base.path", "/data/retry");
        File baseDir = new File(retryBase);
        if (!baseDir.exists()) return;
        Collection<File> files = FileUtils.listFiles(baseDir, new String[]{"json"}, true);
        for (File file : files) {
            try {
                String message = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                String fileName = file.getName();
                String messageType = fileName.split("_")[0];
                boolean sent = messageSender.sendMessageToEndpoint(messageType, message);
                if (sent) {
                    FileUtils.forceDelete(file);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
