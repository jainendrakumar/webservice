package framework.cleanup;

import framework.config.ConfigurationManager;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The {@code CleanupManager} class performs periodic cleanup operations on archive directories.
 * <p>
 * This class is responsible for:
 * <ul>
 *   <li>Zipping the previous day's archive folders for incoming and merged messages.</li>
 *   <li>Moving the generated ZIP files to a designated subfolder (e.g., "zips").</li>
 *   <li>(Optionally) Deleting raw files that are older than a defined retention period.</li>
 * </ul>
 * <p>
 * The cleanup process is scheduled to run periodically (by default, every 24 hours) using a
 * {@link java.util.concurrent.ScheduledExecutorService}. The base archive directories and other
 * configuration values are read from an external properties file via {@link ConfigurationManager}.
 * </p>
 *
 * <h3>Usage</h3>
 * <pre>
 *   // The CleanupManager is a Spring component and is automatically instantiated.
 *   // Once the application starts, it schedules a cleanup task that runs daily.
 * </pre>
 *
 * <h3>Configuration Properties (from message-framework.properties)</h3>
 * <ul>
 *   <li><b>archive.incoming.path</b> - Base directory for incoming message archives (e.g., /data/archive/incoming).</li>
 *   <li><b>archive.merged.path</b> - Base directory for merged message archives (e.g., /data/archive/merged).</li>
 *   <li><b>zip.cleanup.enabled</b> - (Optional) Flag to enable cleanup operations.</li>
 *   <li><b>zip.cleanup.time</b> - (Optional) Time at which cleanup should be performed (not implemented in this demo).</li>
 *   <li><b>zip.retain.days</b> - (Optional) Number of days to retain raw files before deletion.</li>
 * </ul>
 *
 * @author jkr3 (Jainendra Kumar)
 */
@Component
public class CleanupManager {

    @Autowired
    private ConfigurationManager configManager;

    /**
     * Initializes the CleanupManager by scheduling the cleanup task.
     * <p>
     * The cleanup task is scheduled to run periodically after an initial delay. In this example,
     * the task is scheduled to run every 24 hours (i.e., 1440 minutes). Adjust the schedule as needed.
     * </p>
     */
    @PostConstruct
    public void init() {
        // Schedule cleanup to run every 24 hours (1440 minutes) after an initial delay of 1 minute.
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(
                this::performCleanup, 1, 24 * 60, TimeUnit.MINUTES);
    }

    /**
     * Performs the cleanup operation by zipping the previous day's archive folders.
     * <p>
     * This method calculates the folder corresponding to the previous day, and then zips the contents
     * of both the incoming and merged archive folders. The resulting ZIP files are saved in a dedicated
     * "zips" subfolder within the respective archive directories.
     * </p>
     */
    private void performCleanup() {
        // Retrieve archive base paths from configuration.
        String incomingPath = configManager.getArchiveIncomingPath();
        String mergedPath = configManager.getArchiveMergedPath();

        // Calculate the previous day's date in the format yyyyMMdd.
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1);
        String day = new SimpleDateFormat("yyyyMMdd").format(cal.getTime());

        // Construct ZIP file paths for the previous day's archives.
        String incomingZipPath = incomingPath + File.separator + "zips" + File.separator + day + ".zip";
        String mergedZipPath = mergedPath + File.separator + "zips" + File.separator + day + ".zip";

        // Zip the previous day's incoming and merged folders.
        zipDirectory(new File(incomingPath + File.separator + day), incomingZipPath);
        zipDirectory(new File(mergedPath + File.separator + day), mergedZipPath);

        // Optionally, you can delete raw files older than a retention period here.
    }

    /**
     * Zips the contents of a specified folder and saves the ZIP file to the provided path.
     * <p>
     * If the folder does not exist, this method returns without taking action.
     * </p>
     *
     * @param folder      the folder whose contents are to be zipped.
     * @param zipFilePath the destination path for the ZIP file.
     */
    private void zipDirectory(File folder, String zipFilePath) {
        if (!folder.exists()) return;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFilePath))) {
            zipFile(folder, folder.getName(), zos);
        } catch (IOException e) {
            // Log any I/O exceptions encountered during the zipping process.
            e.printStackTrace();
        }
    }

    /**
     * Recursively zips a file or directory.
     * <p>
     * If the {@code fileToZip} is a directory, this method will add all its contents recursively.
     * Hidden files are skipped.
     * </p>
     *
     * @param fileToZip the file or directory to be zipped.
     * @param fileName  the name of the file entry in the ZIP archive.
     * @param zos       the {@link ZipOutputStream} used for writing the ZIP file.
     * @throws IOException if an I/O error occurs during zipping.
     */
    private void zipFile(File fileToZip, String fileName, ZipOutputStream zos) throws IOException {
        // Skip hidden files.
        if (fileToZip.isHidden()) return;
        if (fileToZip.isDirectory()) {
            // Append a trailing slash to directory names.
            if (!fileName.endsWith("/")) {
                fileName += "/";
            }
            zos.putNextEntry(new ZipEntry(fileName));
            zos.closeEntry();
            // Recursively add files from the directory.
            for (File childFile : fileToZip.listFiles()) {
                zipFile(childFile, fileName + childFile.getName(), zos);
            }
            return;
        }
        // For a file, create a new entry and write its content.
        try (FileInputStream fis = new FileInputStream(fileToZip)) {
            ZipEntry zipEntry = new ZipEntry(fileName);
            zos.putNextEntry(zipEntry);
            byte[] bytes = new byte[1024];
            int length;
            while ((length = fis.read(bytes)) >= 0) {
                zos.write(bytes, 0, length);
            }
        }
    }
}
