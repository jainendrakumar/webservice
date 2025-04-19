// src/main/java/com/example/aggregation/ArchiveZipper.java
package com.example.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips yesterday’s archives and cleans up old zips.
 */
@Service
public class ArchiveZipper {

    @Value("${archive.incoming.root}")
    private String incomingArchiveRoot;

    @Value("${archive.merged.root}")
    private String mergedArchiveRoot;

    @Value("${archive.zip.output.root:archive/zipped}")
    private String zipOutputRoot;

    @Value("${archive.zip.cron:0 0 1 * * *}")
    private String zipCron;

    @Value("${archive.zipper.enabled:true}")
    private boolean zipperEnabled;

    @Value("${archive.zip.maxFiles:30}")
    private int maxZipFiles;

    @Scheduled(cron = "${archive.zip.cron}")
    public void zipPreviousDayArchives() {
        if (!zipperEnabled) return;
        LocalDate previousDay = LocalDate.now().minusDays(1);
        String dateStr = previousDay.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        zipAndDeleteDirectories(incomingArchiveRoot, dateStr, "incoming_" + dateStr + ".zip");
        zipAndDeleteDirectories(mergedArchiveRoot, dateStr, "merged_" + dateStr + ".zip");
        cleanupOldZipFiles();
    }

    private void zipAndDeleteDirectories(String rootDir, String datePrefix, String outputZipName) {
        try {
            Path rootPath = Paths.get(rootDir);
            if (!Files.exists(rootPath)) return;
            File[] subDirs = new File(rootDir).listFiles(f ->
                    f.isDirectory() && f.getName().startsWith(datePrefix)
            );
            if (subDirs == null || subDirs.length == 0) return;

            Path zipOut = Paths.get(zipOutputRoot);
            if (!Files.exists(zipOut)) Files.createDirectories(zipOut);
            Path zipFilePath = zipOut.resolve(outputZipName);

            try (FileOutputStream fos = new FileOutputStream(zipFilePath.toFile());
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                for (File dir : subDirs) {
                    zipDirectory(dir, dir.getName(), zos);
                }
            }

            for (File dir : subDirs) {
                deleteDirectory(dir.toPath());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                zipDirectory(file, parentFolder + "/" + file.getName(), zos);
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    zos.putNextEntry(new ZipEntry(parentFolder + "/" + file.getName()));
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

    private void deleteDirectory(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private void cleanupOldZipFiles() {
        try {
            File zipDir = new File(zipOutputRoot);
            if (!zipDir.exists() || !zipDir.isDirectory()) return;
            File[] zipFiles = zipDir.listFiles((d, n) -> n.toLowerCase().endsWith(".zip"));
            if (zipFiles == null || zipFiles.length <= maxZipFiles) return;
            Arrays.sort(zipFiles, Comparator.comparingLong(File::lastModified));
            int toDelete = zipFiles.length - maxZipFiles;
            for (int i = 0; i < toDelete; i++) {
                if (!zipFiles[i].delete()) {
                    System.err.println("Failed to delete old zip: " + zipFiles[i].getAbsolutePath());
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
