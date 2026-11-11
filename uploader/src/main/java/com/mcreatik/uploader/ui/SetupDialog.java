package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
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
import com.mcreatik.uploader.api.ApiModels;
import com.mcreatik.uploader.config.ConnectionCode;
import com.mcreatik.uploader.config.DeviceId;
import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Tok;

/**
 * First-run setup: paste the connection code from the event's dashboard, pick the folder photos are kept in and
 * choose camera Wi-Fi or folder. The same window switches an uploader that is already set up to another event
 * ("Change event…") with a new connection code.
 */
public final class SetupDialog {

    private SetupDialog() {
    }

    public static Optional<UploaderConfig> show(Path dataDir, int concurrency, boolean ftpByDefault) {
        return show(dataDir, concurrency, ftpByDefault, null);
    }

    /**
     * "Change event…": the same flow, with the current folder and Wi-Fi choice filled in. The camera's FTP login and
     * port are kept, so nothing changes in the camera.
     */
    public static Optional<UploaderConfig> showSwitch(Path dataDir, int concurrency, UploaderConfig current) {
        return show(dataDir, concurrency, current.ftp().enabled(), current);
    }

    /** Centres a card on the window background (also used for the screenshots). */
    public static JPanel backdrop(JComponent content) {
        JPanel root = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                g.setColor(Tok.c("bg"));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        root.add(content);
        return root;
    }

    private static Optional<UploaderConfig> show(Path dataDir, int concurrency, boolean ftpByDefault, UploaderConfig current) {
        UiTheme.apply();
        boolean switching = current != null;
        AtomicReference<UploaderConfig> result = new AtomicReference<>();
        String deviceId = switching ? current.deviceId() : DeviceId.generate();

        JDialog dialog = new JDialog((java.awt.Frame) null,
                UiTheme.APP_NAME + (switching ? " – Change event" : " – Setup"), true);
        dialog.setIconImages(UiTheme.windowIcons());
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        // ---- step 3: where photos are kept (and the connection code, in code mode) ----
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

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(field("Connection code", row(code, paste)));
        form.add(Box.createVerticalStrut(4));
        form.add(left(UiTheme.label("<html>In the " + (switching ? "<b>new</b> " : "") + "event's dashboard, open:"
                + " <b>Overview → Cameras &amp; uploaders → Add camera &amp; get code</b> (or <b>Get new code</b>) and copy the connection code.</html>",
                Font.PLAIN, 11.5f, UiTheme.MUTED)));
        form.add(Box.createVerticalStrut(16));
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
            form.add(field("Folder for photos on this computer", folderField));
        } else {
            form.add(field("Folder for photos on this computer", row(folder, browse)));
        }
        form.add(Box.createVerticalStrut(16));
        JPanel ftpCard = new Ui.Surface(new BorderLayout(0, 4), "inset", "border", 12, new Insets(10, 12, 10, 12));
        ftpCard.add(ftp, BorderLayout.NORTH);
        ftpHint.setBorder(BorderFactory.createEmptyBorder(0, 26, 0, 0));
        ftpCard.add(ftpHint, BorderLayout.CENTER);
        form.add(field("How photos arrive", ftpCard));

        JPanel detailsActions = new JPanel(new BorderLayout());
        detailsActions.setOpaque(false);
        detailsActions.add(error, BorderLayout.CENTER);
        JPanel detailsButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        detailsButtons.setOpaque(false);
        detailsButtons.add(cancel);
        detailsButtons.add(connect);
        detailsActions.add(detailsButtons, BorderLayout.EAST);
        Ui.Surface details = new Ui.Surface(new BorderLayout(0, 18), "card", "border", 18, new Insets(24, 28, 20, 28));
        details.shadow(true);
        JPanel detailsHead = Ui.clear(new BorderLayout(14, 0));
        detailsHead.add(new JLabel(UiTheme.logo(40)), BorderLayout.WEST);
        JPanel detailsTitles = Ui.clear(new BorderLayout(0, 2));
        detailsTitles.add(Ui.label(switching ? "Connect to another event" : "Connect with a connection code",
                Font.BOLD, 18f, "text"), BorderLayout.NORTH);
        detailsTitles.add(Ui.label("Choose where this computer keeps the photos.", Font.PLAIN, 13f, "muted"),
                BorderLayout.CENTER);
        detailsHead.add(detailsTitles, BorderLayout.CENTER);
        details.add(detailsHead, BorderLayout.NORTH);
        details.add(form, BorderLayout.CENTER);
        details.add(detailsActions, BorderLayout.SOUTH);

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
            connect.setEnabled(false);
            cancel.setEnabled(false);
            connect.setText("Connecting…");
            new SwingWorker<UploaderConfig, Void>() {
                @Override
                protected UploaderConfig doInBackground() throws Exception {
                    UploaderConfig.Ftp ftpSettings = switching ? current.ftp().withEnabled(ftp.isSelected())
                            : UploaderConfig.Ftp.generate(ftp.isSelected());
                    UploaderConfig config = new UploaderConfig(parsed.server(), parsed.token(), chosen, concurrency,
                            deviceId, ftpSettings);
                    ApiModels.Me me = UploaderMain.verify(config);
                    UploaderConfig saved = config;
                    if (config.ftp().enabled() && me != null) {
                        // McreatiK Photos/<event>/<camera>, unless the user picked a folder themselves.
                        Path own = EventSwitch.folderFor(switching ? current.watchFolder() : chosen, chosen,
                                me.eventName(), EventSwitch.defaultRoot(), me.uploaderName());
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
                        error.setText(cause.getMessage() == null
                                ? "Could not connect. Check the internet and try again." : cause.getMessage());
                        connect.setEnabled(true);
                        cancel.setEnabled(true);
                        connect.setText(connectText);
                    }
                }
            }.execute();
        });

        dialog.setContentPane(backdrop(details));
        dialog.pack();
        dialog.setMinimumSize(new Dimension(Math.max(dialog.getWidth(), 640), Math.max(dialog.getHeight(), 600)));
        dialog.setSize(dialog.getMinimumSize());
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true); // blocks until closed
        return Optional.ofNullable(result.get());
    }

    private static JComponent left(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        p.setAlignmentX(0f);
        p.add(c);
        return p;
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
