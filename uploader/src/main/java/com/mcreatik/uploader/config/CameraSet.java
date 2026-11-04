package com.mcreatik.uploader.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Where each camera of one laptop lives, and its port and folder. Up to {@link #MAX} cameras share one app:
 * <ul>
 *   <li>Camera 1 is the data folder itself (so existing installs keep working);</li>
 *   <li>Cameras 2–4 live in {@code cameras/2 … cameras/4}, each with its own settings and queue;</li>
 *   <li>each camera has its own FTP port (2121, 2122…) and photo folder, and the same camera login as Camera 1.</li>
 * </ul>
 */
public final class CameraSet {

    public static final int MAX = 4;
    /** How far above Camera 1's port to look for a free one. */
    static final int PORT_RANGE = 8;

    private CameraSet() {
    }

    public static Path dirFor(Path root, int number) {
        return number == 1 ? root : root.resolve("cameras").resolve(Integer.toString(number));
    }

    /** The cameras set up on this laptop: always 1, then each of 2–4 that has saved settings. */
    public static List<Integer> numbers(Path root) {
        List<Integer> out = new ArrayList<>();
        out.add(1);
        for (int n = 2; n <= MAX; n++) {
            if (Files.isRegularFile(dirFor(root, n).resolve(UploaderConfig.FILE_NAME))) {
                out.add(n);
            }
        }
        return out;
    }

    /** The lowest free camera number, or -1 when all {@link #MAX} are in use. */
    public static int nextNumber(Collection<Integer> inUse) {
        for (int n = 2; n <= MAX; n++) {
            if (!inUse.contains(n)) {
                return n;
            }
        }
        return -1;
    }

    /**
     * Next to Camera 1's folder, never inside it (the folder watcher looks into subfolders), for example
     * {@code McreatiK Photos/Ananya & Rohit Wedding - Camera 2} (plain ASCII: any file system).
     */
    public static Path folderFor(Path camera1Folder, int number) {
        if (number == 1) {
            return camera1Folder;
        }
        Path abs = camera1Folder.toAbsolutePath().normalize();
        String name = abs.getFileName() == null ? "McreatiK Photos" : abs.getFileName().toString();
        Path parent = abs.getParent() == null ? abs : abs.getParent();
        return parent.resolve(name + " - Camera " + number);
    }

    /** The first port above Camera 1's that no other camera uses and that is free on this computer. */
    public static int portFor(int basePort, Collection<Integer> usedPorts, IntPredicate free) {
        for (int p = basePort + 1; p <= basePort + PORT_RANGE; p++) {
            if (!usedPorts.contains(p) && free.test(p)) {
                return p;
            }
        }
        throw new IllegalStateException("No free port between " + (basePort + 1) + " and " + (basePort + PORT_RANGE)
                + ". Close other programs using these ports and try again.");
    }

    /** True when nothing listens on the port right now. */
    public static boolean portFree(int port) {
        try (java.net.ServerSocket s = new java.net.ServerSocket(port)) {
            s.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Settings for a new camera: its own token, folder and port; Camera 1's computer id and camera login. */
    public static UploaderConfig newCamera(UploaderConfig camera1, String token, int number, int port) {
        return new UploaderConfig(camera1.serverUrl(), token, folderFor(camera1.watchFolder(), number),
                camera1.concurrency(), camera1.deviceId(), camera1.ftp().withPort(port));
    }

    /** Deletes a removed camera's settings and queue (never its photos, which live in the photo folder). */
    public static void deleteDir(Path root, int number) throws IOException {
        if (number == 1) {
            throw new IllegalArgumentException("Camera 1 can't be removed");
        }
        Path dir = dirFor(root, number);
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
