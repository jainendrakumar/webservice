package framework.config;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Loads configuration from an external properties file and supports hot-reload.
 *
 * @author JKR3
 */
@Component
public class ConfigurationManager {

    private Properties properties = new Properties();
    // Path to external properties file (adjust as needed)
    private final String propertiesFilePath = "src/main/resources/message-framework.properties";

    /**
     * Initializes the configuration manager and schedules periodic reloading of properties.
     */
    @PostConstruct
    public void init() {
        loadProperties();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Reload properties every 30 seconds
        scheduler.scheduleAtFixedRate(this::loadProperties, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Loads properties from the configured file.
     */
    private void loadProperties() {
        try (FileInputStream fis = new FileInputStream(propertiesFilePath)) {
            properties.load(fis);
            System.out.println("Properties reloaded");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves a property value.
     *
     * @param key the property key.
     * @return the property value.
     */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Retrieves the batch time window (in seconds) from properties.
     *
     * @return the time window in seconds.
     */
    public int getBatchTimeWindowSec() {
        return Integer.parseInt(properties.getProperty("batch.LoadAttribute.timeWindowSec", "3"));
    }

    /**
     * Retrieves the batch size for the specified message type.
     *
     * @param messageType the message type.
     * @return the batch size.
     */
    public int getBatchSize(String messageType) {
        return Integer.parseInt(properties.getProperty("batch.LoadAttribute.size", "100"));
    }

    /**
     * Retrieves the archive path for incoming messages.
     *
     * @return the archive incoming path.
     */
    public String getArchiveIncomingPath() {
        return properties.getProperty("archive.incoming.path", "/data/archive/incoming");
    }

    /**
     * Retrieves the archive path for merged messages.
     *
     * @return the archive merged path.
     */
    public String getArchiveMergedPath() {
        return properties.getProperty("archive.merged.path", "/data/archive/merged");
    }

    /**
     * Retrieves the target endpoint for a given message type.
     *
     * @param messageType the message type.
     * @return the target endpoint URL.
     */
    public String getEndpointForType(String messageType) {
        return properties.getProperty("endpoint." + messageType);
    }

    /**
     * Retrieves the report path for CSV output.
     *
     * @return the report path.
     */
    public String getReportPath() {
        return properties.getProperty("report.path", "/data/reports");
    }

    /**
     * Retrieves the base path for retry storage.
     *
     * @return the retry base path.
     */
    public String getRetryBasePath() {
        // For demonstration purposes, using a fixed retry folder
        return "/data/retry";
    }
}
