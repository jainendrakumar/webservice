package framework.config;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Loads configuration from an external properties file and supports hot reload.
 *
 * @author JKR3
 */
@Component
public class ConfigurationManager {

    private Properties properties = new Properties();
    // Path to external properties file (adjust as needed)
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
            System.out.println("Properties reloaded");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Returns the property value if present, or the provided defaultValue otherwise.
     */
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getBatchTimeWindowSec() {
        return Integer.parseInt(getProperty("batch.LoadAttribute.timeWindowSec", "3"));
    }

    public int getBatchSize(String messageType) {
        return Integer.parseInt(getProperty("batch.LoadAttribute.size", "100"));
    }

    public String getArchiveIncomingPath() {
        return getProperty("archive.incoming.path", "/data/archive/incoming");
    }

    public String getArchiveMergedPath() {
        return getProperty("archive.merged.path", "/data/archive/merged");
    }

    public String getEndpointForType(String messageType) {
        return getProperty("endpoint." + messageType);
    }

    public String getReportPath() {
        return getProperty("report.path", "/data/reports");
    }

    public String getRetryBasePath() {
        return getProperty("retry.base.path", "/data/retry");
    }
}
