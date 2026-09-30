package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.Optional;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import com.mcreatik.uploader.UploaderMain;
import com.mcreatik.uploader.config.ConnectionCode;
import com.mcreatik.uploader.config.DeviceId;
import com.mcreatik.uploader.config.UploaderConfig;

/** First-run setup: paste the connection code, pick the camera's transfer folder. */
public final class SetupDialog {

    private SetupDialog() {
    }

    public static Optional<UploaderConfig> show(Path dataDir, int concurrency) {
        UiTheme.apply();
        JTextField code = new JTextField(40);
        JTextField folder = new JTextField(30);
        JButton browse = new JButton("Choose…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Folder your camera sends photos to");
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        form.add(new JLabel("<html><b>Connect this computer to a McreatiK event</b><br>"
                + "Copy the connection code from the event dashboard (Uploaders → Add uploader).</html>"), c);
        c.gridwidth = 1;
        c.gridy = 1;
        form.add(new JLabel("Connection code"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(code, c);
        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Camera folder"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(folder, c);
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        form.add(browse, c);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(form, BorderLayout.CENTER);

        while (true) {
            int choice = JOptionPane.showConfirmDialog(null, wrapper, "McreatiK Uploader – Setup",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return Optional.empty();
            }
            try {
                ConnectionCode parsed = ConnectionCode.parse(code.getText());
                if (folder.getText().isBlank()) {
                    throw new IllegalArgumentException("Choose the folder your camera sends photos to.");
                }
                UploaderConfig config = new UploaderConfig(parsed.server(), parsed.token(),
                        Path.of(folder.getText().trim()), concurrency, DeviceId.generate());
                UploaderMain.verify(config);
                config.save(dataDir);
                return Optional.of(config);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, ex.getMessage(), "Cannot connect", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
