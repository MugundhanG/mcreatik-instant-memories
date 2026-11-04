package com.mcreatik.uploader.ui;

import java.nio.file.Path;

import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.engine.UploadEngine;
import com.mcreatik.uploader.source.FtpPhotoSource;

/**
 * What the pages work with: the running engine, its settings, and the camera FTP server (null in folder mode).
 * {@code actions} are the window-level commands pages can trigger.
 */
public record AppContext(UploadEngine engine, UploaderConfig config, FtpPhotoSource ftp, Path dataDir,
                         boolean uploadOriginals, Actions actions) {

    public interface Actions {
        void show(String pageId);

        void changeEvent();

        void openFolder(Path folder);

        void openGallery();

        void browse(String url);
    }

    public String version() {
        return System.getProperty("jpackage.app-version", UploadEngine.APP_VERSION);
    }
}
