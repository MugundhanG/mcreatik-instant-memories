package com.mcreatik.uploader.ui;

import java.nio.file.Path;

import com.mcreatik.uploader.CameraRuntime;
import com.mcreatik.uploader.Cameras;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.engine.UploadEngine;
import com.mcreatik.uploader.source.FtpPhotoSource;

/**
 * What the pages work with: the cameras of this laptop and the one selected in the camera strip. {@link #engine()},
 * {@link #config()} and {@link #ftp()} are the selected camera's, so every page simply follows the selection.
 * {@code actions} are the window-level commands pages can trigger.
 */
public final class AppContext {

    public interface Actions {
        void show(String pageId);

        void changeEvent();

        void openFolder(Path folder);

        void openGallery();

        void browse(String url);

        void selectCamera(CameraRuntime camera);

        void addCamera();

        void removeCamera(CameraRuntime camera);
    }

    private final Cameras cameras;
    private final boolean uploadOriginals;
    private final Actions actions;
    private volatile CameraRuntime selected;

    public AppContext(Cameras cameras, boolean uploadOriginals, Actions actions) {
        this.cameras = cameras;
        this.uploadOriginals = uploadOriginals;
        this.actions = actions;
        this.selected = cameras.first();
    }

    public Cameras cameras() {
        return cameras;
    }

    public CameraRuntime selected() {
        CameraRuntime s = selected;
        return cameras.all().contains(s) ? s : cameras.first();
    }

    public void select(CameraRuntime camera) {
        selected = camera;
    }

    public UploadEngine engine() {
        return selected().engine();
    }

    public UploaderConfig config() {
        return selected().config();
    }

    /** The selected camera's FTP server, or null in folder mode. */
    public FtpPhotoSource ftp() {
        return selected().ftp();
    }

    /** The selected camera's settings and queue folder. */
    public Path dataDir() {
        return selected().dir();
    }

    public boolean uploadOriginals() {
        return uploadOriginals;
    }

    public Actions actions() {
        return actions;
    }

    public String version() {
        return System.getProperty("jpackage.app-version", UploadEngine.APP_VERSION);
    }
}
