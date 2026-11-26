package com.veradroid.home;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 方块风格的下拉面板，照 Kindle 自己的快捷设置：一排最多 5 个方块开关、亮度（有暖光的机型再加暖光）、回到 Kindle，
 * 有通知时下面列出通知。其余的（刷新方式、对比度、全刷周期、字号、自动休眠、手势、切换应用、关机……）都在 我的 → 各设置页里。
 *
 * 和经典面板一样是常驻的悬浮窗口：打开只是设成可见、按状态文件核对一遍（变了的控件才改），所以只刷一次；
 * 点面板外面、往上划、按返回都收起。经典风格仍用 {@link Panel}。
 */
final class BlocksPanel {
    private static BlocksPanel instance;

    static void open(Context c) {
        if (LockScreen.showing()) return;
        Context app = c.getApplicationContext();
        String conf = conf(app);
        if (instance != null && !conf.equals(instance.builtConf)) { instance.destroy(); instance = null; }
        if (instance == null) instance = new BlocksPanel(new android.view.ContextThemeWrapper(app, android.R.style.Theme_Holo_Light_NoActionBar), conf);
        instance.open();
    }

    static void close() { if (instance != null) instance.finish(); }
    static boolean isOpen() { return instance != null && instance.showing; }

    /** 影响面板搭法的设置与显示配置：快捷开关、面板名称、英文小字、字重、蓝牙、密度、字号、屏幕尺寸。变了就丢掉常驻的那份重搭。 */
    private static String conf(Context app) {
        try {
            android.content.SharedPreferences sp = Store.prefs(app, "home");
            android.content.res.Configuration cfg = app.getResources().getConfiguration();
            android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
            return sp.getString("quick", Panel.QUICK_DEFAULT) + "|" + sp.getString("panelname", "") + "|"
                + Blocks.caps(app) + "|" + Fonts.weight(app) + "|" + BluetoothActivity.supported() + "|"
                + dm.densityDpi + "|" + cfg.fontScale + "|" + dm.widthPixels + "x" + dm.heightPixels;
        } catch (Throwable t) { return ""; }
    }

    /** 方块面板一排最多 5 个（Kindle 的快捷设置也是 5 个）。 */
    static final int MAX_TILES = 5;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler();
    private final String builtConf;
    private final Root root;
    private final PageScrollView scroll;
    private boolean attached, showing;
    private TextView clock, info;
    private Blocks.BlockBar light, warm;
    private View lightBox, warmRow, autoRow;
    private Blocks.Switch autoSwitch;
    private LinearLayout notesBox, notesList;
    private TextView notesHead;
    private final java.util.Map<String, Tile> tiles = new java.util.LinkedHashMap<String, Tile>();
    private int wifiOn = 1, invertOn = 0, btOn = 0, animOn = 0, animLast = 1, lightfOn = 0, smoothMode = 2, globalSmoothMode = 2;
    private LinearLayout refreshBox;
    private TextView refreshHead;
    private final TextView[] refreshBtns = new TextView[4];
    private static final String[] REFRESH_LABELS = { "高清", "均衡", "快速", "极速" };
    private static final int[] REFRESH_VALS = { 3, 2, 1, 4 };
    private String panelName = "";
    private String seen;

    /** 最外层：返回键收起；点到面板以外、或者往上划，收起。 */
    private final class Root extends LinearLayout {
        private float dx, dy;
        Root() { super(ctx); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) { if (e.getAction() == KeyEvent.ACTION_UP) finish(); return true; }
            return super.dispatchKeyEvent(e);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_OUTSIDE || (a == MotionEvent.ACTION_DOWN && (e.getY() < 0 || e.getY() > getHeight()))) { finish(); return true; }
            if (a == MotionEvent.ACTION_DOWN) { dx = e.getRawX(); dy = e.getRawY(); Perf.touch(e); }
            if (a == MotionEvent.ACTION_UP) {
                float my = e.getRawY() - dy, mx = Math.abs(e.getRawX() - dx);
                boolean canSwipeClose = scroll == null || scroll.pages() <= 1 || scroll.currentPage() >= scroll.pages();
                if (canSwipeClose && my < -dp(70) && -my > mx * 2) {
                    MotionEvent c = MotionEvent.obtain(e);
                    c.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(c);
                    c.recycle();
                    finish();
                    return true;
                }
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** 方块开关：带投影的方框，图标 + 一行字；开 = 黑底白图标白字。 */
    private final class Tile extends LinearLayout {
        private final Icons.IconView icon;
        private final TextView name;
        private boolean on;
        Tile(Icons.Kind k, String caption, View.OnClickListener l) {
            super(ctx);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            icon = new Icons.IconView(ctx, k);
            addView(icon, Blocks.lp(dp(28), dp(28)));
            name = Ui.text(ctx, caption, 13, true);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(4), 0, 0);
            addView(name, Blocks.match());
            setPadding(dp(2), dp(10), dp(2) + Blocks.shade(ctx), dp(8) + Blocks.shade(ctx));
            setMinimumHeight(dp(84));
            paint();
            setClickable(true);
            setOnClickListener(l);
            Blocks.fast(this);
        }
        private void paint() {
            setBackground(new Blocks.Frame(ctx, true, false, on));
            icon.setInverted(on);
            name.setTextColor(on ? Color.WHITE : Color.BLACK);
        }
        void set(boolean v) { if (v != on) { on = v; paint(); } }
    }

    private BlocksPanel(Context c, String conf) {
        ctx = c;
        builtConf = conf;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        try { panelName = Store.prefs(c, "home").getString("panelname", ""); } catch (Throwable ignored) { }
        root = new Root();
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        // 内容在 body 里（左右留边），外包限高翻页滚动容器避免小屏/横屏/多通知裁掉底部并始终保留下方空白点击收起区；
        // 最底下一条贯通全宽的粗黑边（框线 + 投影的厚度），和下面的应用分开
        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(14));
        final int ruleH = Blocks.stroke(ctx) + Blocks.shade(ctx);
        final int maxBodyH = Math.max(dp(240), (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.85f) - ruleH);
        scroll = new PageScrollView(ctx) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int size = MeasureSpec.getSize(heightMeasureSpec);
                int cap = (mode == MeasureSpec.UNSPECIFIED) ? maxBodyH : Math.min(size, maxBodyH);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.rule(ctx, ruleH, Color.BLACK));

        // 顶上：大字时间，右边日期 / 电量，再右边一个收起的方块
        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tl = new LinearLayout(ctx);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.addView(Blocks.cap(ctx, "Quick settings"));
        clock = Ui.text(ctx, "", 26, true);
        tl.addView(clock);
        top.addView(tl);
        info = Ui.text(ctx, "", 14, false);
        info.setSingleLine(true);
        info.setEllipsize(TextUtils.TruncateAt.END);
        info.setGravity(Gravity.END);
        info.setPadding(dp(12), 0, dp(12), 0);
        top.addView(info, Blocks.weight(1));
        View up = Blocks.squareButton(ctx, Icons.Kind.CHEV_U, new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        top.addView(up);
        body.addView(top, Blocks.match());

        // 一排方块开关
        LinearLayout row = new LinearLayout(ctx);
        String q = Panel.QUICK_DEFAULT;
        try { q = Store.prefs(ctx, "home").getString("quick", Panel.QUICK_DEFAULT); } catch (Throwable ignored) { }
        boolean bt = BluetoothActivity.supported();
        for (String raw : q.split(",")) {
            final String id = raw.trim();
            int k = -1;
            for (int i = 0; i < Panel.QUICK_ID.length; i++) if (Panel.QUICK_ID[i].equals(id)) k = i;
            if (k < 0 || tiles.containsKey(id) || tiles.size() >= MAX_TILES) continue;
            if ("bt".equals(id) && !bt) continue;
            Tile t = new Tile(icon(id), "settings".equals(id) ? "设置" : "invert".equals(id) ? "夜间" : Panel.QUICK_NAME[k], new View.OnClickListener() {
                @Override public void onClick(View v) { action(id); }
            });
            if ("wifi".equals(id)) t.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { finish(); WifiActivity.open(ctx); return true; } });
            if ("bt".equals(id)) t.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { finish(); BluetoothActivity.open(ctx); return true; } });
            tiles.put(id, t);
            LinearLayout.LayoutParams lp = Blocks.weight(1);
            if (row.getChildCount() > 0) lp.leftMargin = dp(8);
            row.addView(t, lp);
        }
        if (tiles.size() > 0) body.addView(row, Blocks.gap(ctx, 10));

        // 刷新模式：对标文石 E-Ink 中心，下拉一点即切（高清 / 均衡 / 快速 / 极速 + 一键全刷）；
        // 在第三方应用内切换自动按包名记忆（AppProf），切回该应用自动恢复；在桌面切换则改全局默认。
        refreshBox = group("刷新模式");
        refreshHead = (TextView) refreshBox.getChildAt(0);
        LinearLayout rrow = new LinearLayout(ctx);
        rrow.setPadding(dp(8), dp(8), dp(8), dp(8));
        for (int i = 0; i < REFRESH_LABELS.length; i++) {
            final int val = REFRESH_VALS[i];
            TextView b = Ui.text(ctx, REFRESH_LABELS[i], 14, true);
            b.setGravity(Gravity.CENTER);
            b.setMinimumHeight(dp(44));
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickRefresh(val); }
            });
            Blocks.fast(b);
            refreshBtns[i] = b;
            LinearLayout.LayoutParams rlp = Blocks.weight(1);
            if (i > 0) rlp.leftMargin = dp(6);
            rrow.addView(b, rlp);
        }
        TextView fullBtn = Ui.text(ctx, "全刷", 14, true);
        fullBtn.setGravity(Gravity.CENTER);
        fullBtn.setMinimumHeight(dp(44));
        fullBtn.setBackground(Blocks.plain(ctx));
        fullBtn.setTextColor(Color.BLACK);
        fullBtn.setClickable(true);
        fullBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
                handler.postDelayed(new Runnable() { @Override public void run() { HostLink.cmd("clear"); } }, 500);
            }
        });
        Blocks.fast(fullBtn);
        LinearLayout.LayoutParams flp = Blocks.weight(1);
        flp.leftMargin = dp(6);
        rrow.addView(fullBtn, flp);
        refreshBox.addView(rrow, Blocks.match());
        paintRefresh();
        body.addView(refreshBox, Blocks.gap(ctx, 12));

        // 屏幕光：一个分组，亮度一行（− 方块条 +），有暖光再一行，有光线传感器再加“自动”
        LinearLayout g = group("屏幕光");
        light = new Blocks.BlockBar(ctx, 24);
        light.showValue();
        light.setListener(new Blocks.BlockBar.Listener() { @Override public void onCommit(int v) { lastLightSetAt = android.os.SystemClock.uptimeMillis(); HostLink.cmd("light " + v); } });
        g.addView(levelRow(Icons.Kind.SUN_S, Icons.Kind.SUN_L, light, "light"), Blocks.match());
        warm = new Blocks.BlockBar(ctx, 24);
        warm.showValue();
        warm.setListener(new Blocks.BlockBar.Listener() { @Override public void onCommit(int v) { lastWarmSetAt = android.os.SystemClock.uptimeMillis(); HostLink.cmd("warmth " + v); } });
        warmRow = levelRow(Icons.Kind.WARM, Icons.Kind.WARM, warm, "warmth");
        View h1 = Blocks.hair(ctx);
        g.addView(h1);
        g.addView(warmRow, Blocks.match());
        warmRow.setTag(h1);
        autoSwitch = new Blocks.Switch(ctx, false, new Icons.Toggle.Listener() { @Override public void onToggle(boolean on) { HostLink.cmd("lightauto " + (on ? 1 : 0)); } });
        autoRow = Blocks.row(ctx, "自动亮度", "Auto", autoSwitch);
        View h2 = Blocks.hair(ctx);
        g.addView(h2);
        g.addView(autoRow, Blocks.match());
        autoRow.setTag(h2);
        lightBox = g;
        body.addView(g, Blocks.gap(ctx, 12));

        // 优化机器状态：一条和“回到 Kindle”一样宽的方框，点了按顺序清后台 / 提速 / 省电，下面出一张结果卡片
        optBar = new LinearLayout(ctx);
        optBar.setGravity(Gravity.CENTER_VERTICAL);
        optBar.setBackground(Blocks.plain(ctx));
        optBar.setPadding(dp(14), dp(6), dp(14), dp(6));
        optBar.setMinimumHeight(dp(52));
        optBar.addView(new Icons.IconView(ctx, Icons.Kind.SLIDERS), Blocks.lp(dp(24), dp(24)));
        LinearLayout ot = Blocks.titled(ctx, "Optimize", "优化机器状态", 16, true);
        optLabel = (TextView) ot.getChildAt(1);
        LinearLayout.LayoutParams otp = Blocks.weight(1);
        otp.leftMargin = dp(12);
        optBar.addView(ot, otp);
        optHint = Ui.text(ctx, "清后台 · 提速 · 省电", 12, false);
        optBar.addView(optHint);
        optBar.setClickable(true);
        optBar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { optimize(); } });
        Blocks.fast(optBar);
        body.addView(optBar, Blocks.gap(ctx, 12));
        optCard = new LinearLayout(ctx);
        optCard.setOrientation(LinearLayout.VERTICAL);
        optCard.setBackground(Blocks.frame(ctx));
        optCard.setPadding(dp(12), dp(10), dp(12) + Blocks.shade(ctx), dp(10) + Blocks.shade(ctx));
        optCard.setVisibility(View.GONE);
        body.addView(optCard, Blocks.gap(ctx, 8));

        // 当前应用的显示设置：前台是别的应用时才有（桌面 / 我们自己的页面在前台时收起）
        appBar = new LinearLayout(ctx);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setBackground(Blocks.plain(ctx));
        appBar.setPadding(dp(14), dp(6), dp(14), dp(6));
        appBar.setMinimumHeight(dp(52));
        appBar.addView(new Icons.IconView(ctx, Icons.Kind.EYE), Blocks.lp(dp(24), dp(24)));
        LinearLayout at = Blocks.titled(ctx, "This app", "当前应用的优化", 16, true);
        LinearLayout.LayoutParams atp = Blocks.weight(1);
        atp.leftMargin = dp(12);
        appBar.addView(at, atp);
        appHint = Ui.text(ctx, "", 12, false);
        appHint.setSingleLine(true);
        appHint.setEllipsize(TextUtils.TruncateAt.END);
        appHint.setMaxWidth(dp(220));
        appBar.addView(appHint);
        appBar.setClickable(true);
        appBar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openAppDisplay(); } });
        Blocks.fast(appBar);
        appBar.setVisibility(View.GONE);
        body.addView(appBar, Blocks.gap(ctx, 12));

        // 回到桌面 | 回到 Kindle：在任何应用里下拉面板都能一下回到 VeraDroid 桌面
        LinearLayout go = new LinearLayout(ctx);
        go.addView(Blocks.button(ctx, "回到桌面", false, new View.OnClickListener() {
            @Override public void onClick(View v) { goHome(); }
        }), Blocks.weight(1));
        LinearLayout.LayoutParams kp = Blocks.weight(1);
        kp.leftMargin = dp(8);
        go.addView(Blocks.button(ctx, "回到 Kindle", false, new View.OnClickListener() {
            @Override public void onClick(View v) { HostLink.cmd("exit"); finish(); }
        }), kp);
        body.addView(go, Blocks.gap(ctx, 12));

        // 通知：有才显示
        notesBox = group("通知");
        notesHead = (TextView) notesBox.getChildAt(0);
        notesList = new LinearLayout(ctx);
        notesList.setOrientation(LinearLayout.VERTICAL);
        notesBox.addView(notesList, Blocks.match());
        notesBox.setVisibility(View.GONE);
        body.addView(notesBox, Blocks.gap(ctx, 12));

    }

    private LinearLayout optBar, optCard, appBar;
    private TextView appHint;
    private String appPkg;

    /** 面板底下那个应用（HostService 记的“按哪个应用算显示设置”）；桌面在前台 = null */
    private void syncApp() {
        String p = HostService.profPkg;
        if (p == null || p.equals("home") || p.equals(ctx.getPackageName())) {
            appPkg = null;
            vis(appBar, false);
            set(refreshHead, "刷新模式 · 全局默认");
            smoothMode = globalSmoothMode;
            paintRefresh();
            return;
        }
        appPkg = p;
        String label = p;
        try { label = String.valueOf(ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(p, 0))); } catch (Throwable ignored) { }
        String sum = "";
        int appSmooth = globalSmoothMode;
        if (AppProf.host()) {
            String[] r = AppProf.lookup(AppProf.table(), p);
            sum = (r[0].startsWith("u") ? "" : r[0].equals("b") ? "推荐 · " : "") + AppProf.summary(r[1]);
            String sm = AppProf.parse(r[1]).get("KFB_SMOOTH");
            if (sm != null && sm.length() > 0) {
                try { int v = Integer.parseInt(sm); if (v >= 1 && v <= 4) appSmooth = v; } catch (Throwable ignored) { }
            }
        }
        set(refreshHead, "刷新模式 · " + label + "（自动记忆）");
        smoothMode = appSmooth;
        paintRefresh();
        set(appHint, label + (sum.length() > 0 ? " · " + sum : ""));
        vis(appBar, true);
    }

    /** 切换刷新模式：第三方应用在前台时按包名自动记忆并立即生效；桌面在前台时改全局默认。 */
    private void pickRefresh(int val) {
        smoothMode = val;
        paintRefresh();
        if (appPkg != null && AppProf.host()) {
            Map<String, String> m = AppProf.parse(AppProf.lookup(AppProf.table(), appPkg)[1]);
            m.put("KFB_SMOOTH", String.valueOf(val));
            m.remove("KFB_MODE");
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            String spec = sb.toString();
            AppProf.save(appPkg, spec);
            AppProf.fg(appPkg);
            String label = appPkg;
            try { label = String.valueOf(ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(appPkg, 0))); } catch (Throwable ignored) { }
            String sum = AppProf.summary(spec);
            set(appHint, label + (sum.length() > 0 ? " · " + sum : ""));
        } else {
            globalSmoothMode = val;
            HostLink.settings("KFB_SMOOTH=" + val + "\n");
        }
    }

    private void openAppDisplay() {
        if (appPkg == null) return;
        String label = appPkg;
        try { label = String.valueOf(ctx.getPackageManager().getApplicationLabel(ctx.getPackageManager().getApplicationInfo(appPkg, 0))); } catch (Throwable ignored) { }
        Intent i = new Intent(ctx, AppDisplayActivity.class);
        i.putExtra("pkg", appPkg);
        i.putExtra("label", label);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        finish();
        ctx.startActivity(i);
    }
    private TextView optLabel, optHint;
    private boolean optRunning;

    /** 优化机器状态：开始时只改这一条的字（一次刷新），做完了一次出整张结果卡片（再一次刷新）。 */
    private void optimize() {
        if (optRunning) return;
        optRunning = true;
        set(optLabel, "正在优化…");
        set(optHint, "清后台 → 提速 → 省电");
        final long t0 = System.currentTimeMillis();
        Optimizer.run(ctx, Cleaner.previousApp(ctx), new Optimizer.Done() { @Override public void done(Optimizer.Result r) {
            optRunning = false;
            set(optLabel, "优化机器状态");
            set(optHint, "用了 " + Math.max(1, (System.currentTimeMillis() - t0 + 500) / 1000) + " 秒");
            showResult(r);
        } });
    }

    private void showResult(Optimizer.Result r) {
        optCard.removeAllViews();
        optCard.addView(Blocks.cap(ctx, "Result"));
        optCard.addView(Ui.text(ctx, r.summary(), 16, true));
        for (String l : r.details()) { TextView t = Ui.text(ctx, l, 13, false); t.setPadding(0, dp(2), 0, 0); optCard.addView(t); }
        if (!r.tips.isEmpty()) {
            TextView h = Ui.text(ctx, "还可以更省电：", 13, true);
            h.setPadding(0, dp(8), 0, dp(2));
            optCard.addView(h);
            for (final Optimizer.Suggestion s : r.tips) {
                LinearLayout row = new LinearLayout(ctx);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(3), 0, dp(3));
                TextView tt = Ui.text(ctx, s.text, 13, false);
                row.addView(tt, Blocks.weight(1));
                final TextView[] btn = new TextView[1];
                btn[0] = Blocks.button(ctx, s.action, false, new View.OnClickListener() { @Override public void onClick(View v) {
                    s.act.run();
                    btn[0].setText("已调整");
                    btn[0].setEnabled(false);
                    seen = null;
                    handler.postDelayed(recheck, 700);
                } });
                btn[0].setMinHeight(dp(40));
                btn[0].setPadding(dp(10), dp(4), dp(10), dp(4));
                row.addView(btn[0]);
                optCard.addView(row, Blocks.match());
            }
        }
        vis(optCard, true);
    }

    private LinearLayout group(String title) {
        LinearLayout g = new LinearLayout(ctx);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Blocks.frame(ctx));
        int st = Blocks.stroke(ctx), sh = Blocks.shade(ctx);
        g.setPadding(st, st, st + sh, st + sh);
        g.addView(Blocks.header(ctx, title));
        return g;
    }

    /** “− 方块条 +”：两边的方块点一下走一格（一格 = 条上的一个方块）。 */
    private LinearLayout levelRow(Icons.Kind lo, Icons.Kind hi, final Blocks.BlockBar bar, final String cmd) {
        LinearLayout r = new LinearLayout(ctx);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(8), dp(8), dp(8));
        r.addView(stepBox(lo, new View.OnClickListener() { @Override public void onClick(View v) { step(bar, cmd, -1); } }));
        LinearLayout.LayoutParams bp = Blocks.weight(1);
        bp.leftMargin = dp(10); bp.rightMargin = dp(10);
        r.addView(bar, bp);
        r.addView(stepBox(hi, new View.OnClickListener() { @Override public void onClick(View v) { step(bar, cmd, 1); } }));
        return r;
    }

    private View stepBox(Icons.Kind k, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(Blocks.plain(ctx));
        f.addView(new Icons.IconView(ctx, k), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setLayoutParams(Blocks.lp(dp(48), dp(44)));
        f.setClickable(true);
        f.setOnClickListener(l);
        Blocks.fast(f);
        return f;
    }

    private void step(Blocks.BlockBar bar, String cmd, int dir) {
        int max = Math.max(1, barMax(bar));
        int unit = Math.max(1, Math.round(max / 24f));
        int v = Math.max(0, Math.min(max, bar.value() + dir * unit));
        if (v == bar.value()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if ("light".equals(cmd)) lastLightSetAt = now; else if ("warmth".equals(cmd)) lastWarmSetAt = now;
        bar.setValue(v);
        HostLink.cmd(cmd + " " + v);
    }

    private int lightMax = 24;
    private long lastLightSetAt = 0, lastWarmSetAt = 0, lastBtSetAt = 0;
    private int barMax(Blocks.BlockBar b) { return lightMax; }

    private static Icons.Kind icon(String id) {
        if ("wifi".equals(id)) return Icons.Kind.WIFI;
        if ("bt".equals(id)) return Icons.Kind.BLUETOOTH;
        if ("invert".equals(id)) return Icons.Kind.MOON;
        if ("clear".equals(id)) return Icons.Kind.REFRESH;
        if ("xfer".equals(id)) return Icons.Kind.UPLOAD;
        if ("shot".equals(id)) return Icons.Kind.CAMERA;
        if ("kindle".equals(id)) return Icons.Kind.EXIT;
        if ("home".equals(id)) return Icons.Kind.HOME;
        if ("clean".equals(id)) return Icons.Kind.CLEAN;
        if ("anim".equals(id)) return Icons.Kind.SWIPE;
        if ("lightf".equals(id)) return Icons.Kind.EYE;
        if ("settings".equals(id)) return Icons.Kind.GEAR;
        if ("switch".equals(id)) return Icons.Kind.GRID;
        if ("restart".equals(id)) return Icons.Kind.REFRESH;
        return Icons.Kind.POWER;
    }

    /** 和经典面板的快捷开关做同样的事（同样的宿主命令和设置键）。 */
    private void action(String id) {
        if ("wifi".equals(id)) { wifiOn = wifiOn == 1 ? 0 : 1; HostLink.cmd("wifi " + wifiOn); paintTiles(); }
        else if ("bt".equals(id)) {
            btOn = btOn == 1 ? 0 : 1;
            lastBtSetAt = android.os.SystemClock.uptimeMillis();
            HostLink.cmd(btOn == 1 ? "bt enable" : "bt disable");
            paintTiles();
            VeraToast.show(ctx, btOn == 1 ? "正在打开蓝牙…（长按进入配对）" : "正在关闭蓝牙…");
        }
        else if ("invert".equals(id)) { invertOn = invertOn == 1 ? 0 : 1; HostLink.cmd("invert"); paintTiles(); }
        else if ("clear".equals(id)) {
            // 先收起面板、等它从屏幕上下去（约 0.5 秒）再整屏刷新：以前面板还在时就刷，刷完收起面板又在那块留下残影
            finish();
            handler.postDelayed(new Runnable() { @Override public void run() { HostLink.cmd("clear"); } }, 500);
        }
        else if ("xfer".equals(id)) {
            Transfer t = Transfer.get(ctx);
            if (t.running()) t.stop(); else if (!t.start()) VeraToast.show(ctx, "传输没打开：" + t.lastError());
            HostLink.cmd("status");
            paintTiles();
        }
        else if ("shot".equals(id)) { finish(); handler.postDelayed(new Runnable() { @Override public void run() { WebAdmin.saveScreenshot(ctx, true); } }, 800); }
        else if ("kindle".equals(id)) { HostLink.cmd("exit"); finish(); }
        else if ("home".equals(id)) goHome();
        else if ("anim".equals(id)) { animOn = animOn > 0 ? 0 : animLast; HostLink.settings("KFB_ANIM_MODE=" + animOn + "\n"); paintTiles(); }
        else if ("lightf".equals(id)) { lightfOn = lightfOn > 0 ? 0 : Panel.lightfLast; HostLink.settings("KFB_LIGHTF=" + lightfOn + "\n"); paintTiles(); }
        else if ("settings".equals(id)) {
            finish();
            SettingsActivity.open(ctx);   // 盖在当前应用上面，返回 / ✕ 回到这个应用
        }
        else if ("switch".equals(id)) { start(new Intent(ctx, SwitcherActivity.class)); finish(); }
        else if ("restart".equals(id)) { HostLink.cmd("restart"); finish(); }
        else if ("stop".equals(id)) HostLink.cmd("stop");
        else if ("clean".equals(id)) { String prev = Cleaner.previousApp(ctx); finish(); Panel.cleanNow(ctx, prev); }
    }

    /** 回到 VeraDroid 桌面（和经典面板的“桌面”一样）：先收起面板再开桌面。 */
    private void goHome() {
        finish();
        start(HomeActivity.homeIntent(ctx));
    }

    private void start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { ctx.startActivity(i); } catch (Throwable t) { android.util.Log.w("VeraHome", "panel start: " + t); }
    }

    private WindowManager.LayoutParams params() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.windowAnimations = 0;
        lp.setTitle("VeraPanel");
        return lp;
    }

    private void open() {
        if (showing) return;
        showing = true;
        if (scroll != null) scroll.scrollTo(0, 0);
        HostLink.epdHint(Panel.PANEL_HINT_MS, Panel.PANEL_HINT_PCT);
        set(clock, Ui.clock(ctx));
        readStatus();
        loadNotes();
        syncApp();
        if (!attached) {
            Perf.measure(root, "blocks panel");
            try { wm.addView(root, params()); attached = true; }
            catch (Throwable t) { android.util.Log.w("VeraHome", "panel: " + t); showing = false; return; }
        } else {
            Perf.measure(root, "blocks panel (reused)");
            root.setVisibility(View.VISIBLE);
        }
        HostLink.cmd("status");   // 只请宿主把状态文件写新，不改设置
        handler.removeCallbacks(recheck);
        handler.postDelayed(recheck, 700);
        // 宿主写状态文件要读十来个 lipc 属性，慢的机器上（KT6 模拟器）超过 700 ms：再看一次，不然亮度条要到下次打开才出来
        handler.postDelayed(recheck, 2500);
    }

    private final Runnable recheck = new Runnable() { @Override public void run() { if (showing) readStatus(); } };

    private void finish() {
        if (!showing) return;
        showing = false;
        handler.removeCallbacks(recheck);
        HostLink.epdHint(Panel.PANEL_HINT_MS, Panel.PANEL_HINT_PCT);
        root.setVisibility(View.GONE);
        // 下次打开面板时不再带着上次的结果
        if (!optRunning && optCard != null) { optCard.setVisibility(View.GONE); optCard.removeAllViews(); set(optHint, "清后台 · 提速 · 省电"); }
    }

    private void destroy() {
        handler.removeCallbacksAndMessages(null);
        showing = false;
        if (attached) { try { wm.removeViewImmediate(root); } catch (Throwable ignored) { } attached = false; }
    }

    private static void set(TextView v, String t) { if (v != null && !t.contentEquals(v.getText())) v.setText(t); }
    private static void vis(View v, boolean on) { if (v != null && (v.getVisibility() == View.VISIBLE) != on) v.setVisibility(on ? View.VISIBLE : View.GONE); }

    /** 只读状态 / 设置文件；内容没变且不在防回跳窗口内就直接返回（不用 1 秒精度的 lastModified 捷径，避免同秒 status_patch 漏刷新）。 */
    private void readStatus() {
        Map<String, String> st = HostLink.status(ctx);
        Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");
        long nowMs = android.os.SystemClock.uptimeMillis();
        boolean guarded = (nowMs - lastLightSetAt <= 1500L) || (nowMs - lastWarmSetAt <= 1500L) || (nowMs - lastBtSetAt <= 5000L);
        Transfer xfer = Transfer.get(ctx);
        String key = new java.util.TreeMap<String, String>(st).toString() + new java.util.TreeMap<String, String>(cfg).toString()
            + "|" + xfer.running() + ":" + xfer.port() + ":" + xfer.pin() + "|" + (System.currentTimeMillis() / 60000L);
        if (!guarded && key.equals(seen)) return;
        seen = guarded ? null : key;
        set(clock, Ui.clock(ctx));
        StringBuilder sb = new StringBuilder();
        if (panelName.length() > 0) sb.append(panelName).append("  ·  ");
        sb.append(new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(new Date()));
        if (st.get("battery") != null) {
            sb.append("  ·  电量 ").append(st.get("battery")).append("%");
            if ("1".equals(st.get("charging"))) sb.append(" 充电中");
        }
        set(info, sb.toString());
        lightMax = HostLink.num(st, "lightmax", 24);
        boolean hasLight = HostLink.num(st, "haslight", 1) == 1, hasWarm = HostLink.num(st, "haswarmth", 0) == 1, hasAuto = HostLink.num(st, "haslightauto", 0) == 1;
        vis(lightBox, hasLight);
        vis(warmRow, hasWarm); vis((View) warmRow.getTag(), hasWarm);
        vis(autoRow, hasAuto); vis((View) autoRow.getTag(), hasAuto);
        light.setMax(lightMax); warm.setMax(lightMax);
        if (nowMs - lastLightSetAt > 1500L) light.setValue(HostLink.num(st, "light", 0));
        if (nowMs - lastWarmSetAt > 1500L) warm.setValue(HostLink.num(st, "warmth", 0));
        if (hasAuto && autoSwitch.isOn() != (HostLink.num(st, "lightauto", 0) == 1)) autoSwitch.set(HostLink.num(st, "lightauto", 0) == 1);
        wifiOn = HostLink.num(st, "wifi", 1);
        invertOn = HostLink.num(st, "invert", 0);
        if (nowMs - lastBtSetAt > 5000L) btOn = HostLink.num(st, "bton", 0);
        try { animOn = Integer.parseInt(cfg.get("KFB_ANIM_MODE")); } catch (Throwable ignored) { animOn = 0; }
        if (animOn > 0) animLast = animOn;
        lightfOn = Math.max(0, Math.min(3, HostLink.num(cfg, "KFB_LIGHTF", 0)));
        if (lightfOn > 0) Panel.lightfLast = lightfOn;
        boolean hasSmooth = cfg.containsKey("KFB_SMOOTH");
        vis(refreshBox, hasSmooth);
        if (hasSmooth) {
            globalSmoothMode = HostLink.num(cfg, "KFB_SMOOTH", 2);
            if (appPkg == null) {
                smoothMode = globalSmoothMode;
                paintRefresh();
            }
        }
        paintTiles();
    }

    private void paintRefresh() {
        for (int i = 0; i < refreshBtns.length; i++) {
            TextView b = refreshBtns[i];
            if (b == null) continue;
            boolean on = (smoothMode == REFRESH_VALS[i]);
            b.setBackground(on ? Blocks.plainFilled(ctx) : Blocks.plain(ctx));
            b.setTextColor(on ? Color.WHITE : Color.BLACK);
        }
    }

    private void tile(String id, boolean on) { Tile t = tiles.get(id); if (t != null) t.set(on); }

    private void paintTiles() {
        tile("wifi", wifiOn == 1);
        tile("bt", btOn == 1);
        tile("invert", invertOn == 1);
        tile("anim", animOn > 0);
        tile("lightf", lightfOn > 0);
        tile("xfer", Transfer.get(ctx).running());
    }

    /** 通知：最多 4 条（多的写“还有 n 条”），点一条打开它，右边 × 去掉；一条都没有时整块不显示。 */
    private static final int MAX_NOTES = 4;
    private void loadNotes() {
        StatusBarNotification[] list = NotifService.active();
        java.util.List<StatusBarNotification> show = new java.util.ArrayList<StatusBarNotification>();
        for (StatusBarNotification sbn : list) {
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            if (n.extras.getCharSequence(Notification.EXTRA_TITLE) == null && n.extras.getCharSequence(Notification.EXTRA_TEXT) == null) continue;
            show.add(sbn);
        }
        StringBuilder sig = new StringBuilder();
        for (StatusBarNotification s : show) sig.append(s.getKey()).append(s.getPostTime()).append(';');
        if (sig.toString().equals(notesList.getTag())) return;
        notesList.setTag(sig.toString());
        notesList.removeAllViews();
        vis(notesBox, !show.isEmpty());
        if (show.isEmpty()) return;
        set(notesHead, "通知 · " + show.size());
        int clearable = 0;
        for (StatusBarNotification s : show) if (s.isClearable()) clearable++;
        int n0 = 0;
        for (final StatusBarNotification sbn : show) {
            if (n0 >= MAX_NOTES) break;
            Notification n = sbn.getNotification();
            CharSequence nt = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence body = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (n0 > 0) notesList.addView(Blocks.hair(ctx));
            LinearLayout r = new LinearLayout(ctx);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(6), dp(6), dp(6));
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t = Ui.text(ctx, nt == null ? "" : nt.toString(), 16, true);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(t);
            if (body != null) {
                TextView bt = Ui.text(ctx, body.toString(), 13, false);
                bt.setMaxLines(2);
                bt.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(bt);
            }
            final PendingIntent pi = n.contentIntent;
            col.setClickable(true);
            col.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                try { if (pi != null) pi.send(); } catch (Exception ignored) { }
                NotifService s = NotifService.instance;
                if (s != null && sbn.isClearable()) s.cancelNotification(sbn.getKey());
                finish();
            } });
            Blocks.fast(col);
            r.addView(col, Blocks.weight(1));
            if (sbn.isClearable()) {
                FrameLayout x = new FrameLayout(ctx);
                x.addView(new Icons.IconView(ctx, Icons.Kind.CLOSE), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                x.setClickable(true);
                x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    NotifService s = NotifService.instance;
                    if (s != null) s.cancelNotification(sbn.getKey());
                    notesList.setTag(null);
                    handler.postDelayed(new Runnable() { @Override public void run() { if (showing) loadNotes(); } }, 150);
                } });
                Blocks.fast(x);
                r.addView(x, Blocks.lp(dp(44), dp(44)));
            }
            notesList.addView(r, Blocks.match());
            n0++;
        }
        if (show.size() > MAX_NOTES || clearable > 0) {
            LinearLayout foot = new LinearLayout(ctx);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            foot.setPadding(dp(12), dp(4), dp(8), dp(8));
            TextView more = Ui.text(ctx, show.size() > MAX_NOTES ? "还有 " + (show.size() - MAX_NOTES) + " 条" : "", 13, false);
            foot.addView(more, Blocks.weight(1));
            if (clearable > 0) {
                foot.addView(Blocks.button(ctx, "全部清除", false, new View.OnClickListener() { @Override public void onClick(View v) {
                    NotifService s = NotifService.instance;
                    if (s != null) for (StatusBarNotification n : NotifService.active()) if (n.isClearable()) s.cancelNotification(n.getKey());
                    notesList.setTag(null);
                    handler.postDelayed(new Runnable() { @Override public void run() { if (showing) loadNotes(); } }, 150);
                } }));
            }
            notesList.addView(Blocks.hair(ctx));
            notesList.addView(foot, Blocks.match());
        }
    }

    private int dp(int v) { return Ui.dp(ctx, v); }
}
