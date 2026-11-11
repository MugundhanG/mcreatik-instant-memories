package com.mcreatik.uploader.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Arrays;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.FlatClientProperties;
import com.mcreatik.uploader.config.SignInValidation;
import com.mcreatik.uploader.ui.components.LinkButton;
import com.mcreatik.uploader.ui.components.SpinnerIcon;
import com.mcreatik.uploader.ui.components.Ui;
import com.mcreatik.uploader.ui.theme.Icons;

/**
 * The sign-in card: studio email and password (the same login as the website). Checks each field as the photographer
 * leaves it, keeps Sign in disabled until both are valid, and shows server and network problems in a banner.
 */
public final class SignInPanel extends JPanel {

    public interface Handler {
        /** Called with valid input only. The password array is the panel's copy: clear it when done. */
        void signIn(String email, char[] password);

        void forgotPassword();

        void useConnectionCode();
    }

    private static final int CARD_WIDTH = 420;

    private final Handler handler;
    private final JTextField email = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final Ui.TLabel emailError = Ui.label(" ", Font.PLAIN, 12f, "red");
    private final Ui.TLabel passwordError = Ui.label(" ", Font.PLAIN, 12f, "red");
    private final Ui.TLabel capsHint = Ui.label("Caps Lock is on", Font.PLAIN, 12f, "amber");
    private final Ui.Surface banner = new Ui.Surface(new BorderLayout(10, 0), "redSoft", "redLine", 10,
            new Insets(10, 12, 10, 12));
    private final Ui.Wrap bannerText = new Ui.Wrap("red", 12.5f);
    private final Ui.Btn submit = new Ui.Btn("Sign in", Ui.Kind.PRIMARY, null);
    private boolean emailTouched;
    private boolean passwordTouched;
    private boolean busy;

    public SignInPanel(Handler handler, String prefillEmail) {
        super(new BorderLayout());
        this.handler = handler;
        setOpaque(false);

        email.setText(prefillEmail == null ? "" : prefillEmail);
        email.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "name@studio.com");
        email.putClientProperty(FlatClientProperties.STYLE, "margin: 8,12,8,12");
        email.getAccessibleContext().setAccessibleName("Email");
        password.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Your password");
        password.putClientProperty(FlatClientProperties.STYLE, "margin: 8,12,8,12; showRevealButton: true");
        password.getAccessibleContext().setAccessibleName("Password");

        Ui.Surface card = new Ui.Surface(new BorderLayout(), "card", "border", 18, new Insets(30, 32, 26, 32)) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(CARD_WIDTH, super.getPreferredSize().height);
            }
        };
        card.shadow(true);
        card.add(buildColumn(), BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);

        DocumentListener changed = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onEdited();
            }
        };
        email.getDocument().addDocumentListener(changed);
        password.getDocument().addDocumentListener(changed);
        email.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                emailTouched = true;
                refreshErrors();
            }
        });
        password.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                passwordTouched = true;
                capsHint.setVisible(false);
                refreshErrors();
            }
        });
        password.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                checkCapsLock();
            }

            @Override
            public void keyReleased(KeyEvent e) {
                checkCapsLock();
            }
        });
        email.addActionListener(e -> password.requestFocusInWindow());
        password.addActionListener(e -> submit());
        submit.addActionListener(e -> submit());
        refreshErrors();
    }

    private JComponent buildColumn() {
        JPanel col = Ui.clear(new GridBagLayout());
        int[] row = {0};
        JLabel logo = new JLabel(UiTheme.logo(52), SwingConstants.CENTER);
        add(col, row, logo, 0);
        add(col, row, centered("Sign in to McreatiK<br>Instant Memories", Font.BOLD, 20f, "text"), 14);
        add(col, row, centered("Use the same email and password<br>as your studio dashboard", Font.PLAIN, 13f, "muted"), 8);

        banner.add(new JLabel(Icons.get("circle-alert", 18, "red")), BorderLayout.WEST);
        banner.add(bannerText, BorderLayout.CENTER);
        banner.setVisible(false);
        add(col, row, banner, 18);

        add(col, row, fieldLabel("Email"), 20);
        add(col, row, email, 6);
        emailError.setVisible(false);
        add(col, row, emailError, 4);

        add(col, row, fieldLabel("Password"), 12);
        add(col, row, password, 6);
        passwordError.setVisible(false);
        add(col, row, passwordError, 4);
        capsHint.setVisible(false);
        capsHint.setIcon(Icons.get("triangle-alert", 14, "amber"));
        capsHint.setIconTextGap(6);
        add(col, row, capsHint, 4);

        add(col, row, submit, 20);
        LinkButton forgot = new LinkButton("Forgot password?", "blue");
        forgot.addActionListener(e -> handler.forgotPassword());
        add(col, row, centeredButton(forgot), 10);

        JComponent rule = new JComponent() {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                g.setColor(com.mcreatik.uploader.ui.theme.Tok.c("border"));
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        rule.setPreferredSize(new Dimension(1, 1));
        add(col, row, rule, 14);
        LinkButton code = new LinkButton("Use a connection code instead", "muted");
        code.addActionListener(e -> handler.useConnectionCode());
        add(col, row, centeredButton(code), 10);
        return col;
    }

    private static void add(JPanel col, int[] row, JComponent c, int top) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = row[0]++;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(top, 0, 0, 0);
        col.add(c, g);
    }

    private static JComponent fieldLabel(String text) {
        return Ui.label(text, Font.BOLD, 12.5f, "text");
    }

    private static JLabel centered(String text, int style, float size, String token) {
        Ui.TLabel l = Ui.label("<html><center>" + text + "</center></html>", style, size, token);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        return l;
    }

    private static JComponent centeredButton(LinkButton b) {
        JPanel p = Ui.clear(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        p.add(b);
        return p;
    }

    // ---- behaviour ----

    private void onEdited() {
        refreshErrors();
        if (banner.isVisible()) {
            clearBanner();
        }
    }

    private void checkCapsLock() {
        boolean on;
        try {
            on = Toolkit.getDefaultToolkit().getLockingKeyState(KeyEvent.VK_CAPS_LOCK);
        } catch (UnsupportedOperationException e) {
            on = false;
        }
        if (capsHint.isVisible() != on) {
            capsHint.setVisible(on);
            revalidate();
        }
    }

    /** Shows the error under a field once it has been visited (or on submit), and enables Sign in. */
    private void refreshErrors() {
        showError(email, emailError, emailTouched ? SignInValidation.emailError(email.getText()) : null);
        showError(password, passwordError, passwordTouched ? SignInValidation.passwordError(passwordText()) : null);
        submit.setEnabled(!busy && SignInValidation.canSubmit(email.getText(), passwordText()));
    }

    private void showError(JTextField field, Ui.TLabel label, String message) {
        boolean show = message != null;
        field.putClientProperty(FlatClientProperties.OUTLINE, show ? FlatClientProperties.OUTLINE_ERROR : null);
        if (show) {
            label.text(message);
        }
        if (label.isVisible() != show) {
            label.setVisible(show);
            revalidate();
            repaint();
        }
    }

    private String passwordText() {
        char[] chars = password.getPassword();
        try {
            return new String(chars);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    private void submit() {
        emailTouched = true;
        passwordTouched = true;
        refreshErrors();
        if (busy) {
            return;
        }
        if (SignInValidation.emailError(email.getText()) != null) {
            email.requestFocusInWindow();
            return;
        }
        if (SignInValidation.passwordError(passwordText()) != null) {
            password.requestFocusInWindow();
            return;
        }
        handler.signIn(SignInValidation.normalizeEmail(email.getText()), password.getPassword());
    }

    // ---- driven by the dialog ----

    /** While a request is running: Sign in shows a spinner and can't be pressed twice. */
    public void setBusy(boolean on) {
        busy = on;
        email.setEnabled(!on);
        password.setEnabled(!on);
        submit.setText(on ? "Signing in…" : "Sign in");
        submit.setIcon(on ? new SpinnerIcon() : null);
        submit.setIconTextGap(8);
        refreshErrors();
    }

    public void showBanner(String message) {
        bannerText.text(message);
        banner.setVisible(true);
        revalidate();
        repaint();
    }

    public void clearBanner() {
        if (banner.isVisible()) {
            banner.setVisible(false);
            revalidate();
            repaint();
        }
    }

    /** Moves the cursor to the field the photographer should fix. */
    public void focusPassword() {
        password.selectAll();
        password.requestFocusInWindow();
    }

    public void focusEmail() {
        email.requestFocusInWindow();
    }

    public void clearPassword() {
        password.setText("");
        passwordTouched = false;
        refreshErrors();
    }

    /** Marks both fields as visited so their errors show (what pressing Sign in does). */
    public void showAllErrors() {
        emailTouched = true;
        passwordTouched = true;
        refreshErrors();
    }

    public void fill(String emailText, String passwordText) {
        email.setText(emailText);
        password.setText(passwordText);
    }
}
