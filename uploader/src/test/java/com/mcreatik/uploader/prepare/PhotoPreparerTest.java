package com.mcreatik.uploader.prepare;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mcreatik.uploader.TestPhotos;

class PhotoPreparerTest {

    @TempDir
    Path dir;

    @Test
    void makesWebAndThumbnailVersionsLikeTheServer() throws Exception {
        Path file = TestPhotos.write(dir, "IMG.JPG", TestPhotos.jpeg(3000, 4500));
        var p = PhotoPreparer.prepare(file);
        assertThat(p.width()).isEqualTo(3000);
        assertThat(p.height()).isEqualTo(4500);
        var web = ImageIO.read(new ByteArrayInputStream(p.web()));
        var thumb = ImageIO.read(new ByteArrayInputStream(p.thumbnail()));
        assertThat(web.getHeight()).isEqualTo(2048);
        assertThat(thumb.getHeight()).isEqualTo(640);
        assertThat(p.thumbnail().length).isLessThan(p.web().length);
    }

    @Test
    void neverUpscalesSmallPhotos() throws Exception {
        Path file = TestPhotos.write(dir, "small.jpg", TestPhotos.jpeg(320, 240));
        var p = PhotoPreparer.prepare(file);
        assertThat(ImageIO.read(new ByteArrayInputStream(p.web())).getWidth()).isEqualTo(320);
        assertThat(ImageIO.read(new ByteArrayInputStream(p.thumbnail())).getWidth()).isEqualTo(320);
        assertThat(p.capturedAt()).isNull(); // no EXIF: fine, never fails the photo
    }
}
