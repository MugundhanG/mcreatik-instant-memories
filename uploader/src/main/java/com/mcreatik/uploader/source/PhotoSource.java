package com.mcreatik.uploader.source;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Where photographs come from. V1 = a watched local folder that the camera's own transfer mechanism
 * (FTP, Wi-Fi app, tethering software, card reader) writes into. Future sources (camera SDKs, a built-in
 * FTP server) implement this interface; the queue, upload engine and backend do not change.
 */
public interface PhotoSource extends AutoCloseable {

    /** Starts emitting complete, stable photo files. May emit the same file more than once; consumers dedupe. */
    void start(Consumer<Path> onPhoto);

    String describe();

    @Override
    void close();
}
