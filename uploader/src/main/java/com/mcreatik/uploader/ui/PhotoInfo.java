package com.mcreatik.uploader.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.mcreatik.uploader.prepare.ImageOps;

/**
 * Reads what the window shows about a photo from the file on this computer: a small thumbnail and the camera model
 * (from the photo's EXIF). All work runs on one background thread; results are cached.
 */
public final class PhotoInfo {

    /** Thumbnail edge in pixels (drawn at about half that on a normal display). */
    public static final int THUMB = 112;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "photo-info");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY); // never slows uploading
        return t;
    });

    /** Small LRU: the Recent list and a Gallery page at a time. */
    private static final Map<Path, BufferedImage> THUMBS = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, BufferedImage> eldest) {
            return size() > 200;
        }
    };
    /** Files whose thumbnail couldn't be made (missing or not an image), so they aren't retried every refresh. */
    private static final Map<Path, Boolean> UNREADABLE = new ConcurrentHashMap<>();
    private static final Map<Path, Boolean> LOADING = new ConcurrentHashMap<>();
    private static final Map<Path, String> MODELS = new ConcurrentHashMap<>();

    private PhotoInfo() {
    }

    /** The cached thumbnail, or null; when null it starts loading and calls {@code ready} on the Swing thread. */
    public static BufferedImage thumbnail(Path file, Runnable ready) {
        synchronized (THUMBS) {
            BufferedImage cached = THUMBS.get(file);
            if (cached != null) {
                return cached;
            }
        }
        if (UNREADABLE.containsKey(file) || LOADING.putIfAbsent(file, Boolean.TRUE) != null) {
            return null;
        }
        WORKER.execute(() -> {
            try {
                BufferedImage thumb = makeThumbnail(file);
                if (thumb == null) {
                    UNREADABLE.put(file, Boolean.TRUE);
                } else {
                    synchronized (THUMBS) {
                        THUMBS.put(file, thumb);
                    }
                    SwingUtilities.invokeLater(ready);
                }
            } finally {
                LOADING.remove(file);
            }
        });
        return null;
    }

    /** True when the file is gone or isn't a readable image, so the list can show a placeholder. */
    public static boolean unreadable(Path file) {
        return UNREADABLE.containsKey(file);
    }

    static BufferedImage makeThumbnail(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            ImageOps.Decoded decoded = ImageOps.decode(bytes, THUMB * 2);
            BufferedImage oriented = ImageOps.applyOrientation(decoded.image(), ImageOps.readExif(bytes).orientation());
            return cropSquare(ImageOps.fitWithin(oriented, THUMB * 2), THUMB);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static BufferedImage cropSquare(BufferedImage src, int edge) {
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage square = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        return ImageOps.fitWithin(square, edge);
    }

    /** The camera model from the newest of these photos that has one (in order), or null. */
    public static void firstCameraModel(java.util.List<Path> newestFirst, Consumer<String> result) {
        WORKER.execute(() -> {
            String found = null;
            for (Path p : newestFirst) {
                String m = MODELS.computeIfAbsent(p, f -> {
                    String r = readModel(f);
                    return r == null ? "" : r;
                });
                if (!m.isEmpty()) {
                    found = m;
                    break;
                }
            }
            String model = found;
            SwingUtilities.invokeLater(() -> result.accept(model));
        });
    }

    static String readModel(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 == null) {
                return null;
            }
            return cameraName(ifd0.getString(ExifIFD0Directory.TAG_MAKE), ifd0.getString(ExifIFD0Directory.TAG_MODEL));
        } catch (Exception e) {
            return null;
        }
    }

    /** "Canon" + "Canon EOS R6m2" style EXIF into a readable name; Sony's codes into their marketing names. */
    public static String cameraName(String make, String model) {
        String mk = make == null ? "" : make.trim();
        String md = model == null ? "" : model.trim();
        if (md.isEmpty()) {
            return mk.isEmpty() ? null : capitalise(mk);
        }
        String known = switch (md.toUpperCase()) {
            case "ILCE-7M4" -> "Sony α7 IV";
            case "ILCE-7M3" -> "Sony α7 III";
            case "ILCE-7RM5" -> "Sony α7R V";
            case "ILCE-7RM4" -> "Sony α7R IV";
            case "ILCE-1" -> "Sony α1";
            case "ILCE-9M2" -> "Sony α9 II";
            case "CANON EOS R6M2" -> "Canon EOS R6 Mark II";
            default -> null;
        };
        if (known != null) {
            return known;
        }
        String brand = capitalise(mk.split("\\s+")[0]);
        return brand.isEmpty() || md.toLowerCase().startsWith(brand.toLowerCase()) ? md : brand + " " + md;
    }

    private static String capitalise(String s) {
        if (s.isEmpty()) {
            return s;
        }
        String lower = s.toLowerCase();
        return switch (lower) {
            case "nikon corporation" -> "Nikon";
            case "fujifilm" -> "Fujifilm";
            default -> Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        };
    }
}
