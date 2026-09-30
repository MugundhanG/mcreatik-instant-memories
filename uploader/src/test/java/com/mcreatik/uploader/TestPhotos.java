package com.mcreatik.uploader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ThreadLocalRandom;

import javax.imageio.ImageIO;

public final class TestPhotos {

    private TestPhotos() {
    }

    /** A unique, valid JPEG. */
    public static byte[] jpeg() throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        g.setColor(new Color(r.nextInt(0xFFFFFF)));
        g.fillRect(0, 0, 320, 240);
        g.setColor(new Color(r.nextInt(0xFFFFFF)));
        g.fillOval(r.nextInt(200), r.nextInt(120), 100, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Writes a photo the way a camera transfer would, back-dated so it counts as "settled". */
    public static Path write(Path folder, String name) throws IOException {
        return write(folder, name, jpeg());
    }

    public static Path write(Path folder, String name, byte[] bytes) throws IOException {
        Path file = folder.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 5_000));
        return file;
    }
}
