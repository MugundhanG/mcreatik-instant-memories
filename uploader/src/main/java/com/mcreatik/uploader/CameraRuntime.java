package com.mcreatik.uploader;

import java.nio.file.Path;
import java.util.List;

import com.mcreatik.uploader.api.ApiClient;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.engine.UploadEngine;
import com.mcreatik.uploader.queue.UploadQueue;
import com.mcreatik.uploader.source.CompositePhotoSource;
import com.mcreatik.uploader.source.FolderPhotoSource;
import com.mcreatik.uploader.source.FtpPhotoSource;
import com.mcreatik.uploader.source.PhotoSource;

/** One running camera: its uploader token, queue, camera FTP server (or folder) and upload engine. */
public final class CameraRuntime {

    private final int number;
    private final Path dir;
    private final UploaderConfig config;
    private final ApiClient api;
    private final UploadQueue queue;
    private final FtpPhotoSource ftp;
    private final UploadEngine engine;

    private CameraRuntime(int number, Path dir, UploaderConfig config, boolean uploadOriginals) {
        this.number = number;
        this.dir = dir;
        this.config = config;
        this.api = new ApiClient(config.serverUrl(), config.token());
        this.queue = new UploadQueue(dir.resolve("queue.db"));
        this.ftp = config.ftp().enabled()
                ? new FtpPhotoSource(config.watchFolder(), config.ftp().port(), config.ftp().username(), config.ftp().password())
                : null;
        PhotoSource source = ftp == null ? new FolderPhotoSource(config.watchFolder())
                : new CompositePhotoSource(List.of(new FolderPhotoSource(config.watchFolder()), ftp));
        this.engine = new UploadEngine(api, queue, source, config.concurrency(), config.deviceId(), !uploadOriginals);
    }

    /** Creates and starts a camera (its FTP server listens from now on). */
    public static CameraRuntime start(int number, Path dir, UploaderConfig config, boolean uploadOriginals) {
        CameraRuntime c = new CameraRuntime(number, dir, config, uploadOriginals);
        c.engine.start();
        return c;
    }

    public int number() {
        return number;
    }

    public Path dir() {
        return dir;
    }

    public UploaderConfig config() {
        return config;
    }

    public ApiClient api() {
        return api;
    }

    public UploadQueue queue() {
        return queue;
    }

    /** The camera FTP server, or null when the camera software writes into the folder itself. */
    public FtpPhotoSource ftp() {
        return ftp;
    }

    public UploadEngine engine() {
        return engine;
    }

    /** Stops uploading and the FTP server; queued photos resume next time. */
    public void close() {
        engine.close();
        queue.close();
    }
}
