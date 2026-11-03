package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
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
import com.mcreatik.uploader.config.DeviceId;
import com.mcreatik.uploader.config.UploaderConfig;

/** First-run setup: paste the connection code, pick the folder photos are kept in, choose camera Wi-Fi or folder. */
public final class SetupDialog {

    private SetupDialog() {
    }

    public static Optional<UploaderConfig> show(Path dataDir, int concurrency, boolean ftpByDefault) {
        UiTheme.apply();
        AtomicReference<UploaderConfig> result = new AtomicReference<>();
        JDialog dialog = new JDialog((java.awt.Frame) null, UiTheme.APP_NAME + " – Setup", true);
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
        Runnable defaultFolder = () -> {
            if (ftp.isSelected() && folder.getText().isBlank()) {
                folder.setText(Path.of(System.getProperty("user.home"), "McreatiK Photos").toString());
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
        JButton connect = UiTheme.primary("Connect");
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
            UploaderConfig config = new UploaderConfig(parsed.server(), parsed.token(),
                    Path.of(folder.getText().trim()), concurrency, DeviceId.generate(),
                    UploaderConfig.Ftp.generate(ftp.isSelected()));
            connect.setEnabled(false);
            connect.setText("Connecting…");
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    UploaderMain.verify(config);
                    config.save(dataDir);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        result.set(config);
                        dialog.dispose();
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        error.setText(cause.getMessage() == null ? "Could not connect. Check the internet and try again."
                                : cause.getMessage());
                        connect.setEnabled(true);
                        connect.setText("Connect");
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
        titles.add(UiTheme.label("Connect this computer to an event", Font.BOLD, 20f, UiTheme.INK));
        titles.add(Box.createVerticalStrut(4));
        titles.add(UiTheme.label("<html>In the event dashboard, open <b>Overview → Cameras &amp; uploaders → Add uploader</b>"
                + " and copy the connection code.</html>", Font.PLAIN, 13f, UiTheme.MUTED));
        header.add(titles, BorderLayout.CENTER);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(field("1  ·  Connection code", row(code, paste)));
        form.add(Box.createVerticalStrut(14));
        form.add(field("2  ·  Folder for photos on this computer", row(folder, browse)));
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
