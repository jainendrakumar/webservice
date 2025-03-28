package framework.persistence;

import framework.config.ConfigurationManager;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * The {@code MessagePersistenceManager} class is responsible for persisting outgoing messages to durable
 * storage, ensuring that messages are not lost in case of system failures. The default implementation uses
 * file-based storage to simulate database persistence. Each outgoing message is written to a separate file
 * under a directory that corresponds to its message type.
 *
 * <p>
 * <b>Design Considerations:</b>
 * <ul>
 *   <li><b>Persistence Mode:</b> This feature is optional and can be enabled or disabled via configuration.</li>
 *   <li><b>Storage Location:</b> The base folder for persisting messages is defined by the property
 *       {@code persistence.db.folder} (default: {@code data/db}).</li>
 *   <li><b>Message Durability:</b> Persisting messages enables the system to resume processing from the last
 *       checkpoint after a failure, supporting reliable delivery semantics.</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 *   &#64;Autowired
 *   private MessagePersistenceManager persistenceManager;
 *
 *   // Persist an outgoing message
 *   persistenceManager.persistMessage("LoadAttribute", jsonMessage);
 * </pre>
 * </p>
 *
 * <p>
 * <b>Configuration Example (in message-framework.properties):</b>
 * <pre>
 *   persistence.enabled=true
 *   persistence.db.folder=data/db
 * </pre>
 * </p>
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class MessagePersistenceManager {

    /**
     * The base folder for persisting outgoing messages, as defined in the configuration.
     */
    private final String dbFolder;

    /**
     * Constructs a new {@code MessagePersistenceManager} with configuration loaded from the
     * {@code ConfigurationManager}. The persistence folder is read from the property
     * {@code persistence.db.folder} and defaults to {@code data/db} if not specified.
     *
     * @param configManager the configuration manager for loading external properties.
     */
    public MessagePersistenceManager(ConfigurationManager configManager) {
        this.dbFolder = configManager.getProperty("persistence.db.folder", "data/db");
    }

    /**
     * Persists the given outgoing message to durable storage.
     * <p>
     * The message is stored in a subdirectory corresponding to its message type under the persistence folder.
     * A unique filename is generated using the current timestamp to ensure that each message is stored separately.
     * The file is written using UTF-8 encoding.
     * </p>
     *
     * @param messageType the type of the message (e.g., "LoadAttribute").
     * @param message     the content of the message in JSON format.
     */
    public void persistMessage(String messageType, String message) {
        try {
            // Create a directory for the message type under the base persistence folder.
            File folder = new File(dbFolder, messageType);
            if (!folder.exists()) {
                FileUtils.forceMkdir(folder);
            }
            // Generate a unique file name using the current timestamp.
            String fileName = System.currentTimeMillis() + ".msg";
            File file = new File(folder, fileName);
            // Write the message content to the file using UTF-8 encoding.
            FileUtils.writeStringToFile(file, message, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // In a production system, consider logging this error using a logging framework.
            e.printStackTrace();
        }
    }
}
