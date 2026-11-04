package com.mcreatik.uploader.ui.theme;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * Lucide icons (ISC licence, see icons/LICENSE-lucide.txt), drawn in a theme token's colour. The colour is looked up
 * while painting, so icons follow a theme switch, and {@link Icon#tint} recolours one for a live status.
 */
public final class Icons {

    private static final String DIR = "com/mcreatik/uploader/ui/icons/";

    private Icons() {
    }

    public static Icon get(String name, int size, String token) {
        return new Icon(name, size, token);
    }

    public static final class Icon extends FlatSVGIcon {
        private volatile String token;

        Icon(String name, int size, String token) {
            super(DIR + name + ".svg", size, size);
            this.token = token;
            setColorFilter(new ColorFilter(c -> Tok.c(this.token)));
        }

        /** Changes the colour token; returns this for chaining. */
        public Icon tint(String newToken) {
            this.token = newToken;
            return this;
        }

        public String token() {
            return token;
        }
    }
}
