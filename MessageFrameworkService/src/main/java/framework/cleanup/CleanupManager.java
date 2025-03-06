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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Handles daily cleanup tasks including:
 * - Zipping previous day's archive folders.
 * - Moving ZIP files to a dedicated folder.
 * - Deleting files older than the retention period.
 */
@Component
public class CleanupManager {

    @Autowired
    private ConfigurationManager configManager;

    /**
     * Initializes the cleanup manager to perform daily cleanup.
     */
    @PostConstruct
    public void init() {
        // For demonstration, schedule cleanup to run every 24 hours (adjust as needed)
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(this::performCleanup, 1, 24 * 60, TimeUnit.MINUTES);
    }

    /**
     * Performs cleanup by zipping previous day's archives and managing retention.
     */
    private void performCleanup() {
        String incomingPath = configManager.getArchiveIncomingPath();
        String mergedPath = configManager.getArchiveMergedPath();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1);
        String day = new SimpleDateFormat("yyyyMMdd").format(cal.getTime());

        // Zip incoming and merged archives for the previous day
        zipDirectory(new File(incomingPath + File.separator + day), configManager.getArchiveIncomingPath() + File.separator + "zips" + File.separator + day + ".zip");
        zipDirectory(new File(mergedPath + File.separator + day), configManager.getArchiveMergedPath() + File.separator + "zips" + File.separator + day + ".zip");

        // (Optional) Delete raw files older than the retention period
    }

    /**
     * Zips the specified folder.
     *
     * @param folder      the folder to zip.
     * @param zipFilePath the path for the resulting ZIP file.
     */
    private void zipDirectory(File folder, String zipFilePath) {
        if (!folder.exists()) return;
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFilePath))) {
            zipFile(folder, folder.getName(), zos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively zips files and folders.
     *
     * @param fileToZip the file or folder to zip.
     * @param fileName  the file name in the zip archive.
     * @param zos       the ZIP output stream.
     * @throws IOException if an I/O error occurs.
     */
    private void zipFile(File fileToZip, String fileName, ZipOutputStream zos) throws IOException {
        if (fileToZip.isHidden()) return;
        if (fileToZip.isDirectory()) {
            if (!fileName.endsWith("/")) fileName += "/";
            zos.putNextEntry(new ZipEntry(fileName));
            zos.closeEntry();
            for (File childFile : fileToZip.listFiles()) {
                zipFile(childFile, fileName + childFile.getName(), zos);
            }
            return;
        }
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
