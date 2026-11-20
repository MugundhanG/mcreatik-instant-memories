package com.mcreatik.gallery.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** The label drawn on guests' downloads: bottom-right, light, never over the rest of the photo. */
class WatermarkDrawTest {

    private static BufferedImage black(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static void mark(BufferedImage img, BufferedImage logo, String name, int scale) {
        Graphics2D g = img.createGraphics();
        Watermark.draw(g, img.getWidth(), img.getHeight(), logo, name, scale);
        g.dispose();
    }

    private static int brightness(int rgb) {
        Color c = new Color(rgb);
        return (c.getRed() + c.getGreen() + c.getBlue()) / 3;
    }

    @Test
    void studioNameOnALightLabelInTheBottomRightCorner() {
        BufferedImage img = black(1200, 800);
        mark(img, null, "Lens & Light Studio", 100);
        int margin = (int) Math.round(800 * Watermark.MARGIN);
        // Just inside the label's bottom-right corner: the light label (white at 78 % over black).
        assertThat(brightness(img.getRGB(1200 - margin - 6, 800 - margin - 6))).isGreaterThan(180);
        // The rest of the photo is untouched.
        assertThat(img.getRGB(10, 10) & 0xFFFFFF).isZero();
        assertThat(img.getRGB(600, 400) & 0xFFFFFF).isZero();
        assertThat(img.getRGB(1200 - 2, 800 - 2) & 0xFFFFFF).isZero(); // the margin stays clear
    }

    @Test
    void logoIsDrawnInsideTheLabelAndNeverWiderThanItsShare() {
        BufferedImage logo = new BufferedImage(1000, 100, BufferedImage.TYPE_INT_RGB); // very wide logo
        Graphics2D lg = logo.createGraphics();
        lg.setColor(Color.RED);
        lg.fillRect(0, 0, 1000, 100);
        lg.dispose();
        BufferedImage img = black(1000, 1000);
        mark(img, logo, "ignored", 200);

        int reds = 0;
        int minX = Integer.MAX_VALUE;
        for (int y = 0; y < 1000; y++) {
            for (int x = 0; x < 1000; x++) {
                Color c = new Color(img.getRGB(x, y));
                if (c.getRed() > 200 && c.getGreen() < 60) {
                    reds++;
                    minX = Math.min(minX, x);
                }
            }
        }
        assertThat(reds).isPositive();
        // The whole label (logo plus padding) is at most 38 % of the width, inside the corner margin.
        int margin = (int) Math.round(1000 * Watermark.MARGIN);
        assertThat(minX).isGreaterThanOrEqualTo(1000 - margin - (int) (1000 * Watermark.MAX_WIDTH));
    }
}
