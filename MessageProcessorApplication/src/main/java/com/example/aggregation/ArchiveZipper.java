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
 * Zips previous day's incoming & merged archives, then enforces
 * retention of at most {@code archive.zip.maxFiles} zip files.
 */
@Service
public class ArchiveZipper {

    @Value("${archive.incoming.root}")
    private String incomingRoot;

    @Value("${archive.merged.root}")
    private String mergedRoot;

    @Value("${archive.zip.output.root}")
    private String zipOutputRoot;

    @Value("${archive.zipper.enabled}")
    private boolean enabled;

    @Value("${archive.zip.maxFiles}")
    private int maxFiles;

    /**
     * Runs daily at the cron defined in external-config.properties.
     */
    @Scheduled(cron = "${archive.zip.cron}")
    public void zipAndCleanup() {
        if (!enabled) return;
        String date = LocalDate.now().minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        zipDir(incomingRoot, date, "incoming_" + date + ".zip");
        zipDir(mergedRoot,   date, "merged_"   + date + ".zip");
        cleanupOldZips();
    }

    private void zipDir(String root, String datePref, String zipName) {
        try {
            File[] dirs = new File(root).listFiles(f ->
                    f.isDirectory() && f.getName().startsWith(datePref)
            );
            if (dirs == null || dirs.length == 0) return;

            Files.createDirectories(Paths.get(zipOutputRoot));
            try (ZipOutputStream zos = new ZipOutputStream(
                    new FileOutputStream(zipOutputRoot + File.separator + zipName))) {
                for (File d : dirs) {
                    addFolderToZip(d, d.getName(), zos);
                }
            }
            for (File d : dirs) { deleteRecursively(d.toPath()); }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void addFolderToZip(File folder, String parent, ZipOutputStream zos)
            throws IOException {
        for (File f : folder.listFiles()) {
            if (f.isDirectory()) {
                addFolderToZip(f, parent + "/" + f.getName(), zos);
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

    private void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.delete(dir); return FileVisitResult.CONTINUE;
            }
        });
    }

    private void cleanupOldZips() {
        File dir = new File(zipOutputRoot);
        File[] zips = dir.listFiles((d,n)->n.endsWith(".zip"));
        if (zips == null || zips.length <= maxFiles) return;
        Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < zips.length - maxFiles; i++) {
            if (!zips[i].delete()) {
                System.err.println("Could not delete old zip: " + zips[i]);
            }
        }
    }
}
