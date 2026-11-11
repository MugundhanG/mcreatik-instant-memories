package com.mcreatik.uploader.ui.pages;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.JComponent;
import javax.swing.JPanel;

import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.ui.AppContext;
import com.mcreatik.uploader.ui.Page;
import com.mcreatik.uploader.ui.Snapshot;
import com.mcreatik.uploader.ui.components.CredentialField;
import com.mcreatik.uploader.ui.components.PageLayout;
import com.mcreatik.uploader.ui.components.Ui;

/** The event this computer uploads to, and how to switch to the next one (Change event…). */
public final class EventsPage implements Page {
    public static final String ID = "events";

    private final PageLayout page = new PageLayout("Events", "This computer uploads to one event at a time.");
    private final EventCard current;
    private final CredentialField link = new CredentialField("Live gallery link", false);
    private final Ui.TLabel guard = Ui.label(" ", Font.PLAIN, 12.5f, "amber");
    private final Ui.Btn change = new Ui.Btn("Change event…", Ui.Kind.PRIMARY, "calendar-days");

    public EventsPage(AppContext ctx) {
        current = new EventCard(ctx);
        page.section(current);
        Ui.Surface linkCard = Ui.Surface.card(new BorderLayout(0, 10));
        linkCard.add(Ui.label("Share with guests", Font.BOLD, 14f, "text"), BorderLayout.NORTH);
        linkCard.add(link, BorderLayout.CENTER);
        Ui.Btn open = new Ui.Btn("Open live gallery", Ui.Kind.SECONDARY, "external-link");
        open.addActionListener(e -> ctx.actions().openGallery());
        JPanel openRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        openRow.add(open);
        linkCard.add(openRow, BorderLayout.SOUTH);
        page.section(linkCard);

        Ui.Surface sw = Ui.Surface.card(new BorderLayout(0, 12));
        JPanel head = Ui.clear(new GridLayout(2, 1, 0, 2));
        head.add(Ui.label("Switch to another event", Font.BOLD, 14f, "text"));
        head.add(Ui.label("The camera's Wi-Fi settings stay the same, and old photos are never sent to the new event.",
                Font.PLAIN, 12.5f, "muted"));
        sw.add(head, BorderLayout.NORTH);
        JPanel steps = Ui.clear(new GridLayout(3, 1, 0, 6));
        steps.add(Ui.label("1   Wait until Waiting and Uploading are both 0.", Font.PLAIN, 13f, "text"));
        steps.add(Ui.label("2   Press Change event… and sign in with your studio email and password (asked again after 12 hours).",
                Font.PLAIN, 13f, "text"));
        steps.add(Ui.label("3   Pick the new event, then Switch event. The uploader restarts by itself. (A connection code still works.)",
                Font.PLAIN, 13f, "text"));
        sw.add(steps, BorderLayout.CENTER);
        JPanel action = Ui.clear(new BorderLayout(12, 0));
        JPanel btnRow = Ui.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnRow.add(change);
        action.add(btnRow, BorderLayout.WEST);
        action.add(guard, BorderLayout.CENTER);
        sw.add(action, BorderLayout.SOUTH);
        change.addActionListener(e -> ctx.actions().changeEvent());
        page.section(sw);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public JComponent view() {
        return page;
    }

    @Override
    public void refresh(Snapshot s) {
        current.refresh(s);
        link.set(s.engine().galleryUrl() == null ? "Available once connected" : s.engine().galleryUrl());
        String blocked = EventSwitch.blockedReason(s.engine().counts());
        guard.text(blocked == null ? " " : blocked);
    }
}
