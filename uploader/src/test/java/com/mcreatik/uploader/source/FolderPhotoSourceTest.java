package com.mcreatik.uploader.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mcreatik.uploader.TestPhotos;

class FolderPhotoSourceTest {

    @TempDir
    Path folder;

    private FolderPhotoSource source() {
        return new FolderPhotoSource(folder, Duration.ofMillis(50), Duration.ofSeconds(1), Clock.systemUTC());
    }

    @Test
    void detectsNewFileOnlyOnceItIsStable() throws Exception {
        FolderPhotoSource source = source();
        assertThat(source.scanOnce()).isEmpty();
        Path photo = TestPhotos.write(folder, "IMG_0001.JPG");

        assertThat(source.scanOnce()).as("first sighting: not yet known to be complete").isEmpty();
        assertThat(source.scanOnce()).containsExactly(photo);
        assertThat(source.scanOnce()).as("emitted once").isEmpty();
    }

    @Test
    void doesNotEmitAFileTheCameraIsStillWriting() throws Exception {
        FolderPhotoSource source = source();
        Path photo = TestPhotos.write(folder, "IMG_0002.JPG", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});
        source.scanOnce();
        Files.write(photo, new byte[5000], StandardOpenOption.APPEND); // still growing
        Files.setLastModifiedTime(photo, FileTime.fromMillis(System.currentTimeMillis() - 5_000));
        assertThat(source.scanOnce()).isEmpty();
        assertThat(source.scanOnce()).containsExactly(photo);
    }

    @Test
    void waitsForMinimumAgeSoFreshWritesSettle() throws Exception {
        Instant now = Instant.now();
        FolderPhotoSource source = new FolderPhotoSource(folder, Duration.ofMillis(50), Duration.ofSeconds(10),
                Clock.fixed(now, ZoneOffset.UTC));
        Path photo = folder.resolve("fresh.jpg");
        Files.write(photo, TestPhotos.jpeg());
        Files.setLastModifiedTime(photo, FileTime.from(now.minusSeconds(2)));
        source.scanOnce();
        assertThat(source.scanOnce()).isEmpty();
    }

    @Test
    void ignoresNonPhotosHiddenAndTempFilesButScansSubfolders() throws Exception {
        FolderPhotoSource source = source();
        TestPhotos.write(folder, "notes.txt");
        TestPhotos.write(folder, "IMG_0003.CR3");
        TestPhotos.write(folder, ".IMG_0004.JPG");
        TestPhotos.write(folder, "~IMG_0005.JPG");
        TestPhotos.write(folder, ".trash/IMG_0006.JPG");
        Path nested = TestPhotos.write(folder, "DCIM/100CANON/IMG_0007.jpeg");
        Path png = TestPhotos.write(folder, "export.PNG");

        source.scanOnce();
        assertThat(source.scanOnce()).containsExactlyInAnyOrder(nested, png);
    }

    @Test
    void reEmitsAFileThatWasReplacedWithNewContent() throws Exception {
        FolderPhotoSource source = source();
        Path photo = TestPhotos.write(folder, "IMG_0008.JPG");
        source.scanOnce();
        assertThat(source.scanOnce()).containsExactly(photo);

        TestPhotos.write(folder, "IMG_0008.JPG", TestPhotos.jpeg());
        Files.setLastModifiedTime(photo, FileTime.fromMillis(System.currentTimeMillis() - 3_000));
        source.scanOnce();
        assertThat(source.scanOnce()).containsExactly(photo);
    }

    @Test
    void missingFolderIsNotAnError() {
        FolderPhotoSource source = new FolderPhotoSource(folder.resolve("not-yet-created"));
        assertThat(source.scanOnce()).isEmpty();
    }
}
