package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
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
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;

import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.engine.EngineStatus;
import com.mcreatik.uploader.engine.UploadEngine;
import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.source.FtpPhotoSource;

/**
 * Status window. Designed to be glanced at from across a room: big connection state, big counters.
 * Closing the window stops uploading (queued photos resume next time the app starts).
 */
public final class UploaderWindow {

    private final UploadEngine engine;
    private final JLabel eventLabel = new JLabel("Connecting…");
    private final JLabel uploaderLabel = new JLabel(" ");
    private final JLabel connectionLabel = new JLabel(" ");
    private final JLabel detailLabel = new JLabel(" ");
    private final JLabel[] counters = new JLabel[5];
    private final JPanel activePanel = new JPanel();
    private final RecentModel recentModel = new RecentModel();
    private final JButton galleryButton = new JButton("Open live gallery");
    private final FtpPhotoSource ftp;
    private final JLabel ftpSettings = new JLabel(" ");
    private final JLabel ftpActivity = new JLabel(" ");
    private String galleryUrl;

    private UploaderWindow(UploadEngine engine, FtpPhotoSource ftp) {
        this.engine = engine;
        this.ftp = ftp;
    }

    /** @param ftp the built-in camera FTP server, or null when the camera writes into the folder itself */
    public static void open(UploadEngine engine, UploaderConfig config, FtpPhotoSource ftp) {
        SwingUtilities.invokeLater(() -> new UploaderWindow(engine, ftp).build(config));
    }

    private void build(UploaderConfig config) {
        UiTheme.apply();
        JFrame frame = new JFrame("McreatiK Uploader");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(620, 520));

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        root.setBackground(UiTheme.PAPER);

        // Header
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        eventLabel.setFont(UiTheme.font(Font.BOLD, 20f));
        eventLabel.setForeground(UiTheme.INK);
        uploaderLabel.setForeground(UiTheme.MUTED);
        connectionLabel.setFont(UiTheme.font(Font.BOLD, 15f));
        detailLabel.setForeground(UiTheme.MUTED);
        header.add(eventLabel);
        header.add(uploaderLabel);
        header.add(Box.createVerticalStrut(10));
        header.add(connectionLabel);
        header.add(detailLabel);
        if (ftp != null) {
            JPanel ftpBox = new JPanel();
            ftpBox.setLayout(new BoxLayout(ftpBox, BoxLayout.Y_AXIS));
            ftpBox.setBackground(java.awt.Color.WHITE);
            ftpBox.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UiTheme.LINE),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            ftpBox.setAlignmentX(0f);
            JLabel title = new JLabel("Camera Wi-Fi (FTP) — enter these in the camera's FTP settings");
            title.setFont(UiTheme.font(Font.BOLD, 12f));
            ftpActivity.setForeground(UiTheme.MUTED);
            ftpBox.add(title);
            ftpBox.add(Box.createVerticalStrut(4));
            ftpBox.add(ftpSettings);
            ftpBox.add(ftpActivity);
            header.add(Box.createVerticalStrut(10));
            header.add(ftpBox);
        }

        // Counters
        String[] names = {"Waiting", "Uploading", "Uploaded", "Duplicates", "Failed"};
        JPanel counterPanel = new JPanel(new GridLayout(1, names.length, 8, 0));
        counterPanel.setOpaque(false);
        for (int i = 0; i < names.length; i++) {
            JPanel box = new JPanel(new BorderLayout());
            box.setBackground(java.awt.Color.WHITE);
            box.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UiTheme.LINE),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            counters[i] = new JLabel("0");
            counters[i].setFont(UiTheme.font(Font.BOLD, 22f));
            JLabel name = new JLabel(names[i]);
            name.setForeground(UiTheme.MUTED);
            box.add(counters[i], BorderLayout.CENTER);
            box.add(name, BorderLayout.SOUTH);
            counterPanel.add(box);
        }

        activePanel.setOpaque(false);
        activePanel.setLayout(new BoxLayout(activePanel, BoxLayout.Y_AXIS));

        JTable table = new JTable(recentModel);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(280);

        JPanel middle = new JPanel(new BorderLayout(0, 10));
        middle.setOpaque(false);
        middle.add(counterPanel, BorderLayout.NORTH);
        middle.add(activePanel, BorderLayout.CENTER);
        JPanel centre = new JPanel(new BorderLayout(0, 10));
        centre.setOpaque(false);
        centre.add(middle, BorderLayout.NORTH);
        centre.add(new JScrollPane(table), BorderLayout.CENTER);

        // Footer
        JLabel folderLabel = new JLabel((ftp != null ? "Photos saved in: " : "Watching: ") + config.watchFolder());
        folderLabel.setForeground(UiTheme.MUTED);
        JButton retry = new JButton("Retry failed");
        retry.addActionListener(e -> engine.retryFailed());
        JButton openFolder = new JButton("Open folder");
        openFolder.addActionListener(e -> browse(config.watchFolder().toUri()));
        galleryButton.setEnabled(false);
        galleryButton.addActionListener(e -> {
            if (galleryUrl != null) {
                browse(URI.create(galleryUrl));
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(openFolder);
        buttons.add(retry);
        buttons.add(galleryButton);
        folderLabel.setToolTipText(config.watchFolder().toString());
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(folderLabel, BorderLayout.NORTH); // own line: long paths never collide with the buttons
        footer.add(buttons, BorderLayout.SOUTH);

        root.add(header, BorderLayout.NORTH);
        root.add(centre, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.pack();
        frame.setSize(760, 600);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        new Timer(500, e -> refresh()).start();
        refresh();
    }

    private void refresh() {
        EngineStatus s = engine.status();
        eventLabel.setText(s.eventName() == null ? "Connecting to McreatiK…" : s.eventName());
        uploaderLabel.setText(s.uploaderName() == null ? " " : "This computer: " + s.uploaderName());
        galleryUrl = s.galleryUrl();
        galleryButton.setEnabled(galleryUrl != null);

        switch (s.connection()) {
            case ONLINE -> {
                connectionLabel.setText("● Online — photos upload automatically");
                connectionLabel.setForeground(UiTheme.GREEN);
                detailLabel.setText(s.counts().pending() > 0 ? " "
                        : ftp != null ? "Waiting for new photos from the camera." : "Waiting for new photos in the folder.");
            }
            case OFFLINE -> {
                connectionLabel.setText("● Offline — photos are safely queued");
                connectionLabel.setForeground(UiTheme.AMBER);
                detailLabel.setText("Uploading resumes automatically when the internet is back.");
            }
            case BLOCKED -> {
                connectionLabel.setText("● Paused");
                connectionLabel.setForeground(UiTheme.RED);
                detailLabel.setText(s.blockedReason());
            }
            case CONNECTING -> {
                connectionLabel.setText("● Connecting…");
                connectionLabel.setForeground(UiTheme.MUTED);
                detailLabel.setText(" ");
            }
        }
        var c = s.counts();
        int[] values = {c.queued(), c.uploading(), c.done(), c.duplicate(), c.failed()};
        for (int i = 0; i < values.length; i++) {
            counters[i].setText(Integer.toString(values[i]));
        }
        counters[4].setForeground(c.failed() > 0 ? UiTheme.RED : UiTheme.INK);

        activePanel.removeAll();
        for (EngineStatus.ActiveUpload a : s.active()) {
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setValue(a.percent());
            bar.setStringPainted(true);
            bar.setString(a.fileName() + "  " + a.percent() + "%");
            activePanel.add(bar);
            activePanel.add(Box.createVerticalStrut(4));
        }
        activePanel.revalidate();
        activePanel.repaint();
        recentModel.update(engine.queue().recent(200));
        if (ftp != null) {
            refreshFtp(ftp.status());
        }
    }

    private void refreshFtp(FtpPhotoSource.Status f) {
        String address = f.addresses().isEmpty() ? "no Wi-Fi network found" : String.join(" or ", f.addresses());
        ftpSettings.setText("<html>Server <b>" + address + "</b> &nbsp; Port <b>" + f.port() + "</b> &nbsp; User <b>"
                + f.username() + "</b> &nbsp; Password <b>" + f.password() + "</b> &nbsp; Passive mode: either</html>");
        if (f.error() != null) {
            ftpActivity.setText(f.error());
            ftpActivity.setForeground(UiTheme.RED);
        } else if (f.lastFileAt() == null) {
            ftpActivity.setText("Waiting for the camera… (camera and this computer must be on the same Wi-Fi / hotspot)");
            ftpActivity.setForeground(UiTheme.MUTED);
        } else {
            long ago = java.time.Duration.between(f.lastFileAt(), java.time.Instant.now()).toSeconds();
            ftpActivity.setText("Camera connected" + (f.lastClient() == null ? "" : " (" + f.lastClient() + ")")
                    + " · " + f.filesReceived() + " received · last photo " + ago + "s ago");
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
                    case DUPLICATE -> "Already uploaded";
                    case FAILED -> "Failed";
                };
                default -> item.lastError() != null && item.status() != com.mcreatik.uploader.queue.QueueStatus.DONE
                        ? item.lastError() : time.format(new Date(item.updatedAt()));
            };
        }
    }
}
