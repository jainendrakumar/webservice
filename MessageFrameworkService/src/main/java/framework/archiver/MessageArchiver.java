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
 * Archives incoming and merged messages into dynamic folder structures based on the current date, hour, and minute.
 *
 * @author JKR3
 */
@Component
public class MessageArchiver {

    @Autowired
    private ConfigurationManager configManager;

    /**
     * Archives a raw incoming message.
     *
     * @param messageType the message type.
     * @param message     the raw message content.
     */
    public void archiveIncoming(String messageType, String message) {
        String archivePath = configManager.getArchiveIncomingPath();
        String folderPath = getDynamicFolderPath(archivePath);
        try {
            File dir = new File(folderPath);
            if (!dir.exists()) {
                FileUtils.forceMkdir(dir);
            }
            String fileName = messageType + "_" + System.currentTimeMillis() + ".json";
            File file = new File(dir, fileName);
            FileUtils.writeStringToFile(file, message, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Archives a merged (batched) message.
     *
     * @param messageType   the message type.
     * @param mergedMessage the merged message content.
     */
    public void archiveMerged(String messageType, String mergedMessage) {
        String archivePath = configManager.getArchiveMergedPath();
        String folderPath = getDynamicFolderPath(archivePath);
        try {
            File dir = new File(folderPath);
            if (!dir.exists()) {
                FileUtils.forceMkdir(dir);
            }
            String fileName = messageType + "_merged_" + System.currentTimeMillis() + ".json";
            File file = new File(dir, fileName);
            FileUtils.writeStringToFile(file, mergedMessage, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Constructs a dynamic folder path using the base path and the current date/time.
     *
     * @param basePath the base archive path.
     * @return the dynamic folder path in the format base/yyyyMMdd/HH/mm.
     */
    private String getDynamicFolderPath(String basePath) {
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd");
        SimpleDateFormat hourFormat = new SimpleDateFormat("HH");
        SimpleDateFormat minuteFormat = new SimpleDateFormat("mm");
        Date now = new Date();
        return basePath + File.separator + dayFormat.format(now)
                + File.separator + hourFormat.format(now)
                + File.separator + minuteFormat.format(now);
    }
}
