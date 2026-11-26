// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * 页面切换耗时（logcat 标签 VeraPerf）：从手指按下（或宿主手势到达）到新页面第一次画完。
 * 只记时间、只打一行日志；墨水屏波形的时间不在里面（那是显示桥之后的事）。
 *   adb: logcat -s VeraPerf
 */
final class Perf {
    private Perf() { }

    /** 最近一次按下的时间（uptimeMillis），或宿主手势 / 命令到达的时间。 */
    static volatile long start;

    static void touch(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) start = e.getEventTime();
    }

    static void begin() { start = SystemClock.uptimeMillis(); }

    /**
     * 冷启动（开机后进程第一次起来）：从进程创建到主页第一次真正画出来（图标都在）用了多久。
     * 只在进程刚起来的那一次 onCreate 里有意义；进程活着时 Activity 重建不算。
     */
    static void coldStart(final View root) {
        final long proc = android.os.Process.getStartUptimeMillis();
        final long created = SystemClock.uptimeMillis();
        if (created - proc > 60000) return;
        whenAttached(root, new Runnable() { @Override public void run() {
        root.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            private boolean done;
            @Override public void onDraw() {
                if (done) return;
                done = true;
                long now = SystemClock.uptimeMillis();
                Log.i("VeraPerf", "home cold start: process->first draw " + (now - proc) + " ms, onCreate->first draw " + (now - created) + " ms");
                final ViewTreeObserver.OnDrawListener self = this;
                root.post(new Runnable() { @Override public void run() {
                    try { root.getViewTreeObserver().removeOnDrawListener(self); } catch (Throwable ignored) { }
                } });
            }
        });
        } });
    }

    /** 控件还没挂到窗口上时，它的 ViewTreeObserver 是临时的（挂上时不一定带上监听）：挂上以后再跑 r。 */
    static void whenAttached(final View v, final Runnable r) {
        if (v.isAttachedToWindow()) { r.run(); return; }
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) { x.removeOnAttachStateChangeListener(this); r.run(); }
            @Override public void onViewDetachedFromWindow(View x) { }
        });
    }

    /** 在 what 这个页面开始搭建时调用：下一次 root 画完时打一行。 */
    static void measure(final View root, final String what) {
        if (root == null) return;
        final long built = SystemClock.uptimeMillis();
        final long from = start;
        if (!root.isAttachedToWindow()) {
            // 还没挂到窗口上（Activity 刚 setContentView）：这时的 ViewTreeObserver 是临时的，挂上时不会带上 OnDrawListener
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { v.removeOnAttachStateChangeListener(this); listen(root, what, built, from); }
                @Override public void onViewDetachedFromWindow(View v) { }
            });
            return;
        }
        listen(root, what, built, from);
    }

    private static final java.util.WeakHashMap<View, ViewTreeObserver.OnDrawListener> PENDING = new java.util.WeakHashMap<View, ViewTreeObserver.OnDrawListener>();

    private static void listen(final View root, final String what, final long built, final long from) {
        synchronized (PENDING) {
            ViewTreeObserver.OnDrawListener old = PENDING.remove(root);
            if (old != null) {
                try { root.getViewTreeObserver().removeOnDrawListener(old); } catch (Throwable ignored) { }
            }
        }
        final ViewTreeObserver.OnDrawListener l = new ViewTreeObserver.OnDrawListener() {
            private boolean done;
            @Override public void onDraw() {
                if (done) return;
                done = true;
                synchronized (PENDING) { PENDING.remove(root); }
                long now = SystemClock.uptimeMillis();
                Log.i("VeraPerf", what + ": build->draw " + (now - built) + " ms"
                    + (from > 0 && now - from < 10000 ? ", input->draw " + (now - from) + " ms" : ""));
                final ViewTreeObserver.OnDrawListener self = this;
                root.post(new Runnable() { @Override public void run() {
                    try { root.getViewTreeObserver().removeOnDrawListener(self); } catch (Throwable ignored) { }
                } });
            }
        };
        synchronized (PENDING) { PENDING.put(root, l); }
        root.getViewTreeObserver().addOnDrawListener(l);
    }
}
