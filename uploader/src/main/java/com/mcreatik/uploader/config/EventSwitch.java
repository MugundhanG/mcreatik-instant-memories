package com.mcreatik.uploader.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mcreatik.uploader.queue.QueueCounts;

/** The rules for moving this uploader to another event: when it is allowed, which folder, and how to restart. */
public final class EventSwitch {

    private EventSwitch() {
    }

    /** Why the event can't be changed right now, or null when it can. */
    public static String blockedReason(QueueCounts counts) {
        int pending = counts.pending();
        if (pending == 0) {
            return null;
        }
        return (pending == 1 ? "1 photo hasn't" : pending + " photos haven't")
                + " reached the current event yet. Wait until Waiting and Uploading are 0, then change event.";
    }

    /** Where photos are kept by default: "McreatiK Photos" in the home folder. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), "McreatiK Photos");
    }

    /** The event folder only (no camera sub-folder): {@code McreatiK Photos/<event name>}. */
    public static Path folderFor(Path current, Path chosen, String newEventName, Path root) {
        return folderFor(current, chosen, newEventName, root, null);
    }

    /**
     * Camera 1's folder for the new event. When the current folder is the default one (or inside it), and the user
     * didn't choose another, each event gets its own folder with one sub-folder per camera:
     * {@code McreatiK Photos/<event name>/<camera name>}. A folder the user picked themselves is kept as it is.
     */
    public static Path folderFor(Path current, Path chosen, String newEventName, Path root, String cameraName) {
        if (newEventName == null || newEventName.isBlank() || !chosen.equals(current)) {
            return chosen;
        }
        Path normalized = current.toAbsolutePath().normalize();
        Path rootNorm = root.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        boolean inDefault = normalized.equals(rootNorm) || rootNorm.equals(parent)
                || (parent != null && rootNorm.equals(parent.getParent()));
        if (!inDefault) {
            return chosen;
        }
        Path event = rootNorm.resolve(safeName(newEventName));
        return cameraName == null || cameraName.isBlank() ? event : event.resolve(safeName(cameraName));
    }

    /** A folder name that works on Windows and Mac. */
    public static String safeName(String eventName) {
        String s = eventName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("[. ]+$", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).trim();
        }
        return s.isEmpty() ? "Event" : s;
    }

    /**
     * The options to start again with. {@code --connect} and {@code --folder} are dropped (the saved settings now hold
     * the new event), and {@code --wait-for-lock} lets the new copy wait while this one closes.
     */
    public static List<String> restartArgs(List<String> original) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) {
            String a = original.get(i);
            if (a.equals("--connect") || a.equals("--folder")) {
                i++; // skip its value too
            } else if (!a.equals("--wait-for-lock")) {
                out.add(a);
            }
        }
        out.add("--wait-for-lock");
        return out;
    }
}
