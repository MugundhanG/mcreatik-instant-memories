package com.mcreatik.uploader.ui.theme;

import java.awt.Window;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;

import javax.swing.JRootPane;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;

/**
 * Light or Dark. Both are the same components with a different token file, so switching only swaps the look and
 * repaints. The choice is saved for this computer's user and applied before any window opens (no flash).
 */
public enum Theme {
    LIGHT("Light"), DARK("Dark");

    static final String PREF_KEY = "theme";
    /** For screenshots and tests: {@code -Dmcreatik.theme=dark} wins over the saved choice without changing it. */
    static final String OVERRIDE_PROPERTY = "mcreatik.theme";

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Theme current;
    private static boolean fontsInstalled;

    public final String label;

    Theme(String label) {
        this.label = label;
    }

    public boolean dark() {
        return this == DARK;
    }

    public static Theme current() {
        return current == null ? LIGHT : current;
    }

    /** Called once, before the first window: installs Inter and the saved (or default Light) theme. */
    public static synchronized void applyAtStartup() {
        if (current != null) {
            return;
        }
        String override = System.getProperty(OVERRIDE_PROPERTY);
        Theme t = override != null ? parse(override) : load(prefs());
        // macOS draws the title bar itself; tell it which appearance to use before AWT starts.
        System.setProperty("apple.awt.application.appearance", t.dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        install(t);
    }

    /** The theme switcher: instant, with a short cross-fade, and remembered for next time. */
    public static void switchTo(Theme t) {
        if (t == current) {
            return;
        }
        try {
            FlatAnimatedLafChange.showSnapshot();
            install(t);
            FlatLaf.updateUI();
            for (Window w : Window.getWindows()) {
                applyTitleBar(w, t);
            }
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        } catch (RuntimeException | LinkageError e) {
            install(t);
            FlatLaf.updateUI();
        }
        store(prefs(), t);
        LISTENERS.forEach(Runnable::run);
    }

    /** Runs after every switch (for colours that depend on live state, such as the status card). */
    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** macOS: the window's own title bar follows the theme. Windows: FlatLaf draws it in the theme already. */
    public static void applyTitleBar(Window w, Theme t) {
        if (w instanceof javax.swing.RootPaneContainer c) {
            JRootPane root = c.getRootPane();
            root.putClientProperty("apple.awt.windowAppearance", t.dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        }
    }

    private static synchronized void install(Theme t) {
        try {
            if (!fontsInstalled) {
                FlatInterFont.install();
                FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
                FlatLaf.setPreferredLightFontFamily(FlatInterFont.FAMILY_LIGHT);
                FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD);
                fontsInstalled = true;
            }
            FlatLaf.setup(t.dark() ? new McreatiKDarkLaf() : new McreatiKLightLaf());
            current = t;
        } catch (RuntimeException | LinkageError e) {
            current = t; // without the theme the app still works with Java's default look
        }
    }

    static Preferences prefs() {
        return Preferences.userRoot().node("com/mcreatik/uploader");
    }

    static Theme load(Preferences p) {
        try {
            return parse(p.get(PREF_KEY, LIGHT.name()));
        } catch (RuntimeException e) {
            return LIGHT; // unreadable preferences never stop the app
        }
    }

    static void store(Preferences p, Theme t) {
        try {
            p.put(PREF_KEY, t.name());
            p.flush();
        } catch (Exception e) {
            // not saved this time; the app still switched
        }
    }

    static Theme parse(String s) {
        return s != null && s.trim().equalsIgnoreCase("dark") ? DARK : LIGHT;
    }

    /** True once a FlatLaf theme is active, so components can rely on the tokens. */
    public static boolean ready() {
        return UIManager.getLookAndFeel() instanceof FlatLaf;
    }
}
