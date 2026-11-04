package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.mcreatik.uploader.UploaderMain;
import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.engine.UploadEngine;
import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.queue.QueueStatus;
import com.mcreatik.uploader.source.FtpPhotoSource;

/**
 * Status window. Designed to be glanced at from across a room: a big coloured connection banner, the camera's
 * Wi-Fi settings in large type, and big counters. Closing the window stops uploading (queued photos resume next
 * time the app starts).
 */
public final class UploaderWindow {

    private final UploadEngine engine;
    private final FtpPhotoSource ftp;
    private final JLabel eventLabel = UiTheme.label("Connecting…", Font.BOLD, 24f, UiTheme.INK);
    private final JLabel uploaderLabel = UiTheme.label(" ", Font.PLAIN, 13f, UiTheme.MUTED);
    private final JPanel banner = new JPanel(new BorderLayout(12, 0));
    private final JLabel bannerDot = new JLabel("●");
    private final JLabel connectionLabel = UiTheme.label(" ", Font.BOLD, 16f, UiTheme.INK);
    private final JLabel detailLabel = UiTheme.label(" ", Font.PLAIN, 13f, UiTheme.MUTED);
    private final JLabel[] counters = new JLabel[5];
    private final JPanel activePanel = new JPanel();
    private final RecentModel recentModel = new RecentModel();
    private final JButton galleryButton = UiTheme.primary("Open live gallery");
    private final JButton retryButton = UiTheme.secondary("Retry failed");
    private final JLabel[] ftpValues = new JLabel[4];
    private final JLabel ftpActivity = UiTheme.label(" ", Font.PLAIN, 13f, UiTheme.MUTED);
    private String galleryUrl;

    private UploaderWindow(UploadEngine engine, FtpPhotoSource ftp) {
        this.engine = engine;
        this.ftp = ftp;
    }

    /** @param ftp the built-in camera FTP server, or null when the camera writes into the folder itself */
    public static void open(UploadEngine engine, UploaderConfig config, FtpPhotoSource ftp) {
        SwingUtilities.invokeLater(() -> {
            UiTheme.apply(); // before any component exists, so every one gets the modern look
            new UploaderWindow(engine, ftp).build(config);
        });
    }

    private void build(UploaderConfig config) {
        UiTheme.apply();
        JFrame frame = new JFrame(UiTheme.APP_NAME);
        frame.setIconImages(UiTheme.windowIcons());
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(700, 620));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(UiTheme.PAPER);
        content.setBorder(BorderFactory.createEmptyBorder(18, 22, 12, 22));

        content.add(left(eventHeader()));
        content.add(Box.createVerticalStrut(14));
        content.add(left(statusBanner()));
        if (ftp != null) {
            content.add(Box.createVerticalStrut(12));
            content.add(left(cameraCard()));
        }
        content.add(Box.createVerticalStrut(12));
        content.add(left(counterRow()));
        activePanel.setOpaque(false);
        activePanel.setLayout(new BoxLayout(activePanel, BoxLayout.Y_AXIS));
        content.add(left(activePanel));
        content.add(Box.createVerticalStrut(12));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UiTheme.PAPER);
        root.add(topBar(frame, config), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(content, BorderLayout.NORTH);
        JPanel recent = recentCard();
        JPanel recentWrap = new JPanel(new BorderLayout());
        recentWrap.setOpaque(false);
        recentWrap.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 22));
        recentWrap.add(recent, BorderLayout.CENTER);
        body.add(recentWrap, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);
        root.add(footer(config), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setSize(860, ftp != null ? 820 : 720);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        new Timer(500, e -> refresh()).start();
        refresh();
    }

    // ---- layout pieces ----

    private JPanel topBar(JFrame frame, UploaderConfig config) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(UiTheme.CARD);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.LINE),
                BorderFactory.createEmptyBorder(10, 22, 10, 22)));
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        brand.add(new JLabel(UiTheme.logo(30)));
        JPanel names = new JPanel(new GridLayout(2, 1));
        names.setOpaque(false);
        names.add(UiTheme.label("McreatiK", Font.BOLD, 14f, UiTheme.INK));
        names.add(UiTheme.label("INSTANT MEMORIES UPLOADER", Font.PLAIN, 10f, UiTheme.GOLD));
        brand.add(names);
        bar.add(brand, BorderLayout.WEST);

        JButton change = UiTheme.secondary("Change event…");
        change.setToolTipText("Connect this computer to another event with a new connection code");
        change.addActionListener(e -> changeEvent(frame, config));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(change);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /** Only with an empty queue, so no photo from this event can end up in the next one. */
    private void changeEvent(JFrame frame, UploaderConfig config) {
        String blocked = EventSwitch.blockedReason(engine.queue().counts());
        if (blocked != null) {
            JOptionPane.showMessageDialog(frame, "<html><body style='width:300px'>" + blocked + "</body></html>",
                    "Change event", JOptionPane.WARNING_MESSAGE);
            return;
        }
        UploaderMain.changeEvent(config);
    }

    private JPanel eventHeader() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(UiTheme.label("LIVE EVENT", Font.BOLD, 10.5f, UiTheme.MUTED));
        header.add(Box.createVerticalStrut(2));
        header.add(eventLabel);
        header.add(uploaderLabel);
        return header;
    }

    private JPanel statusBanner() {
        banner.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 16");
        bannerDot.setFont(UiTheme.font(Font.BOLD, 22f));
        JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
        text.setOpaque(false);
        text.add(connectionLabel);
        text.add(detailLabel);
        banner.add(bannerDot, BorderLayout.WEST);
        banner.add(text, BorderLayout.CENTER);
        return banner;
    }

    private JPanel cameraCard() {
        JPanel card = UiTheme.card(new BorderLayout(0, 10));
        JPanel title = new JPanel(new BorderLayout());
        title.setOpaque(false);
        title.add(UiTheme.label("Camera Wi-Fi settings", Font.BOLD, 14f, UiTheme.INK), BorderLayout.NORTH);
        title.add(UiTheme.label("Enter these in the camera's FTP settings · FTP (not FTPS) · passive mode on or off",
                Font.PLAIN, 12f, UiTheme.MUTED), BorderLayout.SOUTH);
        card.add(title, BorderLayout.NORTH);

        String[] names = {"Server", "Port", "User", "Password"};
        JPanel grid = new JPanel(new GridLayout(1, 4, 10, 0));
        grid.setOpaque(false);
        for (int i = 0; i < names.length; i++) {
            JPanel cell = new JPanel(new BorderLayout(0, 4));
            cell.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 12");
            cell.setBackground(UiTheme.PAPER);
            cell.setBorder(UiTheme.roundedBorder(UiTheme.LINE, new Insets(8, 12, 8, 8)));
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(UiTheme.label(names[i].toUpperCase(), Font.BOLD, 10f, UiTheme.MUTED), BorderLayout.WEST);
            ftpValues[i] = UiTheme.label("…", Font.BOLD, 17f, UiTheme.INK);
            JLabel value = ftpValues[i];
            JButton copy = new JButton("Copy");
            UiTheme.style(copy, "buttonType: borderless; margin: 0,6,0,6; foreground: #8A5A1C");
            copy.setFont(UiTheme.font(Font.PLAIN, 11f));
            copy.setFocusable(false);
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value.getText()), null);
                copy.setText("Copied");
                Timer reset = new Timer(1500, ev -> copy.setText("Copy"));
                reset.setRepeats(false);
                reset.start();
            });
            top.add(copy, BorderLayout.EAST);
            cell.add(top, BorderLayout.NORTH);
            cell.add(value, BorderLayout.CENTER);
            grid.add(cell);
        }
        card.add(grid, BorderLayout.CENTER);
        card.add(ftpActivity, BorderLayout.SOUTH);
        return card;
    }

    private JPanel counterRow() {
        String[] names = {"Waiting", "Uploading", "Uploaded", "Already sent", "Failed"};
        JPanel row = new JPanel(new GridLayout(1, names.length, 10, 0));
        row.setOpaque(false);
        for (int i = 0; i < names.length; i++) {
            JPanel box = UiTheme.card(new BorderLayout(0, 2));
            box.setBorder(UiTheme.roundedBorder(UiTheme.LINE, new Insets(10, 14, 10, 14)));
            counters[i] = UiTheme.label("0", Font.BOLD, 26f, UiTheme.INK);
            box.add(counters[i], BorderLayout.CENTER);
            box.add(UiTheme.label(names[i], Font.PLAIN, 12f, UiTheme.MUTED), BorderLayout.SOUTH);
            row.add(box);
        }
        return row;
    }

    private JPanel recentCard() {
        JPanel card = UiTheme.card(new BorderLayout(0, 8));
        card.setBorder(UiTheme.roundedBorder(UiTheme.LINE, new Insets(12, 4, 4, 4)));
        JLabel title = UiTheme.label("Recent photos", Font.BOLD, 14f, UiTheme.INK);
        title.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        card.add(title, BorderLayout.NORTH);
        JTable table = new JTable(recentModel);
        table.setFillsViewportHeight(true);
        table.setFocusable(false);
        table.setRowSelectionAllowed(false);
        table.getTableHeader().setReorderingAllowed(false);
        ((DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer()).setHorizontalAlignment(SwingConstants.LEFT);
        table.getTableHeader().setFont(UiTheme.font(Font.BOLD, 11f));
        table.getTableHeader().setForeground(UiTheme.MUTED);
        table.getColumnModel().getColumn(0).setPreferredWidth(230);
        table.getColumnModel().getColumn(1).setPreferredWidth(130);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setCellRenderer(new StatusRenderer());
        DefaultTableCellRenderer muted = new DefaultTableCellRenderer();
        muted.setForeground(UiTheme.MUTED);
        table.getColumnModel().getColumn(2).setCellRenderer(muted);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(UiTheme.CARD);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private JPanel footer(UploaderConfig config) {
        JLabel folderLabel = UiTheme.label((ftp != null ? "Photos saved in  " : "Watching  ") + config.watchFolder(),
                Font.PLAIN, 12f, UiTheme.MUTED);
        folderLabel.setToolTipText(config.watchFolder().toString());
        JButton openFolder = UiTheme.secondary("Open folder");
        openFolder.addActionListener(e -> browse(config.watchFolder().toUri()));
        retryButton.addActionListener(e -> engine.retryFailed());
        galleryButton.setEnabled(false);
        galleryButton.addActionListener(e -> {
            if (galleryUrl != null) {
                browse(URI.create(galleryUrl));
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(openFolder);
        buttons.add(retryButton);
        buttons.add(galleryButton);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBackground(UiTheme.PAPER);
        footer.setBorder(BorderFactory.createEmptyBorder(12, 22, 14, 22));
        footer.add(folderLabel, BorderLayout.CENTER);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private static <T extends java.awt.Component> T left(T c) {
        if (c instanceof javax.swing.JComponent j) {
            j.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        return c;
    }

    // ---- live updates ----

    private void refresh() {
        EngineStatus s = engine.status();
        eventLabel.setText(s.eventName() == null ? "Connecting to McreatiK…" : s.eventName());
        uploaderLabel.setText(s.uploaderName() == null ? " " : "This computer: " + s.uploaderName());
        galleryUrl = s.galleryUrl();
        galleryButton.setEnabled(galleryUrl != null);

        switch (s.connection()) {
            case ONLINE -> setBanner(UiTheme.GREEN, UiTheme.GREEN_BG, "Online — photos upload automatically",
                    s.counts().pending() > 0 ? "Uploading the latest photos…"
                            : ftp != null ? "Waiting for new photos from the camera." : "Waiting for new photos in the folder.");
            case OFFLINE -> setBanner(UiTheme.AMBER, UiTheme.AMBER_BG, "Offline — photos are safely queued",
                    "Uploading resumes by itself when the internet is back.");
            case BLOCKED -> setBanner(UiTheme.RED, UiTheme.RED_BG, "Paused", s.blockedReason());
            case CONNECTING -> setBanner(UiTheme.MUTED, UiTheme.CARD, "Connecting…", " ");
        }
        var c = s.counts();
        int[] values = {c.queued(), c.uploading(), c.done(), c.duplicate(), c.failed()};
        for (int i = 0; i < values.length; i++) {
            counters[i].setText(String.format("%,d", values[i]));
        }
        counters[2].setForeground(c.done() > 0 ? UiTheme.GREEN : UiTheme.INK);
        counters[4].setForeground(c.failed() > 0 ? UiTheme.RED : UiTheme.INK);
        retryButton.setEnabled(c.failed() > 0);

        activePanel.removeAll();
        for (EngineStatus.ActiveUpload a : s.active()) {
            activePanel.add(Box.createVerticalStrut(8));
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setValue(a.percent());
            bar.setStringPainted(true);
            bar.setString(a.fileName() + "  ·  " + a.percent() + "%");
            bar.setFont(UiTheme.font(Font.PLAIN, 11f));
            bar.setAlignmentX(Component.LEFT_ALIGNMENT);
            bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
            activePanel.add(bar);
        }
        activePanel.revalidate();
        activePanel.repaint();
        recentModel.update(engine.queue().recent(200));
        if (ftp != null) {
            refreshFtp(ftp.status());
        }
    }

    private void setBanner(Color accent, Color background, String title, String detail) {
        banner.setBackground(background);
        banner.setBorder(UiTheme.roundedBorder(blend(accent, background), new Insets(12, 16, 12, 16)));
        bannerDot.setForeground(accent);
        connectionLabel.setText(title);
        connectionLabel.setForeground(accent == UiTheme.MUTED ? UiTheme.INK : accent);
        detailLabel.setText(detail == null || detail.isBlank() ? " " : detail);
    }

    private static Color blend(Color a, Color b) {
        return new Color((a.getRed() + 3 * b.getRed()) / 4, (a.getGreen() + 3 * b.getGreen()) / 4,
                (a.getBlue() + 3 * b.getBlue()) / 4);
    }

    private void refreshFtp(FtpPhotoSource.Status f) {
        String address = f.addresses().isEmpty() ? "No Wi-Fi" : String.join(" / ", f.addresses());
        String[] values = {address, Integer.toString(f.port()), f.username(), f.password()};
        for (int i = 0; i < values.length; i++) {
            ftpValues[i].setText(values[i]);
        }
        if (f.error() != null) {
            ftpActivity.setText("●  " + f.error());
            ftpActivity.setForeground(UiTheme.RED);
        } else if (f.lastFileAt() == null) {
            ftpActivity.setText("●  Waiting for the camera — it must be on the same Wi-Fi or hotspot as this computer");
            ftpActivity.setForeground(UiTheme.MUTED);
        } else {
            long ago = java.time.Duration.between(f.lastFileAt(), java.time.Instant.now()).toSeconds();
            ftpActivity.setText("●  Camera connected · " + String.format("%,d", f.filesReceived())
                    + " received · last photo " + (ago < 60 ? ago + "s" : ago / 60 + " min") + " ago");
            ftpActivity.setForeground(UiTheme.GREEN);
        }
    }

    private static void browse(URI uri) {
        try {
            Desktop.getDesktop().browse(uri);
        } catch (Exception ignored) {
            // no desktop browser available
        }
    }

    /** Status as a coloured word with a dot, like the dashboard's status chips. */
    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, false, false, row, column);
            String v = String.valueOf(value);
            Color color = switch (v) {
                case "Uploaded" -> UiTheme.GREEN;
                case "Uploading" -> UiTheme.BLUE;
                case "Retrying" -> UiTheme.AMBER;
                case "Failed" -> UiTheme.RED;
                default -> UiTheme.MUTED;
            };
            l.setText("●  " + v);
            l.setForeground(color);
            l.setFont(UiTheme.font(Font.BOLD, 12f));
            l.setHorizontalAlignment(SwingConstants.LEFT);
            return l;
        }
    }

    private static final class RecentModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Photo", "Status", "Details"};
        private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss");
        private List<QueueItem> items = List.of();

        void update(List<QueueItem> newItems) {
            if (!newItems.equals(items)) {
                items = newItems;
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            QueueItem item = items.get(row);
            return switch (column) {
                case 0 -> item.fileName();
                case 1 -> switch (item.status()) {
                    case QUEUED -> item.attempts() > 0 ? "Retrying" : "Waiting";
                    case UPLOADING -> "Uploading";
                    case DONE -> "Uploaded";
                    case DUPLICATE -> "Already sent";
                    case FAILED -> "Failed";
                };
                default -> item.lastError() != null && item.status() != QueueStatus.DONE
                        ? item.lastError() : time.format(new Date(item.updatedAt()));
            };
        }
    }
}
