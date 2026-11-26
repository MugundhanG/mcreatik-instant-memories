package com.mcreatik.gallery.photo;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.mcreatik.gallery.auth.StudioBranding.Brand;
import com.mcreatik.gallery.auth.StudioBranding.Mark;
import com.mcreatik.gallery.processing.ImageOps;

/**
 * Renders sample marks on synthetic photos for a visual check. Off by default; run with
 * {@code mvn -o test -Dtest=WatermarkSamplesTest -Dwm.samples=/some/dir}.
 */
@EnabledIfSystemProperty(named = "wm.samples", matches = ".+")
class WatermarkSamplesTest {

    private static BufferedImage photo(int w, int h, Color a, Color b) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, a, w, h, b));
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 255, 255, 40));
        g.fillOval(w / 5, h / 5, w / 3, w / 3);
        g.setColor(new Color(0, 0, 0, 40));
        g.fillOval(w / 2, h / 4, w / 3, w / 3);
        g.dispose();
        return img;
    }

    /** Dark lettering on a transparent background. */
    private static BufferedImage transparentLogo() {
        BufferedImage logo = new BufferedImage(600, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(40, 30, 25));
        g.setFont(new Font(Font.SERIF, Font.BOLD, 96));
        g.drawString("Lens&Co", 20, 110);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 34));
        g.drawString("WEDDING STORIES", 24, 165);
        g.dispose();
        return logo;
    }

    /** An opaque rectangle (like a JPEG logo). */
    private static BufferedImage opaqueLogo() {
        BufferedImage logo = new BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = logo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(24, 70, 60));
        g.fillRect(0, 0, 600, 200);
        g.setColor(new Color(245, 235, 210));
        g.setFont(new Font(Font.SERIF, Font.BOLD, 90));
        g.drawString("Lens&Co", 40, 125);
        g.dispose();
        return logo;
    }

    private static void write(Path dir, String name, BufferedImage photo, BufferedImage logo, Mark mark,
                              String studio, String instagram, String phone) throws Exception {
        Brand brand = new Brand(studio, null, instagram, false, 100, "cream", phone, mark);
        BufferedImage out = Watermark.render(photo, Watermark.resolve(brand, logo), mark, 100);
        Files.write(dir.resolve(name), ImageOps.encodeJpeg(out, Watermark.JPEG_QUALITY));
    }

    @Test
    void renderSamples() throws Exception {
        Path dir = new File(System.getProperty("wm.samples")).toPath();
        Files.createDirectories(dir);
        BufferedImage bright = photo(2048, 1365, new Color(250, 240, 220), new Color(200, 225, 245));
        BufferedImage dark = photo(2048, 1365, new Color(20, 24, 40), new Color(70, 40, 50));
        BufferedImage portrait = photo(1365, 2048, new Color(230, 190, 160), new Color(90, 110, 140));
        Mark corner = Mark.DEFAULT;
        write(dir, "server-corner-bright.jpg", bright, transparentLogo(), corner, "Lens & Co", "lensandco", null);
        write(dir, "server-corner-dark.jpg", dark, transparentLogo(), corner, "Lens & Co", "lensandco", null);
        write(dir, "server-corner-opaque-logo.jpg", dark, opaqueLogo(), corner, "Lens & Co", "lensandco", null);
        write(dir, "server-corner-name-only.jpg", dark, null, new Mark("CORNER", "RIGHT", "SMALL", false, true, false, false),
                "Lens & Co", null, null);
        Mark strip = new Mark("STRIP", "CENTER", "SMALL", true, true, true, true);
        write(dir, "server-strip-landscape.jpg", bright, transparentLogo(), strip, "Lens & Co", "lensandco", "+919876543210");
        write(dir, "server-strip-portrait.jpg", portrait, transparentLogo(), strip, "Lens & Co", "lensandco", "+919876543210");
    }
}
