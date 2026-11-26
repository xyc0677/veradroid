// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

/**
 * 传统“三大金刚键”导航栏：屏幕底部 返回 / 主页 / 多任务。
 * 设置 → 桌面 → 导航方式 里打开；prefs "navmode"：0 手势，1 三大金刚键，2 两者都要。
 *
 * 让位：窗口类型是 TYPE_NAVIGATION_BAR（要 STATUS_BAR_SERVICE，平台签名有），系统把它当成真的导航栏：
 * 放在底部、高度用系统的 navigation_bar_height（48dp，按各机型的显示密度换算），应用的显示区域和 inset 都按它算。
 * 以前用 overscan 让出底部：“沉浸式状态栏”的应用（微信读书书架：LAYOUT_FULLSCREEN + 自己画系统栏背景）
 * 会把这一截让两次（DecorView 按导航栏 inset 留一次，View 再按 overscan 留一次），按键条上面多出一截空白；
 * overscan 还会写进 display_settings.xml，桌面进程没走 onDestroy 就死了的话，底下一直空着一条。
 * 现在启动时把以前留下的 overscan 清掉；加不上 TYPE_NAVIGATION_BAR（老镜像）才退回 overscan 的老办法。
 */
public class NavBarService extends Service {
    private static final String TAG = "VeraNavBar";
    static final int HEIGHT_DP = 28;   // 24 → 28：三个键按得更准（用户反馈“有点小”）
    private static NavBarService instance;
    private WindowManager wm;
    private View bar;

    static int mode(Context c) { return Store.prefs(c, "home").getInt("navmode", 0); }

    static void apply(Context c) {
        Intent i = new Intent(c, NavBarService.class);
        if (mode(c) == 0) { c.stopService(i); clearStaleOverscan(c); } else c.startService(i);
    }

    private boolean overscanUsed;   // 退回了 overscan 的老办法（关掉时要还原）
    private int builtDpi;
    private int retries;
    private boolean destroyed;
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 显示密度变了（appprof.sh 的每应用密度）：按新密度重搭，三个图标跟着系统给的那一格缩放。 */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (bar instanceof android.widget.FrameLayout && c.densityDpi != builtDpi && !overscanUsed) {
            // 窗口不撤（系统里只能有一个导航栏窗口），只把里面的按键按新密度重搭
            builtDpi = c.densityDpi;
            android.widget.FrameLayout f = (android.widget.FrameLayout) bar;
            f.removeAllViews();
            f.addView(build(LinearLayout.LayoutParams.MATCH_PARENT));
            syncLockVisibility();
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        if (mode(this) == 0) { stopSelf(); return START_NOT_STICKY; }
        retries = 0;
        show();
        syncLockVisibility();
        return START_STICKY;
    }

    static void updateLockState() {
        NavBarService s = instance;
        if (s != null) s.syncLockVisibility();
    }

    private void syncLockVisibility() {
        if (bar != null) bar.setVisibility(LockScreen.showing() ? View.GONE : View.VISIBLE);
    }

    private void show() {
        if (bar != null || destroyed) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        builtDpi = getResources().getConfiguration().densityDpi;
        clearStaleOverscan(this);   // 老版本（overscan）留下的底边
        android.widget.FrameLayout sys = new android.widget.FrameLayout(this);
        sys.setBackgroundColor(Color.WHITE);
        sys.addView(build(LinearLayout.LayoutParams.MATCH_PARENT));
        // 高度 MATCH_PARENT：系统给导航栏的那一格（navigation_bar_height，换显示密度时跟着变）整格填满
        WindowManager.LayoutParams sl = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_NAVIGATION_BAR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING
                | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.OPAQUE);
        sl.gravity = Gravity.BOTTOM | Gravity.LEFT;   // 位置和大小系统自己定（PhoneWindowManager.layoutNavigationBar）
        sl.windowAnimations = 0;
        sl.setTitle("VeraNavBar");
        try {
            wm.addView(sys, sl);
            bar = sys;
            syncLockVisibility();
            return;
        } catch (Throwable t) {
            // 另一个导航栏窗口还在（上一个服务实例刚撤、还没撤完）：过一会儿再试，不退回 overscan
            if (String.valueOf(t.getMessage()).contains("already exists") && retries++ < 6) {
                Log.w(TAG, "nav bar window still there, retrying: " + t);
                retryHandler.postDelayed(new Runnable() { @Override public void run() { show(); } }, 700);
                return;
            }
            Log.w(TAG, "TYPE_NAVIGATION_BAR not allowed, falling back to overscan: " + t);
        }
        showOverscan();
    }

    /** 一条 高 h 的白条，三个键平分。 */
    private View build(int h) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Color.WHITE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(button(Icons.Kind.BACK, new Runnable() { @Override public void run() { key(KeyEvent.KEYCODE_BACK); } }),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        row.addView(button(Icons.Kind.HOME, new Runnable() { @Override public void run() { home(); } }),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        row.addView(button(Icons.Kind.SWITCH, new Runnable() { @Override public void run() { recents(); } }),
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        wrap.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h));
        return wrap;
    }

    /** 老办法：TYPE_PHONE + overscan 让出底部（加不上 TYPE_NAVIGATION_BAR 的老镜像）。 */
    private void showOverscan() {
        int h = Ui.dp(this, HEIGHT_DP);
        View wrap = build(h);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, h,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN,   // 底部这条已经让给 overscan，必须能画进去
            PixelFormat.OPAQUE);
        // 不能用 Gravity.BOTTOM：它参照的是已经被 overscan 缩小的应用区域，整条会往上偏，盖住应用的底部。
        // 按屏幕真实高度从顶部绝对定位，正好落在让出来的那一条里。
        android.util.DisplayMetrics real = new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(real);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.y = real.heightPixels - h;
        lp.windowAnimations = 0;
        lp.setTitle("VeraNavBar");
        try {
            setOverscanBottom(h);
            overscanUsed = true;
            wm.addView(wrap, lp);
            bar = wrap;
            syncLockVisibility();
        } catch (Throwable t) {
            Log.w(TAG, "cannot add nav bar: " + t);
            setOverscanBottom(0);
            overscanUsed = false;
        }
    }

    private View button(Icons.Kind k, final Runnable r) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[] { android.R.attr.state_pressed }, new android.graphics.drawable.ColorDrawable(Color.BLACK));
        b.setBackground(bg);
        b.addView(new Icons.IconView(this, k), new LinearLayout.LayoutParams(Ui.dp(this, 19), Ui.dp(this, 19)));
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!LockScreen.showing()) r.run(); } });
        return b;
    }

    private void key(int code) {
        if (LockScreen.showing()) return;
        try {
            InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
            Method m = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            long now = SystemClock.uptimeMillis();
            for (int action : new int[] { KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP }) {
                KeyEvent e = new KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY, InputDevice.SOURCE_KEYBOARD);
                m.invoke(im, e, 0);   // INJECT_INPUT_EVENT_MODE_ASYNC
            }
        } catch (Throwable t) { Log.w(TAG, "inject key failed: " + t); }
    }

    private void home() {
        if (LockScreen.showing()) return;
        try { startActivity(HomeActivity.homeIntent(this)); }
        catch (Throwable t) { Log.w(TAG, "home failed: " + t); }
    }

    private void recents() {
        if (LockScreen.showing()) return;
        try {
            Intent i = new Intent(this, SwitcherActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
        } catch (Throwable t) { Log.w(TAG, "recents failed: " + t); }
    }

    /**
     * 以前的版本用 overscan 让底边，值存在 /data/system/display_settings.xml 里，重启也还在。
     * 现在不用它了：底边还有 overscan 就清掉（只在不是 0 的时候设，设一次所有窗口要重新排一遍）。
     */
    static void clearStaleOverscan(Context c) {
        try {
            android.view.Display d = ((WindowManager) c.getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            Class<?> dic = Class.forName("android.view.DisplayInfo");
            Object info = dic.newInstance();
            android.view.Display.class.getMethod("getDisplayInfo", dic).invoke(d, info);
            int l = dic.getField("overscanLeft").getInt(info), t = dic.getField("overscanTop").getInt(info);
            int r = dic.getField("overscanRight").getInt(info), b = dic.getField("overscanBottom").getInt(info);
            if (b == 0) return;
            Log.i(TAG, "clearing stale overscan " + l + "," + t + "," + r + "," + b);
            Class<?> g = Class.forName("android.view.WindowManagerGlobal");
            Object wms = g.getMethod("getWindowManagerService").invoke(null);
            wms.getClass().getMethod("setOverscan", int.class, int.class, int.class, int.class, int.class)
                .invoke(wms, 0, l, t, r, 0);
        } catch (Throwable t) { Log.w(TAG, "overscan check failed: " + t); }
    }

    /** IWindowManager.setOverscan(display 0, 0, 0, 0, bottom): apps lay out above the bar instead of under it. */
    private void setOverscanBottom(int px) {
        try {
            Class<?> g = Class.forName("android.view.WindowManagerGlobal");
            Object wms = g.getMethod("getWindowManagerService").invoke(null);
            wms.getClass().getMethod("setOverscan", int.class, int.class, int.class, int.class, int.class)
                .invoke(wms, 0, 0, 0, 0, px);
        } catch (Throwable t) { Log.w(TAG, "setOverscan failed: " + t); }
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        destroyed = true;
        retryHandler.removeCallbacksAndMessages(null);
        if (bar != null) {
            try { wm.removeViewImmediate(bar); } catch (Throwable ignored) { }
            bar = null;
        }
        if (overscanUsed) { setOverscanBottom(0); overscanUsed = false; }
        super.onDestroy();
    }
}
