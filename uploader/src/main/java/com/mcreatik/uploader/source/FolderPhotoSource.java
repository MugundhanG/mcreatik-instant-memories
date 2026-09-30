package com.mcreatik.uploader.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Polls a folder (and its sub-folders) for new photos.
 *
 * <p>Polling is used instead of OS file-watch events on purpose: watch events are unreliable on macOS,
 * network shares and USB volumes, and they fire while a camera is still writing the file. A file is only
 * emitted once its size and modification time are unchanged across two consecutive scans and it is at
 * least {@code minAge} old, so half-written files are never uploaded.
 */
public class FolderPhotoSource implements PhotoSource {

    private static final Logger log = Logger.getLogger(FolderPhotoSource.class.getName());
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final int MAX_DEPTH = 6;

    private final Path folder;
    private final Duration scanInterval;
    private final Duration minAge;
    private final Clock clock;

    private final Map<Path, Observation> pending = new HashMap<>();
    private final Map<Path, Observation> emitted = new HashMap<>();
    private final Set<String> warnedExtensions = new HashSet<>();
    private ScheduledExecutorService scheduler;

    public FolderPhotoSource(Path folder) {
        this(folder, Duration.ofSeconds(2), Duration.ofSeconds(1), Clock.systemUTC());
    }

    public FolderPhotoSource(Path folder, Duration scanInterval, Duration minAge, Clock clock) {
        this.folder = folder.toAbsolutePath().normalize();
        this.scanInterval = scanInterval;
        this.minAge = minAge;
        this.clock = clock;
    }

    @Override
    public void start(Consumer<Path> onPhoto) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "folder-scanner");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                scanOnce().forEach(onPhoto);
            } catch (Exception e) {
                log.log(Level.WARNING, "Folder scan failed: " + e.getMessage(), e);
            }
        }, 0, scanInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One scan. Returns files that became stable since the previous scan. Synchronous; used directly by tests. */
    public synchronized List<Path> scanOnce() {
        List<Path> ready = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return ready;
        }
        Set<Path> seen = new HashSet<>();
        long now = clock.millis();
        try (Stream<Path> files = Files.walk(folder, MAX_DEPTH, FileVisitOption.FOLLOW_LINKS)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!isCandidate(file)) {
                    continue;
                }
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(file, BasicFileAttributes.class);
                } catch (IOException e) {
                    continue; // vanished or locked mid-scan; try again next time
                }
                if (!attrs.isRegularFile() || attrs.size() == 0) {
                    continue;
                }
                seen.add(file);
                Observation current = new Observation(attrs.size(), attrs.lastModifiedTime().toMillis());
                if (current.equals(emitted.get(file))) {
                    continue; // already handed over and unchanged
                }
                Observation previous = pending.put(file, current);
                boolean stable = current.equals(previous) && now - current.modifiedMillis() >= minAge.toMillis();
                if (stable) {
                    pending.remove(file);
                    emitted.put(file, current);
                    ready.add(file);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            log.warning("Cannot read folder " + folder + ": " + e.getMessage());
        }
        pending.keySet().retainAll(seen);
        emitted.keySet().retainAll(seen);
        return ready;
    }

    private boolean isCandidate(Path file) {
        Path relative = folder.relativize(file);
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || name.startsWith("~")) {
                return false; // hidden files/folders, temp files
            }
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (EXTENSIONS.contains(ext)) {
            return true;
        }
        if (Set.of("cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "dng", "heic").contains(ext)
                && warnedExtensions.add(ext)) {
            log.info("Ignoring ." + ext + " files. Set the camera to RAW+JPEG and the JPEGs will be uploaded.");
        }
        return false;
    }

    @Override
    public String describe() {
        return folder.toString();
    }

    public Path folder() {
        return folder;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private record Observation(long size, long modifiedMillis) {
    }
}
