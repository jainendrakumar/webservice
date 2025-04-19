package com.example.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service for zipping and cleaning up archived message directories.
 * <p>
 * This service runs a scheduled task (configurable via {@code archive.zip.cron}) to:
 * <ol>
 *   <li>Zip the previous day's incoming and merged archive directories into zip files.</li>
 *   <li>Delete the original directories after successful zipping.</li>
 *   <li>Cleanup old zip files beyond the configured retention limit.</li>
 * </ol>
 * <p>
 * Configuration properties (external-config.properties):
 * <ul>
 *   <li>{@code archive.incoming.root} - Root folder for incoming message archives.</li>
 *   <li>{@code archive.merged.root}   - Root folder for merged message archives.</li>
 *   <li>{@code archive.zip.output.root} - Output folder for generated zip files.</li>
 *   <li>{@code archive.zip.cron}       - CRON expression for scheduling the zip task.</li>
 *   <li>{@code archive.zipper.enabled} - Switch to enable/disable the zip process.</li>
 *   <li>{@code archive.zip.maxFiles}   - Maximum number of zip files to retain.</li>
 * </ul>
 *
 * @author jkr3
 */
@Service
public class ArchiveZipper {

    /** Root directory containing archived incoming messages. */
    @Value("${archive.incoming.root}")
    private String incomingArchiveRoot;

    /** Root directory containing archived merged messages. */
    @Value("${archive.merged.root}")
    private String mergedArchiveRoot;

    /** Output root for generated zip files. */
    @Value("${archive.zip.output.root:archive/zipped}")
    private String zipOutputRoot;

    /** CRON expression for scheduling the zip job. */
    @Value("${archive.zip.cron:0 0 1 * * *}")
    private String zipCron;

    /** Switch to enable or disable the zip and delete process. */
    @Value("${archive.zipper.enabled:true}")
    private boolean zipperEnabled;

    /** Maximum number of zip files to retain in the output folder. */
    @Value("${archive.zip.maxFiles:30}")
    private int maxZipFiles;

    /**
     * Scheduled task that zips and deletes the previous day's archive directories.
     * Runs according to the configured CRON expression.
     */
    @Scheduled(cron = "${archive.zip.cron}")
    public void zipPreviousDayArchives() {
        if (!zipperEnabled) {
            return;
        }
        // Determine previous day in yyyyMMdd format.
        LocalDate previousDay = LocalDate.now().minusDays(1);
        String dateStr = previousDay.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // Zip and delete incoming and merged directories.
        zipAndDeleteDirectories(incomingArchiveRoot, dateStr, "incoming_" + dateStr + ".zip");
        zipAndDeleteDirectories(mergedArchiveRoot, dateStr, "merged_" + dateStr + ".zip");

        // Cleanup old zip files.
        cleanupOldZipFiles();
    }

    /**
     * Zips all subdirectories of {@code rootDir} starting with {@code datePrefix} into a single zip file,
     * then deletes the original directories upon successful zipping.
     *
     * @param rootDir       the root directory to scan
     * @param datePrefix    the date prefix for directories to zip (e.g., "20250314")
     * @param outputZipName the name of the output zip file
     */
    private void zipAndDeleteDirectories(String rootDir, String datePrefix, String outputZipName) {
        try {
            Path rootPath = Paths.get(rootDir);
            if (!Files.exists(rootPath)) {
                return;
            }
            File[] subDirs = new File(rootDir).listFiles(file ->
                    file.isDirectory() && file.getName().startsWith(datePrefix));
            if (subDirs == null || subDirs.length == 0) {
                return;
            }
            // Ensure the zip output directory exists.
            Path zipOutputPath = Paths.get(zipOutputRoot);
            if (!Files.exists(zipOutputPath)) {
                Files.createDirectories(zipOutputPath);
            }
            Path zipFilePath = zipOutputPath.resolve(outputZipName);
            try (FileOutputStream fos = new FileOutputStream(zipFilePath.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                for (File dir : subDirs) {
                    zipDirectory(dir, dir.getName(), zos);
                }
            }
            // Delete the original directories.
            for (File dir : subDirs) {
                deleteDirectory(dir.toPath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively adds files and subdirectories to the zip output.
     *
     * @param folder       the directory to zip
     * @param parentFolder the path within the zip file
     * @param zos          the ZipOutputStream to write entries
     * @throws IOException if an I/O error occurs
     */
    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos);
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    String zipEntryName = parentFolder + "/" + file.getName();
                    zos.putNextEntry(new ZipEntry(zipEntryName));
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Deletes a directory and all its contents recursively.
     *
     * @param path the root path to delete
     * @throws IOException if an I/O error occurs
     */
    private void deleteDirectory(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Removes the oldest zip files in the output directory if the total exceeds {@code maxZipFiles}.
     */
    private void cleanupOldZipFiles() {
        try {
            File zipDir = new File(zipOutputRoot);
            if (!zipDir.exists() || !zipDir.isDirectory()) {
                return;
            }
            File[] zipFiles = zipDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
            if (zipFiles == null || zipFiles.length <= maxZipFiles) {
                return;
            }
            Arrays.sort(zipFiles, Comparator.comparingLong(File::lastModified));
            int filesToDelete = zipFiles.length - maxZipFiles;
            for (int i = 0; i < filesToDelete; i++) {
                if (!zipFiles[i].delete()) {
                    System.err.println("Failed to delete old zip file: " + zipFiles[i].getAbsolutePath());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}