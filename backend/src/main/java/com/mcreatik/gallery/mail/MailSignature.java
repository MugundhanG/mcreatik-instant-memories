package com.mcreatik.gallery.mail;

import org.springframework.web.util.HtmlUtils;

/** The McreatiK signature (logo, website, Instagram, WhatsApp, email) for the plain-text and HTML parts of an email. */
public final class MailSignature {

    private static final String LOGO_PATH = "/email/mk-logo.png";
    /** Production logo, and the fallback when no usable public gallery base URL is configured. */
    public static final String LOGO_URL = "https://live.mcreatik.com" + LOGO_PATH;
    public static final String WEBSITE = "https://mcreatik.com";
    public static final String INSTAGRAM = "https://instagram.com/mcreatik.studios";
    public static final String WHATSAPP = "https://wa.me/919952758545";
    public static final String EMAIL = "connect@mcreatik.com";

    private MailSignature() {
    }

    public static String text() {
        return """
                McreatiK Instant Memories
                Website: %s
                Instagram: @mcreatik.studios (%s)
                WhatsApp: +91 99527 58545 (%s)
                Email: %s
                """.formatted(WEBSITE, INSTAGRAM, WHATSAPP, EMAIL);
    }

    /**
     * The logo is served by the web app, so it comes from the same site the links in the mail point to
     * (beta mail shows the beta site's copy). Falls back to the production URL if the base URL is blank,
     * not https (e.g. localhost in development: mail clients cannot load it) or malformed.
     */
    public static String logoUrl(String publicGalleryBaseUrl) {
        if (publicGalleryBaseUrl == null || publicGalleryBaseUrl.isBlank()) {
            return LOGO_URL;
        }
        try {
            java.net.URI uri = java.net.URI.create(publicGalleryBaseUrl.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return LOGO_URL;
            }
            return "https://" + uri.getAuthority() + LOGO_PATH;
        } catch (IllegalArgumentException e) {
            return LOGO_URL;
        }
    }

    public static String html(String bodyText) {
        return html(bodyText, LOGO_URL);
    }

    /** Wraps paragraphs (plain text, escaped here; blank line = new paragraph) in a simple table-free HTML email with the signature. */
    public static String html(String bodyText, String logoUrl) {
        StringBuilder paras = new StringBuilder();
        for (String p : bodyText.strip().split("\\n\\s*\\n")) {
            paras.append("<p style=\"margin:0 0 14px\">")
                    .append(linkify(HtmlUtils.htmlEscape(p.strip())).replace("\n", "<br>")).append("</p>");
        }
        return """
                <div style="font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.5;color:#1c1917">
                %s
                <div style="margin-top:22px;padding-top:14px;border-top:1px solid #e7e5e4">
                <img src="%s" alt="McreatiK" width="63" height="40" style="display:block;border:0;margin-bottom:8px">
                <div style="font-weight:bold">McreatiK Instant Memories</div>
                <div style="font-size:13px;color:#57534e">
                <a href="%s" style="color:#57534e">mcreatik.com</a> &middot;
                <a href="%s" style="color:#57534e">Instagram @mcreatik.studios</a> &middot;
                <a href="%s" style="color:#57534e">WhatsApp +91 99527 58545</a> &middot;
                <a href="mailto:%s" style="color:#57534e">%s</a>
                </div></div></div>
                """.formatted(paras, HtmlUtils.htmlEscape(logoUrl), WEBSITE, INSTAGRAM, WHATSAPP, EMAIL, EMAIL);
    }

    private static String linkify(String escaped) {
        return escaped.replaceAll("(https?://[^\\s<]+)", "<a href=\"$1\">$1</a>");
    }
}
