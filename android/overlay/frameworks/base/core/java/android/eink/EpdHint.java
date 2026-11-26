package android.eink;

import android.graphics.Rect;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.view.View;

import java.io.FileDescriptor;

/**
 * Per-frame e-ink update modes ("View.invalidate(mode)"), VeraDroid.
 *
 * Views ask for a mode for their next frame (invalidate(int), applyGCOnce, ...). ViewRootImpl.drawSoftware calls
 * {@link #onPost} right before it posts the frame; if a mode is pending (or the process has a default mode) one line
 * "H <c> x y w h" goes to the display bridge's FIFO, so veradroid-epd uses that mode and region for the frame that
 * follows (display/epd.c H). Transient modes go straight to the bridge: "FAST <ms>" (A2 / DU), "FREEZE <ms>"
 * (no panel update), "FULL" (one flashing refresh). Nothing is written when nobody asked for a mode, so apps that do
 * not use this API behave exactly as before. A bridge without these commands ignores the lines.
 *
 * Mode letters (the bridge's P / D / H): F full flash, G GC16, R REAGL, D DU / A2, A auto.
 * @hide
 */
public final class EpdHint {
    private static final String TAG = "EpdHint";
    private static final String FIFO = "/dev/kfb/epd.fifo";
    private static final long PENDING_MS = 1000;   // a request without a frame within this time is dropped

    // e-ink View constants (android.view.View.EINK_*): our own values, read back by apps through reflection
    public static final int WAVEFORM_MASK = 0x0f;
    public static final int WAVEFORM_INIT = 0x0, WAVEFORM_DU = 0x1, WAVEFORM_GC16 = 0x2, WAVEFORM_GC4 = 0x3,
            WAVEFORM_ANIM = 0x4, WAVEFORM_REAGL = 0x5, WAVEFORM_AUTO = 0x6;
    public static final int UPDATE_FULL = 0x10, WAIT = 0x20, AUTO_AUTOMATIC = 0x40, REAGL = 0x80, REAGLD = 0x100,
            ONYX_AUTO = 0x200, ONYX_GC = 0x400;

    private static final boolean LOG = SystemProperties.getBoolean("persist.vd.epdhint.log", false);
    private static final Object sLock = new Object();
    private static char sPending;                 // 0 = none
    private static final Rect sRect = new Rect(); // screen coordinates, empty = the posted region
    private static long sPendingAt;
    private static volatile char sDefault;        // process default (setDefaultUpdateMode), 0 = auto
    private static volatile boolean sFast;        // enableA2 / applyApplicationFastMode
    private static final int[] sLoc = new int[2];

    private EpdHint() { }

    /** Mode bits -> bridge letter. */
    public static char letter(int mode) {
        if (mode == 0) return 'A';
        if ((mode & (UPDATE_FULL | ONYX_GC)) != 0) return 'F';
        int wf = mode & WAVEFORM_MASK;
        if (wf == WAVEFORM_DU || wf == WAVEFORM_ANIM) return 'D';
        if (wf == WAVEFORM_GC16 || wf == WAVEFORM_GC4) return 'G';
        if (wf == WAVEFORM_REAGL || (mode & (REAGL | REAGLD)) != 0) return 'R';
        return 'A';
    }

    private static int rank(char c) {
        switch (c) { case 'F': return 5; case 'G': return 4; case 'R': return 3; case 'D': return 2; case 'A': return 1; default: return 0; }
    }

    /** Ask for mode c for the next frame; rect in screen coordinates or null (= whatever the frame updates). */
    public static void request(char c, Rect screen) {
        if (c == 0 || c == 'A') return;
        synchronized (sLock) {
            long now = SystemClock.uptimeMillis();
            if (sPending != 0 && now - sPendingAt > PENDING_MS) { sPending = 0; sRect.setEmpty(); }
            if (rank(c) > rank(sPending)) sPending = c;
            if (screen != null && !screen.isEmpty()) sRect.union(screen);
            sPendingAt = now;
        }
    }

    /** request() for a view (its on-screen bounds, or a rect inside it); call on the view's thread. */
    public static void requestView(View v, int mode, Rect local) {
        char c = letter(mode);
        if (c == 'A') return;
        Rect r = null;
        if (v != null && v.isAttachedToWindow()) {
            v.getLocationOnScreen(sLoc);
            if (local != null) {
                r = new Rect(local);
                r.offset(-v.getScrollX(), -v.getScrollY());
                if (!r.intersect(0, 0, v.getWidth(), v.getHeight())) return;
            } else {
                r = new Rect(0, 0, v.getWidth(), v.getHeight());
            }
            r.offset(sLoc[0], sLoc[1]);
        }
        request(c, r);
    }

    /** Called by ViewRootImpl.drawSoftware right before unlockCanvasAndPost. dirty: surface coordinates. */
    public static void onPost(Rect dirty, int winLeft, int winTop) {
        char c;
        Rect r;
        synchronized (sLock) {
            c = sPending;
            if (c != 0 && SystemClock.uptimeMillis() - sPendingAt > PENDING_MS) c = 0;
            if (c == 0) c = sFast ? 'D' : sDefault;
            if (c == 0 || c == 'A') { sPending = 0; sRect.setEmpty(); return; }
            r = new Rect(sRect);
            sPending = 0; sRect.setEmpty();
        }
        if (r.isEmpty() && dirty != null && !dirty.isEmpty()) { r.set(dirty); r.offset(winLeft, winTop); }
        if (r.isEmpty()) send("H " + c);
        else send("H " + c + " " + r.left + " " + r.top + " " + r.width() + " " + r.height());
    }

    public static void gcOnce() { request('F', null); }
    public static void setDefault(int mode) { char c = letter(mode); sDefault = c == 'A' ? 0 : c; }
    public static int getDefault() { char c = sDefault; return c == 'F' ? UPDATE_FULL | WAVEFORM_GC16 : c == 'G' ? WAVEFORM_GC16
            : c == 'D' ? WAVEFORM_DU : c == 'R' ? WAVEFORM_REAGL : 0; }
    public static boolean inFast() { return sFast; }

    /** A2 / fast mode for this process: DU on the panel until disabled (the bridge caps it at 10 minutes). */
    public static void fast(boolean on) {
        if (sFast == on) return;
        sFast = on;
        send(on ? "FAST 600000" : "FAST 0");
    }

    /** enableScreenUpdate(false): the panel stops updating (bridge cap 10 s); true: one update of what changed. */
    public static void freeze(boolean frozen) { send(frozen ? "FREEZE 10000" : "FREEZE 0"); }

    /** refreshScreen: redraw what is on the panel now, flashing for full / GC modes. */
    public static void refresh(int mode) { send(letter(mode) == 'F' ? "FULL" : "REDRAW"); }

    /** One line to the bridge; never blocks (no reader / full pipe: dropped). */
    public static void send(String line) {
        if (LOG) Log.i(TAG, line);
        FileDescriptor fd = null;
        try {
            fd = Os.open(FIFO, OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
            byte[] b = (line + "\n").getBytes();
            Os.write(fd, b, 0, b.length);
        } catch (Throwable t) {
            // bridge not running (ENXIO) or not a VeraDroid host: nothing to do
        } finally {
            if (fd != null) try { Os.close(fd); } catch (Throwable t) { }
        }
    }
}
