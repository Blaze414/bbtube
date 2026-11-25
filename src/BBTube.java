import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Vector;
import javax.microedition.io.Connection;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.*;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VideoControl;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * bbtube for BlackBerry OS: search, thumbnails, frame preview and playback, all through the bbtube server on the Mac.
 * Plain MIDP 2.0 / CLDC 1.1 (Java 1.3 syntax), so it builds with open-source tools and needs no BlackBerry signing.
 *
 * The screens are drawn by hand in the BlackBerry OS 6 style (dark header, white rows, blue selection bar), because
 * the stock LCDUI widgets can't be styled. Search is typed straight into the box on Home: on a Canvas the 9780
 * delivers letters as their character codes, Enter as 10 and Delete as 8.
 * Every action is also in the BlackBerry menu, with Open first, in case this phone's trackpad click opens the menu.
 */
public class BBTube extends MIDlet implements CommandListener, PlayerListener {
    /**
     * BlackBerry connection options, tried in order until one connects; the one that works is kept.
     * By default BlackBerry routes through the BIS servers, which no longer exist, so Wi-Fi ones come first.
     */
    static final String[] TRANSPORTS = {";interface=wifi", ";deviceside=true;interface=wifi", ""};
    private int transport = -1; // index into TRANSPORTS that worked, -1 = not found yet

    // OS 6 palette
    static final int HEAD_TOP = 0x464646, HEAD_BOTTOM = 0x121212, SEL_TOP = 0x5aaaf3, SEL_BOTTOM = 0x1f69c9;
    static final int INK = 0x1a1a1a, DIM = 0x7a7a7a, SEPARATOR = 0xe3e3e3, BLUE = 0x2a8ce8;
    static final int THUMB_W = 120, THUMB_H = 67, HERO_W = 192, HERO_H = 108, FRAME_H = 81; // sizes the Mac serves

    // row kinds and what picking them does
    static final int SEARCHBOX = 0, SECTION = 1, ACTION = 2, VIDEO = 3, HERO = 4, NOTE = 5, FRAMES = 6;
    static final int NONE = 0, SEARCH_TYPED = 1, RECENT = 2, OPEN_VIDEO = 3, MORE = 4, PLAY = 5, MEDIA_PLAYER = 6, PREVIEW = 7;

    private Display display;
    private String server; // host:port of bbtube on the Mac
    private Screen home, results, details, preview;
    private Form options;
    private TextField serverField;
    private String typed = ""; // what's in the search box on Home
    private boolean caretOn;
    private final Vector recent = new Vector(); // recent searches, newest first
    private String lastQuery;
    private int page;
    private int searchGen; // stops a slow earlier search from filling in a newer one
    private String[] info; // /api/video lines of the open video, null while loading
    private String currentId;
    private String currentTitle = "";
    private VideoScreen videoCanvas;
    private Player player;
    private boolean ended; // played to the end: Play starts over
    private final Vector seenKeys = new Vector(); // key codes already reported to the Mac's log

    private final Font fTitle = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    private final Font fText = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    private final Font fSmall = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fSection = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private Image logo, iSearch, iRecent, iMore, iMedia, iPreview, playOverlay;

    private final Command cOpen = new Command("Open", Command.OK, 1); // the highlighted row, on every list screen
    private final Command cSearch = new Command("Search", Command.OK, 1); // Home's Open: the typed text or a recent search
    private final Command cDelete = new Command("Delete", Command.SCREEN, 3);
    private final Command cMore = new Command("More Results", Command.SCREEN, 2);
    private final Command cPlay = new Command("Play", Command.SCREEN, 2);
    private final Command cBrowser = new Command("Play in Media Player", Command.SCREEN, 3);
    private final Command cPreview = new Command("Preview Frames", Command.SCREEN, 4);
    private final Command cPause = new Command("Pause", Command.OK, 1);
    private final Command cResume = new Command("Play", Command.OK, 1);
    private final Command cOptions = new Command("Options", Command.SCREEN, 8);
    private final Command cSave = new Command("Save", Command.OK, 1);
    private final Command cBack = new Command("Back", Command.BACK, 10); // BlackBerry adds Close itself, so no Exit

    protected void startApp() {
        if (display != null) return; // resumed from the background
        display = Display.getDisplay(this);
        server = loadServer();
        loadRecent();
        logo = icon("/logo.png");
        iSearch = icon("/search.png");
        iRecent = icon("/recent.png");
        iMore = icon("/more.png");
        iMedia = icon("/media.png");
        iPreview = icon("/preview.png");
        playOverlay = icon("/play.png");
        home = new Screen("bbtube");
        home.addCommand(cSearch);
        home.addCommand(cDelete);
        home.addCommand(cOptions);
        showHome();
        new Thread() { // blinks the search box's cursor and turns the player's waiting spinner
            public void run() {
                for (int n = 0; ; n++) {
                    try {
                        Thread.sleep(125);
                    } catch (InterruptedException e) {
                        return;
                    }
                    VideoScreen v = videoCanvas;
                    if (v != null && !v.shown && v.percent < 0) {
                        v.spin++;
                        v.repaint();
                    }
                    if (n % 4 == 0) {
                        caretOn = !caretOn;
                        if (home.isShown()) home.repaint(0, headerHeight(), home.getWidth(), fText.getHeight() + 30);
                    }
                }
            }
        }.start();
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
        stopPlayer();
    }

    public void commandAction(Command c, Displayable d) {
        try {
            handle(c, d);
        } catch (Throwable e) { // BlackBerry drops exceptions thrown from command handlers without a word
            report("Couldn't do that", e);
        }
    }

    private void handle(Command c, Displayable d) {
        if ((c == cOpen || c == cSearch) && d instanceof Screen) {
            pick(((Screen) d).selected());
        } else if (c == cDelete) {
            Row r = home.selected();
            if (r != null && r.act == RECENT) {
                recent.removeElement(r.text);
                saveRecent();
                showHome();
            }
        } else if (c == cMore) {
            Row last = results.last();
            if (last != null && last.act == MORE) pick(last);
        } else if (c == cPlay) {
            playInApp();
        } else if (c == cBrowser) {
            playInMediaPlayer();
        } else if (c == cPreview) {
            showPreview();
        } else if (c == cPause || c == cResume) {
            togglePause();
        } else if (c == cOptions) {
            showOptions();
        } else if (c == cSave || (c == cBack && d == options)) { // Back keeps the change, nothing typed is lost
            String s = serverField.getString().trim();
            if (s.length() > 0 && !s.equals(server)) {
                server = s;
                transport = -1;
                saveServer(s);
            }
            options = null;
            showHome();
        } else if (c == cBack) {
            if (d == home) { // Escape clears the search box, as in BlackBerry's own search
                typed = "";
                showHome();
            } else if (d == videoCanvas) {
                stopPlayer();
                videoCanvas = null;
                display.setCurrent(details);
            } else if (d == preview) {
                preview = null;
                display.setCurrent(details);
            } else if (d == details) {
                details = null;
                display.setCurrent(results);
            } else { // results
                searchGen++;
                showHome();
            }
        }
    }

    /** A row was picked: trackpad click, Enter, or Open in the menu. */
    private void pick(Row r) {
        if (r == null) return;
        if (r.act == SEARCH_TYPED) runSearch(typed.trim());
        else if (r.act == RECENT) runSearch(r.text);
        else if (r.act == OPEN_VIDEO) openVideo(r);
        else if (r.act == MORE) {
            results.remove(r);
            page++;
            loadResults();
        } else if (r.act == PLAY) playInApp();
        else if (r.act == MEDIA_PLAYER) playInMediaPlayer();
        else if (r.act == PREVIEW) showPreview();
    }

    // --- screens ---

    /** Home: the search box, then recent searches, narrowed to the ones containing what's typed. */
    private void showHome() {
        home.clear();
        Row box = new Row(SEARCHBOX, "Search YouTube", iSearch);
        box.act = SEARCH_TYPED;
        home.add(box);
        String t = typed.trim().toLowerCase();
        boolean any = false;
        for (int i = 0; i < recent.size(); i++) {
            String q = (String) recent.elementAt(i);
            if (q.toLowerCase().indexOf(t) < 0) continue;
            if (!any) home.add(new Row(SECTION, "Recent Searches", null));
            any = true;
            Row r = new Row(ACTION, q, iRecent);
            r.act = RECENT;
            home.add(r);
        }
        if (recent.isEmpty()) home.add(new Row(NOTE, "Your searches will show up here.", null));
        if (typed.length() > 0) home.addCommand(cBack); // Back clears the box; with it empty there's nothing to go back to
        else home.removeCommand(cBack);
        if (display.getCurrent() != home) display.setCurrent(home);
    }

    /** A key on Home that edits the search box; false for keys that move or pick instead. */
    private boolean typing(int key) {
        if (key == 8 || key == 127) { // Delete
            if (typed.length() > 0) typed = typed.substring(0, typed.length() - 1);
        } else if ((key >= 32 && key < 127) || (key >= 160 && key < 0x3000)) { // not trackpad, phone or media keys
            if (key == ' ' && typed.length() == 0) return true;
            if (typed.length() < 100) typed += (char) key;
        } else {
            return false;
        }
        caretOn = true;
        showHome();
        return true;
    }

    private void runSearch(String q) {
        if (q.length() == 0) return;
        typed = ""; // Home shows all recent searches again on the way back
        lastQuery = q;
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (q.equalsIgnoreCase((String) recent.elementAt(i))) recent.removeElementAt(i);
        }
        recent.insertElementAt(q, 0);
        while (recent.size() > 8) recent.removeElementAt(8);
        saveRecent();
        page = 1;
        searchGen++;
        results = new Screen(q);
        results.addCommand(cOpen);
        results.addCommand(cMore);
        results.addCommand(cBack);
        display.setCurrent(results);
        loadResults();
    }

    private void loadResults() {
        final Screen s = results;
        final String q = lastQuery;
        final int p = page;
        final int gen = searchGen;
        final Row busy = new Row(NOTE, p == 1 ? "Searching..." : "Loading...", null);
        s.add(busy);
        new Thread() {
            public void run() {
                String[] lines;
                try {
                    lines = lines(get("/api/search?q=" + urlEncode(q) + "&page=" + p));
                } catch (Throwable e) {
                    s.remove(busy);
                    if (gen == searchGen) report("Couldn't search", e);
                    return;
                }
                s.remove(busy);
                if (gen != searchGen) return;
                Vector added = new Vector();
                for (int i = 0; i < lines.length; i++) {
                    String[] f = split(lines[i], '\t'); // id, length or "live", title, channel
                    if (f.length < 4) continue;
                    Row r = new Row(VIDEO, f[2], null);
                    r.act = OPEN_VIDEO;
                    r.id = f[0];
                    r.length = f[1];
                    r.sub = f[3];
                    s.add(r);
                    added.addElement(r);
                }
                if (added.isEmpty()) {
                    if (p == 1) s.add(new Row(NOTE, "No videos found.", null));
                    return;
                }
                Row more = new Row(ACTION, "More Results", iMore);
                more.act = MORE;
                s.add(more);
                for (int i = 0; i < added.size() && gen == searchGen; i++) {
                    Row r = (Row) added.elementAt(i);
                    try {
                        byte[] img = get("/api/thumb?v=" + r.id + "&size=" + THUMB_W + "&t=" + urlEncode(r.length));
                        r.img = Image.createImage(img, 0, img.length);
                        s.repaint();
                    } catch (Throwable e) {
                        log("thumb: " + e); // a missing thumbnail is not worth an alert
                    }
                }
            }
        }.start();
    }

    private void openVideo(Row v) {
        final String id = v.id;
        currentId = id;
        currentTitle = v.text;
        info = null;
        final Screen s = new Screen(v.sub); // the channel, like a sender in a message's title
        final Row hero = new Row(HERO, v.text, null);
        hero.act = PLAY;
        hero.length = v.length;
        s.add(hero);
        s.add(action("Play in Media Player", iMedia, MEDIA_PLAYER));
        s.add(action("Preview Frames", iPreview, PREVIEW));
        s.addCommand(cOpen);
        s.addCommand(cPlay);
        s.addCommand(cBrowser);
        s.addCommand(cPreview);
        s.addCommand(cBack);
        details = s;
        display.setCurrent(s);
        new Thread() {
            public void run() {
                try {
                    String[] v = lines(get("/api/video?v=" + id)); // also starts the conversion on the Mac
                    if (details != s) return;
                    info = v;
                    currentTitle = v[0];
                    hero.setText(v[0]);
                    if (!"1".equals(v[3])) hero.sub = "Can't play on BlackBerry";
                    s.repaint();
                    byte[] t = get("/api/thumb?v=" + id + "&size=" + HERO_W + "&t=" + urlEncode(hero.length));
                    hero.img = Image.createImage(t, 0, t.length);
                    s.repaint();
                } catch (Throwable e) {
                    report("Couldn't load the video", e);
                }
            }
        }.start();
    }

    private void showPreview() {
        final String id = currentId;
        final Screen s = new Screen("Preview Frames");
        final Row note = new Row(NOTE, "Loading frames...", null);
        s.add(note);
        s.addCommand(cPlay);
        s.addCommand(cBack);
        preview = s;
        display.setCurrent(s);
        new Thread() {
            public void run() {
                try {
                    String[] v = info != null ? info : lines(get("/api/video?v=" + id));
                    Row row = null;
                    int n = 0;
                    for (int i = 4; i < v.length && preview == s; i++) {
                        String[] fr = split(v[i], '\t'); // time, frame path
                        if (fr.length < 2) continue;
                        byte[] b = get(fr[1] + "&h=" + FRAME_H + "&t=" + urlEncode(fr[0]));
                        if (n % 3 == 0) {
                            row = new Row(FRAMES, null, null);
                            row.frames = new Image[3];
                            if (n == 0) s.remove(note);
                            s.add(row);
                        }
                        row.frames[n++ % 3] = Image.createImage(b, 0, b.length);
                        s.repaint();
                    }
                    if (n == 0) note.setText("No preview for this video.");
                    s.repaint();
                } catch (Throwable e) {
                    report("Couldn't load the preview", e);
                }
            }
        }.start();
    }

    private void showOptions() {
        Form f = new Form(null);
        f.append(header(f, "Options"));
        serverField = new TextField("Server:", server, 64, TextField.URL);
        f.append(serverField);
        f.append(new StringItem(null, "\nAddress and port of bbtube on your Mac.\n\nbbtube " + getAppProperty("MIDlet-Version")));
        f.addCommand(cSave);
        f.addCommand(cBack);
        f.setCommandListener(this);
        options = f;
        display.setCurrent(f);
    }

    private Row action(String label, Image img, int act) {
        Row r = new Row(ACTION, label, img);
        r.act = act;
        return r;
    }

    /** The drawn header as an image, for the two form screens that need a real text field. */
    private ImageItem header(Form f, String title) {
        int w = f.getWidth(), h = headerHeight();
        if (w <= 0) w = home.getWidth(); // not laid out yet
        Image m = Image.createImage(w, h);
        paintHeader(m.getGraphics(), w, h, title, "");
        return new ImageItem(null, Image.createImage(m), ImageItem.LAYOUT_LEFT | ImageItem.LAYOUT_NEWLINE_AFTER, title);
    }

    private int headerHeight() {
        return Math.max(40, fTitle.getHeight() + 14);
    }

    private void paintHeader(Graphics g, int w, int h, String title, String status) {
        gradient(g, 0, 0, w, h, HEAD_TOP, HEAD_BOTTOM);
        g.setColor(0x5c5c5c);
        g.drawLine(0, 0, w, 0); // top highlight
        g.setColor(0);
        g.drawLine(0, h - 1, w, h - 1);
        int x = 10;
        if (logo != null) {
            g.drawImage(logo, x, h / 2, Graphics.LEFT | Graphics.VCENTER);
            x += logo.getWidth() + 10;
        }
        int sw = status.length() > 0 ? fSmall.stringWidth(status) + 14 : 0;
        g.setColor(0xffffff);
        g.setFont(fTitle);
        g.drawString(fit(title, fTitle, w - x - 10 - sw), x, (h - fTitle.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        if (sw > 0) {
            g.setColor(0xb8b8b8);
            g.setFont(fSmall);
            g.drawString(status, w - 10, (h - fSmall.getHeight()) / 2, Graphics.TOP | Graphics.RIGHT);
        }
    }

    static void gradient(Graphics g, int x, int y, int w, int h, int top, int bottom) {
        for (int i = 0; i < h; i++) {
            g.setColor(mix(top, bottom, i, Math.max(h - 1, 1)));
            g.drawLine(x, y + i, x + w - 1, y + i);
        }
    }

    /** Colour a/n of the way from c1 to c2. */
    static int mix(int c1, int c2, int a, int n) {
        int r = ((c1 >> 16 & 255) * (n - a) + (c2 >> 16 & 255) * a) / n;
        int g = ((c1 >> 8 & 255) * (n - a) + (c2 >> 8 & 255) * a) / n;
        int b = ((c1 & 255) * (n - a) + (c2 & 255) * a) / n;
        return r << 16 | g << 8 | b;
    }

    private static Image icon(String path) {
        try {
            return Image.createImage(path);
        } catch (IOException e) {
            return null; // the row just has no icon
        }
    }

    /** One line of a list screen. */
    static class Row {
        final int kind;
        int act = NONE;
        String text, sub, id, length;
        Image img;
        Image[] frames;
        String[] lines; // text wrapped for the current width, worked out once
        int wrapWidth;

        Row(int kind, String text, Image img) {
            this.kind = kind;
            this.text = text;
            this.img = img;
        }

        void setText(String s) {
            text = s;
            lines = null;
        }

        boolean selectable() {
            return act != NONE;
        }
    }

    /** A hand-drawn list screen: OS 6 header, rows, blue selection bar, scroll bar. Trackpad up/down moves. */
    class Screen extends Canvas {
        final String title;
        final Vector rows = new Vector();
        int sel = -1; // selected row
        int top; // pixels scrolled

        Screen(String title) {
            this.title = title;
            setFullScreenMode(true);
            setCommandListener(BBTube.this);
        }

        Row row(int i) {
            return (Row) rows.elementAt(i);
        }

        void add(Row r) {
            synchronized (rows) {
                rows.addElement(r);
                if (sel < 0 && r.selectable()) sel = rows.size() - 1;
            }
            repaint();
        }

        void remove(Row r) {
            synchronized (rows) {
                rows.removeElement(r);
                fixSelection();
            }
            repaint();
        }

        void clear() {
            synchronized (rows) {
                rows.removeAllElements();
                sel = -1;
                top = 0;
            }
        }

        Row selected() {
            synchronized (rows) {
                return sel >= 0 && sel < rows.size() ? row(sel) : null;
            }
        }

        Row last() {
            synchronized (rows) {
                return rows.isEmpty() ? null : row(rows.size() - 1);
            }
        }

        /** After a row goes away: the nearest selectable row, looking down first. */
        void fixSelection() {
            int n = rows.size();
            if (sel >= n) sel = n - 1;
            for (int i = Math.max(sel, 0); i < n; i++) {
                if (row(i).selectable()) {
                    sel = i;
                    return;
                }
            }
            for (int i = Math.min(sel, n - 1); i >= 0; i--) {
                if (row(i).selectable()) {
                    sel = i;
                    return;
                }
            }
            sel = -1;
        }

        int height(Row r) {
            int t = fText.getHeight();
            if (r.kind == SEARCHBOX) return t + 30;
            if (r.kind == SECTION) return fSection.getHeight() + 8;
            if (r.kind == ACTION) return Math.max(24, t) + 20;
            if (r.kind == VIDEO) return Math.max(THUMB_H, 2 * t + fSmall.getHeight() + 2) + 14;
            if (r.kind == HERO) return Math.max(HERO_H, 4 * fTitle.getHeight()) + 20;
            if (r.kind == FRAMES) return FRAME_H + 10;
            return t + 30; // NOTE
        }

        protected void keyPressed(int key) {
            logKey(this, key);
            if (this == home && typing(key)) return;
            move(key);
        }

        protected void keyRepeated(int key) {
            if (this == home && (key == 8 || key == 127)) typing(key); // holding Delete keeps deleting
            else move(key);
        }

        void move(int key) {
            if (key == '\n' || key == '\r') {
                pickSafely();
                return;
            }
            // the 9780's trackpad sends 1 up, 6 down, 2 left, 5 right and -8 for a click; letters and Space
            // also map to game actions on a QWERTY BlackBerry (Space = FIRE), so they're kept out
            if (key >= 32 && key < 127) return;
            int action = 0;
            try {
                action = getGameAction(key);
            } catch (IllegalArgumentException e) {
                return; // a BlackBerry key with no game action
            }
            if (action == FIRE) pickSafely();
            else if (action == UP) step(-1);
            else if (action == DOWN) step(1);
        }

        void pickSafely() {
            try {
                pick(selected());
            } catch (Throwable e) {
                report("Couldn't do that", e);
            }
        }

        void step(int dir) {
            synchronized (rows) {
                int i = sel + dir;
                while (i >= 0 && i < rows.size() && !row(i).selectable()) i += dir;
                int view = getHeight() - headerHeight();
                if (i >= 0 && i < rows.size()) {
                    sel = i;
                    int y = 0, firstSelectable = -1;
                    for (int k = 0; k < sel; k++) {
                        y += height(row(k));
                        if (firstSelectable < 0 && row(k).selectable()) firstSelectable = k;
                    }
                    int h = height(row(sel));
                    if (firstSelectable < 0) top = 0; // first selectable row: show anything above it too
                    else if (y < top) top = y;
                    else if (y + h > top + view) top = y + h - view;
                } else {
                    top += dir * view / 3; // nothing selectable that way: scroll to show the rest
                }
                int total = 0;
                for (int k = 0; k < rows.size(); k++) total += height(row(k));
                top = Math.max(0, Math.min(top, total - view));
            }
            repaint();
        }

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight(), hh = headerHeight();
            g.setClip(0, hh, w, h - hh);
            g.setColor(0xffffff);
            g.fillRect(0, hh, w, h - hh);
            int total = 0;
            synchronized (rows) {
                int y = hh - top;
                for (int i = 0; i < rows.size(); i++) {
                    Row r = row(i);
                    int rh = height(r);
                    if (y + rh > hh && y < h) paintRow(g, r, i == sel, y, w, rh);
                    y += rh;
                    total += rh;
                }
            }
            int view = h - hh;
            if (total > view) { // scroll bar
                g.setColor(0xb4b4b4);
                g.fillRect(w - 4, hh + top * view / total, 3, Math.max(12, view * view / total));
            }
            g.setClip(0, 0, w, h);
            paintHeader(g, w, hh, title, "");
        }

        void paintRow(Graphics g, Row r, boolean on, int y, int w, int rh) {
            int ink = on ? 0xffffff : INK, dim = on ? 0xd6e6fa : DIM;
            if (on && r.kind != SEARCHBOX) gradient(g, 0, y, w, rh, SEL_TOP, SEL_BOTTOM);
            if (r.kind == SEARCHBOX) { // rounded field with a magnifier and a blinking cursor, blue ring when selected
                int bx = 8, by = y + 8, bw = w - 16, bh = rh - 16;
                g.setColor(0xffffff);
                g.fillRoundRect(bx, by, bw, bh, 14, 14);
                g.setColor(on ? BLUE : 0xb4b4b4);
                g.drawRoundRect(bx, by, bw - 1, bh - 1, 14, 14);
                if (on) g.drawRoundRect(bx + 1, by + 1, bw - 3, bh - 3, 12, 12);
                int tx = bx + 12, ty = by + (bh - fText.getHeight()) / 2;
                if (r.img != null) {
                    g.drawImage(r.img, tx, by + bh / 2, Graphics.LEFT | Graphics.VCENTER);
                    tx += r.img.getWidth() + 10;
                }
                g.setFont(fText);
                String t = typed;
                while (fText.stringWidth(t) > bx + bw - 14 - tx) t = t.substring(1); // long text: show its end
                if (t.length() == 0) {
                    g.setColor(0x9a9a9a);
                    g.drawString(r.text, tx, ty, Graphics.TOP | Graphics.LEFT);
                } else {
                    g.setColor(INK);
                    g.drawString(t, tx, ty, Graphics.TOP | Graphics.LEFT);
                }
                if (on && caretOn) {
                    g.setColor(BLUE);
                    g.fillRect(tx + fText.stringWidth(t) + 1, ty, 2, fText.getHeight());
                }
            } else if (r.kind == SECTION) { // grey band, as in BlackBerry's own grouped lists
                g.setColor(0xececec);
                g.fillRect(0, y, w, rh);
                g.setColor(0xd2d2d2);
                g.drawLine(0, y, w, y);
                g.drawLine(0, y + rh - 1, w, y + rh - 1);
                g.setColor(0x6a6a6a);
                g.setFont(fSection);
                g.drawString(r.text, 10, y + (rh - fSection.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
                return;
            } else if (r.kind == ACTION) {
                int tx = 12;
                if (r.img != null) {
                    g.drawImage(r.img, tx, y + rh / 2, Graphics.LEFT | Graphics.VCENTER);
                    tx += r.img.getWidth() + 12;
                }
                g.setColor(ink);
                g.setFont(fText);
                g.drawString(fit(r.text, fText, w - tx - 10), tx, y + (rh - fText.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
            } else if (r.kind == VIDEO || r.kind == HERO) {
                boolean hero = r.kind == HERO;
                int tw = hero ? HERO_W : THUMB_W, th = hero ? HERO_H : THUMB_H, ty = y + (rh - th) / 2;
                if (r.img != null) g.drawImage(r.img, 8, ty, Graphics.TOP | Graphics.LEFT);
                else {
                    g.setColor(0xdcdcdc);
                    g.fillRect(8, ty, tw, th);
                }
                if (hero && playOverlay != null) g.drawImage(playOverlay, 8 + tw / 2, ty + th / 2, Graphics.HCENTER | Graphics.VCENTER);
                int x = 8 + tw + 12, width = w - x - 10;
                Font f = hero ? fTitle : fText;
                if (r.lines == null || r.wrapWidth != width) {
                    r.lines = wrap(r.text, f, width, hero ? 4 : 2);
                    r.wrapWidth = width;
                }
                int lines = r.lines.length, block = lines * f.getHeight() + (r.sub != null ? fSmall.getHeight() + 2 : 0);
                int yy = y + (rh - block) / 2;
                g.setColor(ink);
                g.setFont(f);
                for (int i = 0; i < lines; i++) g.drawString(r.lines[i], x, yy + i * f.getHeight(), Graphics.TOP | Graphics.LEFT);
                if (r.sub != null) {
                    g.setColor(dim);
                    g.setFont(fSmall);
                    g.drawString(fit(r.sub, fSmall, width), x, yy + lines * f.getHeight() + 2, Graphics.TOP | Graphics.LEFT);
                }
            } else if (r.kind == FRAMES) { // three preview frames, centred in equal cells
                int cw = (w - 16) / 3;
                for (int i = 0; i < 3; i++) {
                    Image im = r.frames[i];
                    if (im != null) g.drawImage(im, 8 + i * cw + cw / 2, y + 5, Graphics.TOP | Graphics.HCENTER);
                }
                return;
            } else { // NOTE
                g.setColor(0x8a8a8a);
                g.setFont(fText);
                g.drawString(fit(r.text, fText, w - 20), w / 2, y + (rh - fText.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
                return;
            }
            if (!on) {
                g.setColor(SEPARATOR);
                g.drawLine(r.kind == SEARCHBOX ? w : 8, y + rh - 1, w, y + rh - 1);
            }
        }
    }

    /** Reports the first key codes each kind of screen gets to the Mac's log: shows what this BlackBerry delivers. */
    private void logKey(Canvas c, int key) {
        Integer k = new Integer(key);
        if (seenKeys.size() >= 24 || seenKeys.contains(k)) return;
        seenKeys.addElement(k);
        int action = 0;
        try {
            action = c.getGameAction(key);
        } catch (IllegalArgumentException e) {
            action = -1;
        }
        log("key " + key + " game action " + action + (c instanceof VideoScreen ? " (player)" : ""));
    }

    // --- playback ---

    /**
     * The player: the app's header on top (title, and Buffering/Paused/Finished on the right), the video in the middle,
     * and a control bar like the BlackBerry Media Player's below: blue play/pause button, elapsed, track, time left.
     * A 16:9 video on the 9780's 480x360 screen fits exactly between a 45-pixel header and bar.
     */
    class VideoScreen extends Canvas {
        static final int BAR = 45;
        static final int SKIP = 10; // seconds per trackpad press
        final String title;
        final int length; // seconds, from the Mac, for streams that don't report their duration
        String status = "";
        int percent = -1; // conversion progress, -1 = spinner instead of a progress bar
        int spin; // spinner step, turned by the animation thread while waiting
        boolean shown; // video is on screen
        boolean bars; // video sits between header and bar; false = full screen, nothing to draw
        boolean playing;
        long elapsed, total; // microseconds
        int vx, vy, vw, vh; // where the video is, so only the black around it gets painted
        long target = -1; // where a seek is heading, microseconds; -1 = none pending
        final Object seekLock = new Object();

        VideoScreen(String title, int length) {
            this.title = title;
            this.length = length;
            setFullScreenMode(true);
        }

        void place(VideoControl vc) {
            int w = getWidth(), top = headerHeight(), h = getHeight() - top - BAR;
            try {
                int sw = vc.getSourceWidth(), sh = vc.getSourceHeight();
                if (sw <= 0 || sh <= 0) { // not known yet on some phones: assume 16:9
                    sw = 16;
                    sh = 9;
                }
                int dw = w, dh = sh * w / sw;
                if (dh > h) {
                    dh = h;
                    dw = sw * h / sh;
                }
                vc.setDisplaySize(dw, dh);
                vc.setDisplayLocation((w - dw) / 2, top + (h - dh) / 2);
                vx = (w - dw) / 2;
                vy = top + (h - dh) / 2;
                vw = dw;
                vh = dh;
                bars = true;
            } catch (Throwable e) {
                log("video layout: " + e);
                try {
                    vc.setDisplayFullScreen(true); // what 1.0.5 did, known to work
                } catch (Throwable e2) {
                    log("fullscreen: " + e2);
                }
            }
        }

        /** Once a second: progress from the player, then only the control bar is redrawn so the video doesn't flicker. */
        void tick() {
            Player p = player;
            if (p != null) {
                try {
                    if (target < 0) elapsed = p.getMediaTime(); // mid-seek, keep showing where it's going
                    long d = p.getDuration();
                    if (d > 0) total = d;
                } catch (Throwable e) {
                    // closed by Back
                }
            }
            refresh(); // header too: the video layer can blank it when it first appears
        }

        /** Trackpad left/right: jump by seconds. Presses made while a seek runs add up and are sent as one. */
        void seek(int by) {
            if (player == null || !shown) return;
            long all = total > 0 ? total : length * 1000000L;
            boolean idle;
            synchronized (seekLock) {
                long t = (target >= 0 ? target : elapsed) + by * 1000000L;
                if (all > 0) t = Math.min(t, all - 1000000L);
                t = Math.max(t, 0);
                idle = target < 0;
                target = t;
                elapsed = t;
            }
            if (ended) { // a jump back after the end: Play resumes from here, not from the start
                ended = false;
                setStatus("Paused", -1);
            }
            if (shown && bars) repaint(0, getHeight() - BAR, getWidth(), BAR);
            if (idle) new Thread() { // setMediaTime can wait on the network: never on the UI thread
                public void run() {
                    for (; ; ) {
                        long t;
                        synchronized (seekLock) {
                            t = target;
                        }
                        try {
                            Player p = player;
                            if (p != null) p.setMediaTime(t);
                        } catch (Throwable e) {
                            log("seek: " + e);
                        }
                        synchronized (seekLock) {
                            if (target == t) {
                                target = -1;
                                return;
                            }
                        }
                    }
                }
            }.start();
        }

        void refresh() {
            if (!shown) {
                repaint();
            } else if (bars) {
                repaint(0, 0, getWidth(), headerHeight());
                repaint(0, getHeight() - BAR, getWidth(), BAR);
            }
        }

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight(), top = headerHeight();
            if (!shown) { // waiting: header, then a spinner or the conversion's progress bar on black
                g.setColor(0);
                g.fillRect(0, top, w, h);
                paintHeader(g, w, top, title, "");
                int cy = top + (h - top) / 2, th = fText.getHeight();
                g.setFont(fText);
                g.setColor(0xffffff);
                if (percent >= 0) {
                    g.drawString(fit(status, fText, w - 20), w / 2, cy - th - 8, Graphics.TOP | Graphics.HCENTER);
                    track(g, w / 6, cy, w * 2 / 3, percent, 100, false);
                    g.setColor(0x9a9a9a);
                    g.setFont(fSmall);
                    g.drawString(percent + "%", w / 2, cy + 14, Graphics.TOP | Graphics.HCENTER);
                } else {
                    for (int i = 0; i < 8; i++) { // eight dots in a ring, the bright one going round
                        int[] d = {0, 9, 13, 9, 0, -9, -13, -9};
                        int a = (i - spin) & 7;
                        g.setColor(mix(0xffffff, 0x333333, a, 7));
                        g.fillArc(w / 2 + d[i] - 3, cy - 20 + d[(i + 6) & 7] - 3, 7, 7, 0, 360);
                    }
                    g.setColor(0xffffff);
                    g.drawString(fit(status, fText, w - 20), w / 2, cy + 6, Graphics.TOP | Graphics.HCENTER);
                }
                return;
            }
            if (!bars) return;
            paintHeader(g, w, top, title, status);
            g.setColor(0); // the black around the video, never over it
            g.fillRect(0, top, w, vy - top);
            g.fillRect(0, vy + vh, w, h - BAR - vy - vh);
            g.fillRect(0, vy, vx, vh);
            g.fillRect(vx + vw, vy, w - vx - vw, vh);
            int y0 = h - BAR, cy = y0 + BAR / 2;
            gradient(g, 0, y0, w, BAR, 0x343434, 0x0c0c0c);
            g.setColor(0x585858);
            g.drawLine(0, y0, w, y0);
            int bs = BAR - 12, bx = 8, by = y0 + 6; // play/pause button, glossy blue like the Media Player's
            g.setColor(SEL_BOTTOM);
            g.fillRoundRect(bx, by, bs, bs, 8, 8);
            g.setColor(SEL_TOP);
            g.fillRoundRect(bx, by, bs, bs / 2, 8, 8);
            g.fillRect(bx, by + bs / 4, bs, bs / 4);
            g.setColor(0xffffff);
            int gx = bx + bs / 2;
            if (playing) {
                g.fillRect(gx - 7, cy - 8, 5, 16);
                g.fillRect(gx + 2, cy - 8, 5, 16);
            } else {
                g.fillTriangle(gx - 5, cy - 9, gx - 5, cy + 9, gx + 9, cy);
            }
            long all = total > 0 ? total : length * 1000000L;
            String now = clock((int) (elapsed / 1000000));
            String left = all > 0 ? "-" + clock((int) (Math.max(0, all - elapsed) / 1000000)) : "";
            int lw = Math.max(fSmall.stringWidth(now), fSmall.stringWidth(left.length() > 0 ? left.substring(1) : now));
            int ty = cy - fSmall.getHeight() / 2, tx = bx + bs + 10;
            g.setFont(fSmall);
            g.drawString(now, tx, ty, Graphics.TOP | Graphics.LEFT);
            g.drawString(left, w - 10, ty, Graphics.TOP | Graphics.RIGHT);
            int kx = tx + lw + 12, kw = w - 10 - fSmall.stringWidth(left) - 12 - kx;
            track(g, kx, cy, kw, all > 0 ? elapsed : 0, all > 0 ? all : 1, true);
        }

        /** Rounded track with a blue fill to done/all, and a white knob at the end when asked. */
        void track(Graphics g, int x, int cy, int w, long done, long all, boolean knob) {
            int fill = (int) (w * Math.min(done, all) / all);
            g.setColor(0x4a4a4a);
            g.fillRoundRect(x, cy - 3, w, 6, 6, 6);
            g.setColor(BLUE);
            if (fill > 0) g.fillRoundRect(x, cy - 3, Math.max(fill, 6), 6, 6, 6);
            if (knob) {
                g.setColor(0xffffff);
                g.fillArc(x + fill - 6, cy - 6, 12, 12, 0, 360);
            }
        }

        protected void keyPressed(int key) {
            logKey(this, key);
            if (key == ' ') {
                togglePause(); // Space pauses, as in the BlackBerry Media Player
                return;
            }
            if (key == 8 || key > 32 && key < 127) return; // letters and Delete also report game actions on the 9780 ('d' is LEFT, Delete RIGHT)
            try {
                int a = getGameAction(key);
                if (a == FIRE) togglePause(); // trackpad click
                else if (a == LEFT) seek(-SKIP);
                else if (a == RIGHT) seek(SKIP);
            } catch (IllegalArgumentException e) {
                // a BlackBerry key with no game action
            }
        }
    }

    private void playInApp() {
        int length = info != null && info.length > 2 ? seconds(info[2]) : 0;
        final VideoScreen canvas = new VideoScreen(currentTitle, length);
        canvas.addCommand(cBack);
        canvas.setCommandListener(this);
        videoCanvas = canvas;
        ended = false;
        display.setCurrent(canvas);
        setStatus("Connecting...", -1);
        final String id = currentId;
        new Thread() {
            public void run() {
                stopPlayer();
                try {
                    // the Mac converts each video for the 9780 first; wait here so the player never times out
                    for (; ; ) {
                        String state = new String(get("/api/play?v=" + id), "UTF-8").trim();
                        if (videoCanvas != canvas) return; // Back was pressed
                        if (state.equals("ready")) break;
                        if (state.equals("failed")) {
                            setStatus("This video couldn't be converted.", -1);
                            return;
                        }
                        int pct = state.startsWith("converting ") ? Integer.parseInt(state.substring(11)) : 0;
                        setStatus("Preparing video for BlackBerry", pct);
                        Thread.sleep(2000);
                    }
                    setStatus("Connecting...", -1);
                    Player p = Manager.createPlayer("http://" + server + "/v/" + id + ".mp4" + TRANSPORTS[Math.max(transport, 0)]);
                    player = p;
                    p.addPlayerListener(BBTube.this);
                    p.realize();
                    if (videoCanvas != canvas || player != p) { // Back was pressed while connecting
                        p.close();
                        return;
                    }
                    VideoControl vc = (VideoControl) p.getControl("VideoControl");
                    if (vc != null) {
                        vc.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, canvas);
                        vc.setVisible(false); // hidden until sized, or the first frame shows at full size
                        p.prefetch(); // the video's real size is known only after prefetch
                        if (videoCanvas != canvas || player != p) return;
                        canvas.place(vc);
                        vc.setVisible(true);
                    }
                    canvas.status = "Buffering...";
                    canvas.shown = true;
                    canvas.repaint();
                    p.start();
                    while (videoCanvas == canvas) {
                        Thread.sleep(1000);
                        canvas.tick();
                    }
                } catch (Throwable e) {
                    if (videoCanvas != canvas) return; // Back closed the player under us
                    setStatus("Can't play this video here.", -1);
                    report("Couldn't play the video", e);
                }
            }
        }.start();
    }

    private void playInMediaPlayer() {
        final String url = "http://" + server + "/play?v=" + currentId;
        // BlackBerry runs menu commands without the UI event lock, and launching the browser needs it: callSerially holds it
        display.callSerially(new Runnable() {
            public void run() {
                try {
                    // the browser shows the "Preparing video" page, then hands the MP4 to the built-in Media Player
                    if (platformRequest(url)) notifyDestroyed(); // MIDP: the request waits for the app to close; BlackBerry runs both at once
                } catch (Throwable e) {
                    report("Couldn't open the Media Player", e);
                }
            }
        });
    }

    private void togglePause() {
        final Player p = player;
        if (p == null) return;
        new Thread() {
            public void run() {
                try {
                    if (p.getState() == Player.STARTED) {
                        p.stop();
                    } else {
                        if (ended) p.setMediaTime(0);
                        ended = false;
                        p.start();
                    }
                } catch (MediaException e) {
                    report("Couldn't pause", e);
                }
            }
        }.start();
    }

    private void stopPlayer() {
        Player p = player;
        player = null;
        if (p != null) p.close();
    }

    public void playerUpdate(Player p, String event, Object data) {
        VideoScreen c = videoCanvas;
        if (p != player || c == null) return; // an old player closing
        // buffering events are full JSR-135, not in the MIDP 2.0 subset we compile against: use their spec values
        if (PlayerListener.STARTED.equals(event)) {
            c.playing = true;
            showPauseCommand(c, true);
            setStatus("", -1);
        } else if (PlayerListener.STOPPED.equals(event)) {
            c.playing = false;
            showPauseCommand(c, false);
            setStatus("Paused", -1);
        } else if (PlayerListener.END_OF_MEDIA.equals(event)) {
            c.playing = false;
            ended = true;
            showPauseCommand(c, false);
            setStatus("Finished", -1);
        } else if ("bufferingStarted".equals(event)) {
            setStatus("Buffering...", -1);
        } else if ("bufferingStopped".equals(event)) {
            setStatus("", -1);
        } else if (PlayerListener.ERROR.equals(event)) {
            setStatus("Playback error", -1);
            log("player error: " + data);
        }
    }

    /** The menu says what the action does now: Pause while playing, Play while paused. */
    private void showPauseCommand(Canvas c, boolean playing) {
        c.removeCommand(playing ? cResume : cPause);
        c.addCommand(playing ? cPause : cResume);
    }

    private void setStatus(String s, int percent) {
        VideoScreen c = videoCanvas;
        if (c == null) return;
        c.status = s;
        c.percent = percent;
        c.refresh();
    }

    // --- settings ---

    private String loadServer() {
        try {
            RecordStore rs = RecordStore.openRecordStore("bbtube", true);
            try {
                if (rs.getNumRecords() > 0) return new String(rs.getRecord(1), "UTF-8");
            } finally {
                rs.closeRecordStore();
            }
        } catch (Throwable e) {
            // fall through to the address the app was installed from
        }
        String s = getAppProperty("BBTube-Server"); // written into the .jad by the Mac at install time
        return s != null ? s : "192.168.1.10:8090";
    }

    private void saveServer(String s) {
        try {
            RecordStore rs = RecordStore.openRecordStore("bbtube", true);
            try {
                byte[] b = s.getBytes("UTF-8");
                if (rs.getNumRecords() > 0) rs.setRecord(1, b, 0, b.length);
                else rs.addRecord(b, 0, b.length);
            } finally {
                rs.closeRecordStore();
            }
        } catch (Throwable e) {
            report("Couldn't save the server address", e);
        }
    }

    private void loadRecent() {
        try {
            RecordStore rs = RecordStore.openRecordStore("bbtube-recent", true);
            try {
                if (rs.getNumRecords() > 0) {
                    String[] q = lines(rs.getRecord(1));
                    for (int i = 0; i < q.length; i++) if (q[i].length() > 0) recent.addElement(q[i]);
                }
            } finally {
                rs.closeRecordStore();
            }
        } catch (Throwable e) {
            // no history yet
        }
    }

    private void saveRecent() {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < recent.size(); i++) b.append(recent.elementAt(i)).append('\n');
        try {
            RecordStore rs = RecordStore.openRecordStore("bbtube-recent", true);
            try {
                byte[] d = b.toString().getBytes("UTF-8");
                if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length);
                else rs.addRecord(d, 0, d.length);
            } finally {
                rs.closeRecordStore();
            }
        } catch (Throwable e) {
            log("recent: " + e); // history is a nicety, not worth an alert
        }
    }

    // --- errors: plain words on screen, the technical detail in the Mac's bbtube.log ---

    private void report(String what, Throwable e) {
        log(what + ": " + e);
        String m = String.valueOf(e.getMessage());
        String why;
        if (m.startsWith("Can't reach")) why = "Can't connect to bbtube on your Mac (" + server + "). Check that Wi-Fi is on and bbtube is running.";
        else if (m.startsWith("HTTP ")) why = "bbtube on your Mac had a problem. Please try again.";
        else if (e instanceof SecurityException) why = "bbtube isn't allowed to connect. Allow it in bbtube's application permissions.";
        else if (e instanceof MediaException) why = "This video can't play inside bbtube. Try Play in Media Player.";
        else if (e instanceof IOException) why = "The connection was interrupted. Please try again.";
        else why = "Please try again.";
        Alert a = new Alert(null, what + ".\n\n" + why, null, AlertType.ERROR); // BlackBerry dialogs have no title
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a);
    }

    private void log(final String msg) {
        new Thread() {
            public void run() {
                try {
                    get("/log?m=" + urlEncode(msg));
                } catch (Throwable e) {
                    // the Mac is unreachable; the on-screen alert is all we can do
                }
            }
        }.start();
    }

    // --- network and text helpers (static ones are checked by TestBBTube) ---

    /** Word-wraps text into at most maxLines lines of a pixel width; the last line ends in "..." if text is left over. */
    static String[] wrap(String s, Font f, int width, int maxLines) {
        Vector out = new Vector();
        int start = 0, n = s.length();
        while (start < n) {
            if (out.size() == maxLines - 1) {
                out.addElement(fit(s.substring(start), f, width));
                break;
            }
            int end = start, space = -1;
            while (end < n && f.substringWidth(s, start, end + 1 - start) <= width) {
                if (s.charAt(end) == ' ') space = end;
                end++;
            }
            if (end < n && space > start) end = space; // break at the last space that fits
            if (end == start) end++; // one character wider than the line
            out.addElement(s.substring(start, end).trim());
            start = end;
            while (start < n && s.charAt(start) == ' ') start++;
        }
        String[] a = new String[out.size()];
        out.copyInto(a);
        return a;
    }

    /** Fits text into a pixel width with an ellipsis, for the player's bars. */
    static String fit(String s, Font f, int width) {
        if (f.stringWidth(s) <= width) return s;
        int n = s.length();
        while (n > 0 && f.stringWidth(s.substring(0, n) + "...") > width) n--;
        return s.substring(0, n).trim() + "...";
    }

    /** "4:57" or "1:02:05" to seconds; 0 if it isn't a length (e.g. "live"). */
    static int seconds(String s) {
        int total = 0;
        String[] parts = split(s, ':');
        try {
            for (int i = 0; i < parts.length; i++) total = total * 60 + Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
        return total;
    }

    /** Seconds as "4:57" or "1:02:05". */
    static String clock(int s) {
        String mm = (s / 60 % 60 < 10 ? "0" : "") + s / 60 % 60, ss = (s % 60 < 10 ? "0" : "") + s % 60;
        return s >= 3600 ? s / 3600 + ":" + mm + ":" + ss : s / 60 + ":" + ss;
    }

    private byte[] get(String path) throws IOException {
        String errors = "";
        for (int i = Math.max(transport, 0); i < TRANSPORTS.length; i++) {
            HttpConnection c = null;
            int code;
            try {
                c = (HttpConnection) Connector.open("http://" + server + path + TRANSPORTS[i]);
                code = c.getResponseCode();
            } catch (Throwable e) { // IOException, or SecurityException when BlackBerry denies the connection permission
                close(c);
                errors += "\n[" + (TRANSPORTS[i].length() == 0 ? "default" : TRANSPORTS[i]) + "] " + e;
                if (transport >= 0) break; // the option that worked has stopped: report it rather than wander
                continue;
            }
            if (transport != i) {
                transport = i;
                if (errors.length() > 0) log("connected with [" + TRANSPORTS[i] + "] after:" + errors);
            }
            InputStream in = null;
            try {
                if (code != HttpConnection.HTTP_OK) throw new IOException("HTTP " + code + " from " + path);
                in = c.openInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                if (in != null) in.close();
                close(c);
            }
        }
        throw new IOException("Can't reach the Mac at " + server + errors);
    }

    private static void close(Connection c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            // already closed
        }
    }

    /** Percent-encodes everything but unreserved characters; ';' must never reach the URL, BlackBerry reads it as a connection option. */
    static String urlEncode(String s) throws IOException {
        byte[] b = s.getBytes("UTF-8");
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return out.toString();
    }

    static String[] lines(byte[] utf8) throws IOException {
        String[] all = split(new String(utf8, "UTF-8"), '\n');
        int n = all.length;
        while (n > 0 && all[n - 1].length() == 0) n--; // trailing newline
        String[] out = new String[n];
        System.arraycopy(all, 0, out, 0, n);
        return out;
    }

    static String[] split(String s, char sep) {
        Vector parts = new Vector();
        int start = 0;
        for (int i = s.indexOf(sep); i != -1; i = s.indexOf(sep, start)) {
            parts.addElement(s.substring(start, i));
            start = i + 1;
        }
        parts.addElement(s.substring(start));
        String[] out = new String[parts.size()];
        parts.copyInto(out);
        return out;
    }
}
