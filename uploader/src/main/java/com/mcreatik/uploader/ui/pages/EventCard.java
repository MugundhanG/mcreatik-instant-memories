package com.mcreatik.uploader.ui.pages;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javax.swing.JPanel;

import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.ui.AppContext;
import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.PhotoInfo;
import com.mcreatik.uploader.ui.Snapshot;
import com.mcreatik.uploader.ui.components.Pill;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Icons;

/** The event name, large, with the camera, its model, the date and how long this laptop has been connected. */
final class EventCard extends Ui.Surface {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final Ui.TLabel name = Ui.label("Connecting to McreatiK…", Font.BOLD, 26f, "text");
    private final Pill eventStatus = new Pill();
    private final Ui.TLabel camera = meta("camera");
    private final Ui.TLabel model = meta("aperture");
    private final Ui.TLabel date = meta("calendar");
    private final Ui.TLabel connected = meta("clock");
    private String modelFor;
    private String knownModel;

    EventCard(AppContext ctx) {
        super(new BorderLayout(16, 0), "card", "border", Ui.ARC, new java.awt.Insets(16, 20, 16, 18));
        shadow(true);
        JPanel left = Ui.clear(new BorderLayout(0, 6));
        JPanel top = Ui.row(10);
        top.add(Ui.caps("Current event"));
        top.add(eventStatus);
        left.add(top, BorderLayout.NORTH);
        left.add(name, BorderLayout.CENTER);
        JPanel metaRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (Ui.TLabel l : new Ui.TLabel[] {camera, model, date, connected}) {
            metaRow.add(l);
            metaRow.add(javax.swing.Box.createHorizontalStrut(22));
        }
        left.add(metaRow, BorderLayout.SOUTH);
        add(left, BorderLayout.CENTER);
        Ui.Btn change = new Ui.Btn("Change event…", Ui.Kind.SECONDARY, "calendar-days");
        change.setToolTipText("Connect this computer to another event with a new connection code");
        change.addActionListener(e -> ctx.actions().changeEvent());
        JPanel right = Ui.clear(new java.awt.GridBagLayout());
        right.add(change);
        add(right, BorderLayout.EAST);
        model.text("Model: read from the first photo");
    }

    private static Ui.TLabel meta(String icon) {
        Ui.TLabel l = Ui.label(" ", Font.PLAIN, 13f, "muted");
        l.setIcon(Icons.get(icon, 15, "muted"));
        l.setIconTextGap(7);
        return l;
    }

    void refresh(Snapshot s) {
        EngineStatus e = s.engine();
        name.text(e.eventName() == null ? "Connecting to McreatiK…" : e.eventName());
        name.setToolTipText(e.eventName());
        eventStatus.set(statusTone(e.eventStatus()), statusText(e.eventStatus()));
        eventStatus.setVisible(e.eventStatus() != null);
        camera.text(e.uploaderName() == null ? "Camera: …" : e.uploaderName());
        date.text(dateText(e.eventDate(), e.eventStartTime(), ZoneId.systemDefault()));
        connected.text(!s.live().duration().equals("—") ? s.live().duration()
                : s.live().conn() == com.mcreatik.uploader.ui.LiveState.Conn.ERROR ? "Connection paused" : "Connecting…");
        if (!s.recent().isEmpty()) {
            String path = s.recent().get(0).path().toString();
            if (!path.equals(modelFor)) {
                modelFor = path;
                PhotoInfo.firstCameraModel(s.recent().stream().map(com.mcreatik.uploader.queue.QueueItem::path).toList(), m -> {
                    if (m != null) {
                        knownModel = m; // a later photo without camera data keeps the model already known
                    }
                    model.text(knownModel != null ? knownModel : "Model not in photo data");
                });
            }
        }
    }

    static String dateText(LocalDate day, Instant start, ZoneId zone) {
        if (start != null) {
            return DATE.format(start.atZone(zone)) + " · " + TIME.format(start.atZone(zone));
        }
        if (day != null) {
            return DATE.format(day);
        }
        return "Date not set";
    }

    static Tone statusTone(String status) {
        if (status == null) {
            return Tone.GREY;
        }
        return switch (status) {
            case "LIVE" -> Tone.GREEN;
            case "UPCOMING" -> Tone.BLUE;
            case "COMPLETED" -> Tone.GREY;
            default -> Tone.AMBER; // DRAFT, ARCHIVED: guests can't open the gallery
        };
    }

    static String statusText(String status) {
        if (status == null) {
            return " ";
        }
        return switch (status) {
            case "LIVE" -> "Live";
            case "UPCOMING" -> "Upcoming";
            case "COMPLETED" -> "Completed";
            case "DRAFT" -> "Draft · guests can't open it";
            case "ARCHIVED" -> "Archived · guests can't open it";
            default -> status.charAt(0) + status.substring(1).toLowerCase(Locale.ROOT);
        };
    }
}
