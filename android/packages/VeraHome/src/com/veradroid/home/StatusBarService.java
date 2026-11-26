// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Kindle 风格的状态栏：顶部一条很窄的白条，左边时间、右边电量，点一下打开下拉面板。
 * 只在“设置 → 桌面 → 状态栏”打开时显示；桌面和面板自己已经有顶栏，所以它们在前台时隐藏。
 * 用 TYPE_PHONE 覆盖在别的 App 上；需要 SYSTEM_ALERT_WINDOW（安装后用 appops 放行）。
 *
 * 让位：容器里没有 SystemUI，系统以为顶上没有状态栏，应用拿到的顶部 inset 是 0，这一条就盖在应用上面
 * （微信读书书架这类“沉浸式状态栏”页面：搜索框被时间压住）。所以另外加一个看不见、不接触摸的 TYPE_STATUS_BAR 窗口，
 * 系统就按真的状态栏给应用留出 status_bar_height（各机型按自己的密度换算）；这一条也用同样的高度，正好盖住让出来的那一截。
 * 全屏的应用（阅读页）系统自己把这个占位窗口藏起来，不让位，和以前一样只盖一条。
 */
public class StatusBarService extends Service {
    private static final String TAG = "VeraStatusBar";
    private static boolean legacySuppressed = false;   // 兼容旧调用方（无 owner）
    private static final java.util.Set<Object> suppressors = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());
    private WindowManager wm;
    private View bar;
    private View reserve;                       // 看不见的 TYPE_STATUS_BAR：只为让系统给应用留出顶部
    private static volatile boolean reserving;  // 占位窗口在：别的页面不用自己再让（Blocks.statusInset）
    private TextView clock, batt;
    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshNow();
            handler.postDelayed(this, toNextMinute());
        }
    };

    /** 系统的分钟广播 / 改时间 / 改时区：马上刷新（Kindle 睡醒后 uptime 计时的 tick 还要再等最多一分钟）。 */
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { handler.removeCallbacks(tick); tick.run(); }
    };
    private boolean timeReceiverOn;

    /** 时间只显示到分钟：在整分钟后一点点更新。以前固定每 2 分钟一次，时间最多慢 2 分钟（应用里看到 04:13，其实已经 04:14 多）。 */
    private static long toNextMinute() { return 60000 - System.currentTimeMillis() % 60000 + 300; }

    public static void start(Context c) { c.startService(new Intent(c, StatusBarService.class)); }
    public static void stop(Context c) { c.stopService(new Intent(c, StatusBarService.class)); }
    public static synchronized void setSuppressed(boolean v) { legacySuppressed = v; }
    /** 按页面持有者抑止状态栏：避免半屏/透明弹窗或页面切换时互相冲掉抑止态。 */
    public static synchronized void suppress(Object owner, boolean v) {
        if (owner == null) { legacySuppressed = v; return; }
        if (v) suppressors.add(owner);
        else suppressors.remove(owner);
    }
    /** 清除全部抑止持有者（如内置阅读器首帧就绪、需要立刻放出状态栏时）。 */
    static synchronized void releaseAll() {
        legacySuppressed = false;
        suppressors.clear();
    }
    private static synchronized boolean isSuppressed() { return legacySuppressed || !suppressors.isEmpty(); }
    public static void refresh(Context c) { c.startService(new Intent(c, StatusBarService.class)); }
    /** 系统按真的状态栏给应用留出顶部了（占位窗口加上了）。 */
    static boolean reserving() { return reserving; }

    /** 状态栏高度：系统的 status_bar_height（24dp，按各机型的显示密度换算），拿不到时 24dp。 */
    static int height(Context c) {
        try {
            android.content.res.Resources r = android.content.res.Resources.getSystem();
            int id = r.getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) { int h = r.getDimensionPixelSize(id); if (h > 0) return h; }
        } catch (Throwable ignored) { }
        return Ui.dp(c, 24);
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int flags, int startId) {
        boolean on = Store.prefs(this, "home").getBoolean("statusbar", true);
        if (!on) { hide(); stopSelf(); return START_NOT_STICKY; }
        refreshNow();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, toNextMinute());
        if (!timeReceiverOn) {
            IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
            tf.addAction(Intent.ACTION_TIME_CHANGED); tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            try { registerReceiver(timeReceiver, tf); timeReceiverOn = true; } catch (Throwable ignored) { }
        }
        return START_STICKY;
    }

    private boolean enabled() {
        return Store.prefs(this, "home").getBoolean("statusbar", true);
    }

    private void refreshNow() {
        if (!enabled() || isSuppressed()) { hide(); return; }
        if (bar != null && builtBlocks != Blocks.on(this)) hide();   // 界面风格换了：按新风格重搭这一条
        show();
        // 主题：statusbar.json 可以关掉时间/电量、改时间格式、在左边加一段文字
        org.json.JSONObject cfg = Theme.statusBar();
        if (clock != null) {
            String fmt = cfg.optString("clock_format", Ui.clockPattern(this));
            String now;
            try { now = new SimpleDateFormat(fmt, Locale.CHINA).format(new Date()); }
            catch (Throwable t) { now = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()); }
            String text = cfg.optString("text", "").trim();
            if (!cfg.optBoolean("clock", true)) now = "";
            String clockText = text.length() == 0 ? now : now.length() == 0 ? text : text + "   " + now;
            if (!clockText.equals(String.valueOf(clock.getText()))) clock.setText(clockText);
        }
        if (batt != null) batt.setVisibility(cfg.optBoolean("battery", true) ? View.VISIBLE : View.GONE);
        if (batt != null) {
            int p = -1;
            boolean charging = false;
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                if (bm != null) {
                    int raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (raw >= 0 && raw <= 100) p = raw;
                    charging = bm.isCharging();
                }
            } catch (Throwable ignored) { }
            if (p < 0) {
                try {
                    Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (bi != null) {
                        int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        if (lv >= 0 && sc > 0) p = Math.min(100, lv * 100 / sc);
                        int st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        charging = (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
                    }
                } catch (Throwable ignored) { }
            }
            String battText = p >= 0 ? p + "%" : "";
            if (!battText.equals(String.valueOf(batt.getText()))) batt.setText(battText);
            Stats.sampleBattery(this, p, charging);
        }
    }

    private boolean builtBlocks;
    private int builtHeight;

    /**
     * 显示密度变了（appprof.sh 给多看这类应用临时换密度）：系统按新密度的 status_bar_height 让位，
     * 这一条也按新高度重搭，不然让出来的那一截有一段没盖住（应用自己的状态栏底色露出来）。
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (bar == null || height(this) == builtHeight) return;
        removeBar();   // 只换这一条；占位窗口留着改高度（系统里只能有一个状态栏窗口，撤了马上再加会被拒）
        refreshNow();
    }

    private void show() {
        if (bar != null) return;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        builtBlocks = Blocks.on(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.WHITE);
        row.setPadding(dp(8), 0, dp(4), 0);
        clock = Ui.text(this, "", 13, true);
        batt = Ui.text(this, "", 11, false);
        // 不抗锯齿：状态栏每分钟只刷时钟那一小块，用的是 DU（只有黑白两级）+ 抖动。抗锯齿字边上的灰点被抖成斜纹，
        // 每分钟换一次图案，看起来就是“左上角斜着闪”（PW6 真机：每 60 s 一次 24x22 的 DU，就是分钟那一位）。纯黑白的字 DU 刷出来和原图一样。
        for (TextView tv : new TextView[] { clock, batt }) { tv.getPaint().setAntiAlias(false); tv.getPaint().setSubpixelText(false); }
        row.addView(clock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        row.addView(batt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        // 状态栏只有 20dp 高（现在是系统的 status_bar_height，24dp）。以前用 14dp 的线条箭头（﹀）：线只有 2 像素，墨水屏抖动后又淡又细，看起来像残缺的，
        // 还贴着屏幕右边。改成实心倒三角 ▼（14dp 宽、8dp 高），离右边 10dp；整行仍然都能点开面板。
        View chev = new View(this) {
            private final android.graphics.Paint fill = new android.graphics.Paint();   // 不抗锯齿，理由同上
            private final android.graphics.Path tri = new android.graphics.Path();
            @Override protected void onDraw(android.graphics.Canvas c) {
                float w = getWidth(), h = getHeight();
                fill.setColor(Color.BLACK);
                fill.setStyle(android.graphics.Paint.Style.FILL);
                tri.reset();
                tri.moveTo(0, 0);
                tri.lineTo(w, 0);
                tri.lineTo(w / 2f, h);
                tri.close();
                c.drawPath(tri, fill);
            }
        };
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(12), dp(6));
        cp.leftMargin = dp(10); cp.rightMargin = dp(10);
        if (builtBlocks) {
            // 方块风格：只有时间和电量（粗体），右边留出同样的边距；底下一条实心黑线（2 像素以上，不是会抖成虚线的 1 像素）
            Fonts.apply(batt, true);
            batt.setTextSize(12);
            batt.setPadding(0, 0, dp(8), 0);
        } else row.addView(chev, cp);
        // Click or short pull-down both open the panel.  There is no black
        // divider rule: on e-ink a 1px rule reads as a thick, broken line.
        row.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY(); dragging = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (e.getRawY() - downY > dp(8)) dragging = true;
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging || e.getRawY() - downY > dp(8)) openPanel();
                        else openPanel();
                        return true;
                }
                return false;
            }
        });
        int h = height(this);
        builtHeight = h;
        wrap.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, builtBlocks ? h - Blocks.stroke(this) : h));
        if (builtBlocks) wrap.addView(Ui.rule(this, Blocks.stroke(this), Color.BLACK));
        addReserve(h);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.windowAnimations = 0;
        lp.setTitle("VeraStatusBar");
        try {
            wm.addView(wrap, lp);
            bar = wrap;
        } catch (Throwable t) {
            Log.w(TAG, "cannot add status bar: " + t);
            bar = null;
            hide();   // 这一条没加上：占位窗口也撤掉，不然应用顶上空一截
        }
    }

    /**
     * 占位窗口：TYPE_STATUS_BAR（要 STATUS_BAR_SERVICE，平台签名有），透明、不接触摸、不拿焦点。
     * 系统里只能有一个状态栏窗口；容器里没有 SystemUI，所以这里能加上。加不上（老镜像 / 没有权限）就和以前一样只盖一条。
     */
    private void addReserve(int h) {
        if (reserve != null) {
            try {
                WindowManager.LayoutParams rl = (WindowManager.LayoutParams) reserve.getLayoutParams();
                if (rl.height != h) { rl.height = h; wm.updateViewLayout(reserve, rl); }
            } catch (Throwable ignored) { }
            return;
        }
        View v = new View(this);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSPARENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.windowAnimations = 0;
        lp.setTitle("VeraStatusReserve");
        try {
            wm.addView(v, lp);
            reserve = v;
            reserving = true;
        } catch (Throwable t) {
            Log.w(TAG, "status bar reserve not added (apps keep the full height): " + t);
        }
    }

    private void removeBar() {
        if (bar == null) return;
        try { wm.removeView(bar); } catch (Throwable ignored) { }
        bar = null;
    }

    private void hide() {
        removeBar();
        if (reserve != null) {
            // 马上撤（removeView 是异步的）：紧接着再 show 时系统里还有旧的状态栏窗口，新的会被拒
            try { wm.removeViewImmediate(reserve); } catch (Throwable ignored) { }
            reserve = null;
        }
        reserving = false;
    }

    private void openPanel() {
        Perf.begin();
        Panel.open(this);
    }

    private int dp(int v) { return Ui.dp(this, v); }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (timeReceiverOn) { try { unregisterReceiver(timeReceiver); } catch (Throwable ignored) { } timeReceiverOn = false; }
        hide();
        super.onDestroy();
    }
}
