package android.eink;

import android.app.ActivityThread;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.graphics.Paint;
import android.os.SystemProperties;

/**
 * Global e-ink text contrast (VeraDroid: non-system apps' dark text drawn black, optional fake
 * bold). Canvas.drawText* / drawTextRun / drawTextOnPath call {@link #begin} / {@link #end} around the native draw:
 * dark-ish text colours (luminance up to persist.vd.tc.max, default 176) are drawn pure black (opaque when the
 * colour was at least half opaque, e.g. Material's 87 % primary text; fainter text keeps its alpha);
 * light text (on dark buttons) is left alone. Off by default.
 *   persist.vd.tc       0 = off, 1 = every non-system app, or a comma list of packages
 *   persist.vd.tc.bold  1 = also fake-bold the text of those apps
 *   persist.vd.tc.max   0..255 luminance limit
 * Read once per process, after the application is bound (zygote and system_server are never affected).
 * @hide
 */
public final class EinkText {
    private EinkText() { }

    // 0 = uninitialised, 1 = disabled (fast inline return without ARMv7 dmb barrier), -1 = enabled
    private static int sState;
    private static boolean sBold;
    private static int sMax = 176;

    private static final long CHANGED_COLOR = 1L << 32, CHANGED_BOLD = 1L << 33;

    private static int init() {
        String pkg = ActivityThread.currentPackageName();
        if (pkg == null) return 0;          // zygote preload / not bound yet: try again later
        Application app = ActivityThread.currentApplication();
        if (app == null) return 0;
        ApplicationInfo ai = app.getApplicationInfo();
        String v = SystemProperties.get("persist.vd.tc", "0");
        boolean on;
        if ("1".equals(v)) on = ai != null && (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        else on = !"0".equals(v) && EinkConfig.inList(v, pkg);
        sBold = on && SystemProperties.getBoolean("persist.vd.tc.bold", false);
        sMax = Math.max(0, Math.min(255, SystemProperties.getInt("persist.vd.tc.max", 176)));
        int st = on ? -1 : 1;
        sState = st;
        return st;
    }

    /** Before a text draw: fast no-barrier inline exit when disabled; otherwise darken / embolden. */
    public static long begin(Paint p) {
        int st = sState;
        if (st > 0) return 0;
        return beginSlow(p, st);
    }

    private static long beginSlow(Paint p, int st) {
        if (st == 0) {
            st = init();
            if (st >= 0) return 0;
        }
        if (p == null) return 0;
        long r = 0;
        int c = p.getColor();
        int a = c >>> 24;
        if (a != 0 && c != 0xff000000) {
            int lum = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29) >> 8;
            // Material "primary text" is black at 87 % alpha: mostly opaque text becomes fully opaque black too
            if (lum <= sMax) { p.setColor(a >= 0x80 ? 0xff000000 : c & 0xff000000); r = CHANGED_COLOR | (c & 0xffffffffL); }
        }
        if (sBold && !p.isFakeBoldText() && p.getStyle() == Paint.Style.FILL) { p.setFakeBoldText(true); r |= CHANGED_BOLD; }
        return r;
    }

    public static void end(Paint p, long r) {
        if (r == 0 || p == null) return;
        if ((r & CHANGED_COLOR) != 0) p.setColor((int) r);
        if ((r & CHANGED_BOLD) != 0) p.setFakeBoldText(false);
    }
}
