package com.mcreatik.gallery.processing;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.mcreatik.gallery.support.IntegrationTest;

class ImageOpsTest {

    @Test
    void sniffsRealTypeFromBytesNotExtension() throws Exception {
        assertThat(ImageOps.sniffMimeType(IntegrationTest.jpeg(10, 10))).isEqualTo("image/jpeg");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        assertThat(ImageOps.sniffMimeType(png)).isEqualTo("image/png");
        assertThat(ImageOps.sniffMimeType("GIF89a....".getBytes())).isNull();
        assertThat(ImageOps.sniffMimeType(new byte[0])).isNull();
    }

    @Test
    void decodesWithSubsamplingButReportsOriginalSize() throws Exception {
        byte[] jpeg = IntegrationTest.jpeg(6000, 4000);
        ImageOps.Decoded decoded = ImageOps.decode(jpeg, 2048);
        assertThat(decoded.sourceWidth()).isEqualTo(6000);
        assertThat(decoded.sourceHeight()).isEqualTo(4000);
        assertThat(decoded.image().getWidth()).isBetween(2048, 3000);
    }

    @Test
    void fitWithinKeepsAspectRatioAndNeverUpscales() {
        BufferedImage img = new BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB);
        BufferedImage fitted = ImageOps.fitWithin(img, 640);
        assertThat(fitted.getWidth()).isEqualTo(640);
        assertThat(fitted.getHeight()).isEqualTo(427);
        BufferedImage small = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageOps.fitWithin(small, 640)).isSameAs(small);
    }

    @Test
    void appliesExifOrientation() {
        BufferedImage landscape = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        landscape.setRGB(0, 0, 0xFF0000); // top-left red
        for (int o = 1; o <= 8; o++) {
            BufferedImage out = ImageOps.applyOrientation(landscape, o);
            boolean swapped = ImageOps.swapsDimensions(o);
            assertThat(out.getWidth()).as("orientation %d", o).isEqualTo(swapped ? 300 : 400);
            assertThat(out.getHeight()).as("orientation %d", o).isEqualTo(swapped ? 400 : 300);
        }
        // Orientation 6 = camera rotated 90° clockwise: stored top-left pixel ends up top-right.
        BufferedImage rotated = ImageOps.applyOrientation(landscape, 6);
        assertThat(rotated.getRGB(299, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
        // Orientation 8 = 90° counter-clockwise: top-left ends up bottom-left.
        assertThat(ImageOps.applyOrientation(landscape, 8).getRGB(0, 399) & 0xFFFFFF).isEqualTo(0xFF0000);
        // Orientation 3 = 180°: top-left ends up bottom-right.
        assertThat(ImageOps.applyOrientation(landscape, 3).getRGB(399, 299) & 0xFFFFFF).isEqualTo(0xFF0000);
    }

    @Test
    void encodesJpegWithoutMetadata() throws Exception {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        byte[] out = ImageOps.encodeJpeg(img, 0.8f);
        assertThat(ImageOps.sniffMimeType(out)).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new ByteArrayInputStream(out)).getWidth()).isEqualTo(200);
        assertThat(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }
}
