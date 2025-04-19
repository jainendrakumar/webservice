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
 * Zips and cleans up previous‑day archives for each stream.
 *
 * <p>Each feature has its own cron, output folder, and retention count.</p>
 */
@Service
public class ArchiveZipper {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // LoadPipeline
    @Value("${archive.zipper.loadpipeline.enabled}")  private boolean lpZipEn;
    @Value("${archive.zipper.loadpipeline.cron}")     private String lpZipCron;
    @Value("${archive.zip.loadpipeline.output}")      private String lpZipOut;
    @Value("${archive.zipper.loadpipeline.maxFiles}") private int lpZipMax;

    // MDR
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

    @Scheduled(cron = "${archive.zipper.loadpipeline.cron}")
    public void zipLp() { if (lpZipEn) zipFeature("archive/loadpipeline", lpZipOut, lpZipMax); }

    @Scheduled(cron = "${archive.zipper.mdr.cron}")
    public void zipMdr() { if (mdrZipEn) zipFeature("archive/mdr", mdrZipOut, mdrZipMax); }

    @Scheduled(cron = "${archive.zipper.loadattribute.cron}")
    public void zipLa()  { if (laZipEn ) zipFeature("archive/loadattribute", laZipOut, laZipMax); }

    @Scheduled(cron = "${archive.zipper.maintenanceblock.cron}")
    public void zipMb()  { if (mbZipEn ) zipFeature("archive/maintenanceblock", mbZipOut, mbZipMax); }

    @Scheduled(cron = "${archive.zipper.maintenanceblockresource.cron}")
    public void zipMbr() { if (mbrZipEn) zipFeature("archive/maintenanceblockresource", mbrZipOut, mbrZipMax); }

    @Scheduled(cron = "${archive.zipper.trainserviceupdate.cron}")
    public void zipTsu() { if (tsuZipEn) zipFeature("archive/trainserviceupdate", tsuZipOut, tsuZipMax); }

    /**
     * Zips all subdirectories under rootDir that start with yesterday’s date,
     * writes to zipOutput, then deletes them, and retains only the newest maxFiles.
     */
    private void zipFeature(String rootDir, String zipOutput, int maxFiles) {
        try {
            String date = LocalDate.now().minusDays(1).format(DATE_FMT);
            Path outDir = Paths.get(zipOutput);
            Files.createDirectories(outDir);

            File[] subs = new File(rootDir).listFiles(f ->
                    f.isDirectory() && f.getName().startsWith(date)
            );
            if (subs == null || subs.length == 0) return;

            Path zipFile = outDir.resolve(date + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                for (File d : subs) zipDir(d, d.getName(), zos);
            }
            for (File d : subs) deleteRecursively(d.toPath());

            // Retention: keep only newest maxFiles zips
            File[] zips = outDir.toFile().listFiles((d, n) -> n.endsWith(".zip"));
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

    /** Recursively add a folder’s contents into the ZipOutputStream. */
    private void zipDir(File folder, String parent, ZipOutputStream zos) throws IOException {
        for (File f : folder.listFiles()) {
            if (f.isDirectory()) {
                zipDir(f, parent + "/" + f.getName(), zos);
            } else {
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

    /** Recursively delete a directory and its contents. */
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
