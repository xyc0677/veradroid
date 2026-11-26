/*
 * VeraDroid: per-app e-ink settings inside each app process.
 *
 * The host merges its per-app table (built-in appprofiles.conf, the user's settings.apps and the global settings.conf)
 * into /dev/kfb/appoec, one line per package:
 *     <package>[*] KEY=value;KEY=value        (trailing * = prefix rule; "*" alone = global defaults)
 * Lookup for a package: the "*" line, then the longest prefix rule, then the exact line, key by key.
 *
 * Keys (all integers; missing / 0 = Android's normal behaviour):
 *   OEC_DPI    display density for this app only: 1 = 320 x screen width / 1080 (what 多看 computes), 100..640 = that dpi.
 *              DENSITY=auto1080 / DENSITY=<n> (the host's older built-in key) means the same. Read once per process.
 *              Also the process default density (DENSITY_DEVICE / Bitmap default), image property ro.vd.oec=2.
 *   OEC_FLING  1 = no fling animation: a fling jumps straight to where it would have stopped (OverScroller / Scroller)
 *   OEC_SMOOTH 1 = no smooth-scroll animation (startScroll with duration 0: smoothScrollBy / smoothScrollToPosition)
 *   OEC_PAGEKEY 1 = page keys act as volume keys, 2 = page keys become a swipe (x 50 %, y 80 -> 20 % / 20 -> 80 %, 600 ms)
 *   OEC_TOAST  1 = toasts black on white with a border
 *   OEC_LIGHTBAR 1 = white system bar backgrounds, dark status bar icons
 *   OEC_DLGBORDER 1 = a black border around floating dialogs
 *   OEC_WBOLD  1 = WebView: bold text           OEC_WBLACK 1 = WebView: black text
 *   OEC_WFONT  WebView text zoom in percent (50..300)
 *   OEC_WCSS   1 = WebView: also inject /dev/kfb/oec/<package>.css
 *
 * Nothing here runs unless the file exists and has a rule for the package; system_server never calls init().
 * The file is read at bindApplication and read again (on activity resume) only when its modification time changes.
 */
package com.android.internal.vd;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/** @hide */
public final class OecConfig {
    private static final String TAG = "VdOec";
    public static final String FILE = "/dev/kfb/appoec";
    private static final String CSS_DIR = "/dev/kfb/oec/";

    private static String sPackage;
    private static long sMtime = -1;
    private static long sLen = -1;
    private static final Object sLock = new Object();
    private static final Object sSwipeToken = new Object();
    private static Handler sSwipeHandler;
    private static View sSwipeDecor;
    private static long sSwipeDown;
    private static float sSwipeX, sSwipeY;
    private static boolean sSwipeActive;

    /** density wanted for this process: 0 none, 1 auto, else dpi; fixed at the first read (a running app keeps it). */
    private static int sDpiRule = -1;
    private static int sDpiResolved;   // the dpi actually applied (0 = none yet / none)

    // read by hot paths without a lock
    public static volatile boolean sNoFling, sNoSmooth;
    public static volatile int sPageKey;
    public static volatile boolean sToast, sLightBar, sDialogBorder;
    public static volatile boolean sWebBold, sWebBlack, sWebCss;
    public static volatile int sWebFont;
    private static volatile String sCustomCss;

    private OecConfig() { }

    /** ActivityThread.handleBindApplication, before any resources of the app are made. */
    public static void init(String pkg) {
        if (pkg == null) return;
        synchronized (sLock) {
            sPackage = pkg;
            reloadLocked(true);
        }
    }

    /** Activity resume: pick up changes the user made in the settings page (one stat when nothing changed). */
    public static void refresh() {
        if (sPackage == null) return;
        android.os.StrictMode.ThreadPolicy p = android.os.StrictMode.allowThreadDiskReads();
        try {
            File f = new File(FILE);
            long m = f.lastModified();
            long l = f.length();
            if (m == sMtime && l == sLen) return;
            synchronized (sLock) { reloadLocked(false); }
        } finally {
            android.os.StrictMode.setThreadPolicy(p);
        }
    }

    public static boolean active() { return sPackage != null; }

    private static void reloadLocked(boolean first) {
        File f = new File(FILE);
        long m = f.lastModified();
        long l = f.length();
        sMtime = m;
        sLen = l;
        Map<String, Integer> v = new HashMap<String, Integer>();
        if (m != 0) {
            try {
                lookup(f, sPackage, v);
            } catch (Throwable t) {
                Log.w(TAG, "reading " + FILE + ": " + t);
            }
        }
        if (sDpiRule < 0) {
            int d = get(v, "OEC_DPI", -1);
            if (d < 0) d = get(v, "DENSITY", 0);
            sDpiRule = (d == 1 || (d >= 100 && d <= 640)) ? d : 0;
        }
        sNoFling = get(v, "OEC_FLING", 0) == 1;
        sNoSmooth = get(v, "OEC_SMOOTH", 0) == 1;
        sPageKey = get(v, "OEC_PAGEKEY", 0);
        sToast = get(v, "OEC_TOAST", 0) == 1;
        sLightBar = get(v, "OEC_LIGHTBAR", 0) == 1;
        sDialogBorder = get(v, "OEC_DLGBORDER", 0) == 1;
        sWebBold = get(v, "OEC_WBOLD", 0) == 1;
        sWebBlack = get(v, "OEC_WBLACK", 0) == 1;
        int z = get(v, "OEC_WFONT", 0);
        sWebFont = (z >= 50 && z <= 300) ? z : 0;
        sWebCss = get(v, "OEC_WCSS", 0) == 1;
        sCustomCss = sWebCss ? readSmall(CSS_DIR + sPackage + ".css", 16384) : null;
        if (!v.isEmpty()) Log.i(TAG, sPackage + (first ? " start " : " update ") + v);
    }

    private static int get(Map<String, Integer> v, String k, int def) {
        Integer i = v.get(k);
        return i == null ? def : i;
    }

    /** "*" line, then (unless exact is "-") the longest matching prefix rule, then the exact line, key by key. */
    private static void lookup(File f, String pkg, Map<String, Integer> out) throws java.io.IOException {
        String global = null, prefix = null, exact = null;
        int best = -1;
        BufferedReader r = new BufferedReader(new FileReader(f), 8192);
        try {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 4000) {
                int sp = line.indexOf(' ');
                if (sp <= 0 || line.charAt(0) == '#') continue;
                String k = line.substring(0, sp);
                String spec = line.substring(sp + 1).trim();
                if (k.equals("*")) global = spec;
                else if (k.equals(pkg)) exact = spec;
                else if (k.endsWith("*")) {
                    String pre = k.substring(0, k.length() - 1);
                    if (pkg.startsWith(pre) && pre.length() > best) { best = pre.length(); prefix = spec; }
                }
            }
        } finally {
            r.close();
        }
        parse(global, out);
        if ("-".equals(exact)) return;
        parse(prefix, out); parse(exact, out);
    }

    private static void parse(String spec, Map<String, Integer> out) {
        if (spec == null) return;
        for (String kv : spec.split(";")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).trim(), val = kv.substring(eq + 1).trim();
            if (!(k.startsWith("OEC_") || k.equals("DENSITY"))) continue;
            if (val.equals("auto1080")) { out.put(k, 1); continue; }
            try { out.put(k, Integer.parseInt(val)); } catch (NumberFormatException ignored) { }
        }
    }

    private static String readSmall(String path, int max) {
        File f = new File(path);
        if (!f.isFile() || f.length() > max) return null;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            try {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                return sb.toString();
            } finally { r.close(); }
        } catch (Throwable t) { return null; }
    }

    // ---------------------------------------------------------------- 1. per-app density

    /** dpi for this process, given the natural screen size in pixels; 0 = no override. */
    private static int dpiFor(int w, int h) {
        if (sDpiRule <= 0) return 0;
        if (sDpiResolved > 0) return sDpiResolved;
        int d = sDpiRule;
        if (d == 1) {
            int side = Math.min(w, h);
            if (side < 400) return 0;          // no real size known yet
            d = 320 * side / 1080;             // rounded down, like 多看 does (PW6 1272 -> 376)
        }
        if (d < 100 || d > 640) return 0;
        sDpiResolved = d;
        Log.i(TAG, sPackage + ": density " + d);
        return d;
    }

    /** ResourcesImpl.updateConfiguration: a copy of config with this app's density (or config itself). */
    public static Configuration adjustConfiguration(Configuration config, DisplayMetrics metrics) {
        if (config == null || sDpiRule <= 0) return config;
        int d = dpiFor(metrics != null ? metrics.widthPixels : 0, metrics != null ? metrics.heightPixels : 0);
        int old = config.densityDpi;
        if (d == 0 || old == d || old == Configuration.DENSITY_DPI_UNDEFINED || old <= 0) return config;
        Configuration c = new Configuration(config);
        c.densityDpi = d;
        c.screenWidthDp = scaleDp(c.screenWidthDp, old, d);
        c.screenHeightDp = scaleDp(c.screenHeightDp, old, d);
        c.smallestScreenWidthDp = scaleDp(c.smallestScreenWidthDp, old, d);
        c.compatScreenWidthDp = scaleDp(c.compatScreenWidthDp, old, d);
        c.compatScreenHeightDp = scaleDp(c.compatScreenHeightDp, old, d);
        c.compatSmallestScreenWidthDp = scaleDp(c.compatSmallestScreenWidthDp, old, d);
        return c;
    }

    private static int scaleDp(int dp, int from, int to) {
        if (dp <= 0) return dp;   // UNDEFINED (0) stays
        return (int) ((long) dp * from / to);
    }

    /**
     * ActivityThread.updateDefaultDensity: the process default density (DisplayMetrics.DENSITY_DEVICE, Bitmap's default
     * density) is the app's dpi too, as it was with the global switch. Bitmaps made without a density (多看 draws its
     * reading page into one) are otherwise 300 dpi and get scaled up by 376 / 300 when drawn on the app's canvas.
     */
    public static int defaultDensity(int dpi) {
        if (sDpiRule <= 0 || dpi <= 0 || dpi == Configuration.DENSITY_DPI_UNDEFINED) return dpi;
        if (sDpiResolved <= 0) {
            try {   // resolves the auto rule from the real screen size (adjustDisplayInfo)
                android.hardware.display.DisplayManagerGlobal.getInstance().getDisplayInfo(android.view.Display.DEFAULT_DISPLAY);
            } catch (Throwable t) {
                Log.w(TAG, "display info: " + t);
            }
        }
        return sDpiResolved > 0 ? sDpiResolved : dpi;
    }

    /** DisplayManagerGlobal.getDisplayInfo: the app sees its own density in Display.getMetrics() too. */
    public static void adjustDisplayInfo(DisplayInfo info) {
        if (info == null || sDpiRule <= 0) return;
        int d = dpiFor(info.logicalWidth, info.logicalHeight);
        if (d > 0) info.logicalDensityDpi = d;
    }

    /**
     * AbsListView.smoothScrollToPosition* with OEC_SMOOTH: jump instead of the position scroller (it steps a screen per frame).
     * offset < 0: only make the item visible (nothing to do when it already is), else put it offset pixels from the top.
     */
    public static boolean jumpToPosition(android.widget.AbsListView l, int position, int offset) {
        if (!sNoSmooth || l == null) return false;
        if (offset < 0) {
            int first = l.getFirstVisiblePosition(), last = l.getLastVisiblePosition();
            if (position >= first && position <= last) return true;
            offset = 0;
        }
        l.setSelectionFromTop(position, offset);
        return true;
    }

    // ---------------------------------------------------------------- 3. toast, dialog border, light bars, page keys

    /** Toast.TN.handleShow: black text on white with a thin black border (the default toast is white on dark grey). */
    public static void styleToast(View v) {
        if (!sToast || v == null) return;
        try {
            View m = v.findViewById(com.android.internal.R.id.message);
            if (!(m instanceof TextView)) return;
            ((TextView) m).setTextColor(Color.BLACK);
            ((TextView) m).setShadowLayer(0, 0, 0, 0);
            float dp = v.getResources().getDisplayMetrics().density;
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.WHITE);
            g.setStroke(Math.max(2, Math.round(1.5f * dp)), Color.BLACK);
            g.setCornerRadius(6 * dp);
            v.setBackground(g);
        } catch (Throwable t) {
            Log.w(TAG, "toast: " + t);
        }
    }

    /** Dialog.show, before the window is added: a black frame around the dialog's content (floating windows only). */
    public static void styleDialog(Window w, View decor) {
        if (!sDialogBorder || w == null || decor == null || !w.isFloating()) return;
        try {
            View c = decor.findViewById(com.android.internal.R.id.content);
            if (!(c instanceof FrameLayout)) return;
            float dp = decor.getResources().getDisplayMetrics().density;
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.TRANSPARENT);
            g.setStroke(Math.max(2, Math.round(1.5f * dp)), Color.BLACK);
            ((FrameLayout) c).setForeground(g);
        } catch (Throwable t) {
            Log.w(TAG, "dialog: " + t);
        }
    }

    /** PhoneWindow: colour of a system bar background. */
    public static int barColor(int color) { return sLightBar ? Color.WHITE : color; }

    /** PhoneWindow / DecorView: system UI visibility flags with the dark-icons flag when light bars are forced. */
    public static int barFlags(int vis) { return sLightBar ? (vis | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : vis; }

    /**
     * DecorView.dispatchKeyEvent: page keys per app. Returns the event to dispatch (maybe a volume key instead of the page
     * key), or null when the key was consumed (turned into a swipe).
     */
    public static KeyEvent mapPageKey(View decor, KeyEvent e) {
        int mode = sPageKey;
        if (mode == 0 || e == null) return e;
        int code = e.getKeyCode();
        boolean next;
        if (code == KeyEvent.KEYCODE_PAGE_DOWN) next = true;
        else if (code == KeyEvent.KEYCODE_PAGE_UP) next = false;
        else return e;
        if (mode == 1) {
            return new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
                    next ? KeyEvent.KEYCODE_VOLUME_DOWN : KeyEvent.KEYCODE_VOLUME_UP, e.getRepeatCount(),
                    e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
        }
        if (mode == 2) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) swipe(decor, next);
            return null;
        }
        return e;
    }

    /** A finger swipe in the middle of the window: next page = from 80 % up to 20 %, previous = 20 % down to 80 %. */
    private static void swipe(final View decor, boolean next) {
        final int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        if (sSwipeHandler == null) sSwipeHandler = new Handler(Looper.getMainLooper());
        if (sSwipeActive) {
            sSwipeHandler.removeCallbacksAndMessages(sSwipeToken);
            if (sSwipeDecor != null) {
                long now = SystemClock.uptimeMillis();
                send(sSwipeDecor, sSwipeDown, Math.max(now, sSwipeDown + 1), MotionEvent.ACTION_UP, sSwipeX, sSwipeY);
            }
            sSwipeActive = false;
            sSwipeDecor = null;
        }
        final float x = w * 0.5f, y0 = h * (next ? 0.8f : 0.2f), y1 = h * (next ? 0.2f : 0.8f);
        final long down = SystemClock.uptimeMillis();
        final int steps = 6;
        final long total = 200;
        sSwipeActive = true;
        sSwipeDecor = decor;
        sSwipeDown = down;
        sSwipeX = x;
        sSwipeY = y0;
        send(decor, down, down, MotionEvent.ACTION_DOWN, x, y0);
        for (int i = 1; i <= steps; i++) {
            final int k = i;
            sSwipeHandler.postAtTime(new Runnable() {
                @Override public void run() {
                    if (!sSwipeActive || sSwipeDown != down) return;
                    float y = y0 + (y1 - y0) * k / steps;
                    long t = down + total * k / steps;
                    sSwipeY = y;
                    send(decor, down, t, MotionEvent.ACTION_MOVE, x, y);
                    if (k == steps) {
                        send(decor, down, t + 8, MotionEvent.ACTION_UP, x, y);
                        sSwipeActive = false;
                        sSwipeDecor = null;
                    }
                }
            }, sSwipeToken, down + total * k / steps);
        }
    }

    private static void send(View decor, long down, long t, int action, float x, float y) {
        MotionEvent ev = MotionEvent.obtain(down, t, action, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { decor.dispatchTouchEvent(ev); } catch (Throwable th) { Log.w(TAG, "swipe: " + th); }
        ev.recycle();
    }

    // ---------------------------------------------------------------- 4. WebView CSS

    public static boolean webActive() { return sWebBold || sWebBlack || sWebFont > 0 || sCustomCss != null; }

    /** The CSS for this app (null = nothing to inject). */
    public static String webCss() {
        StringBuilder sb = new StringBuilder();
        if (sWebBold) sb.append("*{font-weight:bold!important}");
        if (sWebBlack) sb.append("*{color:#000!important}");
        String c = sCustomCss;
        if (c != null) sb.append(c);
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Called when a page is visible / finished: text zoom and a style element (same id each time: no duplicates). */
    public static void injectWeb(WebView view) {
        if (view == null) return;
        try {
            if (sWebFont > 0 && view.getSettings().getTextZoom() != sWebFont) view.getSettings().setTextZoom(sWebFont);
            String css = webCss();
            if (css == null) return;
            String js = "(function(){var d=document,id='vd-oec-css',s=d.getElementById(id);if(!s){s=d.createElement('style');"
                    + "s.id=id;(d.head||d.documentElement).appendChild(s);}s.textContent=" + jsString(css) + ";})()";
            view.evaluateJavascript(js, null);
        } catch (Throwable t) {
            Log.w(TAG, "web: " + t);
        }
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '<': sb.append("\\x3c"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }
}
