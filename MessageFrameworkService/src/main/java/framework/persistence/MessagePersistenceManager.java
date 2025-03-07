package framework.persistence;

import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Persists outgoing messages to disk (simulating database storage) for durability.
 *
 * @author JKR3
 */
@Component
public class MessagePersistenceManager {

    private final String dbFolder;

    public MessagePersistenceManager(framework.config.ConfigurationManager configManager) {
        this.dbFolder = configManager.getProperty("persistence.db.folder", "data/db");
    }

    public void persistMessage(String messageType, String message) {
        try {
            File folder = new File(dbFolder, messageType);
            if (!folder.exists()) {
                FileUtils.forceMkdir(folder);
            }
            String fileName = System.currentTimeMillis() + ".msg";
            File file = new File(folder, fileName);
            FileUtils.writeStringToFile(file, message, StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
