package com.mcreatik.gallery.mail;

/** Sends plain-text emails from McreatiK (connect@mcreatik.com). */
public interface Mailer {

    /** Returns false when email is not set up or the message could not be sent (never throws). */
    boolean send(String to, String subject, String body);

    /** Same with an HTML alternative (multipart); mailers that cannot do HTML fall back to the plain text. */
    default boolean send(String to, String subject, String text, String html) {
        return send(to, subject, text);
    }
}
