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
 * The {@code RetryProcessor} class handles the reprocessing of messages that failed to be sent.
 *
 * <p>
 * Failed messages are stored as JSON files in a designated retry folder. The RetryProcessor
 * periodically scans the retry folder, attempts to resend each message using {@link MessageSender#sendMessageToEndpoint(String, String)},
 * and deletes the file upon successful sending.
 * </p>
 *
 * <p>
 * The retry folder location is configurable via the external configuration property
 * {@code retry.base.path}.
 * </p>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * // The RetryProcessor is automatically instantiated as a Spring component.
 * // It periodically processes files from the retry folder.
 * </pre>
 *
 * @see MessageSender
 * @see ConfigurationManager
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class RetryProcessor {

    @Autowired
    private ConfigurationManager configManager;

    @Autowired
    private MessageSender messageSender;

    private ScheduledExecutorService scheduler;

    /**
     * Initializes the RetryProcessor by scheduling the retry task to run periodically.
     * <p>
     * The retry task is scheduled to run every 30 seconds.
     * </p>
     */
    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::processRetryFolders, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Scans the retry folder for failed messages, attempts to resend them, and deletes
     * the message file upon successful delivery.
     */
    private void processRetryFolders() {
        String retryBase = configManager.getProperty("retry.base.path", "/data/retry");
        File baseDir = new File(retryBase);
        if (!baseDir.exists()) return;

        // List all JSON files recursively in the retry folder
        Collection<File> files = FileUtils.listFiles(baseDir, new String[]{"json"}, true);
        for (File file : files) {
            try {
                String message = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
                // Assume filename pattern: <messageType>_retry_*.json
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
