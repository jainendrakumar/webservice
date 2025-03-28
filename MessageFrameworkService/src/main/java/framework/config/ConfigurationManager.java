package framework.config;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@code ConfigurationManager} class is responsible for loading and managing external configuration
 * properties from a file. It supports hot reloading of the configuration at a fixed interval.
 *
 * <p>It provides methods to retrieve configuration values with default fallbacks.</p>
 *
 * <p>Example usage:
 * <pre>
 *     &#64;Autowired
 *     private ConfigurationManager configManager;
 *
 *     String endpoint = configManager.getProperty("endpoint.LoadAttribute", "http://default-endpoint");
 * </pre>
 * </p>
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class ConfigurationManager {

    private Properties properties = new Properties();
    private final String propertiesFilePath = "src/main/resources/message-framework.properties";

    @PostConstruct
    public void init() {
        loadProperties();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::loadProperties, 30, 30, TimeUnit.SECONDS);
    }

    private void loadProperties() {
        try (FileInputStream fis = new FileInputStream(propertiesFilePath)) {
            properties.load(fis);
            System.out.println("Properties reloaded from: " + propertiesFilePath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the value of the given property.
     *
     * @param key the property key.
     * @return the property value, or null if not found.
     */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Retrieves the value of the given property, or returns a default value if not found.
     *
     * @param key          the property key.
     * @param defaultValue the default value.
     * @return the property value or the default value.
     */
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Returns the batch time window (in seconds) for the "LoadAttribute" message type.
     *
     * @return the batch time window in seconds.
     */
    public int getBatchTimeWindowSec() {
        return Integer.parseInt(getProperty("batch.LoadAttribute.timeWindowSec", "3"));
    }

    /**
     * Returns the batch size for the specified message type.
     *
     * @param messageType the message type.
     * @return the batch size.
     */
    public int getBatchSize(String messageType) {
        return Integer.parseInt(getProperty("batch.LoadAttribute.size", "100"));
    }

    /**
     * Returns the base path for archiving incoming messages.
     *
     * @return the archive incoming path.
     */
    public String getArchiveIncomingPath() {
        return getProperty("archive.incoming.path", "/data/archive/incoming");
    }

    /**
     * Returns the base path for archiving merged messages.
     *
     * @return the archive merged path.
     */
    public String getArchiveMergedPath() {
        return getProperty("archive.merged.path", "/data/archive/merged");
    }

    /**
     * Returns the base path for storing retry messages.
     *
     * @return the retry base path.
     */
    public String getRetryBasePath() {
        return getProperty("retry.base.path", "/data/retry");
    }

    /**
     * Returns the report path for metrics.
     *
     * @return the report path.
     */
    public String getReportPath() {
        return getProperty("report.path", "/data/reports");
    }
}
