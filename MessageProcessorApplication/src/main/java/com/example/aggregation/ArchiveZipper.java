package com.example.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Jainendra Kumar
 * ToDo:
 */

@Service
public class ArchiveZipper {

    @Value("${archive.incoming.root}")
    private String incomingArchiveRoot;

    @Value("${archive.merged.root}")
    private String mergedArchiveRoot;

    // Output folder for zipped archives.
    @Value("${archive.zip.output.root:archive/zipped}")
    private String zipOutputRoot;

    // Cron expression for running the zip job daily at a specific time.
    @Value("${archive.zip.cron:0 0 1 * * *}")
    private String zipCron;

    // Switch to enable/disable the zip and delete process.
    @Value("${archive.zipper.enabled:true}")
    private boolean zipperEnabled;

    // Maximum number of zip files to retain in the zip output folder.
    @Value("${archive.zip.maxFiles:30}")
    private int maxZipFiles;

    @Scheduled(cron = "${archive.zip.cron}")
    public void zipPreviousDayArchives() {
        if (!zipperEnabled) {
            return;
        }
        // Determine previous day's date in "yyyyMMdd" format.
        LocalDate previousDay = LocalDate.now().minusDays(1);
        String dateStr = previousDay.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        zipAndDeleteDirectories(incomingArchiveRoot, dateStr, "incoming_" + dateStr + ".zip");
        zipAndDeleteDirectories(mergedArchiveRoot, dateStr, "merged_" + dateStr + ".zip");

        // Clean up old zip files if more than maxZipFiles are present.
        cleanupOldZipFiles();
    }

    private void zipAndDeleteDirectories(String rootDir, String datePrefix, String outputZipName) {
        try {
            Path rootPath = Paths.get(rootDir);
            if (!Files.exists(rootPath)) {
                return;
            }
            // Find all subdirectories whose name starts with the previous day's date.
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
            // After successful zipping, delete the source directories.
            for (File dir : subDirs) {
                deleteDirectory(dir.toPath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                continue;
            }
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
     * Deletes older zip files in the zip output directory if more than maxZipFiles exist.
     */
    private void cleanupOldZipFiles() {
        try {
            File zipDir = new File(zipOutputRoot);
            if (!zipDir.exists() || !zipDir.isDirectory()) {
                return;
            }
            // List zip files in the directory.
            File[] zipFiles = zipDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
            if (zipFiles == null || zipFiles.length <= maxZipFiles) {
                return;
            }
            // Sort the files by last modified date (oldest first).
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
