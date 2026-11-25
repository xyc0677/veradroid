// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.Handler;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 下拉面板，照 Kindle 的“快捷操作”：从顶边下划（或点状态栏、点主页顶栏的快捷设置）出来一张盖住上半屏的面板。
 * 第一屏只有三样：一排圆形开关、亮度条、色温条。墨水屏特有的东西（刷新方式、整屏刷新、对比度、翻页动画、字号、
 * 当前应用的显示效果）收在“显示”一行后面；其余（传书、休眠、切换应用、清理后台、关机）收在“更多”后面；通知在右上角的铃铛里。
 * 这三页是在面板里原地换内容，面板的大小不变，所以每次只是面板这一块刷一次。
 *
 * 面板是一个悬浮窗口（和状态栏一样由本进程直接加到 WindowManager 上），不是 Activity：
 * 以前是 Activity，每次打开都要走一遍“暂停下面的应用 → 切任务 → 恢复面板 → 建窗口”，面板自己画得再快，
 * 从手势到出帧也要多出一百多毫秒；关掉时下面的应用还要被恢复一次（有的应用会因此重画甚至重新加载）。
 * 现在窗口一直挂着，打开只是把它设成可见、按宿主状态文件核对一遍（变了的控件才改），下面的应用不知道面板来过。
 * 打开面板本身不写任何设置，也不发会改状态的命令。
 */
final class Panel {
    private static Panel instance;

    /** 打开面板（已经开着就什么都不做）。任何线程以外的地方都从主线程调。 */
    /** 打开面板时前台的 activity（包名/类名，不算 PanelActivity）。前台换成别的 activity（按了 HOME、切了应用、开了新页面）就收起面板。 */
    static volatile String openedOver;
    static volatile long lastOpenAt;
    static boolean justOpened() { return isOpen() && android.os.SystemClock.uptimeMillis() - lastOpenAt < 650; }
    private static final java.util.concurrent.ExecutorService TOP_EXEC = java.util.concurrent.Executors.newSingleThreadExecutor(
        new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "panel-top"); t.setDaemon(true); return t; }
        });

    static void onForeground(String act) {   // HostService 调（主线程）：前台的 activity 变了
        if (act == null || act.endsWith("/com.veradroid.home.PanelActivity")) return;
        if (openedOver != null && !act.equals(openedOver) && isOpen()) { android.util.Log.i("VeraHome", "panel: foreground " + act + " (opened over " + openedOver + ") - closing"); close(); }
    }

    static void prewarm(Context c) {
        try {
            if (Blocks.on(c)) BlocksPanel.prewarm(c);
            else {
                Context app = c.getApplicationContext();
                if (instance == null) instance = new Panel(new android.view.ContextThemeWrapper(app, android.R.style.Theme_Holo_Light_NoActionBar));
                instance.warmAttach();
            }
        } catch (Throwable t) { android.util.Log.w("VeraHome", "panel prewarm: " + t); }
    }

    static void open(Context c) {
        final Context app = c.getApplicationContext();
        lastOpenAt = android.os.SystemClock.uptimeMillis();
        if (HostService.fgAct != null && !HostService.fgAct.endsWith("/com.veradroid.home.PanelActivity")) {
            openedOver = HostService.fgAct;
        }
        /* Query AMS topActivity off the UI thread so ActivityManager lock contention can never stall panel first draw */
        TOP_EXEC.execute(new Runnable() {
            @Override public void run() {
                if (!isOpen()) return;
                String top = HostService.topActivity(app);
                if (top != null && !top.endsWith("/com.veradroid.home.PanelActivity") && isOpen()) openedOver = top;
            }
        });
        if (Blocks.on(c)) { if (instance != null && instance.showing) instance.finish(); BlocksPanel.open(c); return; }
        BlocksPanel.close();
        String conf = QUICK_DEFAULT, name = "";
        try { android.content.SharedPreferences sp = Store.prefs(app, "home"); conf = sp.getString("quick", QUICK_DEFAULT); name = sp.getString("panelname", ""); } catch (Throwable ignored) { }
        // 设置里改了快捷按钮 / 面板名称：常驻的那份是按旧配置搭的，丢掉重搭
        if (instance != null && !(conf + "|" + name).equals(instance.builtConf)) { instance.destroy(); instance = null; }
        if (instance == null) instance = new Panel(new android.view.ContextThemeWrapper(app, android.R.style.Theme_Holo_Light_NoActionBar));
        instance.open();
    }

    private static final android.os.Handler DEFER_H = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable DEFER_CLOSE = new Runnable() { @Override public void run() { close(); } };
    static void close() { DEFER_H.removeCallbacks(DEFER_CLOSE); openedOver = null; if (instance != null) instance.finish(); BlocksPanel.close(); }
    static void closeDeferred() { openedOver = null; DEFER_H.removeCallbacks(DEFER_CLOSE); DEFER_H.postDelayed(DEFER_CLOSE, 750); }
    static void closeOnDraw(final View root) {
        if (!isOpen()) return;
        if (root == null) { close(); return; }
        Perf.whenAttached(root, new Runnable() { @Override public void run() {
            root.postOnAnimation(DEFER_CLOSE);
            root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    close();
                    return true;
                }
            });
        } });
    }
    static boolean isOpen() { return (instance != null && instance.showing) || BlocksPanel.isOpen(); }

    private final Context ctx;
    private final WindowManager wm;
    private Frame frame;
    private boolean attached, showing;

    /** 面板的最外层：收返回键；点到面板下面（窗口以外）就收起。 */
    private final class Frame extends LinearLayout {
        Frame() { super(ctx); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) { if (current != P_MAIN) show(P_MAIN); else finish(); }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                || (e.getActionMasked() == MotionEvent.ACTION_DOWN && (e.getY() < 0 || e.getY() > getHeight()))) { finish(); return true; }
            Perf.touch(e);
            return super.dispatchTouchEvent(e);
        }
    }

    private final Handler handler = new Handler();
    private LinearLayout root, notes;
    private TextView clock, info, title, contrastValue, xferLine, cleanLabel, offLabel;
    private View backBtn, bellBtn;
    private LevelBar light, warm;
    private View lightRow, warmRow, sleepRow;
    private Check autoLight;
    private final LinearLayout[] pages = new LinearLayout[4];
    private static final int P_MAIN = 0, P_DISPLAY = 1, P_MORE = 2, P_NOTES = 3;
    private static final String[] PAGE_TITLE = { "", "显示", "更多", "通知" };
    private int current = P_MAIN;
    private final boolean[] built = new boolean[4];

    // 第一屏的圆形开关（设置里可以自己挑 3 到 6 个、排顺序，键 quick）：id -> 开关
    private final java.util.Map<String, Round> quick = new java.util.LinkedHashMap<String, Round>();
    static final String[] QUICK_ID = { "wifi", "bt", "invert", "clear", "settings", "clean", "xfer", "shot", "kindle", "anim", "switch", "restart", "stop", "lightf", "home" };
    static final String[] QUICK_NAME = { "Wi-Fi", "蓝牙", "反色", "清残影", "全部设置", "清后台", "传书", "截图", "回 Kindle", "翻页动画", "切换应用", "重启", "关机", "浅色过滤", "回桌面" };
    /** 浅色过滤（宿主 KFB_LIGHTF）：比阈值浅的颜色变纯白（epd.c build_lut）；深色加深（KFB_DARKEN）：其余部分再压深一档 */
    static final String[] LIGHTF_L = { "关", "弱", "中", "强" };
    static final String[] LIGHTF_V = { "0", "1", "2", "3" };
    static final String LIGHTF_DESC = "比一定亮度浅的颜色直接变成纯白：去掉浅灰、米色、浅绿的底色和半透明的灰色蒙层，抖动噪点和大面积变化少了，闪刷少、字更清楚。"
        + "弱：只去接近白的底色；中：一般的浅灰 / 护眼绿底；强：连中浅灰也去掉（浅灰的字和图会丢）。";
    static final String DARKEN_DESC = "没有被滤成白的部分再压深一档：浅灰的字变黑，更清楚；图片会偏暗。";
    static int lightfLast = 2;       // 快捷开关重新打开时用的档位（上次用过的，默认“中”）
    /** 默认这一排和 Kindle 的一样是五个：联网、蓝牙、深色、（同步换成）清残影、全部设置。没有蓝牙的机型不显示蓝牙。 */
    static final String QUICK_DEFAULT = "wifi,bt,invert,clear,settings,clean";   // 方块面板最多 5 个：有蓝牙的机型第 6 个“清后台”不显示

    private String panelName = "", builtConf = "";
    private boolean btOk;            // 宿主说这台机器有 Kindle 的蓝牙栈（status 里 btsupported=1）
    private int btOn = 0;
    private Seg mode, flash, sleep, font;
    private Icons.Toggle animToggle, xferToggle;
    private int wifiOn = 1, invertOn = 0;
    private int contrastIdx = 1;
    private int lightfOn = 0;        // 宿主的 KFB_LIGHTF（全局）：0 = 关
    private int animOn = 0;          // 宿主的 KFB_ANIM_MODE：0 = 关，1–5 = 开（跟随手势 / 左右上下滑）
    private int animLast = 1;        // 上次打开时用的模式，重新打开时恢复它（不再一律改成 1）
    private String seenStatus = null;  // 上次画面板时 status + settings 的原文：没变就不再画一遍（墨水屏）
    private boolean offArmed;

    private static final String[] CONTRAST = { "1.0", "1.3", "1.6", "1.9", "2.2" };
    private static final String[] MODE_V = { "0", "1", "2" };
    private static final String[] FLASH_V = { "0", "1", "3", "6", "8" };   // 8 是宿主默认值，必须在表里
    private static final int[] SLEEP_S = { 300, 600, 1800, 3600 };
    private static final float[] FONT_S = { 0.85f, 1.0f, 1.15f, 1.3f };

    private Panel(Context c) {
        ctx = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        btOk = BluetoothActivity.supported();

        frame = new Frame();
        frame.setOrientation(LinearLayout.VERTICAL);
        root = frame;
        root.setBackgroundColor(Color.WHITE);

        // 顶上一行：时间、日期 / 电量（第一屏）或“‹ 标题”（其余几页）；右边通知和收起
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(6), dp(8), 0);
        backBtn = Icons.bar(ctx, Icons.Kind.BACK, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { show(P_MAIN); }
        });
        backBtn.setVisibility(View.GONE);
        top.addView(backBtn);
        title = Ui.text(ctx, "", 22, true);
        title.setVisibility(View.GONE);
        top.addView(title);
        clock = Ui.text(ctx, "", 26, true);
        clock.setPadding(dp(8), 0, 0, 0);
        top.addView(clock);
        info = Ui.text(ctx, "", 14, false);
        info.setPadding(dp(12), 0, 0, 0);
        info.setSingleLine(true);
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bellBtn = Icons.bar(ctx, Icons.Kind.BELL, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { show(P_NOTES); }
        });
        top.addView(bellBtn);
        top.addView(Icons.bar(ctx, Icons.Kind.CHEV_U, 28, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        root.addView(top);

        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), 0, dp(18), 0);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        for (int i = 0; i < pages.length; i++) {
            LinearLayout p = new LinearLayout(ctx);
            p.setOrientation(LinearLayout.VERTICAL);
            p.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            content.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pages[i] = p;
        }
        // 面板底边：一条粗黑线，和下面的应用明确分开
        root.addView(Ui.rule(ctx, dp(2), Color.BLACK));

        // 只搭第一屏；其余三页第一次点到时才搭
        build(P_MAIN);
    }

    /** 高度固定为半屏多一点：换到“显示”“更多”时面板不变大变小。 */
    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.56),
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,      // 比状态栏那条（TYPE_PHONE）高一层，盖在它上面，不用先把它撤掉
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.windowAnimations = 0;
        lp.setTitle("VeraPanel");
        return lp;
    }

    // 面板打开 / 收起时的一次性显示提示（epd.c HINT）：这段时间里变化超过屏幕 PANEL_HINT_PCT % 的帧用 REAGL，不用 DU
    static final int PANEL_HINT_MS = 1200, PANEL_HINT_PCT = 20;

    private void warmAttach() {
        if (attached || showing) return;
        paintClock();
        readStatus();
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int h = (int) (dm.heightPixels * 0.52);
            frame.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                          View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            frame.layout(0, 0, frame.getMeasuredWidth(), frame.getMeasuredHeight());
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888);
            frame.draw(new android.graphics.Canvas(b));
            b.recycle();
        } catch (Throwable ignored) { }
        frame.setVisibility(View.GONE);
        try { wm.addView(frame, params()); attached = true; }
        catch (Throwable t) { android.util.Log.w("VeraHome", "panel prewarm: " + t); }
    }

    private void open() {
        if (showing) return;
        showing = true;
        HostLink.epdHint(PANEL_HINT_MS, PANEL_HINT_PCT);
        if (attached) Perf.measure(root, "panel (reused)");
        paintClock();
        readStatus();
        if (!attached) {
            Perf.measure(root, "panel");
            try { wm.addView(frame, params()); attached = true; }
            catch (Throwable t) { android.util.Log.w("VeraHome", "panel: " + t); showing = false; return; }
        } else {
            frame.setVisibility(View.VISIBLE);
        }
        HostLink.cmd("status");   // 只是请宿主把状态文件写新（电量、亮度），不改任何设置
        handler.removeCallbacks(recheck);
        handler.postDelayed(recheck, 700);
        // 宿主写状态文件要读十来个 lipc 属性，慢的机器上（KT6 模拟器）超过 700 ms：再看一次，不然亮度条要到下次打开才出来
        handler.postDelayed(recheck, 2500);
    }

    /** 收起：窗口留着，只是不可见；趁这时换回第一屏，下次出来第一帧就是对的。 */
    private void finish() {
        if (!showing) return;
        showing = false;
        handler.removeCallbacks(recheck);
        HostLink.epdHint(PANEL_HINT_MS, PANEL_HINT_PCT);
        frame.setVisibility(View.GONE);
        if (current != P_MAIN) show(P_MAIN);
    }

    private void destroy() {
        handler.removeCallbacksAndMessages(null);
        showing = false;
        if (attached) { try { wm.removeViewImmediate(frame); } catch (Throwable ignored) { } attached = false; }
    }

    private void startActivity(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { ctx.startActivity(i); } catch (Throwable t) { android.util.Log.w("VeraHome", "panel start: " + t); }
    }

    /** 面板里的一句提示（面板不是 Activity，弹不了我们的提示框）：写在顶上那行小字的位置。 */
    private void hint(String s) { set(info, s); seenStatus = null; }

    /** 宿主 700 ms 内写回新的状态；只有内容真的变了才再画（一般不变）。 */
    private final Runnable recheck = new Runnable() { @Override public void run() { readStatus(); } };

    // ------------------------------------------------------------------ small widgets

    /** 圆形开关：一个圆圈里一个图标，下面一行小字；开 = 黑底白图标。没有按下状态（墨水屏上那是多出来的一帧）。 */
    private final class Round extends LinearLayout {
        private final Icons.IconView icon;
        private final View disc;
        private boolean on;

        Round(Icons.Kind kind, String caption, View.OnClickListener l) {
            super(ctx);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout d = new LinearLayout(ctx);
            d.setGravity(Gravity.CENTER);
            icon = new Icons.IconView(ctx, kind);
            icon.setDuplicateParentStateEnabled(false);
            d.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            disc = d;
            addView(d, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView t = Ui.text(ctx, caption, 13, false);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(0, dp(4), 0, 0);
            addView(t);
            paint();
            setClickable(true);
            setOnClickListener(l);
            Ui.fast(this);
        }

        private void paint() {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(on ? Color.BLACK : Color.WHITE);
            g.setStroke(2, Color.BLACK);
            disc.setBackground(g);
            icon.setInverted(on);
        }

        void set(boolean v) { if (v != on) { on = v; paint(); } }
    }

    /** 方框打勾的复选框，自己画（系统的 CheckBox 带灰色和动画）。 */
    private final class Check extends View {
        private boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Check() { super(ctx); }
        void set(boolean v) { if (v != on) { on = v; invalidate(); } }
        boolean get() { return on; }
        @Override protected void onDraw(Canvas c) {
            int s = Math.min(getWidth(), getHeight());
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            c.drawRect(1, 1, s - 1, s - 1, p);
            if (on) {
                p.setStrokeWidth(Math.max(3, s / 8f));
                c.drawLine(s * 0.22f, s * 0.52f, s * 0.43f, s * 0.74f, p);
                c.drawLine(s * 0.43f, s * 0.74f, s * 0.80f, s * 0.26f, p);
            }
        }
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(5), 0, dp(5));
        return r;
    }

    /** 一行“名称 …… 控件”。 */
    private LinearLayout labeled(String name, View control, int controlWeightPercent) {
        LinearLayout r = row();
        TextView t = Ui.text(ctx, name, 16, false);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 100 - controlWeightPercent));
        if (controlWeightPercent > 0) r.addView(control, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, controlWeightPercent));
        else r.addView(control);
        return r;
    }

    /** 一行可以点进去的入口：名称、右边一句说明和 ›。 */
    private View nav(String name, String hint, View.OnClickListener l) {
        LinearLayout r = row();
        r.setPadding(0, dp(12), 0, dp(12));
        r.addView(Ui.text(ctx, name, 18, true));
        TextView h = Ui.text(ctx, hint, 14, false);
        h.setGravity(Gravity.END);
        h.setSingleLine(true);
        h.setPadding(dp(12), 0, dp(4), 0);
        r.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(new Icons.IconView(ctx, Icons.Kind.CHEV_R), new LinearLayout.LayoutParams(dp(20), dp(20)));
        r.setClickable(true);
        r.setOnClickListener(l);
        Ui.fast(r);
        return r;
    }

    private LevelBar slider(LinearLayout parent, Icons.Kind lo, Icons.Kind hi, final String cmd) {
        LinearLayout r = row();
        final LevelBar bar = new LevelBar(ctx);
        bar.setListener(new LevelBar.Listener() {
            @Override public void onCommit(int v) { HostLink.cmd(cmd + " " + v); }
        });
        // 两端的小 / 大图标就是 − / +：点一下走一格
        r.addView(Icons.bar(ctx, lo, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { bar.setValue(bar.getValue() - 1); HostLink.cmd(cmd + " " + bar.getValue()); }
        }));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(34), 1);
        bp.leftMargin = dp(4); bp.rightMargin = dp(4);
        r.addView(bar, bp);
        r.addView(Icons.bar(ctx, hi, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { bar.setValue(bar.getValue() + 1); HostLink.cmd(cmd + " " + bar.getValue()); }
        }));
        parent.addView(r);
        return bar;
    }

    private View bigButton(String label, View.OnClickListener l) {
        TextView t = Ui.text(ctx, label, 16, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(11), dp(6), dp(11));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(2, Color.BLACK);
        g.setCornerRadius(dp(6));
        t.setBackground(g);
        t.setClickable(true);
        t.setOnClickListener(l);
        Ui.fast(t);
        return t;
    }

    private void addCell(LinearLayout r, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (r.getChildCount() > 0) lp.leftMargin = dp(8);
        r.addView(v, lp);
    }

    // ------------------------------------------------------------------ pages

    private void build(int i) {
        if (built[i]) return;
        built[i] = true;
        switch (i) {
        case P_MAIN: buildMain(pages[i]); break;
        case P_DISPLAY: buildDisplay(pages[i]); break;
        case P_MORE: buildMore(pages[i]); break;
        default: buildNotes(pages[i]); break;
        }
    }

    /** 在面板里原地换一页：标题行和内容同一帧换。 */
    private void show(int idx) {
        Perf.measure(root, "panel page " + idx);
        boolean fresh = !built[idx];
        build(idx);
        current = idx;
        boolean main = idx == P_MAIN;
        backBtn.setVisibility(main ? View.GONE : View.VISIBLE);
        title.setVisibility(main ? View.GONE : View.VISIBLE);
        title.setText(PAGE_TITLE[idx]);
        clock.setVisibility(main ? View.VISIBLE : View.GONE);
        info.setVisibility(main ? View.VISIBLE : View.INVISIBLE);
        bellBtn.setVisibility(idx == P_NOTES ? View.INVISIBLE : View.VISIBLE);
        for (int i = 0; i < pages.length; i++) pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        if (fresh) { seenStatus = null; readStatus(); }   // 新搭的控件按当前状态填一次
        if (idx == P_DISPLAY) readLocal();
        if (idx == P_NOTES) loadNotes();
        if (idx == P_MORE && offArmed) { offArmed = false; offLabel.setText("关机"); }
    }

    private void buildMain(LinearLayout p) {
        String conf = QUICK_DEFAULT;
        try {
            android.content.SharedPreferences sp = Store.prefs(ctx, "home");
            conf = sp.getString("quick", QUICK_DEFAULT);
            panelName = sp.getString("panelname", "");
        } catch (Throwable ignored) { }
        builtConf = conf + "|" + panelName;
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(10), 0, dp(8));
        int n = 0;
        for (String raw : conf.split(",")) {
            final String id = raw.trim();
            int k = -1;
            for (int i = 0; i < QUICK_ID.length; i++) if (QUICK_ID[i].equals(id)) k = i;
            if (k < 0 || quick.containsKey(id) || n >= 6) continue;
            if ("bt".equals(id) && !btOk) continue;   // 这台机器没有蓝牙（或宿主太旧，不能在这里配对）
            Round t = new Round(quickIcon(id), QUICK_NAME[k], new View.OnClickListener() {
                @Override public void onClick(View v) { quickAction(id); }
            });
            if ("wifi".equals(id)) t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { finish(); WifiActivity.open(ctx); return true; }
            });
            if ("bt".equals(id)) t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    finish();
                    startActivity(new Intent(ctx, BluetoothActivity.class));
                    return true;
                }
            });
            quick.put(id, t);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            n++;
        }
        if (n > 0) p.addView(r);

        light = slider(p, Icons.Kind.SUN_S, Icons.Kind.SUN_L, "light");
        lightRow = (View) light.getParent();
        // 自动亮度：有光线传感器的机型（宿主状态里 haslightauto=1）才显示，挂在亮度条那一行的最右边
        autoLight = new Check();
        final LinearLayout auto = new LinearLayout(ctx);
        auto.setOrientation(LinearLayout.HORIZONTAL);
        auto.setGravity(Gravity.CENTER_VERTICAL);
        auto.setPadding(dp(8), dp(6), 0, dp(6));
        auto.addView(autoLight, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView at = Ui.text(ctx, "自动", 15, false);
        at.setPadding(dp(6), 0, 0, 0);
        auto.addView(at);
        auto.setClickable(true);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { autoLight.set(!autoLight.get()); HostLink.cmd("lightauto " + (autoLight.get() ? 1 : 0)); }
        });
        Ui.fast(auto);
        auto.setVisibility(View.GONE);
        ((LinearLayout) lightRow).addView(auto);
        autoLight.setTag(auto);
        warm = slider(p, Icons.Kind.WARM, Icons.Kind.WARM, "warmth");
        warmRow = (View) warm.getParent();

        p.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout go = row();
        go.setPadding(0, dp(4), 0, dp(8));
        addCell(go, bigButton("回到桌面", new View.OnClickListener() {
            @Override public void onClick(View v) { openHome(false); }
        }));
        addCell(go, bigButton("回到 Kindle", new View.OnClickListener() {
            @Override public void onClick(View v) { HostLink.cmd("exit"); finish(); }
        }));
        p.addView(go);
        p.addView(Ui.rule(ctx, 1, Color.BLACK));
        p.addView(nav("显示", "刷新方式、对比度、翻页动画、字号", new View.OnClickListener() {
            @Override public void onClick(View v) { show(P_DISPLAY); }
        }));
        p.addView(Ui.rule(ctx, 1, Color.BLACK));
        p.addView(nav("更多", "传书、休眠、切换应用、关机", new View.OnClickListener() {
            @Override public void onClick(View v) { show(P_MORE); }
        }));
    }

    private static Icons.Kind quickIcon(String id) {
        if ("wifi".equals(id)) return Icons.Kind.WIFI;
        if ("bt".equals(id)) return Icons.Kind.BLUETOOTH;
        if ("invert".equals(id)) return Icons.Kind.INVERT;
        if ("clear".equals(id)) return Icons.Kind.REFRESH;
        if ("xfer".equals(id)) return Icons.Kind.UPLOAD;
        if ("shot".equals(id)) return Icons.Kind.CAMERA;
        if ("kindle".equals(id)) return Icons.Kind.EXIT;
        if ("home".equals(id)) return Icons.Kind.HOME;
        if ("clean".equals(id)) return Icons.Kind.CLEAN;
        if ("anim".equals(id)) return Icons.Kind.SWITCH;
        if ("lightf".equals(id)) return Icons.Kind.EYE;
        if ("settings".equals(id)) return Icons.Kind.GEAR;
        if ("switch".equals(id)) return Icons.Kind.GRID;
        if ("restart".equals(id)) return Icons.Kind.REFRESH;
        return Icons.Kind.POWER;
    }

    private void quickAction(String id) {
        if ("wifi".equals(id)) { wifiOn = wifiOn == 1 ? 0 : 1; HostLink.cmd("wifi " + wifiOn); paintToggles(); }
        else if ("bt".equals(id)) toggleBt();
        else if ("invert".equals(id)) { invertOn = invertOn == 1 ? 0 : 1; HostLink.cmd("invert"); paintToggles(); }
        else if ("clear".equals(id)) {
            // 先收起面板、等它从屏幕上下去（约 0.5 秒）再整屏刷新：以前面板还在时就刷，刷完收起面板又在那块留下残影
            finish();
            handler.postDelayed(new Runnable() { @Override public void run() { HostLink.cmd("clear"); } }, 500);
        }
        else if ("xfer".equals(id)) toggleXfer();
        else if ("shot".equals(id)) screenshot();
        else if ("kindle".equals(id)) { HostLink.cmd("exit"); finish(); }
        else if ("home".equals(id)) openHome(false);
        else if ("anim".equals(id)) toggleAnim();
        else if ("lightf".equals(id)) { lightfOn = lightfOn > 0 ? 0 : lightfLast; HostLink.settings("KFB_LIGHTF=" + lightfOn + "\n"); paintToggles(); }
        else if ("settings".equals(id)) { closeDeferred(); SettingsActivity.open(ctx); }   // 盖在当前应用上面，返回回到它
        else if ("switch".equals(id)) { closeDeferred(); startActivity(new Intent(ctx, SwitcherActivity.class)); }
        else if ("restart".equals(id)) { HostLink.cmd("restart"); finish(); }
        else if ("stop".equals(id)) HostLink.cmd("stop");
        else if ("clean".equals(id)) { final String prev = Cleaner.previousApp(ctx); finish(); cleanNow(ctx, prev); }
    }

    /** 面板的“清后台”：除了前台那个，后台的用户应用都停掉，在屏幕下方提示清了几个、多出多少内存。 */
    static void cleanNow(final Context c, String front) {
        Cleaner.run(c, front, new Cleaner.Done() { @Override public void done(int stopped, long freedMb) {
            VeraToast.show(c, stopped == 0 ? "后台没有可以清理的应用" : "已清理 " + stopped + " 个应用，释放约 " + freedMb + " MB");
        } });
    }

    private void openHome(boolean settings) {
        closeDeferred();
        Intent i = HomeActivity.homeIntent(ctx);
        if (settings) i.putExtra("settings", true);
        startActivity(i);
    }

    /** 蓝牙归 Kindle 管（Android 里没有蓝牙服务）。点一下只是开关（和 Wi-Fi 方块一样，用户反馈 PW6 1.01：点了进设置页不符合直觉），
     *  命令经宿主开关 Kindle 的蓝牙（bt.sh 的 enable / disable，打开最多约 15 秒）；配对、按键映射在长按打开的“蓝牙翻页器”页里。 */
    private void toggleBt() {
        btOn = btOn == 1 ? 0 : 1;
        HostLink.cmd(btOn == 1 ? "bt enable" : "bt disable");
        paintToggles();
        VeraToast.show(ctx, btOn == 1 ? "正在打开蓝牙…（长按进入配对）" : "正在关闭蓝牙…");
    }

    private void toggleAnim() {
        animOn = animOn > 0 ? 0 : animLast;
        HostLink.settings("KFB_ANIM_MODE=" + animOn + "\n");
        paintToggles();
    }

    private void toggleXfer() {
        Transfer t = Transfer.get(ctx);
        if (t.running()) t.stop(); else if (!t.start()) VeraToast.show(ctx, "传输没打开：" + t.lastError());
        HostLink.cmd("status");
        paintToggles();
        handler.postDelayed(new Runnable() { @Override public void run() { paintToggles(); } }, 900);
    }

    private void screenshot() {
        // 先收起面板再截，不然截到的是面板自己
        finish();
        handler.postDelayed(new Runnable() { @Override public void run() { HostLink.cmd("screenshot"); } }, 1200);
    }

    /** “显示”：墨水屏相关的显示设置。全部即时生效。 */
    private void buildDisplay(LinearLayout p) {
        mode = new Seg(ctx, new String[] { "均衡", "灰度最准", "最快" }, 15, new Seg.Listener() {
            @Override public void onPick(int i) { HostLink.settings("KFB_MODE=" + MODE_V[i] + "\n"); }
        });
        p.addView(labeled("刷新方式", mode, 76));
        flash = new Seg(ctx, new String[] { "不全刷", "每页", "3 页", "6 页", "8 页" }, 15, new Seg.Listener() {
            @Override public void onPick(int i) { HostLink.settings("KFB_FLASH=" + FLASH_V[i] + "\n"); }
        });
        p.addView(labeled("整屏刷新", flash, 76));
        font = new Seg(ctx, new String[] { "小", "标准", "大", "特大" }, 15, new Seg.Listener() {
            @Override public void onPick(int i) { AppFont.setUser(ctx, FONT_S[i]); }
        });
        p.addView(labeled("应用字号", font, 76));
        LinearLayout cr = new LinearLayout(ctx);
        cr.setOrientation(LinearLayout.HORIZONTAL);
        cr.setGravity(Gravity.CENTER_VERTICAL);
        cr.addView(Icons.bar(ctx, Icons.Kind.MINUS, 24, new View.OnClickListener() {
            @Override public void onClick(View v) { setContrast(contrastIdx - 1); }
        }));
        contrastValue = Ui.text(ctx, CONTRAST[contrastIdx], 18, true);
        contrastValue.setGravity(Gravity.CENTER);
        cr.addView(contrastValue, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        cr.addView(Icons.bar(ctx, Icons.Kind.PLUS, 24, new View.OnClickListener() {
            @Override public void onClick(View v) { setContrast(contrastIdx + 1); }
        }));
        LinearLayout two = row();
        two.addView(Ui.text(ctx, "对比度", 16, false));
        two.addView(cr);
        two.addView(new View(ctx), new LinearLayout.LayoutParams(0, 1, 1));
        two.addView(Ui.text(ctx, "翻页动画", 16, false));
        animToggle = new Icons.Toggle(ctx, animOn > 0, new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) { animOn = on ? animLast : 0; HostLink.settings("KFB_ANIM_MODE=" + animOn + "\n"); paintToggles(); }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(10);
        two.addView(animToggle, tp);
        p.addView(two);
        p.addView(new View(ctx), new LinearLayout.LayoutParams(1, 0, 1));
        p.addView(Ui.rule(ctx, 1, Color.BLACK));
        // 每个应用单独一套显示效果：针对打开面板前正在用的那个应用
        p.addView(nav("当前应用的优化", "刷新、全刷、对比度、字号，只对这一个应用生效", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String pkg = Cleaner.previousApp(ctx);
                if (pkg == null) { hint("现在前台是桌面：长按桌面上的应用图标，选“应用优化”"); return; }
                String label = pkg;
                try { label = String.valueOf(ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0))); } catch (Throwable ignored) { }
                Intent i = new Intent(ctx, AppDisplayActivity.class);
                i.putExtra("pkg", pkg);
                i.putExtra("label", label);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(i);
                finish();
            }
        }));
    }

    private void buildMore(LinearLayout p) {
        LinearLayout xr = row();
        xr.addView(Ui.text(ctx, "传书", 16, false));
        xferLine = Ui.text(ctx, "", 14, true);
        xferLine.setPadding(dp(12), 0, dp(8), 0);
        xferLine.setSingleLine(true);
        xr.addView(xferLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        xferToggle = new Icons.Toggle(ctx, Transfer.get(ctx).running(), new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) { toggleXfer(); }
        });
        xr.addView(xferToggle);
        p.addView(xr);
        sleep = new Seg(ctx, new String[] { "5 分", "10 分", "30 分", "1 小时" }, 15, new Seg.Listener() {
            @Override public void onPick(int i) { HostLink.cmd("sleeptime " + SLEEP_S[i]); }
        });
        sleepRow = labeled("自动休眠", sleep, 76);
        p.addView(sleepRow);

        LinearLayout go = row();
        addCell(go, bigButton("桌面", new View.OnClickListener() { @Override public void onClick(View v) { openHome(false); } }));
        addCell(go, bigButton("切换应用", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(ctx, SwitcherActivity.class)); finish(); }
        }));
        addCell(go, bigButton("截图", new View.OnClickListener() { @Override public void onClick(View v) { screenshot(); } }));
        p.addView(go);
        LinearLayout go2 = row();
        cleanLabel = (TextView) bigButton("清理后台", new View.OnClickListener() {
            @Override public void onClick(View v) {
                cleanLabel.setText("清理中…");
                String prev = Cleaner.previousApp(ctx);   // 打开面板前正在用的应用，清理时保留
                Cleaner.run(ctx, prev, new Cleaner.Done() {
                    @Override public void done(int stopped, long freedMb) {
                        cleanLabel.setText(stopped == 0 ? "没有后台应用" : "已清理 " + stopped + " 个");
                    }
                });
            }
        });
        addCell(go2, cleanLabel);
        addCell(go2, bigButton("回 Kindle", new View.OnClickListener() { @Override public void onClick(View v) { HostLink.cmd("exit"); finish(); } }));
        offLabel = (TextView) bigButton("关机", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!offArmed) { offArmed = true; offLabel.setText("再点一次关机"); return; }
                HostLink.cmd("stop");
            }
        });
        addCell(go2, offLabel);
        p.addView(go2);
        // 优化机器状态（和方块面板同一套：清后台 → 提速 → 省电）；结果写在按钮上
        final TextView opt = (TextView) bigButton("优化机器状态（清后台 · 提速 · 省电）", null);
        opt.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            opt.setText("正在优化…");
            Optimizer.run(ctx, Cleaner.previousApp(ctx), new Optimizer.Done() { @Override public void done(Optimizer.Result r) {
                StringBuilder sb = new StringBuilder(r.summary());
                for (Optimizer.Suggestion s : r.tips) sb.append("\n建议：").append(s.text);
                opt.setText(sb.toString());
            } });
        } });
        LinearLayout go3 = row();
        addCell(go3, opt);
        p.addView(go3);
    }

    private void buildNotes(LinearLayout p) {
        notes = new LinearLayout(ctx);
        notes.setOrientation(LinearLayout.VERTICAL);
        PageScrollView sv = new PageScrollView(ctx);
        sv.addView(notes);
        p.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        p.addView(bigButton("全部清除", new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (StatusBarNotification n : NotifService.active()) {
                    NotifService s = NotifService.instance;
                    if (s != null && n.isClearable()) s.cancelNotification(n.getKey());
                }
                loadNotes();
            }
        }));
    }

    // ------------------------------------------------------------------ state

    private void setContrast(int idx) {
        int next = Math.max(0, Math.min(CONTRAST.length - 1, idx));
        if (next == contrastIdx) return;
        contrastIdx = next;
        contrastValue.setText(CONTRAST[contrastIdx]);
        HostLink.settings("KFB_CONTRAST=" + CONTRAST[contrastIdx] + "\n");
    }

    private static void set(TextView v, String t) { if (v != null && !t.equals(String.valueOf(v.getText()))) v.setText(t); }
    private static void vis(View v, boolean on) { if (v != null && (v.getVisibility() == View.VISIBLE) != on) v.setVisibility(on ? View.VISIBLE : View.GONE); }

    private void paintClock() { set(clock, Ui.clock(ctx)); }

    /** 应用字号是 Android 这边的设置，进“显示”页时才读。 */
    private void readLocal() {
        if (font == null) return;
        float fs = 1.0f;
        try { fs = Settings.System.getFloat(ctx.getContentResolver(), Settings.System.FONT_SCALE, 1.0f); } catch (Throwable ignored) { }
        int best = 0;
        for (int i = 0; i < FONT_S.length; i++) if (Math.abs(FONT_S[i] - fs) < Math.abs(FONT_S[best] - fs)) best = i;
        font.select(best);
    }

    /** 按宿主的状态文件和设置文件核对控件。只读不写；和上次看到的一样就直接返回。 */
    private long seenStamp = -1;
    private void readStatus() {
        // 两个文件都没被改写过（看修改时间和长度）：连读都不用读。打开面板的路上只做这一步检查。
        java.io.File f1 = new java.io.File(HostLink.CTL + "status"), f2 = new java.io.File(HostLink.KFB + "settings");
        long stamp = f1.lastModified() * 31 + f1.length() * 7 + f2.lastModified() * 17 + f2.length() + System.currentTimeMillis() / 86400000L;
        if (stamp == seenStamp && seenStatus != null) return;
        seenStamp = stamp;
        Map<String, String> st = HostLink.status(ctx);
        Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");
        String key = new java.util.TreeMap<String, String>(st).toString() + new java.util.TreeMap<String, String>(cfg).toString();
        if (key.equals(seenStatus)) return;
        seenStatus = key;
        StringBuilder sb = new StringBuilder(new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(new Date()));
        if (st.get("battery") != null) {
            sb.append("  电量 ").append(st.get("battery")).append("%");
            if ("1".equals(st.get("charging"))) sb.append("（充电中）");
        }
        set(info, (panelName.length() > 0 ? panelName + "  ·  " : "") + sb.toString());
        int max = HostLink.num(st, "lightmax", 24);
        // Older hosts do not send these flags; treat missing as "present".
        vis(lightRow, HostLink.num(st, "haslight", 1) == 1);
        vis(warmRow, HostLink.num(st, "haswarmth", 1) == 1);
        vis((View) autoLight.getTag(), HostLink.num(st, "haslightauto", 0) == 1);
        autoLight.set(HostLink.num(st, "lightauto", 0) == 1);
        if (light.getMax() != max) light.setMax(max);
        if (light.getValue() != HostLink.num(st, "light", 0)) light.setValue(HostLink.num(st, "light", 0));
        if (warm.getMax() != max) warm.setMax(max);
        if (warm.getValue() != HostLink.num(st, "warmth", 0)) warm.setValue(HostLink.num(st, "warmth", 0));
        wifiOn = HostLink.num(st, "wifi", 1);
        invertOn = HostLink.num(st, "invert", 0);
        btOn = HostLink.num(st, "bton", 0);
        try { animOn = Integer.parseInt(cfg.get("KFB_ANIM_MODE")); } catch (Throwable ignored) { animOn = 0; }
        if (animOn > 0) animLast = animOn;
        lightfOn = Math.max(0, Math.min(3, HostLink.num(cfg, "KFB_LIGHTF", 0)));
        if (lightfOn > 0) lightfLast = lightfOn;
        paintToggles();
        if (mode != null) pick(mode, MODE_V, cfg.get("KFB_MODE"), 0);
        if (flash != null) pick(flash, FLASH_V, cfg.get("KFB_FLASH"), 0);
        String c = cfg.get("KFB_CONTRAST");
        contrastIdx = nearest(CONTRAST, c, 1);
        set(contrastValue, CONTRAST[contrastIdx]);
        if (sleep != null) {
            vis(sleepRow, HostLink.num(st, "hassleeptime", 1) == 1);
            int sl = HostLink.num(st, "sleep", 600), bi = 1;
            for (int i = 0; i < SLEEP_S.length; i++) if (Math.abs(SLEEP_S[i] - sl) < Math.abs(SLEEP_S[bi] - sl)) bi = i;
            sleep.select(bi);
        }
    }

    private void pick(Seg s, String[] values, String cur, int def) {
        s.select(nearest(values, cur, def));
    }

    /** 表里没有的值（设置页的整屏刷新还有 10 / 20 页，网页的对比度还有 1.5 / 2.0 / 2.5）选最接近的一项，不再退回第一项“不全刷”。 */
    private static int nearest(String[] values, String cur, int def) {
        int idx = def;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) return i;
        try {
            double v = Double.parseDouble(cur), best = Double.MAX_VALUE;
            for (int i = 0; i < values.length; i++) {
                double d = Math.abs(Double.parseDouble(values[i]) - v);
                if (d < best) { best = d; idx = i; }
            }
        } catch (Throwable ignored) { }
        return idx;
    }

    private void on(String id, boolean v) { Round r = quick.get(id); if (r != null) r.set(v); }

    private void paintToggles() {
        Transfer t = Transfer.get(ctx);
        on("wifi", wifiOn == 1);
        on("bt", btOn == 1);
        on("invert", invertOn == 1);
        on("anim", animOn > 0);
        on("lightf", lightfOn > 0);
        on("xfer", t.running());
        if (animToggle != null) animToggle.set(animOn > 0);
        if (xferToggle != null) xferToggle.set(t.running());
        if (xferLine != null) {
            String line = "";
            if (t.running()) {
                String ip = HostLink.kv(HostLink.CTL + "status").get("lanip");
                line = (ip == null || ip.isEmpty() ? "（先连上 Wi-Fi）" : "http://" + ip + ":" + t.port()) + "  PIN " + t.pin();
            }
            set(xferLine, line);
        }
    }

    private void loadNotes() {
        if (notes == null) return;
        notes.removeAllViews();
        StatusBarNotification[] list = NotifService.active();
        int shown = 0;
        for (final StatusBarNotification sbn : list) {
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            CharSequence nt = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence body = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (nt == null && body == null) continue;
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(ctx, nt == null ? "" : nt.toString(), 17, true));
            if (body != null) {
                TextView bt = Ui.text(ctx, body.toString(), 14, false);
                bt.setMaxLines(2);
                bt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(bt);
            }
            final PendingIntent pi = n.contentIntent;
            col.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { if (pi != null) pi.send(); } catch (Exception ignored) { }
                    NotifService s = NotifService.instance;
                    if (s != null && sbn.isClearable()) s.cancelNotification(sbn.getKey());
                    finish();
                }
            });
            r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(Icons.bar(ctx, Icons.Kind.CLOSE, 22, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    NotifService s = NotifService.instance;
                    if (s != null) s.cancelNotification(sbn.getKey());
                    loadNotes();
                }
            }));
            notes.addView(r);
            notes.addView(Ui.rule(ctx, 1, Color.BLACK));
            shown++;
        }
        if (shown == 0) {
            TextView none = Ui.text(ctx, NotifService.instance == null ? "通知功能还没有就绪" : "没有通知", 16, false);
            none.setPadding(0, dp(14), 0, dp(10));
            notes.addView(none);
        }
    }

    private int dp(int v) { return Ui.dp(ctx, v); }
}
