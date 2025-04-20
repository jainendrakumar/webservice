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
 * of pipeline archives from the previous day and manages retention of past archives.
 * <p>
 * Each ingestion pipeline has its own configuration: cron expression, output ZIP folder,
 * and max retention count. If enabled, this service:
 * <ul>
 *     <li>Zips all subdirectories from the previous day that match the date format (yyyyMMdd)</li>
 *     <li>Deletes the original directories after compression</li>
 *     <li>Keeps only the most recent N ZIP files as configured</li>
 * </ul>
 */
@Service
public class ArchiveZipper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ────────────────────────────── LoadPipeline Configuration ──────────────────────────────
    @Value("${archive.zipper.loadpipeline.enabled}")  private boolean lpZipEn;
    @Value("${archive.zipper.loadpipeline.cron}")     private String lpZipCron;
    @Value("${archive.zip.loadpipeline.output}")      private String lpZipOut;
    @Value("${archive.zipper.loadpipeline.maxFiles}") private int lpZipMax;

    // ────────────────────────────── MultiDestinationRake Configuration ───────────────────────
    @Value("${archive.zipper.mdr.enabled}")  private boolean mdrZipEn;
    @Value("${archive.zipper.mdr.cron}")     private String mdrZipCron;
    @Value("${archive.zip.mdr.output}")      private String mdrZipOut;
    @Value("${archive.zipper.mdr.maxFiles}") private int mdrZipMax;

    // ────────────────────────────── LoadAttribute Configuration ──────────────────────────────
    @Value("${archive.zipper.loadattribute.enabled}")  private boolean laZipEn;
    @Value("${archive.zipper.loadattribute.cron}")     private String laZipCron;
    @Value("${archive.zip.loadattribute.output}")      private String laZipOut;
    @Value("${archive.zipper.loadattribute.maxFiles}") private int laZipMax;

    // ────────────────────────────── MaintenanceBlock Configuration ───────────────────────────
    @Value("${archive.zipper.maintenanceblock.enabled}")  private boolean mbZipEn;
    @Value("${archive.zipper.maintenanceblock.cron}")     private String mbZipCron;
    @Value("${archive.zip.maintenanceblock.output}")      private String mbZipOut;
    @Value("${archive.zipper.maintenanceblock.maxFiles}") private int mbZipMax;

    // ─────────────────────── MaintenanceBlockResource Configuration ──────────────────────────
    @Value("${archive.zipper.maintenanceblockresource.enabled}")  private boolean mbrZipEn;
    @Value("${archive.zipper.maintenanceblockresource.cron}")     private String mbrZipCron;
    @Value("${archive.zip.maintenanceblockresource.output}")      private String mbrZipOut;
    @Value("${archive.zipper.maintenanceblockresource.maxFiles}") private int mbrZipMax;

    // ─────────────────────── TrainServiceUpdateActual Configuration ──────────────────────────
    @Value("${archive.zipper.trainserviceupdate.enabled}")  private boolean tsuZipEn;
    @Value("${archive.zipper.trainserviceupdate.cron}")     private String tsuZipCron;
    @Value("${archive.zip.trainserviceupdate.output}")      private String tsuZipOut;
    @Value("${archive.zipper.trainserviceupdate.maxFiles}") private int tsuZipMax;

    // ───────────────────────────────────────── Schedulers ─────────────────────────────────────

    /** Scheduled method for LoadPipeline ZIP operation */
    @Scheduled(cron = "${archive.zipper.loadpipeline.cron}")
    public void zipLp() { if (lpZipEn) zipFeature("archive/loadpipeline", lpZipOut, lpZipMax); }

    /** Scheduled method for MDR ZIP operation */
    @Scheduled(cron = "${archive.zipper.mdr.cron}")
    public void zipMdr() { if (mdrZipEn) zipFeature("archive/mdr", mdrZipOut, mdrZipMax); }

    /** Scheduled method for LoadAttribute ZIP operation */
    @Scheduled(cron = "${archive.zipper.loadattribute.cron}")
    public void zipLa()  { if (laZipEn) zipFeature("archive/loadattribute", laZipOut, laZipMax); }

    /** Scheduled method for MaintenanceBlock ZIP operation */
    @Scheduled(cron = "${archive.zipper.maintenanceblock.cron}")
    public void zipMb()  { if (mbZipEn) zipFeature("archive/maintenanceblock", mbZipOut, mbZipMax); }

    /** Scheduled method for MaintenanceBlockResource ZIP operation */
    @Scheduled(cron = "${archive.zipper.maintenanceblockresource.cron}")
    public void zipMbr() { if (mbrZipEn) zipFeature("archive/maintenanceblockresource", mbrZipOut, mbrZipMax); }

    /** Scheduled method for TrainServiceUpdateActual ZIP operation */
    @Scheduled(cron = "${archive.zipper.trainserviceupdate.cron}")
    public void zipTsu() { if (tsuZipEn) zipFeature("archive/trainserviceupdate", tsuZipOut, tsuZipMax); }

    /**
     * Performs ZIP compression for a specific feature pipeline.
     *
     * @param rootDir  Root folder containing dated archive folders
     * @param zipOutput Output folder to place the zipped archive
     * @param maxFiles Number of most recent ZIPs to retain
     */
    private void zipFeature(String rootDir, String zipOutput, int maxFiles) {
        try {
            // Determine the date to zip (yesterday)
            String date = LocalDate.now().minusDays(1).format(DATE_FMT);
            Path outDir = Paths.get(zipOutput);
            Files.createDirectories(outDir);

            // Filter all subdirectories starting with yesterday's date
            File[] subs = new File(rootDir).listFiles(f ->
                    f.isDirectory() && f.getName().startsWith(date)
            );
            if (subs == null || subs.length == 0) return;

            // Define ZIP file path: e.g., archive/loadpipeline/zipped/20240419.zip
            Path zipFile = outDir.resolve(date + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                // Add each matching folder recursively to the ZIP
                for (File d : subs) {
                    zipDir(d, d.getName(), zos);
                }
            }

            // Delete original subfolders after archiving
            for (File d : subs) {
                deleteRecursively(d.toPath());
            }

            // Cleanup old ZIPs (retain only N most recent)
            File[] zips = outDir.toFile().listFiles((dir, name) -> name.endsWith(".zip"));
            if (zips != null && zips.length > maxFiles) {
                Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < zips.length - maxFiles; i++) {
                    zips[i].delete();
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Recursively adds a directory's files and folders to a ZipOutputStream.
     *
     * @param folder  Current folder being zipped
     * @param parent  Path prefix within the ZIP (relative to root)
     * @param zos     Open ZipOutputStream
     */
    private void zipDir(File folder, String parent, ZipOutputStream zos) throws IOException {
        for (File f : folder.listFiles()) {
            if (f.isDirectory()) {
                // Recursive call for subdirectory
                zipDir(f, parent + "/" + f.getName(), zos);
            } else {
                try (FileInputStream fis = new FileInputStream(f)) {
                    ZipEntry entry = new ZipEntry(parent + "/" + f.getName());
                    zos.putNextEntry(entry);
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
     * Recursively deletes a directory and all its contents.
     *
     * @param path Root directory to delete
     */
    private void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
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
}
