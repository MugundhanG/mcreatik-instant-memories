package com.mcreatik.gallery.photo;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.mcreatik.gallery.auth.StudioBranding;
import com.mcreatik.gallery.processing.ImageOps;

/**
 * The studio's logo (or, without one, its name) in the bottom-right corner of a photo a guest downloads, on a soft
 * white label so any logo reads on any photo. The web app draws the same mark in the browser for its own downloads
 * and shares; this is the server's copy for the plain download link (in-app browsers, the fallback path), so both
 * look alike. Keep the two in step: see {@code web/src/lib/watermark.ts}.
 */
@Component
public class Watermark {

    /** Logo height as a share of the photo's shorter side, at the studio's 100 % logo size. */
    static final double LOGO_HEIGHT = 0.07;
    /** The label never covers more than this share of the photo's width. */
    static final double MAX_WIDTH = 0.38;
    static final double MARGIN = 0.03;
    static final double PADDING = 0.012;
    static final float LABEL_ALPHA = 0.78f;
    static final float JPEG_QUALITY = 0.9f;

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

    /** The photo (a JPEG stream) with the owner's mark, as a JPEG. The stream is closed. */
    public byte[] apply(InputStream jpeg, UUID ownerId) throws IOException, InterruptedException {
        StudioBranding.Brand brand = branding.ofOwner(ownerId);
        BufferedImage logo = logo(ownerId, brand);
        working.acquire();
        try (jpeg) {
            BufferedImage photo = ImageIO.read(jpeg);
            if (photo == null) {
                throw new IOException("Not an image");
            }
            BufferedImage rgb = new BufferedImage(photo.getWidth(), photo.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.drawImage(photo, 0, 0, null);
                draw(g, rgb.getWidth(), rgb.getHeight(), logo, brand.name(), brand.logoScale());
            } finally {
                g.dispose();
            }
            return ImageOps.encodeJpeg(rgb, JPEG_QUALITY);
        } finally {
            working.release();
        }
    }

    /** Draws the label onto a {@code width} x {@code height} canvas. {@code logo} null: the studio's name instead. */
    static void draw(Graphics2D g, int width, int height, BufferedImage logo, String name, int logoScale) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int shortSide = Math.min(width, height);
        double scale = Math.clamp(logoScale, 50, 200) / 100.0;
        int pad = Math.max(4, (int) Math.round(shortSide * PADDING));
        int margin = Math.max(6, (int) Math.round(shortSide * MARGIN));
        int maxContentW = (int) (width * MAX_WIDTH) - 2 * pad;
        int contentH = Math.max(12, (int) Math.round(shortSide * LOGO_HEIGHT * scale));

        int contentW;
        Font font = null;
        if (logo != null) {
            contentW = (int) Math.round(contentH * (double) logo.getWidth() / logo.getHeight());
            if (contentW > maxContentW) {
                contentH = (int) Math.round(contentH * (double) maxContentW / contentW);
                contentW = maxContentW;
            }
        } else {
            int size = Math.max(10, (int) Math.round(contentH * 0.55));
            font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            FontMetrics m = g.getFontMetrics(font);
            while (m.stringWidth(name) > maxContentW && size > 10) {
                font = font.deriveFont((float) --size);
                m = g.getFontMetrics(font);
            }
            contentW = Math.min(maxContentW, m.stringWidth(name));
            contentH = m.getHeight();
        }

        int boxW = contentW + 2 * pad;
        int boxH = contentH + 2 * pad;
        int x = width - margin - boxW;
        int y = height - margin - boxH;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, LABEL_ALPHA));
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(x, y, boxW, boxH, pad * 2.0, pad * 2.0));
        g.setComposite(AlphaComposite.SrcOver);
        if (logo != null) {
            g.drawImage(logo, x + pad, y + pad, contentW, contentH, null);
        } else {
            g.setFont(font);
            g.setColor(new Color(0x2a, 0x25, 0x20));
            FontMetrics m = g.getFontMetrics();
            g.drawString(name, x + pad, y + pad + m.getAscent());
        }
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
