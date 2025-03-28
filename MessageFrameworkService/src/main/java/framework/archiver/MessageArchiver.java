package framework.archiver;

import framework.config.ConfigurationManager;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The {@code MessageArchiver} class provides functionality to archive both raw incoming messages
 * and merged (batched) messages to the file system. It creates a dynamic folder structure based on
 * the current date, hour, and minute, ensuring that messages are organized chronologically.
 * <p>
 * This class uses configuration properties (via {@link ConfigurationManager}) to determine the
 * base paths for archiving incoming and merged messages.
 * <p>
 * <b>Usage:</b>
 * <pre>
 *     // Inject MessageArchiver via Spring
 *     @Autowired
 *     private MessageArchiver archiver;
 *
 *     // Archive a raw incoming message:
 *     archiver.archiveIncoming("LoadAttribute", rawJsonMessage);
 *
 *     // Archive a merged (batched) message:
 *     archiver.archiveMerged("LoadAttribute", mergedJsonMessage);
 * </pre>
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class MessageArchiver {

    @Autowired
    private ConfigurationManager configManager;

    /**
     * Archives a raw incoming message by writing it to a file in a dynamically created directory.
     * <p>
     * The directory is constructed using the base path provided in the configuration property
     * {@code archive.incoming.path} (default: {@code /data/archive/incoming}). The folder structure
     * follows the format: {@code basePath/yyyyMMdd/HH/mm}.
     * <p>
     * The file is named using the message type and a timestamp to ensure uniqueness.
     *
     * @param messageType the type of the message (e.g., "LoadAttribute").
     * @param message     the raw JSON message content.
     */
    public void archiveIncoming(String messageType, String message) {
        // Retrieve the base archive path for incoming messages from configuration.
        String basePath = configManager.getArchiveIncomingPath();
        // Construct a dynamic folder path based on the current date and time.
        String folderPath = getDynamicFolderPath(basePath);
        try {
            File dir = new File(folderPath);
            // Create the directory if it does not exist.
            if (!dir.exists()) {
                FileUtils.forceMkdir(dir);
            }
            // Generate a unique filename using the message type and current timestamp.
            String fileName = messageType + "_" + System.currentTimeMillis() + ".json";
            File file = new File(dir, fileName);
            // Write the message content to the file using UTF-8 encoding.
            FileUtils.writeStringToFile(file, message, "UTF-8");
        } catch (IOException e) {
            // Log the exception (could be enhanced with a proper logging framework).
            e.printStackTrace();
        }
    }

    /**
     * Archives a merged (batched) message by writing it to a file in a dynamically created directory.
     * <p>
     * The directory is constructed using the base path provided in the configuration property
     * {@code archive.merged.path} (default: {@code /data/archive/merged}). The folder structure
     * follows the format: {@code basePath/yyyyMMdd/HH/mm}.
     * <p>
     * The file is named with the message type, a "_merged_" identifier, and a timestamp to ensure uniqueness.
     *
     * @param messageType   the type of the message.
     * @param mergedMessage the merged (batched) message content.
     */
    public void archiveMerged(String messageType, String mergedMessage) {
        // Retrieve the base archive path for merged messages from configuration.
        String basePath = configManager.getArchiveMergedPath();
        // Construct a dynamic folder path based on the current date and time.
        String folderPath = getDynamicFolderPath(basePath);
        try {
            File dir = new File(folderPath);
            // Create the directory if it does not exist.
            if (!dir.exists()) {
                FileUtils.forceMkdir(dir);
            }
            // Generate a unique filename for the merged message.
            String fileName = messageType + "_merged_" + System.currentTimeMillis() + ".json";
            File file = new File(dir, fileName);
            // Write the merged message content to the file using UTF-8 encoding.
            FileUtils.writeStringToFile(file, mergedMessage, "UTF-8");
        } catch (IOException e) {
            // Log any I/O errors encountered during archiving.
            e.printStackTrace();
        }
    }

    /**
     * Constructs a dynamic folder path using a given base path and the current date and time.
     * <p>
     * The format of the folder path is: {@code basePath/yyyyMMdd/HH/mm}, where:
     * <ul>
     *   <li>{@code yyyyMMdd} represents the current date.</li>
     *   <li>{@code HH} represents the current hour.</li>
     *   <li>{@code mm} represents the current minute.</li>
     * </ul>
     *
     * @param basePath the base path for archiving.
     * @return the dynamically generated folder path.
     */
    private String getDynamicFolderPath(String basePath) {
        // Create date formatters for day, hour, and minute.
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat hourFormat = new SimpleDateFormat("HH");
        SimpleDateFormat minuteFormat = new SimpleDateFormat("mm");
        Date now = new Date();
        // Build the folder path by concatenating the base path with formatted date/time components.
        return basePath + File.separator + dayFormat.format(now)
                + File.separator + hourFormat.format(now)
                + File.separator + minuteFormat.format(now);
    }
}
