package com.mcreatik.gallery.photo;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.mcreatik.gallery.auth.StudioBranding;
import com.mcreatik.gallery.processing.ImageOps;

/**
 * The studio's mark on a photo a guest downloads, in the style the studio chose (see {@link StudioBranding.Mark}):
 * CORNER, a small logo and/or text line with a soft shadow in the bottom-left, centre or right, or STRIP, a warm
 * off-white band added below the photo (so the image is never covered). The web app draws the same mark in the
 * browser for its own downloads and shares; this is the server's copy for the plain download link (in-app browsers,
 * the fallback path), so both look alike. Keep the two in step: see {@code web/src/lib/watermark.ts}, whose constants
 * carry the same names.
 */
@Component
public class Watermark {

    // ---- CORNER ----
    /** Distance from the photo's edges, as a share of its shorter side S. */
    static final double MARGIN = 0.03;
    /** Height of the mark (the logo at the studio's 100 % size), share of S: SMALL / MEDIUM. */
    static final double HEIGHT_SMALL = 0.042;
    static final double HEIGHT_MEDIUM = 0.056;
    /** The mark never covers more than this share of the photo's width W; wider marks are scaled down. */
    static final double MAX_WIDTH = 0.6;
    static final float OPACITY = 0.92f;
    /** Text size as a share of the mark's height; the gap between logo and text likewise. */
    static final double TEXT_SIZE = 0.75;
    static final double LOGO_GAP = 0.4;
    /** A logo without transparency (a JPEG rectangle) gets corners this round, share of its height. */
    static final double LOGO_ROUNDING = 0.12;
    /** The soft shadow behind white text and logo silhouettes: blur = this share of the text size, black at this opacity. */
    static final double SHADOW_BLUR = 0.25;
    static final float SHADOW_OPACITY = 0.45f;
    /** A corner logo is this many times the mark's base height, so a logo's small lines stay readable next to the text. */
    static final double CORNER_LOGO = 1.5;
    /** A logo is "transparent" when at least this share of its pixels is mostly see-through. */
    static final double TRANSPARENT_SHARE = 0.005;

    // ---- STRIP ----
    /** The band under the photo: max(STRIP_MIN, STRIP_HEIGHT x W) px, with a 1 px line on top. */
    static final int STRIP_MIN = 40;
    static final double STRIP_HEIGHT = 0.06;
    static final int STRIP_COLOR = 0xFAF8F5;
    static final int STRIP_LINE = 0xE8E2D8;
    static final int STRIP_TEXT_COLOR = 0x2A2520;
    /** Logo height and text size as shares of the band; the gap between them; side padding as a share of W. */
    static final double STRIP_LOGO = 0.6;
    static final double STRIP_TEXT = 0.36;
    static final double STRIP_GAP = 0.25;
    static final double STRIP_PADDING = 0.03;

    static final String SEPARATOR = "  \u00b7  ";
    static final float JPEG_QUALITY = 0.9f;

    /** What is drawn: the logo (or null) and the text parts, in order. */
    record Content(BufferedImage logo, List<String> texts) {
    }

    /** Decoding and encoding a 2048 px photo takes real memory and CPU: two at a time on the small server. */
    private final Semaphore working = new Semaphore(2, true);
    private final StudioBranding branding;
    /** Decoded logos by owner, a handful at most (one per studio with a live event). */
    private final Map<String, BufferedImage> logos = java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > 32;
        }
    });

    public Watermark(StudioBranding branding) {
        this.branding = branding;
    }

    /** The photo (a JPEG stream) with the owner's mark, as a JPEG (taller for STRIP). The stream is closed. */
    public byte[] apply(InputStream jpeg, UUID ownerId) throws IOException, InterruptedException {
        StudioBranding.Brand brand = branding.ofOwner(ownerId);
        Content content = resolve(brand, logo(ownerId, brand));
        working.acquire();
        try (jpeg) {
            BufferedImage photo = ImageIO.read(jpeg);
            if (photo == null) {
                throw new IOException("Not an image");
            }
            BufferedImage rgb = render(photo, content, brand.mark(), brand.logoScale());
            return ImageOps.encodeJpeg(rgb, JPEG_QUALITY);
        } finally {
            working.release();
        }
    }

    /** The photo with the mark as an RGB image (taller than the photo for STRIP). */
    static BufferedImage render(BufferedImage photo, Content content, StudioBranding.Mark mark, int logoScale) {
        int w = photo.getWidth();
        int h = photo.getHeight();
        BufferedImage rgb = new BufferedImage(w, markedHeight(w, h, mark), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(photo, 0, 0, null);
            draw(g, w, h, content, mark, logoScale);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    /**
     * What to draw: the logo when ticked and uploaded, then the name, "@instagram" and phone when ticked and set.
     * With nothing to show the studio name is drawn: a mark is never empty.
     */
    static Content resolve(StudioBranding.Brand brand, BufferedImage logo) {
        StudioBranding.Mark m = brand.mark();
        List<String> texts = new ArrayList<>();
        BufferedImage shownLogo = m.logo() ? logo : null;
        if (m.name()) {
            texts.add(brand.name());
        }
        if (m.instagram() && brand.instagram() != null && !brand.instagram().isBlank()) {
            texts.add("@" + brand.instagram());
        }
        if (m.phone() && brand.phone() != null && !brand.phone().isBlank()) {
            texts.add(formatPhone(brand.phone()));
        }
        if (shownLogo == null && texts.isEmpty()) {
            texts.add(brand.name());
        }
        return new Content(shownLogo, texts);
    }

    /** "+919876543210" becomes "+91 98765 43210"; other numbers are shown as saved (as the web app does). */
    static String formatPhone(String phone) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\+91(\\d{5})(\\d{5})$").matcher(phone);
        return m.matches() ? "+91 " + m.group(1) + " " + m.group(2) : phone;
    }

    /** The height of the band a STRIP adds below a photo {@code width} px wide. */
    static int bandHeight(int width) {
        return Math.max(STRIP_MIN, (int) Math.round(width * STRIP_HEIGHT));
    }

    /** The finished image's height: the photo's, plus the band for STRIP. */
    static int markedHeight(int width, int height, StudioBranding.Mark mark) {
        return "STRIP".equals(mark.style()) ? height + bandHeight(width) : height;
    }

    /**
     * Draws the mark for a {@code width} x {@code height} photo already on the canvas. For STRIP the canvas is
     * {@link #markedHeight} tall and the band is drawn below the photo.
     */
    static void draw(Graphics2D g, int width, int height, Content content, StudioBranding.Mark mark, int logoScale) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        if ("STRIP".equals(mark.style())) {
            drawStrip(g, width, height, content, mark.position());
        } else {
            drawCorner(g, width, height, content, mark, logoScale);
        }
    }

    private static double textWidth(Graphics2D g, Font font, String text) {
        return text.isEmpty() ? 0 : font.getStringBounds(text, g.getFontRenderContext()).getWidth();
    }

    private static double startX(String position, int width, double edge, double total) {
        return switch (position) {
            case "LEFT" -> edge;
            case "CENTER" -> (width - total) / 2.0;
            default -> width - edge - total;
        };
    }

    private static void drawCorner(Graphics2D g, int width, int height, Content content, StudioBranding.Mark mark,
                                   int logoScale) {
        int shortSide = Math.min(width, height);
        double margin = Math.max(6, Math.round(shortSide * MARGIN));
        double base = shortSide * ("MEDIUM".equals(mark.size()) ? HEIGHT_MEDIUM : HEIGHT_SMALL);
        BufferedImage logo = content.logo();
        String text = String.join(SEPARATOR, content.texts());
        double fontPx = Math.max(6, base * TEXT_SIZE);
        double logoH = logo == null ? 0 : base * CORNER_LOGO * Math.clamp(logoScale, 50, 200) / 100.0;
        double logoW = logo == null ? 0 : logoH * logo.getWidth() / logo.getHeight();
        double gap = logo == null || text.isEmpty() ? 0 : base * LOGO_GAP;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 12).deriveFont((float) fontPx);
        double total = logoW + gap + textWidth(g, font, text);
        double fit = Math.min(1, width * MAX_WIDTH / total);
        if (fit < 1) {
            logoH *= fit;
            logoW *= fit;
            gap *= fit;
            fontPx *= fit;
            font = font.deriveFont((float) fontPx);
            total = logoW + gap + textWidth(g, font, text);
        }
        double lineH = Math.max(logoH, fontPx);
        double x = startX(mark.position(), width, margin, total);
        double cy = height - margin - lineH / 2.0;

        // White parts (text, a transparent logo's silhouette) go on a layer of their own, so their shadow can be a real
        // blur of their shape, like the browser's canvas shadow. An opaque logo is drawn as it is, without a shadow.
        int pad = (int) Math.ceil(fontPx * SHADOW_BLUR * 2) + 2;
        double top = cy - lineH / 2.0;
        BufferedImage layer = new BufferedImage((int) Math.ceil(total) + 2 * pad, (int) Math.ceil(lineH) + 2 * pad,
                BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D lg = layer.createGraphics();
        lg.setRenderingHints(g.getRenderingHints());
        lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        lg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        lg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double lcy = pad + lineH / 2.0;
        boolean opaqueLogo = logo != null && !hasTransparency(logo);
        if (logo != null && !opaqueLogo) {
            lg.drawImage(tint(logo, 0xFFFFFF), pad, (int) Math.round(lcy - logoH / 2.0), (int) Math.round(logoW),
                    (int) Math.round(logoH), null);
        }
        if (!text.isEmpty()) {
            FontMetrics m = lg.getFontMetrics(font);
            lg.setFont(font);
            lg.setColor(Color.WHITE);
            lg.drawString(text, (float) (pad + logoW + gap), (float) (lcy + (m.getAscent() - m.getDescent()) / 2.0));
        }
        lg.dispose();

        Composite before = g.getComposite();
        int lx = (int) Math.round(x) - pad;
        int ly = (int) Math.round(top) - pad;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OPACITY * SHADOW_OPACITY));
        g.drawImage(blur(tint(layer, 0x000000), Math.max(1, (int) Math.round(fontPx * SHADOW_BLUR / 2))), lx, ly, null);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OPACITY));
        g.drawImage(layer, lx, ly, null);
        if (opaqueLogo) {
            double oy = cy - logoH / 2.0;
            Shape clip = g.getClip();
            g.clip(new RoundRectangle2D.Double(x, oy, logoW, logoH, logoH * LOGO_ROUNDING * 2, logoH * LOGO_ROUNDING * 2));
            g.drawImage(logo, (int) Math.round(x), (int) Math.round(oy), (int) Math.round(logoW), (int) Math.round(logoH), null);
            g.setClip(clip);
        }
        g.setComposite(before);
    }

    /** A soft blur (two box passes each way, close to a Gaussian), for the corner mark's shadow. */
    static BufferedImage blur(BufferedImage src, int radius) {
        int size = radius * 2 + 1;
        float[] k = new float[size];
        java.util.Arrays.fill(k, 1f / size);
        java.awt.image.ConvolveOp h = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(size, 1, k),
                java.awt.image.ConvolveOp.EDGE_ZERO_FILL, null);
        java.awt.image.ConvolveOp v = new java.awt.image.ConvolveOp(new java.awt.image.Kernel(1, size, k),
                java.awt.image.ConvolveOp.EDGE_ZERO_FILL, null);
        BufferedImage out = src;
        for (int pass = 0; pass < 2; pass++) {
            out = v.filter(h.filter(out, null), null);
        }
        return out;
    }

    private static void drawStrip(Graphics2D g, int width, int height, Content content, String position) {
        int band = bandHeight(width);
        g.setColor(new Color(STRIP_COLOR));
        g.fillRect(0, height, width, band);
        g.setColor(new Color(STRIP_LINE));
        g.fillRect(0, height, width, 1);

        BufferedImage logo = content.logo();
        String text = String.join(SEPARATOR, content.texts());
        double pad = width * STRIP_PADDING;
        double logoH = logo == null ? 0 : band * STRIP_LOGO;
        double logoW = logo == null ? 0 : logoH * logo.getWidth() / logo.getHeight();
        double gap = logo == null || text.isEmpty() ? 0 : band * STRIP_GAP;
        double fontPx = band * STRIP_TEXT;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 12).deriveFont((float) fontPx);
        double total = logoW + gap + textWidth(g, font, text);
        double fit = Math.min(1, (width - 2 * pad) / total);
        if (fit < 1) {
            logoH *= fit;
            logoW *= fit;
            gap *= fit;
            fontPx *= fit;
            font = font.deriveFont((float) fontPx);
            total = logoW + gap + textWidth(g, font, text);
        }
        double x = startX(position, width, pad, total);
        double cy = height + band / 2.0;
        if (logo != null) {
            g.drawImage(logo, (int) Math.round(x), (int) Math.round(cy - logoH / 2.0), (int) Math.round(logoW),
                    (int) Math.round(logoH), null);
        }
        if (!text.isEmpty()) {
            FontMetrics m = g.getFontMetrics(font);
            g.setFont(font);
            g.setColor(new Color(STRIP_TEXT_COLOR));
            g.drawString(text, (float) (x + logoW + gap), (float) (cy + (m.getAscent() - m.getDescent()) / 2.0));
        }
    }

    /** True when the logo has real see-through areas (a PNG cut-out), not just soft edges or none at all. */
    static boolean hasTransparency(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        int step = Math.max(1, Math.max(img.getWidth(), img.getHeight()) / 200);
        long seen = 0;
        long clear = 0;
        for (int y = 0; y < img.getHeight(); y += step) {
            for (int x = 0; x < img.getWidth(); x += step) {
                seen++;
                if ((img.getRGB(x, y) >>> 24) < 128) {
                    clear++;
                }
            }
        }
        return seen > 0 && clear >= seen * TRANSPARENT_SHARE;
    }

    /** The logo's shape in one colour, alpha kept: a white silhouette (or its black shadow). */
    static BufferedImage tint(BufferedImage img, int rgb) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                out.setRGB(x, y, (img.getRGB(x, y) & 0xFF000000) | rgb);
            }
        }
        return out;
    }

    /** The owner's logo, decoded once; null when it has none or it can't be read (then the name is drawn). */
    private BufferedImage logo(UUID ownerId, StudioBranding.Brand brand) {
        if (brand.logoUrl() == null) {
            return null;
        }
        String cacheKey = ownerId + "|" + brand.logoUrl(); // a new logo gets a new URL
        BufferedImage cached = logos.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            BufferedImage img = branding.logoFile(ownerId)
                    .map(f -> {
                        try {
                            return ImageIO.read(new ByteArrayInputStream(f.data()));
                        } catch (IOException e) {
                            return null;
                        }
                    }).orElse(null);
            if (img != null) {
                logos.put(cacheKey, img);
            }
            return img;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
