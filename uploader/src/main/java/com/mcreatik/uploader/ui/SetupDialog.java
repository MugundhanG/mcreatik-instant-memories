package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.UploaderMain;
import com.mcreatik.uploader.config.ConnectionCode;
import com.mcreatik.uploader.api.ApiModels;
import com.mcreatik.uploader.config.DeviceId;
import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.config.UploaderConfig;

/**
 * First-run setup: paste the connection code, pick the folder photos are kept in, choose camera Wi-Fi or folder.
 * The same window, prefilled, switches an uploader that is already set up to another event.
 */
public final class SetupDialog {

    private SetupDialog() {
    }

    public static Optional<UploaderConfig> show(Path dataDir, int concurrency, boolean ftpByDefault) {
        return show(dataDir, concurrency, ftpByDefault, null);
    }

    /**
     * "Change event…": the same form, with the current folder and Wi-Fi choice filled in. The camera's FTP login and
     * port are kept, so nothing changes in the camera.
     */
    public static Optional<UploaderConfig> showSwitch(Path dataDir, int concurrency, UploaderConfig current) {
        return show(dataDir, concurrency, current.ftp().enabled(), current);
    }

    private static Optional<UploaderConfig> show(Path dataDir, int concurrency, boolean ftpByDefault, UploaderConfig current) {
        UiTheme.apply();
        boolean switching = current != null;
        AtomicReference<UploaderConfig> result = new AtomicReference<>();
        JDialog dialog = new JDialog((java.awt.Frame) null,
                UiTheme.APP_NAME + (switching ? " – Change event" : " – Setup"), true);
        dialog.setIconImages(UiTheme.windowIcons());
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JTextField code = new JTextField(36);
        code.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "MCK1.…");
        code.putClientProperty(FlatClientProperties.STYLE, "margin: 6,10,6,10");
        code.setFont(UiTheme.font(Font.PLAIN, 13f));
        JTextField folder = new JTextField(28);
        folder.putClientProperty(FlatClientProperties.STYLE, "margin: 6,10,6,10");
        JCheckBox ftp = new JCheckBox("Receive photos from the camera over Wi-Fi (built-in FTP server)", ftpByDefault);
        ftp.setFont(UiTheme.font(Font.BOLD, 13f));
        ftp.setOpaque(false);
        JLabel ftpHint = UiTheme.label("For cameras with FTP transfer, such as Canon EOS R6 Mark II or Sony α7 IV. "
                + "Untick if your camera software already saves into a folder.", Font.PLAIN, 12f, UiTheme.MUTED);
        if (switching) {
            folder.setText(current.watchFolder().toString());
        }
        Runnable defaultFolder = () -> {
            if (ftp.isSelected() && folder.getText().isBlank()) {
                folder.setText(EventSwitch.defaultRoot().toString());
            }
        };
        ftp.addActionListener(e -> defaultFolder.run());
        defaultFolder.run();

        JButton paste = UiTheme.secondary("Paste");
        paste.addActionListener(e -> {
            try {
                code.setText(String.valueOf(Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor)).trim());
            } catch (Exception ignored) {
                code.requestFocusInWindow();
            }
        });
        JButton browse = UiTheme.secondary("Choose…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(ftp.isSelected() ? "Folder to keep received photos in" : "Folder your camera sends photos to");
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JLabel error = UiTheme.label(" ", Font.PLAIN, 12.5f, UiTheme.RED);
        JButton cancel = UiTheme.secondary("Cancel");
        String connectText = switching ? "Switch event" : "Connect";
        JButton connect = UiTheme.primary(connectText);
        cancel.addActionListener(e -> dialog.dispose());
        connect.addActionListener(e -> {
            error.setText(" ");
            final ConnectionCode parsed;
            try {
                parsed = ConnectionCode.parse(code.getText());
                if (folder.getText().isBlank()) {
                    throw new IllegalArgumentException("Choose the folder your photos are kept in.");
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage());
                return;
            }
            Path chosen = Path.of(folder.getText().trim());
            UploaderConfig config = switching
                    // Same computer, same camera login: only the event (and maybe the folder) changes.
                    ? new UploaderConfig(parsed.server(), parsed.token(), chosen, concurrency, current.deviceId(),
                            current.ftp().withEnabled(ftp.isSelected()))
                    : new UploaderConfig(parsed.server(), parsed.token(), chosen, concurrency, DeviceId.generate(),
                            UploaderConfig.Ftp.generate(ftp.isSelected()));
            connect.setEnabled(false);
            connect.setText("Connecting…");
            new SwingWorker<UploaderConfig, Void>() {
                @Override
                protected UploaderConfig doInBackground() throws Exception {
                    ApiModels.Me me = UploaderMain.verify(config);
                    UploaderConfig saved = config;
                    if (switching && config.ftp().enabled() && me != null) {
                        // Each event in its own folder, unless the user picked a folder themselves.
                        Path own = EventSwitch.folderFor(current.watchFolder(), chosen, me.eventName(),
                                EventSwitch.defaultRoot());
                        if (!own.equals(chosen)) {
                            Files.createDirectories(own);
                            saved = config.withWatchFolder(own);
                        }
                    }
                    saved.save(dataDir);
                    return saved;
                }

                @Override
                protected void done() {
                    try {
                        result.set(get());
                        dialog.dispose();
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        error.setText(cause.getMessage() == null ? "Could not connect. Check the internet and try again."
                                : cause.getMessage());
                        connect.setEnabled(true);
                        connect.setText(connectText);
                    }
                }
            }.execute();
        });

        // Header: logo, title, where the code comes from.
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setOpaque(false);
        header.add(new JLabel(UiTheme.logo(48)), BorderLayout.WEST);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.add(UiTheme.label("INSTANT MEMORIES UPLOADER", Font.BOLD, 10.5f, UiTheme.GOLD));
        titles.add(Box.createVerticalStrut(2));
        titles.add(UiTheme.label(switching ? "Switch to another event" : "Connect this computer to an event",
                Font.BOLD, 20f, UiTheme.INK));
        titles.add(Box.createVerticalStrut(4));
        titles.add(UiTheme.label("<html>In the " + (switching ? "<b>new</b> " : "") + "event's dashboard, open"
                + " <b>Overview → Cameras &amp; uploaders → Add uploader</b> and copy the connection code.</html>",
                Font.PLAIN, 13f, UiTheme.MUTED));
        header.add(titles, BorderLayout.CENTER);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(field("1  ·  Connection code", row(code, paste)));
        form.add(Box.createVerticalStrut(14));
        if (switching) {
            JPanel folderField = new JPanel(new BorderLayout(0, 6));
            folderField.setOpaque(false);
            folderField.add(row(folder, browse), BorderLayout.NORTH);
            boolean ownFolders = current.ftp().enabled() && !EventSwitch.folderFor(current.watchFolder(),
                    current.watchFolder(), "x", EventSwitch.defaultRoot()).equals(current.watchFolder());
            folderField.add(UiTheme.label("<html>" + (ownFolders
                    ? "Left as it is, the new event's photos go into their own folder inside McreatiK Photos. " : "")
                    + "The camera's Wi-Fi settings stay the same.</html>", Font.PLAIN, 12f, UiTheme.MUTED),
                    BorderLayout.CENTER);
            form.add(field("2  ·  Folder for photos on this computer", folderField));
        } else {
            form.add(field("2  ·  Folder for photos on this computer", row(folder, browse)));
        }
        form.add(Box.createVerticalStrut(14));
        JPanel ftpCard = UiTheme.card(new BorderLayout(0, 4));
        ftpCard.setBackground(UiTheme.PAPER);
        ftpCard.setBorder(UiTheme.roundedBorder(UiTheme.LINE, new Insets(10, 12, 10, 12)));
        ftpCard.add(ftp, BorderLayout.NORTH);
        ftpHint.setBorder(BorderFactory.createEmptyBorder(0, 26, 0, 0));
        ftpCard.add(ftpHint, BorderLayout.CENTER);
        form.add(field("3  ·  How photos arrive", ftpCard));

        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.add(error, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(connect);
        actions.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(UiTheme.CARD);
        root.setBorder(BorderFactory.createEmptyBorder(24, 26, 20, 26));
        root.add(header, BorderLayout.NORTH);
        root.add(form, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(connect);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(640, dialog.getHeight()));
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true); // blocks until closed
        return Optional.ofNullable(result.get());
    }

    private static JPanel field(String label, JComponent input) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        p.setAlignmentX(0f);
        p.add(UiTheme.label(label, Font.BOLD, 12.5f, UiTheme.INK), BorderLayout.NORTH);
        p.add(input, BorderLayout.CENTER);
        return p;
    }

    private static JPanel row(JComponent main, JComponent side) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        p.add(main, BorderLayout.CENTER);
        p.add(side, BorderLayout.EAST);
        return p;
    }
}
