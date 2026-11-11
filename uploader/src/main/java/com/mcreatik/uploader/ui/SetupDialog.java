package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
import com.mcreatik.uploader.api.ApiException;
import com.mcreatik.uploader.api.ApiModels;
import com.mcreatik.uploader.api.StudioClient;
import com.mcreatik.uploader.config.ConnectionCode;
import com.mcreatik.uploader.config.DeviceId;
import com.mcreatik.uploader.config.EventList;
import com.mcreatik.uploader.config.EventSwitch;
import com.mcreatik.uploader.config.UploaderConfig;
import com.mcreatik.uploader.ui.components.LinkButton;
import com.mcreatik.uploader.ui.components.Pill;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Tok;

/**
 * First-run setup: sign in with the studio login, pick the event, pick the folder photos are kept in and choose camera
 * Wi-Fi or folder. "Use a connection code instead" is the older way in. The same window switches an uploader that is
 * already set up to another event: straight to the event list while the sign-in is still valid, to sign-in when it has
 * expired, and to the connection code when this uploader was set up with a code.
 */
public final class SetupDialog {

    /** The website's login page, where "Forgot password?" is handled. */
    static final String FORGOT_PASSWORD_URL = "https://live.mcreatik.com/admin";
    private static final String SIGN_IN = "signin";
    private static final String EVENTS = "events";
    private static final String DETAILS = "details";

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
        AtomicReference<UploaderConfig.Session> session = new AtomicReference<>(switching ? current.session() : null);
        AtomicReference<ApiModels.StudioEvent> chosenEvent = new AtomicReference<>();
        AtomicReference<Boolean> codeMode = new AtomicReference<>(false);
        String server = StudioClient.resolveServer(switching ? current.serverUrl() : null);
        StudioClient studio = new StudioClient(server);
        String deviceId = switching ? current.deviceId() : DeviceId.generate();

        JDialog dialog = new JDialog((java.awt.Frame) null,
                UiTheme.APP_NAME + (switching ? " – Change event" : " – Setup"), true);
        dialog.setIconImages(UiTheme.windowIcons());
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        CardLayout steps = new CardLayout();
        JPanel stack = new JPanel(steps);
        stack.setOpaque(false);

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
        JButton back = UiTheme.secondary("Back");
        String connectText = switching ? "Switch event" : "Connect";
        JButton connect = UiTheme.primary(connectText);

        Ui.TLabel eventName = Ui.label(" ", Font.BOLD, 16f, "text");
        Pill eventStatus = new Pill();
        LinkButton changeEvent = new LinkButton("Change", "blue");
        JPanel eventRow = Ui.clear(new BorderLayout(10, 0));
        JPanel eventLeft = Ui.clear(new FlowLayout(FlowLayout.LEFT, 10, 0));
        eventLeft.add(eventName);
        eventLeft.add(eventStatus);
        eventRow.add(eventLeft, BorderLayout.CENTER);
        eventRow.add(changeEvent, BorderLayout.EAST);
        JPanel eventField = field("Event", eventRow);

        JPanel codeBlock = new JPanel();
        codeBlock.setOpaque(false);
        codeBlock.setLayout(new BoxLayout(codeBlock, BoxLayout.Y_AXIS));
        JPanel codeField = field("Connection code", row(code, paste));
        codeBlock.add(codeField);
        codeBlock.add(Box.createVerticalStrut(4));
        codeBlock.add(left(UiTheme.label("<html>In the " + (switching ? "<b>new</b> " : "") + "event's dashboard, open"
                + " <b>Overview → Cameras &amp; uploaders → Add uploader</b> and copy the connection code.</html>",
                Font.PLAIN, 12f, UiTheme.MUTED)));
        LinkButton useSignIn = new LinkButton("Sign in with your studio login instead", "blue");
        codeBlock.add(left(useSignIn));

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(eventField);
        form.add(codeBlock);
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
        detailsButtons.add(back);
        detailsButtons.add(connect);
        detailsActions.add(detailsButtons, BorderLayout.EAST);
        Ui.Surface details = new Ui.Surface(new BorderLayout(0, 18), "card", "border", 18, new Insets(24, 28, 20, 28));
        details.shadow(true);
        JPanel detailsHead = Ui.clear(new BorderLayout(14, 0));
        detailsHead.add(new JLabel(UiTheme.logo(40)), BorderLayout.WEST);
        JPanel detailsTitles = Ui.clear(new BorderLayout(0, 2));
        Ui.TLabel detailsTitle = Ui.label(" ", Font.BOLD, 18f, "text");
        detailsTitles.add(detailsTitle, BorderLayout.NORTH);
        detailsTitles.add(Ui.label("Choose where this computer keeps the photos.", Font.PLAIN, 13f, "muted"),
                BorderLayout.CENTER);
        detailsHead.add(detailsTitles, BorderLayout.CENTER);
        details.add(detailsHead, BorderLayout.NORTH);
        details.add(form, BorderLayout.CENTER);
        details.add(detailsActions, BorderLayout.SOUTH);

        // ---- step 2: the event list ----
        JButton pickCancel = UiTheme.secondary("Cancel");
        JButton pickNext = UiTheme.primary("Continue");
        pickNext.setEnabled(false);
        SignInPanel[] signInRef = new SignInPanel[1];
        EventPickerPanel picker = new EventPickerPanel(() -> {
            session.set(null);
            chosenEvent.set(null);
            pickNext.setEnabled(false);
            signInRef[0].clearBanner();
            steps.show(stack, SIGN_IN);
        }, ev -> {
            chosenEvent.set(ev);
            pickNext.setEnabled(ev != null); // an event must be picked before Continue
        });
        Ui.Surface pickerCard = new Ui.Surface(new BorderLayout(0, 14), "card", "border", 18, new Insets(24, 28, 20, 28));
        pickerCard.shadow(true);
        pickerCard.add(picker, BorderLayout.CENTER);
        JPanel pickButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        pickButtons.setOpaque(false);
        pickButtons.add(pickCancel);
        pickButtons.add(pickNext);
        pickerCard.add(pickButtons, BorderLayout.SOUTH);
        picker.setPreferredSize(new Dimension(520, 440));

        // ---- step 1: sign in ----
        SignInPanel.Handler handler = new SignInPanel.Handler() {
            @Override
            public void signIn(String email, char[] password) {
                SignInPanel panel = signInRef[0];
                panel.clearBanner();
                panel.setBusy(true);
                new SwingWorker<Object[], Void>() {
                    @Override
                    protected Object[] doInBackground() throws Exception {
                        try {
                            ApiModels.LoginResponse login = studio.login(email, new String(password));
                            return new Object[] {login, studio.events(login.token())};
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                    }

                    @Override
                    protected void done() {
                        panel.setBusy(false);
                        try {
                            Object[] both = get();
                            ApiModels.LoginResponse login = (ApiModels.LoginResponse) both[0];
                            @SuppressWarnings("unchecked")
                            List<ApiModels.StudioEvent> events = (List<ApiModels.StudioEvent>) both[1];
                            session.set(new UploaderConfig.Session(login.email() == null ? email : login.email(),
                                    login.token(), expiry(login.expiresAt())));
                            panel.clearPassword();
                            showEvents(picker, events, session.get().email(), steps, stack);
                        } catch (Exception ex) {
                            panel.clearPassword();
                            ApiException api = ex.getCause() instanceof ApiException a ? a : null;
                            panel.showBanner(api != null ? api.getMessage() : StudioClient.NO_INTERNET);
                            if (api != null && "INVALID_CREDENTIALS".equals(api.code())) {
                                panel.focusPassword();
                            } else {
                                panel.focusEmail();
                            }
                        }
                    }
                }.execute();
            }

            @Override
            public void forgotPassword() {
                openInBrowser(FORGOT_PASSWORD_URL);
            }

            @Override
            public void useConnectionCode() {
                codeMode.set(true);
                showDetails(steps, stack, eventField, codeBlock, detailsTitle, null, eventName, eventStatus, connect, true);
            }
        };
        signInRef[0] = new SignInPanel(handler, session.get() == null ? null : session.get().email());
        SignInPanel signIn = signInRef[0];

        stack.add(backdrop(signIn), SIGN_IN);
        stack.add(backdrop(pickerCard), EVENTS);
        stack.add(backdrop(details), DETAILS);

        // ---- navigation ----
        pickCancel.addActionListener(e -> dialog.dispose());
        pickNext.addActionListener(e -> {
            ApiModels.StudioEvent ev = chosenEvent.get();
            if (ev != null) {
                codeMode.set(false);
                showDetails(steps, stack, eventField, codeBlock, detailsTitle, ev, eventName, eventStatus, connect, false);
            }
        });
        changeEvent.addActionListener(e -> steps.show(stack, EVENTS));
        useSignIn.addActionListener(e -> steps.show(stack, SIGN_IN));
        back.addActionListener(e -> {
            error.setText(" ");
            if (codeMode.get()) {
                steps.show(stack, session.get() != null && session.get().valid(Instant.now()) ? EVENTS : SIGN_IN);
            } else {
                steps.show(stack, EVENTS);
            }
        });

        connect.addActionListener(e -> {
            error.setText(" ");
            boolean viaCode = codeMode.get();
            final ConnectionCode parsed;
            try {
                parsed = viaCode ? ConnectionCode.parse(code.getText()) : null;
                if (folder.getText().isBlank()) {
                    throw new IllegalArgumentException("Choose the folder your photos are kept in.");
                }
            } catch (Exception ex) {
                error.setText(ex.getMessage());
                return;
            }
            Path chosen = Path.of(folder.getText().trim());
            ApiModels.StudioEvent event = chosenEvent.get();
            UploaderConfig.Session signedIn = session.get();
            connect.setEnabled(false);
            back.setEnabled(false);
            connect.setText("Connecting…");
            new SwingWorker<UploaderConfig, Void>() {
                @Override
                protected UploaderConfig doInBackground() throws Exception {
                    String useServer = server;
                    String token;
                    if (viaCode) {
                        useServer = parsed.server();
                        token = parsed.token();
                    } else {
                        // The same token a connection code carries: this computer becomes an uploader of the event.
                        token = studio.createUploader(signedIn.token(), event.id(), DeviceId.computerName(deviceId)).token();
                    }
                    UploaderConfig.Ftp ftpSettings = switching ? current.ftp().withEnabled(ftp.isSelected())
                            : UploaderConfig.Ftp.generate(ftp.isSelected());
                    UploaderConfig config = new UploaderConfig(useServer, token, chosen, concurrency, deviceId,
                            ftpSettings, viaCode && !switching ? null : signedIn);
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
                        if (cause instanceof ApiException api && StudioClient.SESSION_EXPIRED.equals(api.code())) {
                            session.set(null);
                            signIn.showBanner(api.getMessage());
                            steps.show(stack, SIGN_IN);
                        } else {
                            error.setText(cause.getMessage() == null
                                    ? "Could not connect. Check the internet and try again." : cause.getMessage());
                        }
                        connect.setEnabled(true);
                        back.setEnabled(true);
                        connect.setText(connectText);
                    }
                }
            }.execute();
        });

        // ---- where to start ----
        UploaderConfig.Session start = session.get();
        // A laptop set up with a connection code also starts at sign-in now; the code stays one click away.
        if (start != null && start.valid(Instant.now())) {
            picker.setSignedIn(start.email());
            picker.showLoading();
            steps.show(stack, EVENTS);
            new SwingWorker<List<ApiModels.StudioEvent>, Void>() {
                @Override
                protected List<ApiModels.StudioEvent> doInBackground() throws Exception {
                    return studio.events(start.token());
                }

                @Override
                protected void done() {
                    try {
                        picker.setEvents(get());
                    } catch (Exception ex) {
                        session.set(null);
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        signIn.showBanner(cause instanceof ApiException ? cause.getMessage() : StudioClient.NO_INTERNET);
                        steps.show(stack, SIGN_IN);
                    }
                }
            }.execute();
        } else {
            steps.show(stack, SIGN_IN);
        }

        dialog.setContentPane(stack);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(640, Math.max(dialog.getHeight(), 700)));
        dialog.setSize(dialog.getMinimumSize());
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true); // blocks until closed
        return Optional.ofNullable(result.get());
    }

    private static void showEvents(EventPickerPanel picker, List<ApiModels.StudioEvent> events, String email,
                                   CardLayout steps, JPanel stack) {
        picker.setSignedIn(email);
        picker.setEvents(events);
        steps.show(stack, EVENTS);
    }

    private static void showDetails(CardLayout steps, JPanel stack, JComponent eventField, JComponent codeBlock,
                                    Ui.TLabel title, ApiModels.StudioEvent event, Ui.TLabel eventName, Pill status,
                                    JButton connect, boolean viaCode) {
        eventField.setVisible(!viaCode);
        codeBlock.setVisible(viaCode);
        title.text(viaCode ? "Connect with a connection code" : "Almost there");
        if (event != null) {
            eventName.text(event.name());
            status.set(EventPickerPanel.toneOf(event.status()), EventList.statusLabel(event.status()));
        }
        steps.show(stack, DETAILS);
    }

    private static Instant expiry(String iso) {
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return Instant.now().plus(Duration.ofHours(12));
        }
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {
            // no browser: the link is also on the website
        }
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
