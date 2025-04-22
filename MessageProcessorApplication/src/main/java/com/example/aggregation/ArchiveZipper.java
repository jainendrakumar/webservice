// src/main/java/com/example/aggregation/ArchiveZipper.java
package com.example.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
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
 * ArchiveZipper is a scheduled Spring service that performs daily ZIP compression
 * of archived subfolders (by date) for each ingestion pipeline.
 * <p>
 * For each pipeline, it:
 * <ul>
 *     <li>Zips all directories created on the previous day</li>
 *     <li>Deletes original directories after successful zipping</li>
 *     <li>Maintains only the N most recent ZIP files</li>
 * </ul>
 * <p>
 * Configurations are defined per-pipeline using external properties.
 *
 * @author jkr3 (Jainendra.kumar@3ds.com)
 * @version 1.0.0
 * @since 2025-04-20
 */
@Service
public class ArchiveZipper {

    // Formatter to generate date-based folder prefixes, e.g. "20240419"
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ───────────────────────────── Configuration fields for each pipeline ─────────────────────────────

    // LoadPipeline
    @Value("${archive.zipper.loadpipeline.enabled}")  private boolean lpZipEn;
    @Value("${archive.zipper.loadpipeline.cron}")     private String lpZipCron;
    @Value("${archive.zip.loadpipeline.output}")      private String lpZipOut;
    @Value("${archive.zipper.loadpipeline.maxFiles}") private int lpZipMax;

    // MultiDestinationRake
    @Value("${archive.zipper.mdr.enabled}")  private boolean mdrZipEn;
    @Value("${archive.zipper.mdr.cron}")     private String mdrZipCron;
    @Value("${archive.zip.mdr.output}")      private String mdrZipOut;
    @Value("${archive.zipper.mdr.maxFiles}") private int mdrZipMax;

    // LoadAttribute
    @Value("${archive.zipper.loadattribute.enabled}")  private boolean laZipEn;
    @Value("${archive.zipper.loadattribute.cron}")     private String laZipCron;
    @Value("${archive.zip.loadattribute.output}")      private String laZipOut;
    @Value("${archive.zipper.loadattribute.maxFiles}") private int laZipMax;

    // MaintenanceBlock
    @Value("${archive.zipper.maintenanceblock.enabled}")  private boolean mbZipEn;
    @Value("${archive.zipper.maintenanceblock.cron}")     private String mbZipCron;
    @Value("${archive.zip.maintenanceblock.output}")      private String mbZipOut;
    @Value("${archive.zipper.maintenanceblock.maxFiles}") private int mbZipMax;

    // MaintenanceBlockResource
    @Value("${archive.zipper.maintenanceblockresource.enabled}")  private boolean mbrZipEn;
    @Value("${archive.zipper.maintenanceblockresource.cron}")     private String mbrZipCron;
    @Value("${archive.zip.maintenanceblockresource.output}")      private String mbrZipOut;
    @Value("${archive.zipper.maintenanceblockresource.maxFiles}") private int mbrZipMax;

    // TrainServiceUpdateActual
    @Value("${archive.zipper.trainserviceupdate.enabled}")  private boolean tsuZipEn;
    @Value("${archive.zipper.trainserviceupdate.cron}")     private String tsuZipCron;
    @Value("${archive.zip.trainserviceupdate.output}")      private String tsuZipOut;
    @Value("${archive.zipper.trainserviceupdate.maxFiles}") private int tsuZipMax;

    // ───────────────────────────── Scheduled zipping per pipeline ─────────────────────────────

    /** Scheduled zipping for LoadPipeline archives */
    @Scheduled(cron = "${archive.zipper.loadpipeline.cron}")
    public void zipLp() {
        if (lpZipEn) zipFeature("archive/loadpipeline", lpZipOut, lpZipMax);
    }

    /** Scheduled zipping for MultiDestinationRake archives */
    @Scheduled(cron = "${archive.zipper.mdr.cron}")
    public void zipMdr() {
        if (mdrZipEn) zipFeature("archive/mdr", mdrZipOut, mdrZipMax);
    }

    /** Scheduled zipping for LoadAttribute archives */
    @Scheduled(cron = "${archive.zipper.loadattribute.cron}")
    public void zipLa() {
        if (laZipEn) zipFeature("archive/loadattribute", laZipOut, laZipMax);
    }

    /** Scheduled zipping for MaintenanceBlock archives */
    @Scheduled(cron = "${archive.zipper.maintenanceblock.cron}")
    public void zipMb() {
        if (mbZipEn) zipFeature("archive/maintenanceblock", mbZipOut, mbZipMax);
    }

    /** Scheduled zipping for MaintenanceBlockResource archives */
    @Scheduled(cron = "${archive.zipper.maintenanceblockresource.cron}")
    public void zipMbr() {
        if (mbrZipEn) zipFeature("archive/maintenanceblockresource", mbrZipOut, mbrZipMax);
    }

    /** Scheduled zipping for TrainServiceUpdateActual archives */
    @Scheduled(cron = "${archive.zipper.trainserviceupdate.cron}")
    public void zipTsu() {
        if (tsuZipEn) zipFeature("archive/trainserviceupdate", tsuZipOut, tsuZipMax);
    }

    /**
     * Compresses all subfolders under the given root directory whose names start with yesterday's date.
     * After successful zipping, deletes original folders and retains only the most recent ZIPs.
     *
     * @param rootDir   the root path where date-stamped folders reside (e.g., archive/loadpipeline)
     * @param zipOutput the folder where the ZIP file will be written
     * @param maxFiles  the number of most recent ZIPs to retain (older ones will be deleted)
     */
    private void zipFeature(String rootDir, String zipOutput, int maxFiles) {
        try {
            // Get yesterday’s date (e.g., "20240419")
            String date = LocalDate.now().minusDays(1).format(DATE_FMT);

            // Ensure output ZIP folder exists
            Path outDir = Paths.get(zipOutput);
            Files.createDirectories(outDir);

            // Identify all subdirectories that start with yesterday’s date
            File[] subs = new File(rootDir).listFiles(f ->
                    f.isDirectory() && f.getName().startsWith(date)
            );
            if (subs == null || subs.length == 0) return; // Nothing to zip

            // ZIP filename: e.g., archive/loadpipeline/zipped/20240419.zip
            Path zipFile = outDir.resolve(date + ".zip");

            // Create and write to the ZIP archive
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                for (File d : subs) {
                    zipDir(d, d.getName(), zos);
                }
            }

            // Delete original subfolders after successful compression
            for (File d : subs) {
                deleteRecursively(d.toPath());
            }

            // Perform retention: delete oldest ZIPs beyond maxFiles
            File[] zips = outDir.toFile().listFiles((d, n) -> n.endsWith(".zip"));
            if (zips != null && zips.length > maxFiles) {
                Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < zips.length - maxFiles; i++) {
                    zips[i].delete();
                }
            }

        } catch (IOException e) {
            // Any error during zip or delete will be printed to stderr
            e.printStackTrace();
        }
    }

    /**
     * Recursively adds the contents of a directory to a ZIP output stream.
     *
     * @param folder  the directory to be zipped
     * @param parent  relative path within the ZIP file
     * @param zos     output stream to write the zipped content
     * @throws IOException if file access or writing fails
     */
    private void zipDir(File folder, String parent, ZipOutputStream zos) throws IOException {
        for (File f : folder.listFiles()) {
            if (f.isDirectory()) {
                // Recurse into subdirectory
                zipDir(f, parent + "/" + f.getName(), zos);
            } else {
                // Add file entry into the ZIP archive
                try (FileInputStream fis = new FileInputStream(f)) {
                    zos.putNextEntry(new ZipEntry(parent + "/" + f.getName()));
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = fis.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Recursively deletes a folder and all its contents (files + subfolders).
     *
     * @param path the directory path to delete
     * @throws IOException if a file/folder cannot be deleted
     */
    private void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); // Delete each file
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir); // Delete directory after files
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
