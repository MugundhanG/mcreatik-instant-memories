package com.mcreatik.uploader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import com.mcreatik.uploader.api.ApiException;
import com.mcreatik.uploader.api.ApiModels;
import com.mcreatik.uploader.config.CameraSet;
import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.queue.QueueCounts;

/**
 * The cameras this laptop serves (1 to {@link CameraSet#MAX}). "Add camera" asks the server for another camera in
 * the same event and starts it at once, so cameras already shooting never stop.
 */
public final class Cameras {

    private static final Logger log = Logger.getLogger(Cameras.class.getName());

    private final Path root;
    private final boolean uploadOriginals;
    private final List<CameraRuntime> cameras = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private Cameras(Path root, boolean uploadOriginals) {
        this.root = root;
        this.uploadOriginals = uploadOriginals;
    }

    /** Starts Camera 1 and every other camera saved on this laptop. */
    public static Cameras start(Path root, UploaderConfig camera1, boolean uploadOriginals) throws IOException {
        Cameras set = new Cameras(root, uploadOriginals);
        set.cameras.add(CameraRuntime.start(1, root, camera1, uploadOriginals));
        for (int n : CameraSet.numbers(root)) {
            if (n == 1) {
                continue;
            }
            Path dir = CameraSet.dirFor(root, n);
            var cfg = UploaderConfig.load(dir);
            if (cfg.isPresent()) {
                Files.createDirectories(cfg.get().watchFolder());
                set.cameras.add(CameraRuntime.start(n, dir, cfg.get(), uploadOriginals));
            }
        }
        return set;
    }

    public List<CameraRuntime> all() {
        return List.copyOf(cameras);
    }

    public CameraRuntime first() {
        return cameras.get(0);
    }

    public Path root() {
        return root;
    }

    public boolean canAdd() {
        return cameras.size() < CameraSet.MAX && first().ftp() != null;
    }

    /** Called (on any thread) after a camera is added or removed. */
    public void onChange(Runnable r) {
        listeners.add(r);
    }

    /** Why the event can't be changed (photos of some camera still on their way), or null. */
    public String blockedReason() {
        int queued = 0;
        int uploading = 0;
        for (CameraRuntime c : cameras) {
            QueueCounts k = c.queue().counts();
            queued += k.queued();
            uploading += k.uploading();
        }
        return EventSwitch.blockedReason(new QueueCounts(queued, uploading, 0, 0, 0));
    }

    /**
     * "Add camera": the server creates the next camera in this event; it gets the next port and its own folder, and
     * starts receiving at once.
     */
    public synchronized CameraRuntime add() throws ApiException, IOException {
        if (!canAdd()) {
            throw new IllegalStateException(cameras.size() >= CameraSet.MAX
                    ? "One computer can serve up to " + CameraSet.MAX + " cameras."
                    : "Adding cameras needs camera Wi-Fi (FTP). Turn it on with Change event….");
        }
        int number = CameraSet.nextNumber(cameras.stream().map(CameraRuntime::number).toList());
        List<Integer> ports = cameras.stream().map(c -> c.config().ftp().port()).toList();
        UploaderConfig camera1 = first().config();
        int port = CameraSet.portFor(camera1.ftp().port(), ports, CameraSet::portFree);
        ApiModels.CameraCreated created = first().api().addCamera();
        UploaderConfig cfg = CameraSet.newCamera(camera1, created.token(), number, port);
        Files.createDirectories(cfg.watchFolder());
        Path dir = CameraSet.dirFor(root, number);
        cfg.save(dir);
        CameraRuntime c = CameraRuntime.start(number, dir, cfg, uploadOriginals);
        cameras.add(c);
        cameras.sort(java.util.Comparator.comparingInt(CameraRuntime::number));
        log.info("Added " + created.name() + " on port " + port);
        listeners.forEach(Runnable::run);
        return c;
    }

    /**
     * "Remove camera" (not Camera 1), only when its queue is empty. It leaves the event on the server and stops here;
     * its photos stay in its folder. Returns a note when the server couldn't be told (offline).
     */
    public synchronized String remove(CameraRuntime c) throws IOException {
        if (c.number() == 1) {
            throw new IllegalArgumentException("Camera 1 can't be removed.");
        }
        String blocked = EventSwitch.blockedReason(c.queue().counts());
        if (blocked != null) {
            throw new IllegalStateException(blocked);
        }
        String note = null;
        try {
            c.api().removeSelf();
        } catch (ApiException e) {
            if (e.kind() != ApiException.Kind.BLOCKED) { // BLOCKED: already removed in the dashboard
                note = "McreatiK couldn't be reached, so this camera may still be listed in the dashboard. "
                        + "You can delete it there.";
            }
        }
        cameras.remove(c);
        c.close();
        CameraSet.deleteDir(root, c.number());
        listeners.forEach(Runnable::run);
        return note;
    }

    /**
     * After Change event saved Camera 1's new event: the other cameras get new cameras in that event (same ports and
     * camera login), and every queue starts the new event at 0. Returns the cameras that couldn't be moved.
     */
    public List<Integer> moveToNewEvent(UploaderConfig newCamera1) {
        List<Integer> failed = new ArrayList<>();
        com.mcreatik.uploader.api.ApiClient api = new com.mcreatik.uploader.api.ApiClient(newCamera1.serverUrl(),
                newCamera1.token());
        for (CameraRuntime c : cameras) {
            c.queue().startNewEvent(); // counts start at 0; photos already seen are still never re-sent
            if (c.number() == 1) {
                continue;
            }
            try {
                ApiModels.CameraCreated created = api.addCamera();
                UploaderConfig cfg = CameraSet.newCamera(newCamera1, created.token(), c.number(), c.config().ftp().port());
                Files.createDirectories(cfg.watchFolder());
                cfg.save(c.dir());
            } catch (ApiException | IOException e) {
                log.warning("Camera " + c.number() + " couldn't be moved to the new event: " + e.getMessage());
                failed.add(c.number());
                try {
                    cameras.remove(c);
                    c.close();
                    CameraSet.deleteDir(root, c.number()); // never leave it uploading into the old event
                } catch (IOException ignored) {
                    // best effort; it is not started again without settings
                }
            }
        }
        return failed;
    }

    public void closeAll() {
        for (CameraRuntime c : cameras) {
            c.close();
        }
    }
}
