package com.mcreatik.uploader.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.prefs.Preferences;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ThemeTest {

    private final Preferences node = Preferences.userRoot().node("com/mcreatik/uploader-test-" + System.nanoTime());

    @AfterEach
    void cleanUp() throws Exception {
        node.removeNode();
    }

    @Test
    void lightIsTheDefaultAndTheChoiceIsRemembered() {
        assertThat(Theme.load(node)).isEqualTo(Theme.LIGHT);
        Theme.store(node, Theme.DARK);
        assertThat(Theme.load(node)).isEqualTo(Theme.DARK);
        Theme.store(node, Theme.LIGHT);
        assertThat(Theme.load(node)).isEqualTo(Theme.LIGHT);
    }

    @Test
    void anUnknownSavedValueFallsBackToLight() {
        node.put(Theme.PREF_KEY, "purple");
        assertThat(Theme.load(node)).isEqualTo(Theme.LIGHT);
        assertThat(Theme.parse("DARK")).isEqualTo(Theme.DARK);
    }

    @Test
    void bothThemesDefineEveryToken() throws Exception {
        java.util.Properties light = new java.util.Properties();
        java.util.Properties dark = new java.util.Properties();
        try (var l = McreatiKLightLaf.class.getResourceAsStream("McreatiKLightLaf.properties");
             var d = McreatiKDarkLaf.class.getResourceAsStream("McreatiKDarkLaf.properties")) {
            light.load(l);
            dark.load(d);
        }
        var lightTokens = light.stringPropertyNames().stream().filter(k -> k.startsWith("McK.")).sorted().toList();
        var darkTokens = dark.stringPropertyNames().stream().filter(k -> k.startsWith("McK.")).sorted().toList();
        assertThat(lightTokens).isNotEmpty().isEqualTo(darkTokens);
    }
}
