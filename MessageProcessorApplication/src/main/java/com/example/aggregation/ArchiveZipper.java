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
 * Zips & cleans up archives per‑feature:
 *  • LoadPipeline: cron=archive.zipper.loadpipeline.cron, root=archive.loadpipeline.*
 *  • MDR         : cron=archive.zipper.mdr.cron,         root=archive.mdr.*
 */
@Service
public class ArchiveZipper {

    // LoadPipeline zipping
    @Value("${archive.zipper.loadpipeline.enabled}")    private boolean lpZipEnabled;
    @Value("${archive.zipper.loadpipeline.cron}")       private String lpZipCron;
    @Value("${archive.zip.loadpipeline.output}")        private String lpZipOutput;
    @Value("${archive.zipper.loadpipeline.maxFiles}")   private int lpZipMax;

    // MDR zipping
    @Value("${archive.zipper.mdr.enabled}")             private boolean mdrZipEnabled;
    @Value("${archive.zipper.mdr.cron}")                private String mdrZipCron;
    @Value("${archive.zip.mdr.output}")                 private String mdrZipOutput;
    @Value("${archive.zipper.mdr.maxFiles}")            private int mdrZipMax;

    // Format for yesterday
    private static final DateTimeFormatter DATEFMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Scheduled(cron = "${archive.zipper.loadpipeline.cron}")
    public void zipLpDay() {
        if (!lpZipEnabled) return;
        zipAndClean("archive/loadpipeline", DATEFMT.format(LocalDate.now().minusDays(1)),
                lpZipOutput, "incoming", lpZipMax);
        zipAndClean("archive/loadpipeline", DATEFMT.format(LocalDate.now().minusDays(1)),
                lpZipOutput, "merged", lpZipMax);
    }

    @Scheduled(cron = "${archive.zipper.mdr.cron}")
    public void zipMdrDay() {
        if (!mdrZipEnabled) return;
        zipAndClean("archive/mdr", DATEFMT.format(LocalDate.now().minusDays(1)),
                mdrZipOutput, "incoming", mdrZipMax);
        zipAndClean("archive/mdr", DATEFMT.format(LocalDate.now().minusDays(1)),
                mdrZipOutput, "merged", mdrZipMax);
    }

    /**
     * Zips all subdirs under rootDir/datePrefix/type_/ into one zip, then deletes them.
     */
    private void zipAndClean(String rootDir, String datePref, String zipOut, String type, int max) {
        try {
            Path outDir = Paths.get(zipOut);
            Files.createDirectories(outDir);

            String prefix = datePref + type;
            File[] toZip = new File(rootDir).listFiles(f->f.isDirectory() && f.getName().startsWith(prefix));
            if (toZip==null || toZip.length==0) return;

            Path zipFile = outDir.resolve(type + "_" + datePref + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                for (File d:toZip) zipDir(d, d.getName(), zos);
            }
            for (File d:toZip) deleteRec(d.toPath());

            // Retention
            File[] zips = outDir.toFile().listFiles((d,n)->n.endsWith(".zip"));
            if (zips!=null && zips.length>max) {
                Arrays.sort(zips, Comparator.comparingLong(File::lastModified));
                for (int i=0;i<zips.length-max;i++) zips[i].delete();
            }
        } catch(Exception e) { e.printStackTrace(); }
    }

    private void zipDir(File folder,String parent,ZipOutputStream zos) throws IOException {
        for(File f:folder.listFiles()){
            if(f.isDirectory()) zipDir(f,parent+"/"+f.getName(),zos);
            else try(FileInputStream fis=new FileInputStream(f)) {
                zos.putNextEntry(new ZipEntry(parent+"/"+f.getName()));
                byte[] buf=new byte[1024];int len;
                while((len=fis.read(buf))>0) zos.write(buf,0,len);
                zos.closeEntry();
            }
        }
    }

    private void deleteRec(Path path)throws IOException{
        Files.walkFileTree(path,new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                Files.delete(file);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path dir,IOException exc)throws IOException {
                Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
}
