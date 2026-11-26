package com.mcreatik.gallery.photo;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mcreatik.gallery.auth.StudioBranding.Brand;
import com.mcreatik.gallery.auth.StudioBranding.Mark;

/** The mark drawn on guests' downloads: a light corner line or a strip below the photo, never a box over it. */
class WatermarkDrawTest {

    private static final Mark CORNER = Mark.DEFAULT;
    private static final Mark STRIP = new Mark("STRIP", "CENTER", "SMALL", true, true, true, true);

    private static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static BufferedImage black(int w, int h) {
        return solid(w, h, Color.BLACK);
    }

    private static BufferedImage mark(BufferedImage photo, Watermark.Content content, Mark mark, int scale) {
        return Watermark.render(photo, content, mark, scale);
    }

    private static Watermark.Content text(String... parts) {
        return new Watermark.Content(null, List.of(parts));
    }

    /** A logo: opaque dark artwork in the middle of a transparent PNG. */
    private static BufferedImage transparentLogo() {
        BufferedImage logo = new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(40, 20, 320, 60);
        g.dispose();
        return logo;
    }

    private static BufferedImage opaqueLogo(Color c) {
        return solid(400, 100, c);
    }

    private record Bounds(int minX, int maxX, int minY, int maxY, int count) {
    }

    /** The area where pixels differ from {@code bg} noticeably, within y in [from, to). */
    private static Bounds changed(BufferedImage img, Color bg, int from, int to) {
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        int minY = Integer.MAX_VALUE;
        int maxY = -1;
        int n = 0;
        for (int y = from; y < to; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color c = new Color(img.getRGB(x, y));
                if (Math.abs(c.getRed() - bg.getRed()) + Math.abs(c.getGreen() - bg.getGreen())
                        + Math.abs(c.getBlue() - bg.getBlue()) > 90) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    n++;
                }
            }
        }
        return new Bounds(minX, maxX, minY, maxY, n);
    }

    private static Brand brand(String instagram, String phone, Mark mark) {
        return new Brand("Lens & Light", null, instagram, false, 100, "cream", phone, mark);
    }

    @Test
    void cornerMarkSitsBottomRightInsideTheMarginWithoutABox() {
        BufferedImage img = mark(black(1200, 800), text("Lens & Light"), CORNER, 100);
        Bounds b = changed(img, Color.BLACK, 0, 800);
        int margin = (int) Math.round(800 * Watermark.MARGIN);
        assertThat(b.count()).isPositive();
        assertThat(b.maxX()).isLessThanOrEqualTo(1200 - margin);
        assertThat(b.maxY()).isLessThanOrEqualTo(800 - margin + 2);
        assertThat(b.maxX()).isGreaterThan(1200 - margin - 12); // flush with the right margin
        // Small: about 4.2 % of the shorter side tall, and only that corner is touched.
        assertThat(b.maxY() - b.minY()).isLessThan((int) (800 * Watermark.HEIGHT_SMALL * 1.3));
        assertThat(b.minX()).isGreaterThan(600);
        assertThat(img.getRGB(10, 10) & 0xFFFFFF).isZero();
        assertThat(img.getRGB(600, 400) & 0xFFFFFF).isZero();
    }

    @Test
    void positionAndSizeFollowTheSettings() {
        Mark left = new Mark("CORNER", "LEFT", "SMALL", true, true, false, false);
        Mark centre = new Mark("CORNER", "CENTER", "SMALL", true, true, false, false);
        Mark medium = new Mark("CORNER", "RIGHT", "MEDIUM", true, true, false, false);
        Mark small = new Mark("CORNER", "RIGHT", "SMALL", true, true, false, false);
        Bounds l = changed(mark(black(1200, 800), text("Lens"), left, 100), Color.BLACK, 0, 800);
        Bounds c = changed(mark(black(1200, 800), text("Lens"), centre, 100), Color.BLACK, 0, 800);
        assertThat(l.minX()).isLessThan(60);
        assertThat((c.minX() + c.maxX()) / 2.0).isBetween(580.0, 620.0);
        Bounds m = changed(mark(black(1200, 800), text("Lens"), medium, 100), Color.BLACK, 0, 800);
        Bounds s = changed(mark(black(1200, 800), text("Lens"), small, 100), Color.BLACK, 0, 800);
        assertThat(m.maxY() - m.minY()).isGreaterThan(s.maxY() - s.minY());
    }

    @Test
    void aLongMarkIsScaledDownToSixtyPercentOfTheWidth() {
        Watermark.Content c = text("A very long studio name that goes on and on", "@a_very_long_instagram_handle", "+91 98765 43210");
        BufferedImage img = mark(black(1000, 1000), c, CORNER, 100);
        Bounds b = changed(img, Color.BLACK, 0, 1000);
        assertThat(b.count()).isPositive();
        assertThat(b.maxX() - b.minX()).isLessThanOrEqualTo((int) (1000 * Watermark.MAX_WIDTH) + 4);
    }

    @Test
    void textIsWhiteWithASoftShadowSoItReadsOnLightPhotos() {
        BufferedImage img = mark(solid(1200, 800, new Color(235, 235, 235)), text("Lens & Light Studio"), CORNER, 100);
        int darker = 0;
        for (int y = 600; y < 800; y++) {
            for (int x = 800; x < 1200; x++) {
                if (new Color(img.getRGB(x, y)).getRed() < 225) {
                    darker++;
                }
            }
        }
        assertThat(darker).isPositive(); // the shadow
    }

    @Test
    void aTransparentLogoIsDrawnAsAWhiteSilhouette() {
        Color grey = new Color(128, 128, 128);
        Watermark.Content c = new Watermark.Content(transparentLogo(), List.of());
        BufferedImage img = mark(solid(2000, 1000, grey), c, CORNER, 100);
        Bounds b = changed(img, grey, 0, 1000);
        int cx = (b.minX() + b.maxX()) / 2;
        int cy = (b.minY() + b.maxY()) / 2;
        Color centre = new Color(img.getRGB(cx, cy));
        assertThat(centre.getRed()).isGreaterThan(235); // dark artwork became white, not dark on grey
        assertThat(centre.getBlue()).isGreaterThan(235);
    }

    @Test
    void anOpaqueLogoIsDrawnAsIsWithRoundedCorners() {
        Color grey = new Color(128, 128, 128);
        Watermark.Content c = new Watermark.Content(opaqueLogo(new Color(200, 30, 30)), List.of());
        BufferedImage img = mark(solid(2000, 1000, grey), c, CORNER, 100);
        Bounds b = changed(img, grey, 0, 1000);
        Color centre = new Color(img.getRGB((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2));
        assertThat(centre.getRed()).isGreaterThan(centre.getBlue() + 100); // still red
        // The very corner of its box is cut off (rounded), so it stays the photo's grey.
        Color corner = new Color(img.getRGB(b.maxX(), b.maxY()));
        assertThat(Math.abs(corner.getRed() - 128)).isLessThan(30);
    }

    @Test
    void theLogoSizeSettingScalesTheLogo() {
        Watermark.Content c = new Watermark.Content(opaqueLogo(Color.RED), List.of());
        Bounds big = changed(mark(black(2000, 1000), c, CORNER, 200), Color.BLACK, 0, 1000);
        Bounds small = changed(mark(black(2000, 1000), c, CORNER, 50), Color.BLACK, 0, 1000);
        assertThat(big.maxY() - big.minY()).isGreaterThan((small.maxY() - small.minY()) * 3);
    }

    @Test
    void stripMakesTheImageTallerAndNeverTouchesThePhoto() {
        BufferedImage photo = black(2048, 1365);
        Watermark.Content c = new Watermark.Content(opaqueLogo(Color.RED), List.of("Lens & Light", "@lenslight"));
        BufferedImage img = mark(photo, c, STRIP, 100);
        int band = Math.max(40, (int) Math.round(2048 * 0.06));
        assertThat(img.getWidth()).isEqualTo(2048);
        assertThat(img.getHeight()).isEqualTo(1365 + band);
        assertThat(Watermark.markedHeight(2048, 1365, STRIP)).isEqualTo(1365 + band);
        assertThat(Watermark.markedHeight(2048, 1365, CORNER)).isEqualTo(1365);
        // The photo is untouched; the band is warm off-white with a line on top.
        assertThat(changed(img, Color.BLACK, 0, 1365).count()).isZero();
        assertThat(img.getRGB(5, 1365 + band - 3) & 0xFFFFFF).isEqualTo(0xFAF8F5);
        assertThat(img.getRGB(5, 1365) & 0xFFFFFF).isEqualTo(0xE8E2D8);
        // Logo in its own colours (red), text in dark, both inside the band.
        boolean red = false;
        boolean dark = false;
        for (int y = 1366; y < img.getHeight(); y++) {
            for (int x = 0; x < 2048; x++) {
                Color px = new Color(img.getRGB(x, y));
                red |= px.getRed() > 200 && px.getGreen() < 60;
                dark |= px.getRed() < 90 && px.getGreen() < 90 && px.getBlue() < 90;
            }
        }
        assertThat(red).isTrue();
        assertThat(dark).isTrue();
    }

    @Test
    void aTinyPhotoStillGetsAFortyPixelBand() {
        assertThat(Watermark.bandHeight(400)).isEqualTo(40);
        assertThat(Watermark.bandHeight(2000)).isEqualTo(120);
    }

    @Test
    void stripContentFollowsThePosition() {
        Watermark.Content c = text("Lens");
        Mark left = new Mark("STRIP", "LEFT", "SMALL", false, true, false, false);
        Mark right = new Mark("STRIP", "RIGHT", "SMALL", false, true, false, false);
        Color bg = new Color(0xFAF8F5);
        int h = 1000;
        Bounds l = changed(mark(black(1200, h), c, left, 100), bg, h + 2, h + 72);
        Bounds r = changed(mark(black(1200, h), c, right, 100), bg, h + 2, h + 72);
        assertThat(l.minX()).isLessThan(60);
        assertThat(r.maxX()).isGreaterThan(1200 - 60);
    }

    @Test
    void detailsShownAreTheOnesTickedAndAvailable() {
        Mark all = new Mark("CORNER", "RIGHT", "SMALL", true, true, true, true);
        Watermark.Content c = Watermark.resolve(brand("lenslight", "+919876543210", all), null);
        assertThat(c.logo()).isNull(); // ticked but the studio has no logo
        assertThat(c.texts()).containsExactly("Lens & Light", "@lenslight", "+91 98765 43210");

        BufferedImage logo = opaqueLogo(Color.RED);
        Watermark.Content withLogo = Watermark.resolve(brand("lenslight", null, all), logo);
        assertThat(withLogo.logo()).isSameAs(logo);
        assertThat(withLogo.texts()).containsExactly("Lens & Light", "@lenslight"); // no phone set: skipped

        Mark logoOff = new Mark("CORNER", "RIGHT", "SMALL", false, false, true, false);
        Watermark.Content noLogo = Watermark.resolve(brand("lenslight", null, logoOff), logo);
        assertThat(noLogo.logo()).isNull();
        assertThat(noLogo.texts()).containsExactly("@lenslight");
    }

    @Test
    void whenNothingTickedIsAvailableTheStudioNameIsDrawn() {
        // Default settings (logo + Instagram) but the studio has neither.
        Watermark.Content c = Watermark.resolve(brand(null, null, Mark.DEFAULT), null);
        assertThat(c.logo()).isNull();
        assertThat(c.texts()).containsExactly("Lens & Light");
        // Everything unticked.
        Mark none = new Mark("STRIP", "LEFT", "SMALL", false, false, false, false);
        assertThat(Watermark.resolve(brand("lenslight", "+919876543210", none), opaqueLogo(Color.RED)).texts())
                .containsExactly("Lens & Light");
        // And the name really is drawn.
        BufferedImage img = mark(black(1200, 800), c, CORNER, 100);
        assertThat(changed(img, Color.BLACK, 0, 800).count()).isPositive();
    }

    @Test
    void phonesAreFormattedLikeTheWebApp() {
        assertThat(Watermark.formatPhone("+919876543210")).isEqualTo("+91 98765 43210");
        assertThat(Watermark.formatPhone("+442079460958")).isEqualTo("+442079460958");
    }
}
