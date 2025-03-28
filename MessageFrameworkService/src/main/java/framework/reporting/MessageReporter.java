package framework.reporting;

import framework.config.ConfigurationManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The {@code MessageReporter} class is responsible for collecting and reporting metrics related
 * to the message processing within the Message Framework Service.
 *
 * <p>
 * Metrics such as the number of incoming messages, outgoing messages, and processing latency are recorded.
 * The reporter generates CSV reports at regular intervals (e.g., every minute) and saves them to a configurable
 * directory. These metrics can be used for operational monitoring, performance tuning, and capacity planning.
 * </p>
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * // The MessageReporter is automatically instantiated as a Spring component.
 * // It schedules report generation every minute.
 * // Metrics are recorded by calling recordIncoming() and recordOutgoing() from other components.
 * </pre>
 *
 * @see ConfigurationManager
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class MessageReporter {

    @Autowired
    private ConfigurationManager configManager;

    /**
     * Scheduled executor to run report generation periodically.
     */
    private ScheduledExecutorService scheduler;

    /**
     * Counter for incoming messages.
     */
    private volatile int incomingCount = 0;

    /**
     * Counter for outgoing messages.
     */
    private volatile int outgoingCount = 0;

    /**
     * Accumulator for incoming message bytes.
     */
    private volatile long incomingBytes = 0;

    /**
     * Accumulator for outgoing message bytes.
     */
    private volatile long outgoingBytes = 0;

    /**
     * Initializes the MessageReporter and schedules the report generation task.
     * <p>
     * Reports are generated every minute as configured.
     * </p>
     */
    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::generateReport, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Records metrics for incoming messages.
     *
     * @param count the number of incoming messages.
     * @param bytes the total number of bytes received.
     */
    public synchronized void recordIncoming(int count, long bytes) {
        incomingCount += count;
        incomingBytes += bytes;
    }

    /**
     * Records metrics for outgoing messages.
     *
     * @param count the number of outgoing messages.
     * @param bytes the total number of bytes sent.
     */
    public synchronized void recordOutgoing(int count, long bytes) {
        outgoingCount += count;
        outgoingBytes += bytes;
    }

    /**
     * Generates CSV reports for the current reporting interval and resets counters.
     * <p>
     * Reports are stored in a directory defined by the configuration property {@code report.path}.
     * Each report file is named based on the current date (yyyyMMdd) and contains the timestamp,
     * count, and total bytes for both incoming and outgoing messages.
     * </p>
     */
    private void generateReport() {
        String reportPath = configManager.getProperty("report.path", "/data/reports");
        String day = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        String dirPath = reportPath + File.separator + day;
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String incomingFile = dirPath + File.separator + "incoming.csv";
        String outgoingFile = dirPath + File.separator + "outgoing.csv";
        try {
            // Write incoming metrics
            FileWriter incomingWriter = new FileWriter(incomingFile, true);
            CSVPrinter incomingPrinter = new CSVPrinter(incomingWriter, CSVFormat.DEFAULT);
            incomingPrinter.printRecord(System.currentTimeMillis(), incomingCount, incomingBytes);
            incomingPrinter.close();

            // Write outgoing metrics
            FileWriter outgoingWriter = new FileWriter(outgoingFile, true);
            CSVPrinter outgoingPrinter = new CSVPrinter(outgoingWriter, CSVFormat.DEFAULT);
            outgoingPrinter.printRecord(System.currentTimeMillis(), outgoingCount, outgoingBytes);
            outgoingPrinter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Reset counters for the next interval
        incomingCount = 0;
        incomingBytes = 0;
        outgoingCount = 0;
        outgoingBytes = 0;
    }

    /**
     * Returns the current count of incoming messages.
     *
     * @return the incoming message count.
     */
    public int getIncomingCount() {
        return incomingCount;
    }

    /**
     * Returns the current count of outgoing messages.
     *
     * @return the outgoing message count.
     */
    public int getOutgoingCount() {
        return outgoingCount;
    }
}
