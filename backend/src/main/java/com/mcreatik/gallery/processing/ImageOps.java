package com.mcreatik.gallery.processing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Iterator;
import java.util.TimeZone;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;

/** Pure-Java image helpers. Output JPEGs carry no EXIF, so GPS and camera serials never reach guests. */
public final class ImageOps {

    private ImageOps() {
    }

    /** Detects the real type from magic bytes. File extensions and client-declared types are not trusted. */
    public static String sniffMimeType(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }

    public record ExifInfo(int orientation, Instant capturedAt) {
    }

    public static ExifInfo readExif(byte[] bytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
            int orientation = 1;
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null && ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                orientation = ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
            Instant captured = null;
            ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (sub != null) {
                Date date = sub.getDateOriginal(TimeZone.getTimeZone("UTC"));
                captured = date == null ? null : date.toInstant();
            }
            return new ExifInfo(orientation < 1 || orientation > 8 ? 1 : orientation, captured);
        } catch (Exception e) {
            return new ExifInfo(1, null); // metadata is optional; never fail a photo because of it
        }
    }

    public record Decoded(BufferedImage image, int sourceWidth, int sourceHeight) {
    }

    /**
     * Decodes at reduced resolution when possible (source subsampling), so a 24 MP JPEG needs ~25 MB of heap
     * instead of ~100 MB. The result is still at least {@code minLongEdge} pixels on its long edge.
     */
    public static Decoded decode(byte[] bytes, int minLongEdge) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("No image reader for this file");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                int factor = Math.max(1, Math.max(w, h) / Math.max(1, minLongEdge));
                ImageReadParam param = reader.getDefaultReadParam();
                if (factor > 1) {
                    param.setSourceSubsampling(factor, factor, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                return new Decoded(toRgb(image), w, h);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Flattens any colour model (alpha, grey, CMYK-decoded) onto an opaque RGB canvas. */
    static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    public static boolean swapsDimensions(int orientation) {
        return orientation >= 5 && orientation <= 8;
    }

    /** Applies the EXIF orientation so portrait photos display upright everywhere. */
    public static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        boolean swap = swapsDimensions(orientation);
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    /** High-quality downscale (progressive halving + bilinear). Never upscales. */
    public static BufferedImage fitWithin(BufferedImage src, int longEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = (double) longEdge / Math.max(w, h);
        if (scale >= 1.0) {
            return src;
        }
        int targetW = Math.max(1, (int) Math.round(w * scale));
        int targetH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage current = src;
        int cw = w;
        int ch = h;
        while (cw / 2 >= targetW && ch / 2 >= targetH) {
            cw /= 2;
            ch /= 2;
            current = draw(current, cw, ch);
        }
        return (cw == targetW && ch == targetH) ? current : draw(current, targetW, targetH);
    }

    private static BufferedImage draw(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Progressive JPEG: renders a quick preview on slow venue Wi-Fi, then sharpens. */
    public static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            if (param.canWriteProgressive()) {
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
