package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.api.ApiModels.StudioEvent;
import com.mcreatik.uploader.config.EventList;
import com.mcreatik.uploader.ui.LiveState.Tone;
import com.mcreatik.uploader.ui.components.EmptyState;
import com.mcreatik.uploader.ui.components.LinkButton;
import com.mcreatik.uploader.ui.components.Pill;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Icons;
import com.mcreatik.uploader.ui.theme.Tok;

/** After sign-in: who is signed in, a search box and one card per event. Picking a card selects that event. */
public final class EventPickerPanel extends JPanel {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);

    private final Consumer<StudioEvent> onSelect;
    private final Ui.TLabel who = Ui.label(" ", Font.PLAIN, 12.5f, "muted");
    private final JTextField search = new JTextField();
    private final JPanel list = Ui.clear(new GridBagLayout());
    private final EmptyState empty = new EmptyState("calendar-days", " ", " ");
    private final JPanel body = new JPanel(new CardLayout());
    private final Ui.Surface banner = new Ui.Surface(new BorderLayout(10, 0), "redSoft", "redLine", 10,
            new Insets(10, 12, 10, 12));
    private final Ui.Wrap bannerText = new Ui.Wrap("red", 12.5f);
    private List<StudioEvent> all = List.of();
    private StudioEvent selected;

    /** {@code onSelect} gets the chosen event (or null when the list changed and nothing is chosen). */
    public EventPickerPanel(Runnable onSignOut, Consumer<StudioEvent> onSelect) {
        super(new BorderLayout(0, 14));
        this.onSelect = onSelect;
        setOpaque(false);

        JPanel top = Ui.clear(new BorderLayout());
        top.add(Ui.label("Choose an event", Font.BOLD, 20f, "text"), BorderLayout.NORTH);
        JPanel account = Ui.clear(new BorderLayout(6, 0));
        account.add(new JLabel(Icons.get("mail", 15, "muted")), BorderLayout.WEST);
        account.add(who, BorderLayout.CENTER);
        LinkButton signOut = new LinkButton("Sign out", "blue");
        signOut.addActionListener(e -> onSignOut.run());
        account.add(signOut, BorderLayout.EAST);
        JPanel sub = Ui.clear(new BorderLayout(0, 10));
        sub.add(Ui.label("Photos from this computer go to the event you pick.", Font.PLAIN, 13f, "muted"),
                BorderLayout.NORTH);
        sub.add(account, BorderLayout.CENTER);
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search events");
        search.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        search.putClientProperty(FlatClientProperties.STYLE, "margin: 8,12,8,12");
        search.getAccessibleContext().setAccessibleName("Search events");
        sub.add(search, BorderLayout.SOUTH);
        top.add(sub, BorderLayout.CENTER);
        banner.add(new JLabel(Icons.get("circle-alert", 18, "red")), BorderLayout.WEST);
        banner.add(bannerText, BorderLayout.CENTER);
        banner.setVisible(false);
        top.add(banner, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        JPanel listHost = Ui.clear(new BorderLayout());
        listHost.add(list, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(listHost, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.getViewport().setBackground(Tok.c("card"));
        scroll.setBackground(Tok.c("card"));
        body.setOpaque(false);
        body.add(scroll, "list");
        body.add(empty, "empty");
        add(body, BorderLayout.CENTER);

        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                rebuild();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                rebuild();
            }
        });
    }

    public void setSignedIn(String email) {
        who.text("Signed in as " + email);
    }

    public void setEvents(List<StudioEvent> events) {
        all = events == null ? List.of() : events;
        if (selected != null && all.stream().noneMatch(e -> e.id().equals(selected.id()))) {
            select(null);
        }
        rebuild();
    }

    /** While the list is being fetched. */
    public void showLoading() {
        empty.set("loader-circle", "faint", "Loading your events…", "One moment");
        ((CardLayout) body.getLayout()).show(body, "empty");
    }

    public void showBanner(String message) {
        bannerText.text(message);
        banner.setVisible(true);
        revalidate();
    }

    public StudioEvent selected() {
        return selected;
    }

    public void select(StudioEvent event) {
        selected = event;
        onSelect.accept(event);
        rebuild();
    }

    private void rebuild() {
        List<StudioEvent> shown = EventList.visible(all, search.getText());
        list.removeAll();
        int row = 0;
        for (StudioEvent e : shown) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = row++;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(row == 1 ? 0 : 8, 0, 0, 0);
            list.add(new EventRow(e, selected != null && selected.id().equals(e.id())), g);
        }
        if (shown.isEmpty()) {
            if (EventList.visible(all, "").isEmpty()) {
                empty.set("calendar-days", "faint", "No events yet", "Create the event on the website first");
            } else {
                empty.set("calendar-days", "faint", "No event matches “" + search.getText().trim() + "”",
                        "Try another name");
            }
        }
        empty.revalidate();
        ((CardLayout) body.getLayout()).show(body, shown.isEmpty() ? "empty" : "list");
        list.revalidate();
        list.repaint();
    }

    static String dateText(String iso) {
        try {
            return iso == null ? "Date not set" : LocalDate.parse(iso).format(DATE);
        } catch (DateTimeParseException e) {
            return "Date not set";
        }
    }

    static Tone toneOf(String status) {
        return switch (status == null ? "" : status) {
            case "LIVE" -> Tone.GREEN;
            case "UPCOMING" -> Tone.BLUE;
            case "DRAFT" -> Tone.AMBER;
            default -> Tone.GREY;
        };
    }

    /** One event: name, date, status chip. A click selects it. */
    private final class EventRow extends Ui.Surface {
        EventRow(StudioEvent event, boolean chosen) {
            super(new BorderLayout(12, 0), chosen ? "greenSoft" : "card", chosen ? "green" : "border", 12,
                    new Insets(12, 14, 12, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            JLabel mark = new JLabel(Icons.get(chosen ? "circle-check" : "calendar-days", 20, chosen ? "green" : "faint"));
            add(mark, BorderLayout.WEST);
            JPanel text = Ui.clear(new java.awt.GridLayout(0, 1, 0, 2));
            text.add(Ui.label(event.name(), Font.BOLD, 14f, "text"));
            String second = dateText(event.eventDate())
                    + ("DRAFT".equals(event.status()) ? "   ·   Gallery is not live yet" : "");
            text.add(Ui.label(second, Font.PLAIN, 12.5f, "DRAFT".equals(event.status()) ? "amber" : "muted"));
            add(text, BorderLayout.CENTER);
            JPanel pill = Ui.clear(new GridBagLayout());
            pill.add(new Pill().set(toneOf(event.status()), EventList.statusLabel(event.status())));
            add(pill, BorderLayout.EAST);
            getAccessibleContext().setAccessibleName(event.name());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    select(event);
                }
            });
        }
    }

    /** For screenshots and tests: the rows' container. */
    JComponent listView() {
        return list;
    }
}
