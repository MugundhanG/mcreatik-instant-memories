package com.mcreatik.uploader.ui;

import javax.swing.JComponent;

/** One sidebar section. {@code refresh} runs every half second while the page is visible. */
public interface Page {
    String id();

    JComponent view();

    void refresh(Snapshot s);
}
