package com.mcreatik.uploader;

import java.util.logging.Logger;

/**
 * Stops a Mac from going to sleep while the uploader runs: if it sleeps mid-event, the camera can't reach it and
 * uploads stop. Uses macOS's own {@code caffeinate}, tied to this process, so it ends by itself when the uploader
 * quits. Windows laptops are covered by the power settings in the guide. Never fails the start-up.
 */
final class KeepAwake {

    private static final Logger log = Logger.getLogger(KeepAwake.class.getName());

    private KeepAwake() {
    }

    static void start() {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("mac")) {
            return;
        }
        try {
            // -d display, -i idle, -m disk, -s system (on power); -w: stop when this process exits.
            new ProcessBuilder("caffeinate", "-dims", "-w", Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            log.info("Keeping this Mac awake while the uploader runs");
        } catch (Exception e) {
            log.warning("Could not keep the Mac awake (" + e.getMessage() + "); set it not to sleep in System Settings");
        }
    }
}
