package com.mcreatik.uploader.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mcreatik.uploader.queue.QueueCounts;

class EventSwitchTest {

    private final Path root = Path.of("/home/a/McreatiK Photos");

    @Test
    void changingEventIsBlockedWhilePhotosAreStillOnTheirWay() {
        assertTrue(EventSwitch.blockedReason(new QueueCounts(3, 1, 10, 0, 0)).startsWith("4 photos haven't"));
        assertTrue(EventSwitch.blockedReason(new QueueCounts(0, 1, 0, 0, 0)).startsWith("1 photo hasn't"));
        assertNull(EventSwitch.blockedReason(new QueueCounts(0, 0, 120, 4, 2)), "done, duplicates and failed don't block");
    }

    @Test
    void theDefaultFolderGetsOneFolderPerEvent() {
        assertEquals(root.resolve("Arun & Priya Reception"),
                EventSwitch.folderFor(root, root, "Arun & Priya Reception", root));
        Path previous = root.resolve("Arun & Priya Wedding");
        assertEquals(root.resolve("Meena 60th"), EventSwitch.folderFor(previous, previous, "Meena 60th", root));
    }

    @Test
    void aFolderTheUserChoseIsKept() {
        Path own = Path.of("/data/Camera1");
        assertEquals(own, EventSwitch.folderFor(own, own, "Meena 60th", root));
        Path picked = Path.of("/data/Other");
        assertEquals(picked, EventSwitch.folderFor(root, picked, "Meena 60th", root));
        assertEquals(root, EventSwitch.folderFor(root, root, null, root), "offline: event name unknown, keep it");
    }

    @Test
    void eventNamesBecomeSafeFolderNames() {
        assertEquals("Ravi - Divya Wedding", EventSwitch.safeName("Ravi / Divya: Wedding?".replace("/", "-").replace(":", "")));
        assertEquals("a b c", EventSwitch.safeName("a<b>c."));
        assertEquals("Event", EventSwitch.safeName(" ... "));
    }

    @Test
    void restartKeepsOptionsButNotTheOldCode() {
        assertEquals(List.of("--ftp-port", "2122", "--data-dir", "/x", "--wait-for-lock"),
                EventSwitch.restartArgs(List.of("--connect", "MCK1.old", "--ftp-port", "2122", "--folder", "/f",
                        "--data-dir", "/x", "--wait-for-lock")));
        assertEquals(List.of("--wait-for-lock"), EventSwitch.restartArgs(List.of()));
    }

    @Test
    void eachEventGetsAFolderWithOneSubfolderPerCamera() {
        Path event = root.resolve("Meena 60th");
        assertEquals(event.resolve("Main - Karthik"),
                EventSwitch.folderFor(root, root, "Meena 60th", root, "Main - Karthik"), "first event, from the default folder");
        Path previous = root.resolve("Arun & Priya Wedding").resolve("Camera 1");
        assertEquals(event.resolve("Camera 1"), EventSwitch.folderFor(previous, previous, "Meena 60th", root, "Camera 1"),
                "from an event folder of the new layout");
        Path legacy = root.resolve("Arun & Priya Wedding");
        assertEquals(event.resolve("Camera 1"), EventSwitch.folderFor(legacy, legacy, "Meena 60th", root, "Camera 1"),
                "from an older event folder");
        Path own = Path.of("/data/Camera1");
        assertEquals(own, EventSwitch.folderFor(own, own, "Meena 60th", root, "Camera 1"), "a folder the user chose is kept");
    }
}
