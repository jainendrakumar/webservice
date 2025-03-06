package framework.reporting;

import framework.config.ConfigurationManager;
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
 * Generates CSV reports every minute capturing incoming and outgoing message statistics.
 *
 * @author JKR3
 */
@Component
public class MessageReporter {

    @Autowired
    private ConfigurationManager configManager;

    private ScheduledExecutorService scheduler;

    // Counters for statistics
    private volatile int incomingCount = 0;
    private volatile int outgoingCount = 0;
    private volatile long incomingBytes = 0;
    private volatile long outgoingBytes = 0;

    /**
     * Initializes the reporter to generate a report every minute.
     */
    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Schedule report generation every minute
        scheduler.scheduleAtFixedRate(this::generateReport, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Records incoming message statistics.
     *
     * @param count number of messages.
     * @param bytes total bytes.
     */
    public void recordIncoming(int count, long bytes) {
        incomingCount += count;
        incomingBytes += bytes;
    }

    /**
     * Records outgoing message statistics.
     *
     * @param count number of messages.
     * @param bytes total bytes.
     */
    public void recordOutgoing(int count, long bytes) {
        outgoingCount += count;
        outgoingBytes += bytes;
    }

    /**
     * Generates CSV reports and resets counters.
     */
    private void generateReport() {
        String reportPath = configManager.getReportPath();
        String day = new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date());
        String dirPath = reportPath + File.separator + day;
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String incomingFile = dirPath + File.separator + "incoming.csv";
        String outgoingFile = dirPath + File.separator + "outgoing.csv";
        try {
            // Append incoming statistics
            FileWriter incomingWriter = new FileWriter(incomingFile, true);
            CSVPrinter incomingPrinter = new CSVPrinter(incomingWriter, CSVFormat.DEFAULT);
            incomingPrinter.printRecord(System.currentTimeMillis(), incomingCount, incomingBytes);
            incomingPrinter.close();

            // Append outgoing statistics
            FileWriter outgoingWriter = new FileWriter(outgoingFile, true);
            CSVPrinter outgoingPrinter = new CSVPrinter(outgoingWriter, CSVFormat.DEFAULT);
            outgoingPrinter.printRecord(System.currentTimeMillis(), outgoingCount, outgoingBytes);
            outgoingPrinter.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Reset counters after reporting
        incomingCount = 0;
        incomingBytes = 0;
        outgoingCount = 0;
        outgoingBytes = 0;
    }
}
