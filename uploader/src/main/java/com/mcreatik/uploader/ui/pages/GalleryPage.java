package com.mcreatik.uploader.ui.pages;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;

import javax.swing.JComponent;
import javax.swing.JPanel;

import com.mcreatik.uploader.queue.QueueItem;
import com.mcreatik.uploader.ui.AppContext;
import com.mcreatik.uploader.ui.Page;
import com.mcreatik.uploader.ui.Snapshot;
import com.mcreatik.uploader.ui.components.EmptyState;
import com.mcreatik.uploader.ui.components.PageLayout;
import com.mcreatik.uploader.ui.components.PhotoList;
import com.mcreatik.uploader.ui.components.Ui;

/** Every photo this computer has handled for the current event, newest first, 40 per page. */
public final class GalleryPage implements Page {
    public static final String ID = "gallery";
    static final int PAGE_SIZE = 40;

    private final AppContext ctx;
    private final PageLayout page = new PageLayout("Gallery", "Every photo from this camera for the current event, newest first.");
    private final PhotoList list = new PhotoList();
    private final JPanel body = Ui.clear(new CardLayout());
    private final EmptyState empty = new EmptyState("images", "No photos yet", "Photos appear here as soon as the camera sends them.");
    private final Ui.TLabel count = Ui.label(" ", Font.BOLD, 13f, "text");
    private final Ui.TLabel pageLabel = Ui.label(" ", Font.PLAIN, 12.5f, "muted");
    private final Ui.Btn prev = new Ui.Btn("Newer", Ui.Kind.SECONDARY, "chevron-left");
    private final Ui.Btn next = new Ui.Btn("Older", Ui.Kind.SECONDARY, "chevron-right");
    private int pageIndex;
    private int total;

    public GalleryPage(AppContext ctx) {
        this.ctx = ctx;
        Ui.Surface actions = Ui.Surface.card(new BorderLayout(12, 0));
        actions.add(count, BorderLayout.CENTER);
        JPanel buttons = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        Ui.Btn folder = new Ui.Btn("Open folder", Ui.Kind.SECONDARY, "folder-open");
        folder.addActionListener(e -> ctx.actions().openFolder(ctx.config().watchFolder()));
        Ui.Btn live = new Ui.Btn("Open live gallery", Ui.Kind.PRIMARY, "external-link");
        live.addActionListener(e -> ctx.actions().openGallery());
        buttons.add(folder);
        buttons.add(live);
        actions.add(buttons, BorderLayout.EAST);
        page.section(actions);

        Ui.Surface card = Ui.Surface.card(new BorderLayout(0, 8));
        body.add(list, "list");
        body.add(empty, "empty");
        body.setMinimumSize(new java.awt.Dimension(10, 260));
        card.add(body, BorderLayout.CENTER);
        JPanel pager = Ui.clear(new BorderLayout());
        pager.add(pageLabel, BorderLayout.WEST);
        JPanel nav = Ui.clear(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        nav.add(prev);
        nav.add(next);
        pager.add(nav, BorderLayout.EAST);
        card.add(pager, BorderLayout.SOUTH);
        page.fill(card);
        prev.addActionListener(e -> {
            pageIndex = Math.max(0, pageIndex - 1);
            load();
        });
        next.addActionListener(e -> {
            pageIndex++;
            load();
        });
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public JComponent view() {
        return page;
    }

    @Override
    public void refresh(Snapshot s) {
        total = s.engine().counts().total();
        load();
    }

    private void load() {
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        pageIndex = Math.min(pageIndex, pages - 1);
        List<QueueItem> items = ctx.engine().queue().list(PAGE_SIZE, pageIndex * PAGE_SIZE);
        list.set(items);
        ((CardLayout) body.getLayout()).show(body, items.isEmpty() ? "empty" : "list");
        count.text(String.format(Locale.ROOT, "%,d photo%s for this event", total, total == 1 ? "" : "s"));
        pageLabel.text(total == 0 ? " " : String.format(Locale.ROOT, "Showing %,d–%,d of %,d",
                pageIndex * PAGE_SIZE + 1, pageIndex * PAGE_SIZE + items.size(), total));
        prev.setEnabled(pageIndex > 0);
        next.setEnabled(pageIndex < pages - 1);
    }
}
