// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.inputmethod.InputMethodManager;

import java.io.File;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * VeraDroid home: a paged grid of the installed apps and a settings page with tabs. White background, black text, no
 * animation - everything is drawn on an e-ink panel, so pages change in one step and then get a flashing refresh.
 * Everything that has to reach the Kindle side goes through {@link HostLink}.
 */
public class HomeActivity extends Activity {
    // “恢复默认设置”交给宿主恢复默认的设置项（显示、手势、返回方式）
    static final String[] RESET_KEYS = { "KFB_SETTLE", "KFB_MODE", "KFB_FLASH", "KFB_CONTRAST", "KFB_DITHER", "KFB_INVERT", "KFB_LIGHTF", "KFB_DARKEN", "KFB_GESTURES",
        "RETURN_MODE", "KFB_ANIM_MODE", "KFB_ANIM_STEPS", "KFB_ANIM_DIR", "KFB_ANIM_MIN", "KFB_SMOOTH", "KFB_PACE", "KFB_HOLD", "KFB_CLEAN", "KFB_REAGL_MIN",
        "G_TL", "G_TR", "G_BL", "G_BR", "G_EDGE_BACK", "G_EDGE_PANEL", "G_TWO_TAP", "G_TWO_LONG", "G_TWO_BACK", "G_TWO_SLIDE", "G_BACK_EXCLUDE",
        "G_EDGE_THIRDS", "G_EB_L", "G_EB_M", "G_EB_R", "G_EB_HOLD", "G_EB_HOLD_MS", "PAGE_KEYS_FLIP", "PAGE_KEYS_MAP" };

    private final Handler handler = new Handler();
    private final List<ResolveInfo> apps = new ArrayList<ResolveInfo>();
    private final Map<String, Long> installed = new HashMap<String, Long>();
    private final Map<String, String> appLabels = new HashMap<String, String>();
    private final Map<String, Drawable> iconCache = new HashMap<String, Drawable>();
    private PackageManager pm;
    private SharedPreferences prefs;
    private boolean resumed, inSettings;
    private int tab = -1, gridPage;
    private String note = "";

    private LinearLayout page;
    private LinearLayout gridHolder, pager;
    private TextView pageLabel;
    private int perPage = 6;
    private long appsLoadedAt;

    // choices that live in this app
    private static final String[] SORTS = { "最近使用", "最久未用", "名称拼音", "包名字母", "最近安装" };
    private static final String[] SIZES = { "小", "中", "大" };
    private static final float[] SIZE_SCALE = { 0.85f, 1.0f, 1.2f };
    private static final String[] DIM_L = { "20%", "30%", "50%", "70%", "原图" };
    private static final String[] WALLMODE_L = { "铺满", "完整显示", "原大小" };
    private static final String[] ICONSTYLE_L = { "原样", "黑白描框" };

    // choices the host applies (labels / values)
    private static final String[] CONTRAST_L = { "1.0 柔和", "1.3 标准", "1.6 较黑", "1.9 很黑", "2.2 最黑" };
    private static final String[] CONTRAST_V = { "1.0", "1.3", "1.6", "1.9", "2.2" };
    private static final String[] DITHER_L = { "关", "Floyd-Steinberg", "Atkinson", "有序点阵" };
    private static final String[] DITHER_V = { "0", "1", "2", "3" };
    private static final String[] MODE_L = { "均衡（残影少、不闪）", "灰度最准（图片最好，稍慢）", "最快（只有黑白，残影多）" };
    private static final String[] MODE_V = { "0", "1", "2" };
    private static final String[] FLASH_L = { "不按页数全刷", "每页（残影最少）", "每 3 页", "每 6 页", "每 8 页", "每 10 页", "每 20 页" };
    // 宿主默认 KFB_FLASH=8（boot/lib/settings.sh）；选项里没有 8 时设置页会错误地显示成“不按页数全刷”
    private static final String[] FLASH_V = { "0", "1", "3", "6", "8", "10", "20" };
    private static final String[] SETTLE_L = { "关", "快（40 毫秒）", "标准（120 毫秒）", "强（250 毫秒）" };
    private static final String[] SETTLE_V = { "0", "40", "120", "250" };
    private static final String[] ANIM_L = { "跟随手势", "左滑", "右滑", "上滑", "下滑", "关闭（硬刷新）" };
    private static final String[] ANIM_V = { "1", "2", "3", "4", "5", "0" };
    private static final String[] ANIM_STEP_L = { "快（6 步）", "标准（12 步）", "慢（24 步）" };
    private static final String[] ANIM_STEP_V = { "6", "12", "24" };
    private static final String[] ONOFF_L = { "关", "开" };
    private static final String[] ONOFF_V = { "0", "1" };
    private static final String[] RETURN_L = { "挂起（再打开几秒）", "关闭（省内存）" };
    private static final String[] DNS_L = { "自动（Wi-Fi 给的）", "阿里 223.5.5.5", "腾讯 119.29.29.29", "Google 8.8.8.8" };
    private static final String[] DNS_V = { "auto", "aliyun", "tencent", "google" };
    private static final String[] RETURN_V = { "freeze", "off" };
    private static final String[] ACT_L = { "不做操作", "返回", "回到桌面", "回到主界面", "打开面板", "切换应用", "清除残影", "反色", "当前应用的显示设置" };
    private static final String[] ACT_V = { "none", "back", "home", "kindle", "panel", "switch", "clear", "invert", "appopt" };
    private static final String[] HOLD_MS_L = { "0.3 秒", "0.5 秒", "0.8 秒", "1.2 秒" };
    private static final String[] HOLD_MS_V = { "300", "500", "800", "1200" };
    // 哪些应用在前台时不做“底边上划返回”（这些应用自己用上划，例如微信读书的阅读工具栏）
    private static final String[] EXCL_L = { "不排除", "微信读书", "微信读书、番茄、晋江" };
    private static final String[] EXCL_V = { "none", "com.tencent.weread.eink", "com.tencent.weread.eink,com.dragon.read,com.jjwxc.reader" };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            Stats.sampleBattery(HomeActivity.this);
            refreshStatus();
            if (blocks != null && !inSettings) blocks.tickClock();
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000 + 200);
        }
    };

    /**
     * 系统的分钟广播 / 改时间 / 改时区：马上跑一次 tick。tick 自己按 uptime 排下一次，Kindle 真睡下去时 uptime 不走，
     * 醒来后要再等最多一分钟；TIME_TICK 按开机时间（含休眠）到期，醒来就到（宿主 alarmfix 之后 AlarmManager 才按时发它）。
     */
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (resumed) { handler.removeCallbacks(tick); tick.run(); } }
    };
    private boolean timeReceiverOn;

    /** 这是盖在应用上面的单独设置页（SettingsActivity），不是桌面。 */
    boolean standalone;
    boolean closingToBack;
    private static final List<ResolveInfo> sSharedApps = new ArrayList<ResolveInfo>();
    private static final Map<String, Long> sSharedInstalled = new HashMap<String, Long>();
    private static final Map<String, String> sSharedLabels = new HashMap<String, String>();
    private static final Map<String, Drawable> sSharedIcons = new HashMap<String, Drawable>();
    private static String sSharedIconKey = "";
    private static long sSharedLoadedAt, sSharedGen;
    private static int sSharedSort = -1;

    private void seedFromSharedApps() {
        synchronized (sSharedApps) {
            if (sSharedLoadedAt == 0 || sSharedApps.isEmpty() || sSharedSort != prefs.getInt("sort", 0)) return;
            apps.clear(); apps.addAll(sSharedApps);
            installed.clear(); installed.putAll(sSharedInstalled);
            appLabels.clear(); appLabels.putAll(sSharedLabels);
            String ik = iconKey();
            if (ik.equals(sSharedIconKey)) { iconCache.clear(); iconCache.putAll(sSharedIcons); iconCacheKey = ik; appsIconKey = ik; }
            appsGen = Math.max(1, sSharedGen);
            loadedSort = sSharedSort;
            appsLoadedAt = sSharedLoadedAt;
        }
    }

    /**
     * 显示密度（宿主 appprof.sh：多看在前台时临时换成 320 × 屏宽 / 1080）：桌面自己不跟着重建。以前每换一次密度桌面整个重建一次——
     * 它就在多看下面，重建中的窗口先交出一帧黑底、再一帧大一号的桌面，显示桥照画（PW6 实测：回桌面时 大号桌面 → 半张 → 整屏黑 → 正确的桌面）。
     * 现在 manifest 里声明自己处理 density：换过去又换回来什么都不做；回到桌面后密度真的和建界面时不一样（用户改了系统密度之类）才重建。
     */
    private int builtDpi;

    /**
     * 桌面固定按设备本来的密度（ro.sf.lcd_density）排版，不跟着宿主临时换给多看的密度走（0.98 用户反馈：从多看划回来，桌面图标先大一号）。
     * 以前桌面在 onResume 时看到密度不一样，1.5 秒后自己重建一次——宿主换回默认往往更晚（诊断：1.7–6.5 秒，有一次 30 秒没换回），
     * 于是先按 376 重建出一个大一号的桌面，宿主换回来后再重建一次。现在 Activity 的资源一直是默认密度：密度换来换去都不重建、不变样。
     * 用户真的改了设备密度（memory.sh 的 ro.sf.lcd_density）要重启安卓，那时 DENSITY_DEVICE 跟着变。
     */
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        int dev = android.util.DisplayMetrics.DENSITY_DEVICE;
        if (dev > 0) {
            android.content.res.Configuration o = new android.content.res.Configuration();
            o.fontScale = 0;   // new Configuration() 的 fontScale 是 1：不清掉会盖住系统的字体缩放
            o.densityDpi = dev;
            try { applyOverrideConfiguration(o); } catch (Throwable t) { android.util.Log.w("VeraHome", "pin density: " + t); }
        }
    }
    private final Runnable densityCheck = new Runnable() { @Override public void run() {
        if (resumed && getResources().getConfiguration().densityDpi != builtDpi) recreate();
    } };
    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        handler.removeCallbacks(densityCheck);
        if (resumed && c.densityDpi != builtDpi) handler.postDelayed(densityCheck, 1500);
    }

    /**
     * 回桌面用的 Intent：MAIN + HOME、只限本包、不写组件名。写了组件名（new Intent(c, HomeActivity.class)）的话，
     * ActivityManager 不把它当成桌面，在应用的栈里另开一个任务、再建一个 HomeActivity（onCreate + 整页重搭，
     * 回桌面要多花一倍时间，内存里也多一份主页），之后返回键还会退回到这个副本。这样走的是已经在的那一个（onNewIntent）。
     */
    static Intent homeIntent(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.setPackage(c.getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        return i;
    }

    private static volatile boolean crashHandlerInstalled;
    private static void installCrashHandler(final Context appCtx) {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable e) {
                DiagConsent.onCrash(appCtx);   // 先问再传（DiagConsent）：没选过就只记下，下次起来时问
                if (prev != null) prev.uncaughtException(th, e);
            }
        });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        builtDpi = getResources().getConfiguration().densityDpi;
        Stats.pruneAsync(this);   // 偏好文件里过期的每日统计和已删书的进度键（一天最多一次，后台）
        standalone = getClass() != HomeActivity.class;
        // If VeraHome itself dies, queue a system diagnostic upload before the
        // process goes away. The host watchdog catches native crashes separately.
        installCrashHandler(getApplicationContext());
        pm = getPackageManager();
        prefs = Store.prefs(this, "home");
        Store.purgeLegacyCloud();
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (standalone) seedFromSharedApps();
        if (!standalone) {
        startService(new Intent(this, HostService.class));
        if (prefs.getBoolean("statusbar", true)) StatusBarService.start(this);
        NavBarService.apply(this);
        }
        // the app list changes by itself: the user storage unlocks a moment after we start, and apps get installed / removed
        // from the transfer web page
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_USER_UNLOCKED);
        pf.addAction(Intent.ACTION_BOOT_COMPLETED);
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
        IntentFilter uf = new IntentFilter();
        uf.addAction(Intent.ACTION_USER_UNLOCKED);
        uf.addAction(Intent.ACTION_BOOT_COMPLETED);
        registerReceiver(pkgReceiver, uf);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        setContentView(page);
        // 书库的索引和扫目录不在开机这一刻做：主页上已经没有书了，第一次打开书库（或主页闲下来预先搭书库）时才读
        Perf.coldStart(page);
        themeVer = Theme.version();
        // 开机这一刻不压住第一帧：开机画面盖着屏幕，而 WindowManager 要等主页画出来才算开机完成（压住会拖慢开机）
        if (standalone || getIntent().getBooleanExtra("settings", false)) openSettingsEntry(); else showHome();
        if (!standalone) handler.postDelayed(new Runnable() { @Override public void run() { if (!isFinishing()) DiagConsent.askIfPending(HomeActivity.this); } }, 3000);
    }

    /**
     * 主页的网格重搭以后（改了列数 / 大小）：按真实高度排好那一页之前不让窗口画（最多等 ms 毫秒），
     * 不先画一版再改一版（墨水屏上是两次刷新）。到时间还没好也放行（再安排一次重画，免得没人触发）。
     */
    private long holdUntil;
    private void holdFirstFrame(long ms) {
        holdUntil = android.os.SystemClock.uptimeMillis() + ms;
        handler.postDelayed(new Runnable() { @Override public void run() { page.invalidate(); } }, ms + 50);
        Perf.whenAttached(page, new Runnable() { @Override public void run() {
            page.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    boolean ready = inSettings || (appsLoadedAt > 0 && !gridDirty);
                    if (!ready && android.os.SystemClock.uptimeMillis() < holdUntil) return false;
                    page.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }
            });
        } });
    }

    /** 书库的数据：第一次要用时才读索引、开始监听、后台扫目录。 */
    private boolean libReady;
    /** 设置 → 外观 → “书库”（默认开）。关掉后不显示书库标签和搜索框，也不读索引、不扫目录。 */
    boolean libOn() { return prefs.getBoolean("library", true); }

    void ensureLib() {
        if (libReady || !libOn()) return;
        libReady = true;
        Lib.load(this);
        Lib.addListener(libListener);
        Lib.scan(this, 0);
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            SwitcherActivity.LABELS.clear();
            SwitcherActivity.ICONS.clear();
            handler.removeCallbacks(reloadSoon);
            handler.postDelayed(reloadSoon, 800);
        }
    };
    private final Runnable reloadSoon = new Runnable() { @Override public void run() { reload(); } };

    private final Runnable restoreBarFallback = new Runnable() {
        @Override public void run() { if (!resumed && !StatusBarService.isSuppressed()) StatusBarService.sync(HomeActivity.this); }
    };

    @Override protected void onStop() {
        super.onStop();
        handler.removeCallbacks(restoreBarFallback);
        if (!resumed && !StatusBarService.isSuppressed()) StatusBarService.sync(this);
        if (standalone) {
            if (closingToBack) {
                closingToBack = false;
                tab = -1;
                blocksReturn = -1;
                if (blocks != null && Blocks.on(this) && blocks.current() != BlocksHome.ME) blocks.show(BlocksHome.ME);
                else if (!Blocks.on(this) && inSettings && tab != -1) showSettings();
            }
            return;   // 设置页开了个子页面（Wi-Fi、统计……）：回来时还在原来那一页
        }
        BlocksCards.stopListening(this);
        // coming back (back gesture out of an app) should show the app grid, not whatever page we were on last
        if (inSettings) { tab = -1; showHome(); }
        else if (libPage != null && libPage.searching()) libPage.endSearch();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        Lib.removeListener(libListener);
        stopUploadWatch();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (standalone || i.getBooleanExtra("settings", false)) openSettingsEntry();
        else if (i.getBooleanExtra("library", false) && libOn()) showMain(TAB_LIB);
        else if (inSettings) showHome();
        else if (resumed && blocks != null && Blocks.on(this)) { if (blocks.current() != BlocksHome.HOME) { blocks.show(BlocksHome.HOME); changed(); } }
        else if (resumed && mainTab != TAB_HOME) showMain(TAB_HOME);   // 已经在桌面上再按一次主页：回“主页”标签
    }

    @Override protected void onResume() {
        super.onResume();
        // 方块风格的“继续阅读”：刚离开的第三方应用最后的画面，趁主页还没画先从显示缓冲里取一张缩略图
        if (Blocks.on(this) && leftForApp && !standalone) { leftForApp = false; try { BlocksCards.captureLastApp(this); } catch (Throwable t) { android.util.Log.w("VeraHome", "lastapp: " + t); } }
        resumed = true;
        // 桌面到了前台：马上告诉宿主（换回默认密度、桌面的显示参数），不等任务栈通知——那个通知有时晚好几秒，偶尔整个漏掉
        if (!standalone && AppProf.host()) HostService.foregroundHome();
        // 离开时密度被换过（多看），回来时宿主马上会换回默认（fg home）：等一会儿还不一样才重建（densityCheck）
        handler.removeCallbacks(densityCheck);
        if (getResources().getConfiguration().densityDpi != builtDpi) handler.postDelayed(densityCheck, 1500);
        // 收起输入法是一次跨进程调用：从应用回来时系统正忙，单核机型上主线程要在这里等一秒多（模拟器实测 1.2 s）才开始画主页。
        // 放到后台线程去发（窗口本来就是 SOFT_INPUT_STATE_ALWAYS_HIDDEN，这只是保险）
        final InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        final android.os.IBinder tok = getWindow().getDecorView().getWindowToken();
        // 晚一秒再发：不和主页这一帧的排版 / 画完通知（同样要进 system_server 的窗口锁）挤在一起
        if (imm != null && tok != null) handler.postDelayed(new Runnable() { @Override public void run() {
            new Thread(new Runnable() { @Override public void run() {
                try { imm.hideSoftInputFromWindow(tok, 0); } catch (Throwable ignored) { }
            } }, "hide-ime").start();
        } }, 1000);
        closingToBack = false;
        handler.removeCallbacks(restoreBarFallback);
        StatusBarService.setSuppressed(true);
        StatusBarService.sync(this);
        Panel.closeOnDraw(page);
        HostLink.cmd("status");
        long tv = Theme.version();
        if (tv != themeVer) { themeVer = tv; wallApplied = ""; }   // 在传书网页换了壁纸 / 图标
        if (!standalone && shownStyle != null && !shownStyle.equals(styleName(Blocks.on(this)))) applyStyle(styleName(Blocks.on(this)));
        if (standalone) {
            if (appsLoadedAt == 0) seedFromSharedApps();
            if (appsLoadedAt == 0) { if (blocks != null) reloadAfterFirstDraw(); else reload(); }
            refreshStatus();
            if (blocks != null && Blocks.on(this)) blocks.dataChanged();
        }
        else if (!inSettings) {
            // Returning from an app does not change the launcher list; keep the existing e-ink grid.
            if (appsLoadedAt == 0) { if (blocks != null) reloadAfterFirstDraw(); else reload(); }
            else if (!iconKey().equals(iconCacheKey)) showHome();   // 主题变了：同步重画这一页
            Update.maybe(this);
            // 从阅读器 / 应用回来：进度、时间在窗口重新画出来之前就同步改好，不留到之后再补一次
            if (libReady && Lib.refreshProgress(this) && libPage != null) { if (mainTab == TAB_LIB) libPage.refresh(); else libPage.refreshSoon(); }
            refreshStatus();
            if (blocks != null && Blocks.on(this)) {
                blocks.dataChanged(); blocksWall(blocks.current());
                // 时钟卡片的签名只到“日期”（perf/home-1core 只重搭数据变了的卡片），离开期间过的分钟要在这里补上，不然要等下一次整分钟
                if (!inSettings) blocks.tickClock();
            }
            // 自动更新选了“只提醒”而宿主发现了新版本：列出更新内容让用户选。上面刚让宿主重写状态（status），等它写完再看
            handler.removeCallbacks(updateNotice);
            handler.postDelayed(updateNotice, 2000);
            handler.postDelayed(new Runnable() { @Override public void run() { if (resumed && !inSettings && !standalone) UiMode.maybeAsk(HomeActivity.this); } }, 1200);
        }
        if (libReady) Lib.scan(this, 20000);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60000 - System.currentTimeMillis() % 60000 + 200);
        if (!timeReceiverOn) {
            IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
            tf.addAction(Intent.ACTION_TIME_CHANGED); tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            try { registerReceiver(timeReceiver, tf); timeReceiverOn = true; } catch (Throwable ignored) { }
        }
        showUploadResult();
    }

    private final Runnable updateNotice = new Runnable() { @Override public void run() { if (resumed && !isFinishing()) SysUpdate.maybeNotify(HomeActivity.this); } };

    /** 小白模式 / 资深模式切换后刷新「我的」与各设置页的菜单结构。 */
    void onUiModeChanged() {
        if (blocks != null) {
            blocks.invalidate(BlocksHome.ME);
            blocks.invalidate(BlocksHome.S_DISPLAY);
            blocks.invalidate(BlocksHome.S_POWER);
            blocks.invalidate(BlocksHome.S_STORAGE);
            blocks.invalidate(BlocksHome.S_ABOUT);
            blocks.invalidate(BlocksHome.S_CARDS);
            if (Blocks.on(this)) { blocks.show(blocks.current()); changed(); }
        }
    }

    /** 这次离开主页是去开别的应用（不是自己的阅读器 / 设置页），回来时才取它的画面。 */
    private boolean leftForApp, ownStart, ownStartSuppressesBar;

    /** 所有 startActivity 都走这里：记下打开的是不是 VeraHome 自己的页面（阅读器、统计、商店……）。 */
    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        ownStartSuppressesBar = false;
        try {
            android.content.ComponentName cn = intent.getComponent();
            if (cn != null && getPackageName().equals(cn.getPackageName())) {
                ownStart = true;
                String cls = cn.getClassName();
                ownStartSuppressesBar = cls != null && (cls.endsWith(".HomeActivity") || cls.endsWith(".SettingsActivity"));
            } else if (getPackageName().equals(intent.getPackage()) && intent.hasCategory(Intent.CATEGORY_HOME)) {
                ownStart = true;
                ownStartSuppressesBar = true;
            }
        } catch (Throwable ignored) { }
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        boolean stayHidden = ownStartSuppressesBar || ReaderActivity.opening || closingToBack;
        if (Blocks.on(this) && !ownStart && !standalone) { leftForApp = true; BlocksCards.onLeaveHome(this); }
        ownStart = false;
        ownStartSuppressesBar = false;
        handler.removeCallbacks(tick);
        if (timeReceiverOn) { try { unregisterReceiver(timeReceiver); } catch (Throwable ignored) { } timeReceiverOn = false; }
        if (!stayHidden) {
            StatusBarService.setSuppressed(false);
            handler.removeCallbacks(restoreBarFallback);
            handler.postDelayed(restoreBarFallback, 250);
        }
    }

    @Override public void onBackPressed() {
        // 单独的设置页：在根页（“我的”）按返回就关掉，回到原来的应用
        if (standalone && !inSettings && blocks != null && Blocks.on(this) && blocks.current() == BlocksHome.ME) { finish(); return; }
        if (inSettings) settingsBack();
        else if (blocks != null && Blocks.on(this)) blocks.back();
        else if (libPage != null && libPage.searching()) libPage.endSearch();
    }

    /**
     * 手指正按在桌面上：这时不换整页（BlocksHome.dataChanged 等到抬手）。从阅读器 / 应用回来后书库进度、封面、统计会在后台陆续更新，
     * 首页整页重搭——按下落在旧的那一页上、抬手时它已经被换掉，这一下点按就丢了（用户：“继续阅读”“最近阅读”要点两次）。
     */
    private final List<Runnable> afterTouch = new ArrayList<Runnable>();
    /** 触摸入口：只交一根手指给系统、手势中间页面换了就取消、系统分发出错不崩（见 TouchGuard）。 */
    private final TouchGuard touchGuard = new TouchGuard(new TouchGuard.Target() {
        @Override public boolean superDispatch(android.view.MotionEvent e) { return HomeActivity.super.dispatchTouchEvent(e); }
        @Override public void gestureEnded() {
            if (afterTouch.isEmpty()) return;
            final List<Runnable> l = new ArrayList<Runnable>(afterTouch);
            afterTouch.clear();
            // 抬手的点按（performClick）是 post 出去的：排在它后面再重搭，点到的还是原来那一页
            handler.post(new Runnable() { @Override public void run() { for (Runnable x : l) x.run(); } });
        }
    });
    boolean touching() { return touchGuard.active(); }
    void afterTouch(Runnable r) { if (!afterTouch.contains(r)) afterTouch.add(r); }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        Perf.touch(e);
        return touchGuard.dispatch(e, getWindow().getDecorView());
    }

    private float scale() { return SIZE_SCALE[Math.min(SIZES.length - 1, Math.max(0, prefs.getInt("size", 1)))]; }
    private int dp(int v) { return Ui.dp(this, v); }
    /** A framed value that says what it is now and shows a chevron: tap to go to the next choice. */
    private class ValueButton extends LinearLayout {
        private final TextView label;
        ValueButton(Context c, String t) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(Ui.frame(c, false));
            setPadding(dp(14), dp(8), dp(8), dp(8));
            setClickable(true);
            label = Ui.text(c, t, 18 * scale(), false);
            label.setDuplicateParentStateEnabled(true);
            label.setTextColor(new android.content.res.ColorStateList(new int[][] { { android.R.attr.state_pressed }, {} }, new int[] { Color.WHITE, Color.BLACK }));
            addView(label);
            addView(new Icons.IconView(c, Icons.Kind.CHEV_R), new LinearLayout.LayoutParams(dp(22), dp(22)));
        }
        void setText(CharSequence t) { label.setText(t); }
    }

    private ValueButton valueButton(String t, View.OnClickListener l) {
        ValueButton b = new ValueButton(this, t);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, float sp, boolean bold) { return Ui.text(this, s, sp * scale(), bold); }
    private Button button(String s, View.OnClickListener l) { return Ui.button(this, s, 18 * scale(), false, l); }

    /** Pages change in one step; ask for a flashing full refresh right after, so no ghost of the old page stays. */
    void changed() { if (prefs.getBoolean("flashui", false)) HostLink.flashSoon(handler); }

    // ------------------------------------------------------------------ 方块风格（BlocksHome）用到的入口

    /** 方块风格的控件树；经典风格时是 null。 */
    private BlocksHome blocks;
    /** 从方块风格的设置页进了经典设置的某一类：返回时回到这一页（-1 = 不是从方块风格进来的）。 */
    private int blocksReturn = -1;
    static final int REQ_WIDGET_CONFIG = 41;
    int pendingWidget;
    Runnable pendingWidgetDone;

    View perfRoot() { return page; }
    Handler handler() { return handler; }
    List<ResolveInfo> appList() { return apps; }
    /** 应用列表的版本：只在列表、顺序、名字或图标设置真变了时加一（见 reload）。 */
    long appsVersion() { return appsGen; }
    private long appsGen;
    private String appsIconKey = "";
    ResolveInfo findApp(String pkg) { for (ResolveInfo ri : apps) if (ri.activityInfo.packageName.equals(pkg)) return ri; return null; }
    void refreshStatusNow() { refreshStatus(); }
    /** 方块风格“应用”页的排序按钮：和设置里的“应用排序”是同一个偏好。 */
    void setAppSort(int mode) { prefs.edit().putInt("sort", mode).apply(); reload(); }
    void confirmRestart() { confirm("重启系统？", "正在运行的应用会被关闭。", new Runnable() { @Override public void run() { HostLink.cmd("restart"); } }); }

    /** 换界面风格（经典 / 方块）：存下来，马上按新风格显示主页（一次刷新）。 */
    void setStyle(String style) {
        prefs.edit().putString(Blocks.STYLE_KEY, style).commit();
        applyStyle(style);
    }

    /**
     * 现在显示的是哪种风格（showMain 记下）。在别处换了风格（盖在应用上面的设置页 SettingsActivity，或者传书网页）时，
     * 这个桌面还是老样子，要等进程重启才换（测试机实测：从面板“全部设置”换成方块，回到桌面还是经典）。回到前台时对一下，不一样就按新风格重搭。
     */
    private String shownStyle;
    private static String styleName(boolean blocksOn) { return blocksOn ? "blocks" : "classic"; }

    private void applyStyle(String style) {
        settingsViews[0] = null;   // 设置页上的“界面风格”要跟着变
        java.util.Arrays.fill(settingsSigs, null);
        if (!"blocks".equals(style) && blocks != null) {
            if (blocks.root().getParent() != null) ((ViewGroup) blocks.root().getParent()).removeView(blocks.root());
            blocks = null;
            BlocksCards.stopListening(this);
        }
        tab = -1;
        blocksReturn = -1;
        showMain(TAB_HOME);
        changed();
    }

    /** 界面字重之类会改变每一个字的设置：方块的整棵控件树（含顶上一行和底部标签）丢掉重搭，停在 key 那一页（一次刷新）。 */
    void reloadBlocks(int key) {
        if (blocks != null) {
            if (blocks.root().getParent() != null) ((ViewGroup) blocks.root().getParent()).removeView(blocks.root());
            blocks = null;
        }
        showBlocks(key);
        changed();
    }

    /** 方块风格的设置页里点“更多…”：打开经典设置的某一类（-1 = 分类列表），返回时回到 back 那一页。 */
    void openClassicSettings(int t, int back) {
        if (blocks != null && Blocks.on(this)) { blocks.openCat(t); return; }
        blocksReturn = back;
        tab = t;
        showSettings();
        changed();
    }

    /** 经典设置的分类名（方块风格的设置页标题用）。 */
    static String catName(int t) { return t < 0 ? "全部设置" : t == 0 && !BluetoothActivity.supported() ? "Wi-Fi" : CATS[Math.min(CATS.length - 1, t)]; }
    static final int CAT_COUNT = 7, CAT_XFER_ = 4, CAT_ABOUT_ = 6;

    /** 方块风格：这时正在搭方块样子的设置内容（下面的 section / row / action… 都按方块画），bGroup 是当前的分组框。 */
    private boolean bs;
    private LinearLayout bGroup;

    /** 方块风格的“经典设置某一类”：同一份设置项（同样的偏好键和宿主命令），按方块的样子画。 */
    View blocksCategory(int t) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(8));
        int keep = tab;
        tab = t; bs = true; bGroup = null;
        try { fillSettings(list, HostLink.kv(HostLink.KFB + "settings")); }
        finally { bs = false; bGroup = null; tab = keep; }
        return list;
    }

    private LinearLayout bNewGroup(LinearLayout list, String title) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Blocks.frame(this));
        int st = Blocks.stroke(this), sh = Blocks.shade(this);
        g.setPadding(st, st, st + sh, st + sh);
        if (title != null) g.addView(Blocks.header(this, title));
        list.addView(g, Blocks.gap(this, list.getChildCount() == 0 ? 4 : 14));
        return g;
    }

    /** 放进当前分组（没有就开一个不带标题的），两行之间一条细线。 */
    private void bAdd(LinearLayout list, View v) {
        if (bGroup == null) bGroup = bNewGroup(list, null);
        if (bGroup.getChildCount() > 0 && !(bGroup.getChildCount() == 1 && bGroup.getChildAt(0).getTag() == HEADER_TAG)) bGroup.addView(Blocks.hair(this));
        bGroup.addView(v, Blocks.match());
    }
    private static final Object HEADER_TAG = new Object();

    /** 方块的设置行：左边粗体名称和一两行说明，右边放控件（调用方往 tag 上加）。 */
    private LinearLayout bRow(LinearLayout list, String title, String desc) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(8), dp(8));
        r.setMinimumHeight(dp(60));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(0, 0, dp(8), 0);
        left.addView(Ui.text(this, title, 17, true));
        if (desc != null && desc.length() > 0) {
            TextView d = Ui.text(this, desc, 12, false);
            d.setPadding(0, dp(2), 0, 0);
            d.setMaxLines(3);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            left.addView(d);
        }
        r.addView(left, Blocks.weight(1));
        r.setTag(r);
        bAdd(list, r);
        return r;
    }

    /** 步进器的值格按最长的选项定宽（括号里的说明不进值格）。 */
    private Blocks.Stepper bStepper(String[] labels, int idx, Blocks.Stepper.Listener l) {
        String[] shortL = new String[labels.length];
        android.graphics.Paint p = new android.graphics.Paint();
        p.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
        p.setTypeface(Fonts.typeface(this));
        float w = 0;
        for (int i = 0; i < labels.length; i++) {
            String x = labels[i];
            int k = x.indexOf('（');
            shortL[i] = k > 0 ? x.substring(0, k) : x;
            w = Math.max(w, p.measureText(shortL[i]));
        }
        Blocks.Stepper st = new Blocks.Stepper(this, shortL, idx, l);
        int dpw = (int) (w / getResources().getDisplayMetrics().density) + 24;
        st.setValueWidth(Math.max(112, Math.min(196, dpw)));
        return st;
    }

    /** 和 warm() 一样，但挂到 parent 下面（方块风格的页面挂在它自己的 FrameLayout 里）。 */
    void warmIn(View v, ViewGroup parent) {
        if (page.getWidth() == 0 || parent.getWidth() == 0) return;
        v.measure(View.MeasureSpec.makeMeasureSpec(parent.getWidth(), View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(parent.getHeight(), View.MeasureSpec.EXACTLY));
        v.layout(0, 0, parent.getWidth(), parent.getHeight());
        try { v.draw(warmCanvas(parent.getWidth(), parent.getHeight())); } catch (Throwable ignored) { }
    }

    /**
     * 预先画一遍用的那张不显示的位图：整个预搭过程共用一张，搭完就放掉（{@link #dropWarm}）。以前每预搭一页就新建一张整屏大小的位图再回收，
     * 开机后五六秒里每 0.3 秒扔掉 2–5 MB，单核的老机型上后台 GC 一直在跑（用户诊断：开机后的 GC 每次都释放几 MB 的大对象）。
     */
    private Bitmap warmBmp;
    private android.graphics.Canvas warmCv;
    private android.graphics.Canvas warmCanvas(int w, int h) {
        if (warmBmp == null || warmBmp.getWidth() != w || warmBmp.getHeight() != h) {
            dropWarm();
            warmBmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            warmCv = new android.graphics.Canvas(warmBmp);
        }
        return warmCv;
    }
    private void dropWarm() {
        if (warmBmp != null) warmBmp.recycle();
        warmBmp = null; warmCv = null;
    }

    /** 面板 / 宿主里的“设置”：方块风格打开“我的”（方块的设置），经典风格打开经典设置。 */
    private void openSettingsEntry() {
        if (Blocks.on(this)) { tab = -1; blocksReturn = -1; showBlocks(BlocksHome.ME); changed(); }
        else showSettings();
    }

    private void showBlocks(int key) {
        Perf.measure(page, "blocks main");
        inSettings = false;
        if (blocks == null) { blocks = new BlocksHome(this); if (!standalone) BlocksCards.startListening(this); }
        refreshStatus();
        if (appsLoadedAt == 0) reloadAfterFirstDraw();
        else if (prefs.getInt("sort", 0) != loadedSort) reload();
        blocks.show(key < 0 ? blocks.current() : key);
        showOnly(blocks.root());
        prebuildSettingsSoon();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_WIDGET_CONFIG && pendingWidget != 0) {
            int id = pendingWidget; pendingWidget = 0;
            if (res == RESULT_OK) { BlocksCards.addWidget(this, id); if (pendingWidgetDone != null) pendingWidgetDone.run(); }
            else try { BlocksCards.host(this).deleteAppWidgetId(id); } catch (Throwable ignored) { }
            pendingWidgetDone = null;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (blocks != null) BlocksCards.startListening(this);
    }

    // ------------------------------------------------------------------ home (主页 / 书库)

    static final int TAB_HOME = 0, TAB_LIB = 1;
    private int mainTab = TAB_HOME;
    private LibraryPage libPage;
    private final TextView[] clockViews = new TextView[2], stateViews = new TextView[2];
    private long themeVer;

    /** 主题壁纸：只在主页显示，书库和设置页保持白底。 */
    private String wallApplied = "";
    private void applyWallpaper(boolean home) {
        // 状态没变就不碰：主题版本只在回到前台 / 应用里改了主题时更新，换页面时不查文件
        final String key = home + "|" + themeVer + "|" + prefs.getInt("wallpaperdim", 2) + "|" + prefs.getInt("wallmode", 0);
        if (key.equals(wallApplied)) return;
        wallApplied = key;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        // 只拿已经处理好的那张，主线程不等后台的处理、也不自己解码（Theme.wallpaperIfReady）。还没好就先白底，
        // 后台做好后再贴一次（只重试一次：图打不开时不会来回转）。
        wallHome = home;
        Drawable w = home ? Theme.wallpaperIfReady(this, sw, sh) : null;
        if (w != null) page.setBackground(w); else page.setBackgroundColor(Color.WHITE);
        if (home && w == null && Theme.hasWallpaper() && !wallPending) {
            wallPending = true;
            Theme.prepareAsync(this, new Runnable() { @Override public void run() {
                wallPending = false;
                if (isFinishing() || !wallHome || !key.equals(wallApplied)) return;   // 已经换了页面 / 设置
                Drawable d = Theme.wallpaperIfReady(HomeActivity.this, getResources().getDisplayMetrics().widthPixels,
                                                    getResources().getDisplayMetrics().heightPixels);
                if (d != null) page.setBackground(d);
            } });
        }
    }
    private boolean wallPending, wallHome;

    /**
     * 方块风格：壁纸只铺在“首页”标签下面（和经典风格只在主页显示一样），卡片按“卡片遮盖”画成实心白 / 半透明白。
     * 方块的根控件本身不涂底色，底下就是 page 的背景（白纸或壁纸）。BlocksHome.show 每次换页都会叫这里；
     * 壁纸只取已经在后台处理好的那张（Theme.wallpaperIfReady），主线程不解码。
     */
    void blocksWall(int key) {
        boolean on = key == BlocksHome.HOME && !standalone && Theme.hasWallpaper();
        Blocks.veil = on ? Blocks.wallCardAlpha(this) : 255;
        applyWallpaper(on);
    }

    private void showHome() { if (standalone) { finish(); return; } showMain(mainTab); }

    /**
     * 主页和书库是两棵各自完整的控件树（各有一份顶栏和底栏）：点底栏只是换一棵树，标签高亮、顶栏、内容在同一帧里一起变。
     * 以前书库是另一个 Activity：先起窗口、再后台扫目录、再一格一格补封面，墨水屏上要刷四五次。
     */
    private void showMain(int which) {
        if (standalone) { openSettingsEntry(); return; }   // 单独的设置页里换了界面风格：按新风格再开设置
        shownStyle = styleName(Blocks.on(this));
        if (Blocks.on(this)) { showBlocks(-1); return; }
        if (which == TAB_LIB && !libOn()) which = TAB_HOME;
        Perf.measure(page, which == TAB_LIB ? "library" : "home");
        inSettings = false;
        mainTab = which;
        // 图标缓存只在图标大小、样式或主题变了时才丢：以前每回一次主页就把所有图标重新解码一遍
        String ik = iconKey();
        if (!ik.equals(iconCacheKey)) { iconCache.clear(); iconCacheKey = ik; gridRenderedAt = 0; }
        // 主页的控件树搭一次留着：从设置回来只是换回这棵树。影响排版的设置（列数、大小、时钟格式）变了才重搭。
        String sig = homeSig();
        if (homeRoot == null || !sig.equals(homeSigBuilt)) {
            if (libPage != null && libPage.root().getParent() != null) ((ViewGroup) libPage.root().getParent()).removeView(libPage.root());
            libPage = null;
            homeRoot = new LinearLayout(this);
            homeRoot.setOrientation(LinearLayout.VERTICAL);
            homeSigBuilt = sig;
            gridRenderedAt = 0;
            buildHome();
            homeGrid = gridHolder; homePager = pager; homeLabel = pageLabel;
            // 新搭的网格要等第一次排版才知道一页放几个：排好那一页之前不画，免得先画一版再改
            if (page.isAttachedToWindow()) holdFirstFrame(600);
        } else {
            gridHolder = homeGrid; pager = homePager; pageLabel = homeLabel;
        }
        applyWallpaper(which == TAB_HOME);
        refreshStatus();
        if (which == TAB_LIB) {
            ensureLib();
            if (libPage == null) libPage = new LibraryPage(this);
            else libPage.refresh();
            Lib.scan(this, 20000);   // 后台看看有没有新书；没变化就什么都不动
            showOnly(libPage.root());
        } else {
            if (libPage != null && libPage.searching()) libPage.endSearch();
            showOnly(homeRoot);
        }
        // 列表已经有了、排序方式也没变：直接用它画（应用装卸由 pkgReceiver 触发 reload），不再每回主页都重新查一遍
        if (appsLoadedAt == 0 || prefs.getInt("sort", 0) != loadedSort) reload();
        else if (gridRenderedAt < appsLoadedAt) renderGrid();
        prebuildSettingsSoon();
    }

    private LinearLayout homeRoot;
    private String homeSigBuilt;
    private TextView homeLabel;
    private LinearLayout homeGrid, homePager;
    private long gridRenderedAt;

    private String homeSig() {
        return cols() + "|" + prefs.getInt("size", 1) + "|" + prefs.getBoolean("clock12", false) + "|" + libOn() + "|" + OneHand.on(this);
    }

    /**
     * 顶栏（主页和书库各一份，长得一样）。照 Kindle 主页：最上面一行小字是时间和电量，下面是圆角搜索框，
     * 右边是快捷设置（打开下拉面板）和菜单；最左边多一个“回到 Kindle”，因为我们是跑在 Kindle 里面的另一个系统。
     */
    View topChrome(final int which) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setPadding(dp(20), dp(6), dp(20), 0);
        clockViews[which] = Ui.text(this, "", 14, true);
        st.addView(clockViews[which], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stateViews[which] = Ui.text(this, "", 14, false);
        st.addView(stateViews[which]);
        box.addView(st);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(2), dp(8), dp(6));
        bar.addView(Icons.bar(this, Icons.Kind.EXIT, 28, new View.OnClickListener() {
            @Override public void onClick(View v) { HostLink.cmd("exit"); }
        }));
        // 搜索框在这里只是个按钮（不是输入框，没有焦点和光标）；点了才换成真正的输入框
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(Color.WHITE);
        pbg.setStroke(2, Color.BLACK);
        pbg.setCornerRadius(dp(26));
        pill.setBackground(pbg);
        pill.setPadding(dp(14), dp(9), dp(14), dp(9));
        pill.addView(new Icons.IconView(this, Icons.Kind.SEARCH), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView hint = Ui.text(this, "搜索书名、作者、应用", 17, false);
        hint.setPadding(dp(10), 0, 0, 0);
        hint.setSingleLine(true);
        pill.addView(hint);
        pill.setClickable(true);
        if (!libOn()) pill.setVisibility(View.GONE);   // 搜索在书库页里做：书库关了就不显示
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!libOn()) return;
                if (mainTab != TAB_LIB) showMain(TAB_LIB);
                libPage.beginSearch();
                changed();
            }
        });
        Ui.fast(pill);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        pp.leftMargin = dp(4); pp.rightMargin = dp(4);
        bar.addView(pill, pp);
        bar.addView(Icons.bar(this, Icons.Kind.SLIDERS, 28, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Panel.open(HomeActivity.this);
            }
        }));
        bar.addView(Icons.bar(this, Icons.Kind.MORE, 28, new View.OnClickListener() {
            @Override public void onClick(View v) { mainMenu(); }
        }));
        box.addView(bar);
        return box;
    }

    /** 底栏：主页 / 书库，和 Kindle 的 Home / Library 一样固定在最下面。当前的那个加粗、上面压一条粗线。 */
    View bottomNav(int which) {
        if (!libOn()) { View none = new View(this); none.setVisibility(View.GONE); return none; }   // 只剩“主页”一个标签：整条底栏不要
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        box.addView(Ui.rule(this, 2, Color.BLACK));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        final String[] names = { "主页", "书库" };
        final Icons.Kind[] kinds = { Icons.Kind.HOME, Icons.Kind.BOOK };
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            View mark = new View(this);
            mark.setBackgroundColor(i == which ? Color.BLACK : Color.WHITE);
            cell.addView(mark, new LinearLayout.LayoutParams(dp(72), dp(4)));
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            line.setPadding(0, dp(9), 0, dp(12));
            line.addView(new Icons.IconView(this, kinds[i]), new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView t = Ui.text(this, names[i], 18, i == which);
            t.setPadding(dp(8), 0, 0, 0);
            line.addView(t);
            cell.addView(line);
            cell.setClickable(true);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == mainTab && !inSettings) { if (idx == TAB_LIB && libPage != null && libPage.searching()) { libPage.endSearch(); changed(); } return; }
                    showMain(idx);
                    changed();
                }
            });
            Ui.fast(cell);
            nav.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(nav);
        return box;
    }

    /** 顶栏右边的菜单（Kindle 主页右上角的三点菜单）。 */
    void mainMenu() {
        new VeraDialog.Builder(this)
            .items(new String[] { "传书", "软件商店", "使用统计", "全部设置", "整屏刷新一次", "回到 Kindle", "关机…" }, new VeraDialog.OnPick() {
                @Override public void onPick(int which) {
                    switch (which) {
                    case 0: startActivity(new Intent(HomeActivity.this, TransferActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); overridePendingTransition(0, 0); break;
                    case 1: startActivity(new Intent(HomeActivity.this, StoreActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); overridePendingTransition(0, 0); break;
                    case 2: startActivity(new Intent(HomeActivity.this, StatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); overridePendingTransition(0, 0); break;
                    case 3: tab = -1; showSettings(); changed(); break;
                    case 4: HostLink.cmd("clear"); break;
                    case 5: HostLink.cmd("exit"); break;
                    default: powerMenu(); break;
                    }
                }
            }).show();
    }

    private void buildHome() {
        View oneHand = OneHand.topSpacer(this);   // 单手模式：上面空出一块（放“下拉面板”），顶栏和网格挪到下半屏
        if (oneHand != null) homeRoot.addView(oneHand);
        homeRoot.addView(topChrome(TAB_HOME));
        homeRoot.addView(Ui.rule(this, 2, Color.BLACK));

        // 主页就是应用网格（原来上面的“继续阅读”去掉了：书都在“书库”里，点书直接接着读）
        PagedBox grid = new PagedBox(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(8), dp(10), dp(8), dp(4));
        grid.setListener(new PagedBox.Listener() {
            @Override public void onPage(int dir) { int before = gridPage; gridPage = Math.max(0, gridPage + dir); renderGrid(); if (gridPage != before) changed(); }
        });
        gridHolder = grid;
        homeRoot.addView(gridHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        pager = new LinearLayout(this);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER);
        pager.setPadding(0, dp(2), 0, dp(2));
        pager.addView(Icons.bar(this, Icons.Kind.BACK, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { if (gridPage > 0) { gridPage--; renderGrid(); changed(); } }
        }));
        pageLabel = text("", 16, false);
        pageLabel.setPadding(dp(20), 0, dp(20), 0);
        pager.addView(pageLabel);
        pager.addView(Icons.bar(this, Icons.Kind.CHEV_R, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { int before = gridPage; gridPage++; renderGrid(); if (gridPage != before) changed(); }
        }));
        homeRoot.addView(pager);
        homeRoot.addView(bottomNav(TAB_HOME));

        // how many cells fit depends on the real height of the holder: wait for the first layout
        gridHolder.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int h = b - t - v.getPaddingTop() - v.getPaddingBottom();
                int rows = Math.max(1, h / cellHeight());
                int np = rows * cols();
                if (np != perPage && h > 0) { perPage = np; gridDirty = true; v.post(new Runnable() { @Override public void run() { renderGrid(); } }); }
            }
        });
    }

    private final Lib.Listener libListener = new Lib.Listener() {
        @Override public void onLibraryChanged(boolean listChanged) {
            if (isFinishing() || isDestroyed()) return;
            if (libPage != null) libPage.onLibraryChanged(listChanged, !inSettings && mainTab == TAB_LIB && blocks == null);
            if (blocks != null && !inSettings) { if (listChanged) blocks.files.forget(); blocks.dataChanged(); }
        }
    };

    /** 长按一本书：和 Kindle 长按封面出来的那张菜单一个思路，只放我们真有的操作。 */
    void bookMenu(final Lib.Book b) {
        final List<String> items = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        if (b.builtin()) { items.add("打开"); acts.add(new Runnable() { @Override public void run() { Lib.openBuiltin(HomeActivity.this, b); } }); }
        items.add("用 KOReader 打开"); acts.add(new Runnable() { @Override public void run() { Lib.openKoreader(HomeActivity.this, b); } });
        items.add(b.percent >= 100 ? "标为未读完" : "标为已读"); acts.add(new Runnable() { @Override public void run() { Lib.markDone(HomeActivity.this, b, b.percent < 100); } });
        items.add("书籍信息"); acts.add(new Runnable() { @Override public void run() {
            new VeraDialog.Builder(HomeActivity.this).title(b.title)
                .message((b.author != null && b.author.length() > 0 ? "作者：" + b.author + "\n" : "") + "格式：" + b.ext()
                    + "\n大小：" + (b.size >= 1048576 ? (b.size * 10 / 1048576) / 10.0 + " MB" : Math.max(1, b.size / 1024) + " KB")
                    + "\n进度：" + (b.percent >= 100 ? "已读完" : b.lastRead > 0 ? b.percent + "%" : "未读")
                    + "\n位置：" + b.path)
                .positive("知道了", null).show();
        } });
        items.add("默认阅读器…"); acts.add(new Runnable() { @Override public void run() { readerDefaultDialog(); } });
        items.add("删除…"); acts.add(new Runnable() { @Override public void run() {
            confirm("删除《" + b.title + "》？", "文件会从设备上删掉，不能恢复。", new Runnable() {
                @Override public void run() { if (!Lib.delete(HomeActivity.this, b)) VeraToast.show(HomeActivity.this, "删不掉（权限或只读）"); }
            });
        } });
        new VeraDialog.Builder(this).title(b.title).items(items.toArray(new String[0]), new VeraDialog.OnPick() {
            @Override public void onPick(int which) { acts.get(which).run(); }
        }).show();
    }

    private void readerDefaultDialog() {
        String cur = prefs.getString("reader_default", "builtin");
        new VeraDialog.Builder(this).title("默认阅读器")
            .items(new String[] { "VeraDroid 阅读器（直接打开）", "KOReader", "每次询问" }, new VeraDialog.OnPick() {
                @Override public void onPick(int which) {
                    prefs.edit().putString("reader_default", which == 0 ? "builtin" : which == 1 ? "koreader" : "ask").apply();
                }
            }).selected("koreader".equals(cur) ? 1 : "ask".equals(cur) ? 2 : 0).negative("取消", null).show();
    }

    /** 搜索用：名字里有这段字的应用。 */
    List<ResolveInfo> appsMatching(String q) {
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        String s = q.toLowerCase(Locale.US);
        for (ResolveInfo ri : apps) if (labelFor(ri).toLowerCase(Locale.US).contains(s)) out.add(ri);
        return out;
    }

    /** 显示用的名字：用户改过的名字（长按 → 重命名）优先。 */
    String labelFor(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        String own = prefs.getString("label_" + pkg, null);
        if (own != null && own.length() > 0) return own;
        String label = appLabels.get(pkg);
        return label == null ? String.valueOf(ri.loadLabel(pm)) : label;
    }

    private int loadedSort = -1;

    private int cols() { return Math.max(2, Math.min(5, prefs.getInt("cols", 3))); }
    private int iconPx() { return (int) (dp(84) * scale()); }
    private int cellHeight() { return iconPx() + (int) (dp(64) * scale()) + dp(12); }

    private boolean gridDirty;

    private void renderGrid() {
        if (gridHolder == null) return;
        gridDirty = false;
        gridRenderedAt = System.currentTimeMillis();
        gridHolder.removeAllViews();
        // 主页只放已安装的应用；图书馆在底部导航里单独打开。
        java.util.List<ResolveInfo> items = new java.util.ArrayList<ResolveInfo>(apps);
        // store + stats tiles are always first. The Send-to-Kindle tile was only an instruction dialog
        // (Amazon delivers to the native Kindle library, not to VeraDroid), so it is gone.
        int total = items.size() + 2;
        int pages = Math.max(1, (total + perPage - 1) / perPage);
        if (gridPage >= pages) gridPage = pages - 1;
        int c = cols(), from = gridPage * perPage, to = Math.min(total, from + perPage);
        LinearLayout row = null;
        for (int i = from; i < to; i++) {
            if ((i - from) % c == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                gridHolder.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellHeight()));
            }
            if (i == 0) row.addView(storeCell(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            else if (i == 1) row.addView(statsCell(), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            else row.addView(cell(items.get(i - 2)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        if (row != null) for (int k = (to - from) % c; k != 0 && k < c; k++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (apps.isEmpty() && gridPage == 0) {
            TextView empty = text("还没有安装其他应用。\n\n点右上角菜单里的“传书”，或把 APK 放进 veradroid/apps。\n\n软件商店在左上角。", 16, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(32), dp(24), dp(32), 0);
            gridHolder.addView(empty);
        }
        pager.setVisibility(pages > 1 ? View.VISIBLE : View.INVISIBLE);
        pageLabel.setText((gridPage + 1) + " / " + pages);
    }

    /** Pinned store tile: looks like an installed app, but is part of VeraHome. */
    private View storeCell() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(4), dp(10), dp(4), dp(8));
        Icons.IconView icon = new Icons.IconView(this, Icons.Kind.STORE);
        c.addView(icon, new LinearLayout.LayoutParams(iconPx(), iconPx()));
        TextView t = text("软件商店", 17, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, 0);
        c.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, StoreActivity.class));
                overridePendingTransition(0, 0);
            }
        });
        return c;
    }

    /** Local battery/reading stats; not an installed APK. */
    private View statsCell() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(4), dp(10), dp(4), dp(8));
        Icons.IconView icon = new Icons.IconView(this, Icons.Kind.CHART);
        c.addView(icon, new LinearLayout.LayoutParams(iconPx(), iconPx()));
        TextView t = text("统计", 17, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, 0);
        c.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, StatsActivity.class)); overridePendingTransition(0, 0); }
        });
        return c;
    }

    private View cell(final ResolveInfo ri) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(4), dp(10), dp(4), dp(8));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(iconFor(ri));
        c.addView(icon, new LinearLayout.LayoutParams(iconPx(), iconPx()));
        TextView t = text(labelFor(ri), 17, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, 0);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { launch(ri); } });
        c.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { appMenu(ri); return true; } });
        return c;
    }

    /** 图标样式：0 原样，1 黑白描框（灰度、缩进一圈、外面一个圆角黑框）。 */
    private int iconStyle() { return prefs.getInt("iconstyle", 0) == 1 ? 1 : 0; }
    private String iconCacheKey = "";
    /** 图标缓存的有效条件：图标大小、样式、主题（自定义图标）都没变。 */
    private String iconKey() { return iconPx() + "|" + iconStyle() + "|" + themeVer; }

    /** 网格里的图标：一张正好 iconPx 见方的位图，解一次、缩一次就留着用（画的时候不再缩放）。 */
    Drawable iconFor(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        Drawable cached = iconCache.get(pkg);
        if (cached != null) return cached;
        Drawable d = loadIcon(ri, iconPx(), iconStyle());
        iconCache.put(pkg, d);
        return d;
    }

    /**
     * 不碰缓存，后台线程也能调。自定义图标（主题目录 / VeraDroid/icons / 老的 Icons 目录）优先，只解到需要的大小；
     * 画到一张 size 见方的位图上，原图马上回收。
     */
    private Drawable loadIcon(ResolveInfo ri, int size, int style) {
        String pkg = ri.activityInfo.packageName;
        Bitmap custom = null;
        File f = Theme.iconFile(pkg);
        if (f != null) custom = Theme.decodeAtLeast(f, size);
        Drawable src;
        try { src = custom != null ? new android.graphics.drawable.BitmapDrawable(getResources(), custom) : ri.loadIcon(pm).mutate(); }
        catch (Throwable t) { src = new ColorDrawable(Color.TRANSPARENT); }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        if (style == 1) {
            float sw = Math.max(2f, size / 40f);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(Color.WHITE);
            android.graphics.RectF r = new android.graphics.RectF(sw / 2, sw / 2, size - sw / 2, size - sw / 2);
            c.drawRoundRect(r, size * 0.2f, size * 0.2f, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(Color.BLACK);
            c.drawRoundRect(r, size * 0.2f, size * 0.2f, p);
            android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
            m.setSaturation(0);
            src.setColorFilter(new android.graphics.ColorMatrixColorFilter(m));
            int pad = Math.round(size * 0.18f);
            src.setBounds(pad, pad, size - pad, size - pad);
        } else src.setBounds(0, 0, size, size);
        src.draw(c);
        if (custom != null) custom.recycle();
        return new android.graphics.drawable.BitmapDrawable(getResources(), out);
    }

    private int reloadGen;
    private boolean reloadQueued, firstDrawn;

    /**
     * 方块风格开机第一次读应用列表：等主页第一帧画出来再开始。以前 onCreate 和 onResume 各起一个读取线程，
     * 而且图标从磁盘缓存读得快时，结果会赶在第一帧之前回来，首页还没画就要再换一次卡片；读取时对系统服务的一串跨进程调用
     * 也和窗口的第一次排版抢 system_server（单核机型上第一帧要多等几百毫秒）。经典风格的网格要等应用列表（holdFirstFrame），照旧马上读。
     */
    private void reloadAfterFirstDraw() {
        if (reloadGen > 0) return;   // 第一次读取已经开始了（还没回来）
        if (firstDrawn) { reload(); return; }
        if (reloadQueued) return;
        reloadQueued = true;
        Perf.whenAttached(page, new Runnable() { @Override public void run() {
            page.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
                private boolean done;
                @Override public void onDraw() {
                    if (done) return;
                    done = true;
                    final android.view.ViewTreeObserver.OnDrawListener self = this;
                    handler.post(new Runnable() { @Override public void run() {
                        try { page.getViewTreeObserver().removeOnDrawListener(self); } catch (Throwable ignored) { }
                        firstDrawn = true;
                        reloadQueued = false;
                        if (appsLoadedAt == 0) reload();
                    } });
                }
            });
        } });
    }

    /**
     * 后台读应用列表时用：做好的图标（已经按大小、样式画好的那张）存在缓存目录里，下次进程起来直接解这张小 PNG，
     * 不再每次开机都从每个 APK 里读资源、缩放、重画（单核机型上一个应用几十毫秒）。应用更新、图标设置或主题变了，文件名就变了。
     */
    private Drawable cachedIcon(ResolveInfo ri, int size, int style, long upd, String ik) {
        String pkg = ri.activityInfo.packageName;
        File dir = Store.cache("icons");
        String prefix = pkg + "-" + Integer.toHexString(ri.activityInfo.name.hashCode()) + "-";
        String name = prefix + Integer.toHexString((upd + "|" + size + "|" + style + "|" + ik).hashCode()) + ".png";
        File f = new File(dir, name);
        if (upd > 0 && f.isFile()) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (bm != null && bm.getWidth() == size) return new android.graphics.drawable.BitmapDrawable(getResources(), bm);
            } catch (Throwable ignored) { }
        }
        Drawable d = loadIcon(ri, size, style);
        if (upd > 0 && d instanceof android.graphics.drawable.BitmapDrawable) {
            File[] old = dir.listFiles();
            if (old != null) for (File x : old) if (x.getName().startsWith(prefix)) x.delete();
            File tmp = new File(dir, name + ".tmp");
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                try { ((android.graphics.drawable.BitmapDrawable) d).getBitmap().compress(Bitmap.CompressFormat.PNG, 100, out); } finally { out.close(); }
                if (!tmp.renameTo(f)) tmp.delete();
            } catch (Throwable t) { tmp.delete(); }
        }
        return d;
    }

    /**
     * 重新读已装应用：查询、取名称 / 安装时间、排序、解码图标都在后台线程做，完了回主线程一次换掉列表再画网格。
     * 以前全在主线程：装了几十个应用时，回到主页 / 装卸应用都要卡一下。连着来几次时只用最后一次的结果。
     */
    private void reload() {
        final int gen = ++reloadGen;
        final int mode = prefs.getInt("sort", 0);
        final String self = getPackageName();
        final String ik = iconKey();
        final int size = iconPx(), style = iconStyle();
        new Thread(new Runnable() {
            @Override public void run() {
                Ui.lowPriority();
                final List<ResolveInfo> list = new ArrayList<ResolveInfo>();
                final Map<String, Long> inst = new HashMap<String, Long>();
                final Map<String, String> labels = new HashMap<String, String>();
                final Map<String, Drawable> icons = new HashMap<String, Drawable>();
                try {
                    Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                        String pkg = ri.activityInfo.packageName;
                        if (self.equals(pkg)) continue;
                        ApplicationInfo ai = ri.activityInfo.applicationInfo;
                        // system apps (Settings and the like) are not for this desktop; an updated system app stays visible
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
                        list.add(ri);
                        try { labels.put(pkg, String.valueOf(ri.loadLabel(pm))); } catch (Throwable ignored) { }
                        long upd = 0;
                        try { android.content.pm.PackageInfo pi = pm.getPackageInfo(pkg, 0); inst.put(pkg, pi.firstInstallTime); upd = pi.lastUpdateTime; } catch (Exception ignored) { }
                        try { icons.put(pkg, cachedIcon(ri, size, style, upd, ik)); } catch (Throwable ignored) { }
                    }
                    final Collator zh = Collator.getInstance(Locale.CHINA);
                    Collections.sort(list, new Comparator<ResolveInfo>() {
                        @Override public int compare(ResolveInfo a, ResolveInfo b) {
                            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
                            switch (mode) {
                            case 0: return Long.compare(prefs.getLong("t_" + pb, 0), prefs.getLong("t_" + pa, 0));
                            case 1: return Long.compare(prefs.getLong("t_" + pa, 0), prefs.getLong("t_" + pb, 0));
                            case 3: return pa.compareToIgnoreCase(pb);
                            case 4: return Long.compare(get(pb), get(pa));
                            default: {
                                String la = labels.get(pa), lb = labels.get(pb);
                                return zh.compare(la == null ? pa : la, lb == null ? pb : lb);
                            }
                            }
                        }
                        private long get(String p) { Long v = inst.get(p); return v == null ? 0 : v; }
                    });
                } catch (Throwable t) { android.util.Log.w("VeraHome", "reload: " + t); }
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (gen != reloadGen || isDestroyed()) return;
                        // 和屏幕上的一样（同样的应用、顺序、名字，图标也是同一套设置做的）就不重画：没有平白多出的一次刷新
                        boolean sameList = sameApps(list, labels) && ik.equals(appsIconKey);
                        boolean same = gridRenderedAt > 0 && ik.equals(iconCacheKey) && sameList;
                        // 方块风格的页面按 appsVersion 认“应用变了没有”：只有列表 / 顺序 / 名字 / 图标设置真变了才算新版本，
                        // 不然每读一次（开机、回到前台、装卸别的应用）首页和应用页都要整页重搭一次
                        if (!sameList) appsGen++;
                        appsIconKey = ik;
                        apps.clear(); apps.addAll(list);
                        installed.clear(); installed.putAll(inst);
                        appLabels.clear(); appLabels.putAll(labels);
                        if (ik.equals(iconKey())) { iconCache.clear(); iconCache.putAll(icons); iconCacheKey = ik; }
                        appsLoadedAt = System.currentTimeMillis();
                        loadedSort = mode;
                        synchronized (sSharedApps) {
                            sSharedApps.clear(); sSharedApps.addAll(apps);
                            sSharedInstalled.clear(); sSharedInstalled.putAll(installed);
                            sSharedLabels.clear(); sSharedLabels.putAll(appLabels);
                            sSharedIcons.clear(); sSharedIcons.putAll(iconCache);
                            sSharedIconKey = iconCacheKey;
                            sSharedGen = appsGen;
                            sSharedSort = loadedSort;
                            sSharedLoadedAt = appsLoadedAt;
                        }
                        if (same) gridRenderedAt = appsLoadedAt; else renderGrid();
                        if (blocks != null) blocks.appsChanged();
                    }
                });
            }
        }, "home-reload").start();
    }

    private boolean sameApps(List<ResolveInfo> list, Map<String, String> labels) {
        if (list.size() != apps.size()) return false;
        for (int i = 0; i < list.size(); i++) {
            String a = list.get(i).activityInfo.packageName, b = apps.get(i).activityInfo.packageName;
            if (!a.equals(b) || !String.valueOf(labels.get(a)).equals(String.valueOf(appLabels.get(a)))) return false;
        }
        return true;
    }

    /**
     * 顶栏那一行小字：时间在左，Wi-Fi 和电量在右。只在换页面时和整分钟时更新——以前回到主页 0.8 秒后还要改一次
     *（里面有“可用内存”，每次都不一样），墨水屏上就是页面画完一秒多以后角上又刷一小块。
     */
    private void refreshStatus() {
        String name = prefs.getString("name", "VeraDroid");
        String clock = Ui.clock(this) + (name.length() > 0 ? "    " + name : "");
        Map<String, String> st = HostLink.status(this);
        StringBuilder sb = new StringBuilder();
        if ("0".equals(st.get("wifi"))) sb.append("Wi-Fi 关   ");
        if (st.get("battery") != null) {
            if ("1".equals(st.get("charging"))) sb.append("充电中 ");
            sb.append(st.get("battery")).append("%");
        }
        String state = sb.toString();
        if (blocks != null) blocks.refreshStatus(Ui.clock(this), state);
        for (int i = 0; i < 2; i++) {
            if (clockViews[i] != null && !clock.equals(String.valueOf(clockViews[i].getText()))) clockViews[i].setText(clock);
            if (stateViews[i] != null && !state.equals(String.valueOf(stateViews[i].getText()))) stateViews[i].setText(state);
        }
    }

    private void say(String s) { VeraToast.show(this, s); }

    void launch(ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        prefs.edit().putLong("t_" + pkg, System.currentTimeMillis()).apply();   // 不在点图标的那一下同步写盘
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        i.setClassName(pkg, ri.activityInfo.name);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ActivityManager am = null;
            int warmTaskId = -1;
            try {
                am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<ActivityManager.RunningTaskInfo> tasks = am != null ? am.getRunningTasks(12) : null;
                if (tasks != null) {
                    for (ActivityManager.RunningTaskInfo t : tasks) {
                        if (t.numActivities > 0 && ((t.topActivity != null && pkg.equals(t.topActivity.getPackageName()))
                                || (t.baseActivity != null && pkg.equals(t.baseActivity.getPackageName())))) {
                            warmTaskId = t.id;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            boolean warm = warmTaskId >= 0;
            if (!warm) HostLink.cmd("appdirs " + pkg);
            if (AppProf.host()) {
                HostService.launchedPkg = pkg;
                AppProf.launching(pkg, warm);
            } else {
                String spec = prefs.getString("appdisp_" + pkg, "");
                if (spec == null) spec = "";
                if (spec.length() > 0) HostLink.cmd("appdisp " + pkg + "|" + spec);
            }
            AppFont.forForeground(this, pkg, null);
            boolean moved = false;
            if (warm && am != null) {
                try { am.moveTaskToFront(warmTaskId, 0); moved = true; } catch (Throwable ignored) { }
            }
            if (!moved) startActivity(i);
            overridePendingTransition(0, 0);
            if (!moved) {
                // JNI/native cold launches can take a while to paint; ask the host for one GC16 only on cold launch
                handler.postDelayed(new Runnable() { @Override public void run() { HostLink.cmd("clear"); } }, 1500);
            }
        } catch (Exception e) { say("启动失败：" + e.getMessage()); }
    }

    // ------------------------------------------------------------------ app menu (long press)

    void appMenu(final ResolveInfo ri) {
        final String pkg = ri.activityInfo.packageName;
        final String name = labelFor(ri);
        final List<String> items = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        items.add("打开"); acts.add(new Runnable() { @Override public void run() { launch(ri); } });
        items.add("更换图标…"); acts.add(new Runnable() { @Override public void run() {
            pickImage("给“" + name + "”选一张图标", new PickFile() {
                @Override public void picked(final File f) {
                    // 解码用户的图（可能很大、可能在 FUSE 上）+ 压 PNG 放到后台：以前在主线程上做，点完要卡 0.5–2 秒
                    new Thread(new Runnable() { @Override public void run() {
                        final String err = Theme.setIcon(pkg, f);
                        runOnUiThread(new Runnable() { @Override public void run() {
                            if (isFinishing()) return;
                            if (err != null) { say(err); return; }
                            iconChanged(pkg);
                        } });
                    } }, "icon-set").start();
                }
            });
        } });
        if (Theme.hasOwnIcon(pkg)) { items.add("恢复默认图标"); acts.add(new Runnable() { @Override public void run() { Theme.clearIcon(pkg); iconChanged(pkg); } }); }
        items.add("重命名…"); acts.add(new Runnable() { @Override public void run() { renameDialog(ri); } });
        items.add("应用优化"); acts.add(new Runnable() { @Override public void run() {
            Intent i = new Intent(HomeActivity.this, AppDisplayActivity.class); i.putExtra("pkg", pkg); i.putExtra("label", name); startActivity(i);
        } });
        if (Cleaner.userApp(this, pkg)) {
            final boolean kept = Cleaner.keepAlive(this, pkg);
            items.add(kept ? "保持后台：开（点一下关掉）" : "保持后台：关（点一下打开）"); acts.add(new Runnable() { @Override public void run() {
                Cleaner.setKeepAlive(HomeActivity.this, pkg, !kept);
                say(!kept ? "“" + name + "”会一直留在后台，不会被自动清理" : "“" + name + "”恢复按“后台保留应用”清理");
            } });
        }
        items.add("强制停止"); acts.add(new Runnable() { @Override public void run() { forceStop(pkg); say("已停止 " + name); } });
        items.add("清除缓存"); acts.add(new Runnable() { @Override public void run() { clearCache(pkg); say("已清除缓存"); } });
        items.add("清除数据…"); acts.add(new Runnable() { @Override public void run() {
            confirm("清除“" + name + "”的数据？", "登录状态、设置和下载的内容都会被清掉，等于重新安装。", new Runnable() {
                @Override public void run() { clearData(pkg); say("已清除数据"); }
            });
        } });
        items.add("卸载…"); acts.add(new Runnable() { @Override public void run() {
            confirm("卸载“" + name + "”？", "应用和它的数据都会被删除。", new Runnable() {
                @Override public void run() {
                    uninstall(pkg); say("正在卸载…");
                    handler.postDelayed(new Runnable() { @Override public void run() { reload(); say("已卸载"); } }, 2500);
                }
            });
        } });
        new VeraDialog.Builder(this).title(name).items(items.toArray(new String[0]), new VeraDialog.OnPick() {
            @Override public void onPick(int which) { acts.get(which).run(); }
        }).show();
    }

    /** 一个应用的图标换了：只丢它这一个的缓存，同步重画当前这一页（一次刷新）。 */
    private void iconChanged(String pkg) {
        themeVer = Theme.version();
        iconCacheKey = iconKey();       // 别的应用的图标还能用
        iconCache.remove(pkg);
        SwitcherActivity.ICONS.remove(pkg);
        appsGen++; appsIconKey = iconCacheKey;
        if (blocks != null) blocks.appsChanged();
        if (!inSettings && mainTab == TAB_HOME) renderGrid(); else gridRenderedAt = 0;
        changed();
    }

    /** 改应用在桌面上显示的名字；留空就恢复原名。存在偏好 label_<包名>。 */
    private void renameDialog(final ResolveInfo ri) {
        final String pkg = ri.activityInfo.packageName;
        final EditText input = new EditText(this);
        input.setText(labelFor(ri));
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setTypeface(Fonts.typeface(this));
        input.setBackground(Ui.frame(this, false));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        String orig = appLabels.get(pkg);
        new VeraDialog.Builder(this).title("重命名").message("最多 16 个字；留空恢复原来的名字" + (orig != null ? "（" + orig + "）" : "") + "。")
            .view(input).negative("取消", null)
            .positive("保存", new Runnable() {
                @Override public void run() {
                    String t = input.getText().toString().trim();
                    if (t.length() > 16) t = t.substring(0, 16);
                    if (t.length() == 0 || t.equals(appLabels.get(pkg))) prefs.edit().remove("label_" + pkg).apply();
                    else prefs.edit().putString("label_" + pkg, t).apply();
                    appsGen++;
                    if (blocks != null) blocks.appsChanged();
                    renderGrid();
                    changed();
                }
            }).show();
    }

    interface PickFile { void picked(File f); }

    /**
     * 选一张图片：在后台把常放图片的几个文件夹（VeraDroid/wallpaper、Pictures、Download、DCIM、Books、Kindle 的 documents……）
     * 找一遍，再弹一张列表（新的在前）。没有缩略图：几十张图片一张张解码太慢，墨水屏上也看不出多少。
     */
    private void pickImage(final String title, final PickFile cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<File> imgs = Theme.findImages();
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (imgs.isEmpty()) {
                            new VeraDialog.Builder(HomeActivity.this).title(title)
                                .message("没有找到图片。\n\n用传书网页的“主题”页上传，或者把 jpg / png 放进内部存储的 VeraDroid/wallpaper 文件夹、Kindle 的 documents 文件夹，再来选。")
                                .positive("知道了", null).show();
                            return;
                        }
                        String[] names = new String[imgs.size()];
                        for (int i = 0; i < names.length; i++) {
                            File f = imgs.get(i);
                            names[i] = f.getName() + "\n" + Theme.placeLabel(f) + " · " + Math.max(1, f.length() / 1024) + " KB";
                        }
                        new VeraDialog.Builder(HomeActivity.this).title(title).items(names, new VeraDialog.OnPick() {
                            @Override public void onPick(int which) { cb.picked(imgs.get(which)); }
                        }).negative("取消", null).show();
                    }
                });
            }
        }, "pick-image").start();
    }

    private void pickWallpaper() {
        pickImage("选择壁纸", new PickFile() {
            @Override public void picked(File f) {
                say("正在处理壁纸…");
                // 复制、解码、缩放、转灰度都在后台；做完才重画设置页（多出“铺法 / 深浅 / 不用壁纸”几行）
                Theme.setWallpaper(HomeActivity.this, f, new Theme.Done() {
                    @Override public void done(String error) {
                        if (error != null) say(error);
                        themeVer = Theme.version(); wallApplied = "";
                        if (inSettings && !isFinishing()) showSettings();
                    }
                });
            }
        });
    }

    /** VeraDroid 自己的白色对话框：不用 Android 的 Holo/原生关机提示。 */
    private void box(String title, String msg, String[] labels, final Runnable[] actions) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(2, Color.BLACK);
        bg.setCornerRadius(dp(6));
        box.setBackground(bg);
        box.setPadding(dp(24), dp(20), dp(24), dp(16));
        box.addView(text(title, 24, true));
        TextView m = text(msg, 17, false);
        m.setPadding(0, dp(10), 0, dp(18));
        box.addView(m);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.RIGHT);
        for (int i = 0; i < labels.length; i++) {
            final Runnable a = actions[i];
            Button b = Ui.button(this, labels[i], 18 * scale(), i == labels.length - 1, new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); if (a != null) a.run(); }
            });
            row.addView(b);
        }
        box.addView(row);
        d.setContentView(box);
        d.show();
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.82), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(0);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);   // 墨水屏不压暗背景
        }
    }

    private void confirm(String title, String msg, final Runnable yes) {
        box(title, msg, new String[] { "取消", "确定" }, new Runnable[] { null, yes });
    }

    /** 检查系统更新：先列出新版本的更新内容，用户选“更新”才开始（SysUpdate）。 */
    void fullUpdateDialog() { SysUpdate.check(this); }

    private void fontDialog() {
        final java.util.List<Books.Font> fonts = Books.fonts();
        final String[] names = new String[fonts.size()];
        int selected = 0;
        String current = prefs.getString("system_font_path", null);
        for (int i = 0; i < fonts.size(); i++) {
            names[i] = fonts.get(i).name;
            if ((fonts.get(i).path == null && current == null) || (fonts.get(i).path != null && fonts.get(i).path.equals(current))) selected = i;
        }
        new VeraDialog.Builder(this)
            .title("系统字体")
            .items(names, new VeraDialog.OnPick() {
                @Override public void onPick(int which) {
                    Books.Font f = fonts.get(which);
                    prefs.edit().putString("system_font_path", f.path).putString("system_font_name", f.name)
                        .putString("reader_font_path", f.path).putString("reader_font_name", f.name).commit();
                    say("系统字体已设为 " + f.name);
                    showSettings();
                    if (prefs.getBoolean("system_font_all", true)) systemFontAll(f.path);
                }
            })
            .selected(selected)
            .negative("取消", null)
            .show();
    }

    /**
     * 系统字体也给其他应用用（宿主的 SYSTEM_FONT：开机前把改过的 fonts.xml 和字体文件绑进系统，见 host 的 container.sh）。
     * path = null：其他应用回到原样字体。zygote 开机时预载字体，要重启 Android 才生效：和宿主现在用着的不一样就问一下。
     */
    private void systemFontAll(String path) {
        HostLink.settings("SYSTEM_FONT=" + (path == null ? "default" : path) + "\n");
        String live = HostLink.kv(HostLink.KFB + "settings").get("SYSTEM_FONT_LIVE");
        if ((path == null ? "" : path).equals(live == null ? "" : live)) return;
        box("重启 Android", "需要重启 Android 才能让其他应用生效（约 15 秒）。", new String[] { "稍后", "现在重启" },
            new Runnable[] { null, new Runnable() { @Override public void run() { HostLink.cmd("restart"); } } });
    }

    /** One Wi-Fi switch instead of separate "open" and "close" actions. */
    private void wifiSwitch(LinearLayout list) {
        final int[] on = { HostLink.num(HostLink.kv(HostLink.CTL + "status"), "wifi", 1) };
        LinearLayout r = row(list, "Wi-Fi", "VeraDroid 通过宿主 Wi-Fi 联网；关闭后软件商店、OTA 和 SSH 都会断开。");
        Icons.Toggle.Listener wl = new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean value) {
                on[0] = value ? 1 : 0;
                HostLink.cmd("wifi " + on[0]);
                HostLink.cmd("status");
                say(value ? "Wi-Fi 已打开" : "Wi-Fi 已关闭");
            }
        };
        ((LinearLayout) r.getTag()).addView(bs ? new Blocks.Switch(this, on[0] == 1, wl) : new Icons.Toggle(this, on[0] == 1, wl));
        action(list, "Wi-Fi 网络", "列出在 Kindle 上保存过的 Wi-Fi，点一个就切换过去；“附近的网络”可以输入密码连接新网络。下拉面板里长按 Wi-Fi 磁贴也能打开。", "选择", new View.OnClickListener() {
            @Override public void onClick(View v) { WifiActivity.open(HomeActivity.this); }
        });
    }

    void textDialog(String title, String hint, final String key, String def, final int max) {
        final EditText input = new EditText(this);
        input.setText(prefs.getString(key, def));
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setTypeface(Fonts.typeface(this));
        input.setBackground(Ui.frame(this, false));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new VeraDialog.Builder(this).title(title).message(hint).view(input).negative("取消", null)
            .positive("保存", new Runnable() {
                @Override public void run() {
                    String t = input.getText().toString().trim();
                    if (t.length() > max) t = t.substring(0, max);
                    prefs.edit().putString(key, t).apply();
                    showSettings();
                }
            }).show();
    }

    // ------------------------------------------------------------------ 问题反馈 / 日志上传结果

    /** 问题反馈：一段话 + 完整系统日志。文字先写到 ctl/feedback.txt，宿主的 feedback 命令把它放进诊断目录再一起上传。 */
    void feedbackDialog() {
        final EditText input = new EditText(this);
        input.setHint("例如：打开某本书以后翻页没有反应");
        input.setHintTextColor(Color.BLACK);
        input.setTextColor(Color.BLACK);
        input.setMinLines(3);
        input.setMaxLines(5);
        input.setGravity(Gravity.TOP | Gravity.LEFT);
        input.setTextSize(18);
        input.setTypeface(Fonts.typeface(this));
        input.setBackground(Ui.frame(this, false));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(500) });
        new VeraDialog.Builder(this).title("问题反馈")
            .message("最多 500 字。提交后会保存并上传完整系统日志（可能包含打开过的网址和书名），这段话放在日志包里一起发出。")
            .view(input).negative("取消", null)
            .positive("提交", new Runnable() {
                @Override public void run() {
                    String t = input.getText().toString().trim();
                    if (t.length() == 0) { VeraToast.show(HomeActivity.this, "没有填写内容，未提交"); return; }
                    if (!writeFeedback(t)) { VeraToast.show(HomeActivity.this, "保存反馈失败，未提交"); return; }
                    watchUpload("反馈已提交");
                    HostLink.cmd("feedback");
                    VeraToast.show(HomeActivity.this, "正在收集日志并上传，大约一两分钟…");
                }
            }).show();
    }

    private static boolean writeFeedback(String text) {
        try {
            File tmp = new File(HostLink.CTL + "feedback.tmp");
            java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
            o.write((text + "\n").getBytes("UTF-8"));
            o.close();
            return tmp.renameTo(new File(HostLink.CTL + "feedback.txt"));
        } catch (Exception e) { return false; }
    }

    private static final String UPLOAD_RESULT = "diag.upload.result";
    private CtlWatch.Listener uploadWatch;
    private String uploadTitle, uploadPending;
    private final Runnable uploadTimeout = new Runnable() {
        @Override public void run() { if (uploadWatch != null) uploadDone("ok=0\nerror=等了 6 分钟没有结果，可能没有网络"); }
    };

    /** 等宿主写出上传结果（inotify，不轮询），然后把编号或失败原因告诉用户。 */
    private void watchUpload(String okTitle) {
        stopUploadWatch();
        uploadTitle = okTitle;
        new File(HostLink.CTL + UPLOAD_RESULT).delete();
        uploadWatch = new CtlWatch.Listener() {
            @Override public void onCtl(String path) {
                if (!UPLOAD_RESULT.equals(path)) return;
                handler.post(new Runnable() { @Override public void run() { if (uploadWatch != null) uploadDone(null); } });
            }
        };
        CtlWatch.add(uploadWatch);
        handler.postDelayed(uploadTimeout, 360000);
    }

    private void stopUploadWatch() {
        handler.removeCallbacks(uploadTimeout);
        if (uploadWatch != null) { CtlWatch.remove(uploadWatch); uploadWatch = null; }
    }

    private void uploadDone(String forced) {
        stopUploadWatch();
        Map<String, String> m = new HashMap<String, String>();
        if (forced == null) m = HostLink.kv(HostLink.CTL + UPLOAD_RESULT);
        else for (String l : forced.split("\n")) { int eq = l.indexOf('='); if (eq > 0) m.put(l.substring(0, eq), l.substring(eq + 1)); }
        String id = m.get("id"), err = m.get("error");
        if ("1".equals(m.get("ok")) && id != null) uploadPending = uploadTitle + "\n编号：" + id + "\n联系开发者时报这个编号，就能找到这次的日志。";
        else uploadPending = "上传失败\n" + (err == null || err.length() == 0 ? "没有拿到结果" : err) + "\n日志已保存在 Diagnostics 目录，联网后可以再试。";
        if (resumed) showUploadResult();
    }

    private void showUploadResult() {
        if (uploadPending == null || isFinishing()) return;
        int nl = uploadPending.indexOf('\n');
        String title = uploadPending.substring(0, nl), body = uploadPending.substring(nl + 1);
        uploadPending = null;
        new VeraDialog.Builder(this).title(title).message(body).positive("知道了", null).show();
    }

    /** 面板快捷按钮：点一下加入（按点的先后排序），再点一下拿掉；3 到 6 个。 */
    void quickDialog() {
        final java.util.List<String> chosen = new java.util.ArrayList<String>();
        for (String s : prefs.getString("quick", Panel.QUICK_DEFAULT).split(",")) if (s.trim().length() > 0) chosen.add(s.trim());
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView[] rows = new TextView[Panel.QUICK_ID.length];
        final Runnable paint = new Runnable() {
            @Override public void run() {
                for (int i = 0; i < rows.length; i++) {
                    int at = chosen.indexOf(Panel.QUICK_ID[i]);
                    rows[i].setText((at >= 0 ? "［" + (at + 1) + "］ " : "［  ］ ") + Panel.QUICK_NAME[i]);
                    rows[i].setTypeface(null, at >= 0 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                }
            }
        };
        for (int i = 0; i < rows.length; i++) {
            final String id = Panel.QUICK_ID[i];
            TextView r = text("", 18, false);
            r.setPadding(dp(8), dp(9), dp(8), dp(9));
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (chosen.contains(id)) chosen.remove(id);
                    else if (chosen.size() < 6) chosen.add(id);
                    else say("最多 6 个：先点掉一个");
                    paint.run();
                }
            });
            rows[i] = r;
            box.addView(r);
        }
        paint.run();
        new VeraDialog.Builder(this).title("面板快捷按钮").message("点一下加入（数字是顺序），再点一下拿掉。放 3 到 6 个。").view(box)
            .negative("恢复默认", new Runnable() { @Override public void run() { prefs.edit().remove("quick").apply(); showSettings(); } })
            .positive("保存", new Runnable() {
                @Override public void run() {
                    if (chosen.size() < 3) { say("至少选 3 个"); return; }
                    StringBuilder sb = new StringBuilder();
                    for (String s : chosen) { if (sb.length() > 0) sb.append(","); sb.append(s); }
                    prefs.edit().putString("quick", sb.toString()).apply();
                    showSettings();
                }
            }).show();
    }

    private void titleDialog() {
        final EditText input = new EditText(this);
        input.setText(prefs.getString("name", "VeraDroid"));
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setTypeface(Fonts.typeface(this));
        input.setBackground(Ui.frame(this, false));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new VeraDialog.Builder(this)
            .title("桌面标题")
            .message("最多 24 个字；留空就不显示标题。")
            .view(input)
            .negative("取消", null)
            .positive("保存", new Runnable() {
                @Override public void run() {
                    String t = input.getText().toString().trim();
                    if (t.length() > 24) t = t.substring(0, 24);
                    prefs.edit().putString("name", t).apply();
                    showSettings();
                }
            }).show();
    }

    private void bindDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("6 位绑定码");
        input.setSingleLine(true);
        input.setTextSize(22);
        input.setTypeface(Fonts.typeface(this));
        input.setBackground(Ui.frame(this, false));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new VeraDialog.Builder(this)
            .title("绑定账号")
            .message("先在 veradroid.wenjin.jp 注册，登录后在“我的设备”生成绑定码。")
            .view(input)
            .negative("取消", null)
            .positive("绑定", new Runnable() {
                @Override public void run() {
                    final String code = input.getText().toString().trim();
                    if (!code.matches("\\d{6}")) { say("请输入 6 位数字"); return; }
                    Update.bind(HomeActivity.this, code, new Update.BindResult() {
                        @Override public void done(final boolean ok, String message) {
                            say(message);
                            new VeraDialog.Builder(HomeActivity.this)
                                .title(ok ? "绑定成功" : "绑定失败")
                                .message(message)
                                .positive("知道了", new Runnable() { @Override public void run() { if (ok) showSettings(); } })
                                .show();
                        }
                    });
                }
            }).show();
    }

    /** 电源菜单：返回 Kindle / 关机，替代 Android 原生关机提示。 */
    void powerMenu() {
        box("电源", "返回 Kindle：冻结在后台，再打开几秒。\n关机：完全退出，下次打开约 15 秒。",
            new String[] { "取消", "返回 Kindle", "关机" },
            new Runnable[] { null,
                new Runnable() { @Override public void run() { HostLink.cmd("exit"); } },
                new Runnable() { @Override public void run() { HostLink.cmd("stop"); } } });
    }

    // Package manager calls that are hidden from apps; this app is signed with the platform key, so they are allowed.
    private void forceStop(String pkg) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(am, pkg);
        } catch (Throwable t) { say("失败：" + t); }
    }

    private void clearCache(String pkg) {
        try {
            Class<?> obs = Class.forName("android.content.pm.IPackageDataObserver");
            PackageManager.class.getMethod("deleteApplicationCacheFiles", String.class, obs).invoke(pm, pkg, null);
        } catch (Throwable t) { say("失败：" + t); }
    }

    private void clearData(String pkg) {
        try {
            Class<?> obs = Class.forName("android.content.pm.IPackageDataObserver");
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.class.getMethod("clearApplicationUserData", String.class, obs).invoke(am, pkg, null);
        } catch (Throwable t) { say("失败：" + t); }
    }

    private void uninstall(final String pkg) {
        // deletePackage() needs a privileged observer and silently fails with
        // null. The host runs pm uninstall as root instead.
        say("正在卸载…");
        HostLink.cmd("uninstallapp " + pkg);
    }

    // ------------------------------------------------------------------ settings (Kindle 式：一页分类，每类一个子页面)

    /** 分类。tab = -1 是分类列表，0..N-1 是各个子页面。 */
    private static final String[] CATS = { "Wi-Fi 与蓝牙", "显示与刷新", "手势", "应用与桌面", "传书", "设备与更新", "关于" };
    private static final String[] CAT_HINT = { "联网、已保存的网络、蓝牙设备、DNS", "字体、刷新方式、翻页动画、残影、对比度", "边缘滑动、双指手势、四角长按、导航键",
        "应用网格、壁纸、图标、面板、状态栏、输入法", "用手机或电脑把书和应用传进来", "账号、系统更新、存储空间、日志、重启、卸载", "版本、内存、问题反馈" };
    private static final Icons.Kind[] CAT_ICON = { Icons.Kind.WIFI, Icons.Kind.EYE, Icons.Kind.SWIPE, Icons.Kind.GRID, Icons.Kind.UPLOAD, Icons.Kind.SLIDERS, Icons.Kind.INFO };
    private static final int CAT_XFER = 4, CAT_ABOUT = 6;

    private void section(LinearLayout list, String title) {
        if (bs) { bGroup = bNewGroup(list, title); bGroup.getChildAt(0).setTag(HEADER_TAG); return; }
        TextView h = text(title, 14, true);
        h.setPadding(dp(2), dp(18), 0, dp(6));
        list.addView(h);
        list.addView(Ui.rule(this, 2, Color.BLACK));
    }

    private void showSettings() {
        if (tab >= CATS.length || tab < -1) tab = -1;
        // 方块风格：经典设置的各类都是方块的设置页（BlocksHome 里的 S_CATS 一组）。这里的“重画设置页”就是重画当前那一页。
        if (blocks != null && Blocks.on(this)) {
            int cur = blocks.current();
            if (!BlocksHome.isCat(cur)) { blocks.openCat(tab); showBlocks(blocks.current()); }
            else showBlocks(cur);
            return;
        }
        Perf.measure(page, tab < 0 ? "settings" : "settings page " + tab);
        inSettings = true;
        handler.removeCallbacks(prebuild);
        handler.removeCallbacks(xferTick);
        applyWallpaper(false);
        showOnly(settingsView(tab));
        if (tab == CAT_XFER) handler.postDelayed(xferTick, 4000);
    }

    /**
     * 主页、书库和设置各页的控件树都挂在 page 下面，只切换哪个可见：不拆下再挂上（那样每次都要重新挂到窗口、
     * 整棵树重新量一遍）。重搭过的旧树从 page 上拿掉。
     */
    private void showOnly(View v) {
        if (v.getParent() != page) {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            page.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (int i = page.getChildCount() - 1; i >= 0; i--) {
            View c = page.getChildAt(i);
            if (c == v) { if (c.getVisibility() != View.VISIBLE) c.setVisibility(View.VISIBLE); continue; }
            boolean live = c == homeRoot || (libPage != null && c == libPage.root()) || (blocks != null && c == blocks.root());
            for (View sv : settingsViews) if (c == sv) live = true;
            if (!live) page.removeViewAt(i);
            else if (c.getVisibility() != View.GONE) c.setVisibility(View.GONE);
        }
    }

    /**
     * 设置每一页的控件树缓存起来（下标 = tab + 1）：内容取决于宿主设置 / 状态文件和本应用的偏好，这些没变就直接换上去，
     * 变了（或者是“关于”页：显示可用内存）才重搭。主页闲下来时会把分类列表和各子页面先搭好（prebuild），
     * 所以点进任何一页都只是“换一棵树 + 画一次”。
     */
    private final View[] settingsViews = new View[CATS.length + 1];
    private final String[] settingsSigs = new String[CATS.length + 1];

    private View settingsView(int t) {
        String sig = settingsSig();
        if (t != CAT_ABOUT && settingsViews[t + 1] != null && sig.equals(settingsSigs[t + 1])) return settingsViews[t + 1];
        int keep = tab;
        tab = t;
        LinearLayout sroot = new LinearLayout(this);
        sroot.setOrientation(LinearLayout.VERTICAL);
        buildSettings(sroot);
        tab = keep;
        settingsViews[t + 1] = sroot;
        settingsSigs[t + 1] = sig;
        return sroot;
    }

    String settingsSig() {
        StringBuilder sb = new StringBuilder();
        // 状态文件里设置页只用到 Wi-Fi 开关（电量之类每次都在变，算进来缓存就永远用不上）
        sb.append(new java.util.TreeMap<String, String>(HostLink.kv(HostLink.KFB + "settings"))).append('|')
          .append(HostLink.kv(HostLink.CTL + "status").get("wifi")).append('|').append(SysUpdate.mode()).append('|');
        for (Map.Entry<String, ?> e : new java.util.TreeMap<String, Object>(prefs.getAll()).entrySet()) {
            String k = e.getKey();
            // 和设置页显示无关、又经常变的键：应用最近打开时间、检查更新的时间、每应用显示 / 字号、收件箱同步、书库的排序筛选
            if (k.startsWith("t_") || k.startsWith("update_") || k.startsWith("appdisp_") || k.startsWith("appfont_")
                || k.startsWith("font_") || k.startsWith("inbox") || k.startsWith("lib_") || k.startsWith("label_") || k.equals("reader_default")) continue;
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        sb.append(Transfer.get(this).running()).append('|').append(Theme.hasWallpaper());
        return sb.toString();
    }

    /** 主页显示出来、闲下来以后，把设置各页和书库先搭好并排好版（不可见）。一次搭一页，不长时间占着主线程。 */
    private int prebuildNext = -1;
    private final Runnable prebuild = new Runnable() {
        @Override public void run() {
            if (inSettings || isFinishing()) { dropWarm(); return; }
            if (page.getWidth() == 0) { prebuildSettingsSoon(); return; }   // 开机时主页还没排版：等一会儿再搭
            // 预搭一页要在主线程上占几百毫秒（单核机型上更久）：手指按着或刚点过就先不搭，免得点按要排在它后面等
            if (touching() || android.os.SystemClock.uptimeMillis() - Perf.start < 2000) { handler.postDelayed(this, 1000); return; }
            if (blocks != null && Blocks.on(HomeActivity.this)) {
                if (!blocks.prebuilt()) { blocks.prebuild(page); handler.postDelayed(this, 300); } else dropWarm();
                return;   // 方块风格：经典的书库 / 设置页不预先搭（进经典设置时当场搭）
            }
            // 书库页先搭好（第一页的封面这时同步解码）：第一次点“书库”和以后每次一样，只是换一棵树
            if (libOn()) {
                if (libPage == null) { ensureLib(); libPage = new LibraryPage(HomeActivity.this); }
                if (libPage.root().getParent() == null) { warm(libPage.root()); handler.postDelayed(this, 300); return; }
            }
            String sig = settingsSig();
            for (int t = -1; t < CATS.length; t++) {
                if (t == CAT_ABOUT) continue;
                if (settingsViews[t + 1] != null && sig.equals(settingsSigs[t + 1]) && settingsViews[t + 1].getParent() != null) continue;
                View old = settingsViews[t + 1];
                View v = settingsView(t);
                if (old != null && old != v && old.getParent() == page) page.removeView(old);
                warm(v);
                handler.postDelayed(this, 300);
                return;
            }
            dropWarm();
        }
    };

    /** 把一棵还没显示过的控件树挂到 page 上（不可见），量好、排好，再往一张不显示的位图上画一遍。 */
    private void warm(View v) {
        if (v.getParent() != null) return;
        // 先挂到 page 上但不可见（GONE 不参与量 / 画），再手工量一遍、排一遍：真正切过去时尺寸没变，不用再算
        v.setVisibility(View.GONE);
        page.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.measure(View.MeasureSpec.makeMeasureSpec(page.getWidth(), View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(page.getHeight(), View.MeasureSpec.EXACTLY));
        v.layout(0, 0, page.getWidth(), page.getHeight());
        // 再往一张不显示的位图上画一遍：第一次画一页中文要现栅格化每个字形，这一步占了第一次打开的大半时间；
        // 画过一次字形就在进程的缓存里了。位图用完就回收。
        try { v.draw(warmCanvas(page.getWidth(), page.getHeight())); } catch (Throwable ignored) { }
    }

    private void prebuildSettingsSoon() { if (standalone) return; handler.removeCallbacks(prebuild); handler.postDelayed(prebuild, 1500); }

    /** 设置里的“返回”：子页面回分类列表，分类列表回主页。 */
    private void settingsBack() {
        if (blocksReturn >= 0 && blocks != null && Blocks.on(this)) {
            int back = blocksReturn;
            blocksReturn = -1;
            tab = -1;
            showBlocks(back);
        } else if (tab >= 0) { tab = -1; showSettings(); } else showHome();
        changed();
    }

    private void buildSettings(LinearLayout sroot) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(16), dp(8));
        bar.addView(Icons.bar(this, Icons.Kind.BACK, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { settingsBack(); }
        }));
        TextView t = text(tab < 0 ? "设置" : CATS[tab], 24, true);
        t.setPadding(dp(8), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (tab < 0) bar.addView(Icons.bar(this, Icons.Kind.MORE, 28, new View.OnClickListener() {
            @Override public void onClick(View v) { settingsMenu(); }
        }));
        if (standalone) bar.addView(Icons.bar(this, Icons.Kind.CLOSE, 28, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }   // 关掉设置，回到原来的应用
        }));
        sroot.addView(bar);
        sroot.addView(Ui.rule(this, 2, Color.BLACK));

        final PageScrollView sv = new PageScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(2), dp(16), dp(24));
        sv.addView(list);
        sroot.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // 一页一页翻：在列表上划一下，或点下面的箭头
        final LinearLayout pg = new LinearLayout(this);
        pg.setOrientation(LinearLayout.HORIZONTAL);
        pg.setGravity(Gravity.CENTER);
        pg.setPadding(0, dp(2), 0, dp(6));
        final TextView pl = text("", 16, false);
        pl.setPadding(dp(20), 0, dp(20), 0);
        final Runnable label = new Runnable() {
            @Override public void run() {
                String s = sv.currentPage() + " / " + sv.pages();
                if (!s.equals(String.valueOf(pl.getText()))) pl.setText(s);
                int vis = sv.pages() > 1 ? View.VISIBLE : View.INVISIBLE;
                if (pg.getVisibility() != vis) pg.setVisibility(vis);
            }
        };
        pg.addView(Icons.bar(this, Icons.Kind.CHEV_U, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { Perf.measure(page, "settings scroll"); if (sv.page(-1)) { label.run(); changed(); } }
        }));
        pg.addView(pl);
        pg.addView(Icons.bar(this, Icons.Kind.CHEV_D, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { Perf.measure(page, "settings scroll"); if (sv.page(1)) { label.run(); changed(); } }
        }));
        sv.setOnPage(label);
        list.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int tt, int r, int b, int ol, int ot, int or, int ob) { label.run(); }
        });
        sroot.addView(Ui.rule(this, 1, Color.BLACK));
        sroot.addView(pg);
        fillSettings(list, HostLink.kv(HostLink.KFB + "settings"));
    }

    /** 某一类设置的内容（经典和方块共用同一份：每一项的偏好键、宿主命令都一样，只是 section / row / 控件按风格画）。 */
    private void fillSettings(LinearLayout list, Map<String, String> host) {
        switch (tab) {
        case -1:
            for (int i = 0; i < CATS.length; i++) {
                if (i == 0) {
                    boolean bt0 = BluetoothActivity.supported();
                    category(list, i, bt0 ? CATS[i] : "Wi-Fi", bt0 ? CAT_HINT[i] : "联网、已保存的网络、DNS");
                } else category(list, i, CATS[i], CAT_HINT[i]);
            }
            break;
        case 0:
            wifiSwitch(list);
            // 蓝牙芯片归 Kindle（Android 里没有蓝牙服务）：宿主说这台机器有 Kindle 的蓝牙栈才显示，配对、连接都经宿主做
            if (BluetoothActivity.supported()) action(list, "蓝牙翻页器", "扫描、配对、连接蓝牙翻页器，选翻页键发成什么键。", "打开", new View.OnClickListener() {
                @Override public void onClick(View v) { BluetoothActivity.open(HomeActivity.this); }
            });
            hostChoice(list, host, "DNS", "自动用 Wi-Fi 给的 DNS；也可以固定成阿里/腾讯/Google。改完立即生效，下次开机沿用。", "DNS_MODE", DNS_L, DNS_V, "auto");
            break;
        case 1:
            section(list, "字体");
            action(list, "系统字体", "当前：" + prefs.getString("system_font_name", "系统默认") + "。桌面、设置、阅读器一起换。", "选择", new View.OnClickListener() {
                @Override public void onClick(View v) { fontDialog(); }
            });
            {
                LinearLayout r = row(list, "应用到所有应用", "其他应用（微信读书的“系统默认字体”、设置、浏览器）也用这个字体。重启 Android 生效。");
                Icons.Toggle.Listener tl = new Icons.Toggle.Listener() {
                    @Override public void onToggle(boolean on) {
                        prefs.edit().putBoolean("system_font_all", on).commit();
                        systemFontAll(on ? prefs.getString("system_font_path", null) : null);
                    }
                };
                boolean all = prefs.getBoolean("system_font_all", true);
                ((LinearLayout) r.getTag()).addView(bs ? new Blocks.Switch(this, all, tl) : new Icons.Toggle(this, all, tl));
            }
            action(list, "字体商店", "下载大家分享的字体（都经过审核），可以设为系统字体或阅读字体，也可以在这里删除下载的字体。", "打开", new View.OnClickListener() {
                @Override public void onClick(View v) { FontStoreActivity.open(HomeActivity.this); }
            });
            action(list, "导入字体", "在传输网页上传 .ttf/.otf，会自动进入字体库；再回这里选择。", "打开传输", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, TransferActivity.class)); }
            });
            localChoice(list, "文字与图标大小", "桌面和设置页的大小，不影响其他应用。", "size", SIZES, 0, 1);
            action(list, "显示与刷新", "屏幕旋转、显示档位（流畅 / 均衡 / 画质），局部刷新和整页刷新分开设。", "打开", new View.OnClickListener() {
                @Override public void onClick(View v) { HostSettingsActivity.open(HomeActivity.this, "display"); }
            });
            section(list, "刷新");
            hostChoice(list, host, "刷新方式", "屏幕上只有一小块变化时（点按钮、翻列表）用的刷新方式。均衡：反应快，残影少；灰度最准：颜色最准，稍慢；最快：只有黑白，最灵敏，残影多。",
                "KFB_MODE", MODE_L, MODE_V, "0");
            hostChoice(list, host, "滑动刷新合并", "滑动、翻页动画时只画停下来的那一帧，不画中间帧：残影少、更省电。关掉则每一帧都刷。", "KFB_SETTLE", SETTLE_L, SETTLE_V, "40");
            hostChoice(list, host, "整屏刷新", "每翻这么多页（大面积变化），整个屏幕闪一下彻底刷新，清掉累积的残影。和老款 Kindle 的“页面刷新”一样。", "KFB_FLASH", FLASH_L, FLASH_V, "8");
            localSwitch(list, "换页后整屏刷新", "桌面翻页、进出设置、切换分类后闪一下整屏，清掉上一页的残影。默认关：整页变化改走 MTK 硬件滑动动画。", "flashui", false);
            action(list, "立即整屏刷新", "马上闪一下整个屏幕。屏幕上有残影时用。", "刷新", new View.OnClickListener() {
                @Override public void onClick(View v) { HostLink.cmd("clear"); }
            });
            section(list, "动画");
            hostChoice(list, host, "翻页动画", "整页变化时用墨水屏控制器的硬件滑动，和 Kindle 原生翻页同一套。跟随手势：按你滑动或点击的方向；也可以固定方向或关闭。",
                "KFB_ANIM_MODE", ANIM_L, ANIM_V, "1");
            hostChoice(list, host, "动画速度", "步数越多越慢越细腻；觉得不跟手就选快。", "KFB_ANIM_STEPS", ANIM_STEP_L, ANIM_STEP_V, "12");
            section(list, "画面");
            hostChoice(list, host, "对比度", "数值越大文字越黑越清楚，图片里的灰色会变深。", "KFB_CONTRAST", CONTRAST_L, CONTRAST_V, "1.3");
            hostChoice(list, host, "灰度抖动", "墨水屏灰度有限，用细密的小点模拟更多层次，封面和插图的过渡更自然。", "KFB_DITHER", DITHER_L, DITHER_V, "3");
            hostChoice(list, host, "屏幕反色", "白字黑底，夜间阅读用。", "KFB_INVERT", ONOFF_L, ONOFF_V, "0");
            hostChoice(list, host, "浅色过滤", Panel.LIGHTF_DESC, "KFB_LIGHTF", Panel.LIGHTF_L, Panel.LIGHTF_V, "0");
            hostChoice(list, host, "深色加深", Panel.DARKEN_DESC, "KFB_DARKEN", ONOFF_L, ONOFF_V, "0");
            break;
        case 2:
            if ("1".equals(host.get("PAGE_KEYS_HW"))) {
                section(list, "实体翻页键");
                hostChoice(list, host, "上下翻页键对换", "对调机身上两个实体翻页键的功能（默认：上键下一页、下键上一页；开启后：上键上一页、下键下一页），立即生效。",
                    "PAGE_KEYS_FLIP", new String[] { "关（默认）", "开（上下对换）" }, new String[] { "0", "1" }, "0");
                hostChoice(list, host, "实体翻页键键值", "微信读书等阅读应用请在应用设置里开启“音量键翻页”；不认音量键的应用可改成翻页键或方向键，立即生效。",
                    "PAGE_KEYS_MAP", new String[] { "音量键（推荐）", "翻页键", "方向键" }, new String[] { "volume", "page", "dpad" }, "volume");
            }
            section(list, "导航");
            navModeChoice(list);
            hostChoice(list, host, "手势提示", "识别到手势时在屏幕边上闪一下提示；应用卡住、2 秒没反应时提示怎么回到 Kindle。", "GESTURE_HINT",
                new String[] { "全部", "只在没响应时", "关" }, new String[] { "all", "stuck", "off" }, "all");
            hostChoice(list, host, "启用手势", "下面所有手势的总开关。关掉后它们都不起作用，单指点按的反应也会快一点；“从最下边往上划：返回”只看它自己的开关。", "KFB_GESTURES", ONOFF_L, ONOFF_V, "1");
            section(list, "单个手势（每个都能单独关，立即生效）");
            hostChoice(list, host, "从最下边往上划：返回", "从屏幕最下边一小条往上划，相当于返回键。和应用自己的上划冲突时可以关掉，或者用下一项只在某些应用里关掉。", "G_EDGE_BACK", ONOFF_L, ONOFF_V, "1");
            hostChoice(list, host, "这些应用里不用上划返回", "微信读书在阅读页上划会呼出底部工具栏。这些应用在前台时，上划直接交给应用；要返回可以用两根手指左右滑。", "G_BACK_EXCLUDE", EXCL_L, EXCL_V, "com.tencent.weread.eink");
            hostChoice(list, host, "底边分三段", "开：从最下边的左、中、右三分之一往上划分别做下面三项，往上划后停住不动做“上划停住”。关：整条底边上划都是返回。要上面的“从最下边往上划”开着。",
                "G_EDGE_THIRDS", ONOFF_L, ONOFF_V, "0");
            hostChoice(list, host, "　左段上划", "默认：当前应用的显示设置（应用优化；在桌面上打开面板）。", "G_EB_L", ACT_L, ACT_V, "appopt");
            hostChoice(list, host, "　中段上划", "默认：回到桌面。", "G_EB_M", ACT_L, ACT_V, "home");
            hostChoice(list, host, "　右段上划", "默认：返回。", "G_EB_R", ACT_L, ACT_V, "back");
            hostChoice(list, host, "　上划后停住", "往上划，手指停住不动一会儿再松开。默认：切换应用（最近任务）。选“不做操作”时三段一划到就生效，不用等松手。", "G_EB_HOLD", ACT_L, ACT_V, "switch");
            hostChoice(list, host, "　停住多久", "停住不动这么久算“上划停住”。", "G_EB_HOLD_MS", HOLD_MS_L, HOLD_MS_V, "500");
            hostChoice(list, host, "从最上边往下划：打开面板", "亮度、色温、通知、关机。", "G_EDGE_PANEL", ONOFF_L, ONOFF_V, "1");
            hostChoice(list, host, "两根手指轻点：清除残影", "整屏刷新一次。", "G_TWO_TAP", ONOFF_L, ONOFF_V, "1");
            hostChoice(list, host, "两根手指长按 2 秒：反色", "白字黑底 / 黑字白底切换。", "G_TWO_LONG", ONOFF_L, ONOFF_V, "1");
            hostChoice(list, host, "两根手指左右滑：返回", "相当于返回键。", "G_TWO_BACK", ONOFF_L, ONOFF_V, "1");
            hostChoice(list, host, "边缘滑动灵敏度", "从屏幕底边上划返回、顶边下划开面板。高：靠近边缘一点、滑一小段就算；低：必须贴着边、滑得更长，不容易误触。", "G_EDGE_SENS",
                new String[] { "低", "标准", "高" }, new String[] { "0", "1", "2" }, "1");
            hostChoice(list, host, "两根手指上下滑：亮度 / 色温", "左半屏调亮度，右半屏调色温。", "G_TWO_SLIDE", ONOFF_L, ONOFF_V, "1");
            section(list, "四角长按");
            hostChoice(list, host, "左上角长按", "在屏幕左上角按住约 2 秒。", "G_TL", ACT_L, ACT_V, "none");
            hostChoice(list, host, "右上角长按", "在屏幕右上角按住约 2 秒。", "G_TR", ACT_L, ACT_V, "none");
            hostChoice(list, host, "左下角长按", "在屏幕左下角按住约 2 秒。", "G_BL", ACT_L, ACT_V, "kindle");
            hostChoice(list, host, "右下角长按", "在屏幕右下角按住约 2 秒。", "G_BR", ACT_L, ACT_V, "none");
            break;
        case 3:
            section(list, "应用网格");
            localChoice(list, "每行应用数", "列越多图标越小。", "cols", new String[] { "2 个", "3 个", "4 个", "5 个" }, 2, 3);
            localChoice(list, "应用排序", "最近使用：刚用过的在前；最久未用：很久没打开的在前；名称拼音；包名字母；最近安装：新装的在前。", "sort", SORTS, 0, 0);
            action(list, "软件商店", "安装和更新为墨水屏挑过的应用。", "打开", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, StoreActivity.class)); overridePendingTransition(0, 0); }
            });
            section(list, "壁纸与图标");
            final boolean hasWall = Theme.hasWallpaper();
            action(list, "桌面壁纸", (hasWall ? "当前：已设置。" : "当前：没有壁纸（白底）。")
                + "从内部存储的 VeraDroid/wallpaper、Pictures、Download 或 Kindle 的 documents 里选一张图；传书网页的“主题”页也能上传。转成灰度，只在主页（方块风格是“首页”标签）显示。",
                "选择", new View.OnClickListener() {
                @Override public void onClick(View v) { pickWallpaper(); }
            });
            if (hasWall) {
                localChoice(list, "壁纸铺法", "铺满：放大到盖满屏幕，多出来的裁掉；完整显示：整张图都看得到，空出来的地方留白；原大小：不缩放，放在正中间。", "wallmode", WALLMODE_L, 0, 0);
                localChoice(list, "壁纸深浅", "20% 最淡，文字最清楚；原图最深。", "wallpaperdim", DIM_L, 0, 2);
                localChoice(list, "卡片遮盖（方块风格）", "方块风格的首页上，卡片怎么盖住壁纸。实心白卡片：卡片里是白纸，文字最清楚，壁纸只在卡片之间露出来；半透明、通透：卡片里也透出一点壁纸。", Blocks.WALLCARD_KEY, Blocks.WALLCARD_L, 0, 0);
                action(list, "不用壁纸", "回到白底。图片文件不会被删掉。", "去掉", new View.OnClickListener() {
                    // 先问一句：这个按钮在设置页中间，翻页 / 点下一行时一下误触就把壁纸去掉了（测试机实测）
                    @Override public void onClick(View v) { confirm("不用壁纸？", "主页回到白底。图片文件不会被删掉，之后可以在“桌面壁纸”里重新选。", new Runnable() {
                        @Override public void run() { Theme.clearWallpaper(); themeVer = Theme.version(); wallApplied = ""; showSettings(); } }); }
                });
            }
            localChoice(list, "图标样式", "原样：应用自己的图标；黑白描框：转成灰度，外面加一圈黑色圆角框，桌面更整齐。单个应用的图标可以长按它换。", "iconstyle", ICONSTYLE_L, 0, 0);
            section(list, "外观");
            action(list, "桌面标题", "主页和书库最上面、时间旁边的那行字。当前：" + (prefs.getString("name", "VeraDroid").length() == 0 ? "（不显示）" : prefs.getString("name", "VeraDroid")) + "。可以改成一句话，留空就不显示。", "修改", new View.OnClickListener() {
                @Override public void onClick(View v) { titleDialog(); }
            });
            styleChoice(list);
            localSwitch(list, "英文小字", "方块风格里每个中文标题上面那行小号英文（CONTINUE、THIS WEEK…）。", Blocks.CAPS_KEY, true);
            localSwitch(list, "书库", "主页底部的“书库”标签和顶上的搜索框。只用来开应用的话可以关掉：书库页不再显示，也不再读书的索引、不扫描书的文件夹。", "library", true);
            localSwitch(list, "12 小时制", "时间显示成 上午 / 下午 几点。默认 24 小时制。", "clock12", false);
            localSwitch(list, "单手模式", "主页的顶栏和应用挪到下半屏，上面多一个大的“下拉面板”按钮。", OneHand.KEY, false);
            localSwitch(list, "状态栏", "在别的应用顶部显示时间和电量，点它打开下拉面板。桌面自己已经有顶栏，所以桌面不重复显示。", "statusbar", true);
            section(list, "下拉面板");
            action(list, "面板名称", "下拉面板顶部显示的名字，比如“某某的 Kindle”。当前：" + (prefs.getString("panelname", "").length() == 0 ? "（不显示）" : prefs.getString("panelname", "")) + "。", "修改", new View.OnClickListener() {
                @Override public void onClick(View v) { textDialog("面板名称", "最多 16 个字；留空就不显示。", "panelname", "", 16); }
            });
            action(list, "面板快捷按钮", "下拉面板最上面一排圆形开关放哪几个、什么顺序。当前 " + prefs.getString("quick", Panel.QUICK_DEFAULT).split(",").length + " 个。", "选择", new View.OnClickListener() {
                @Override public void onClick(View v) { quickDialog(); }
            });
            section(list, "输入");
            action(list, "输入法", "启用并切换到 Vera 输入法：中文拼音、英文、候选词。安装好输入法后点这里一次即可；以后点输入框会自动弹出。", "启用", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { pm.getPackageInfo("com.veradroid.ime", 0); }
                    catch (Exception e) { say("还没安装 Vera 输入法（VeraIME.apk）"); return; }
                    Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_INPUT_METHODS,
                        "com.veradroid.ime/.VeraImeService");
                    Settings.Secure.putString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD,
                        "com.veradroid.ime/.VeraImeService");
                    InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (im != null) im.showInputMethodPicker();
                    say("已启用 Vera 输入法；点输入框试试");
                }
            });
            break;
        case 4:
            buildTransfer(list);
            break;
        case 5:
            section(list, "账号与更新");
            action(list, "绑定账号", "在网站“我的设备”生成 6 位绑定码，填到这里；绑定后可以在后台看到机器和版本。", "绑定", new View.OnClickListener() {
                @Override public void onClick(View v) { bindDialog(); }
            });
            action(list, "检查完整系统更新", "检查最新 VeraDroid 整机版本；有新版本先列出更新内容，再由你选“更新”或“以后再说”。有增量补丁时只下载变了的部分。系统更新会短暂退出 Android。", "检查", new View.OnClickListener() {
                @Override public void onClick(View v) { fullUpdateDialog(); }
            });
            action(list, "自动更新", "自动安装：在你回到 Kindle、不用 Android 时自动装新版本（默认）；只提醒：发现新版本时在桌面列出更新内容，由你决定装不装；关闭：只在你自己检查时更新。当前：" + SysUpdate.MODE_L[SysUpdate.mode()] + "。",
                "选择", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    new VeraDialog.Builder(HomeActivity.this).title("自动更新").items(SysUpdate.MODE_L, new VeraDialog.OnPick() {
                        @Override public void onPick(int which) {
                            SysUpdate.setMode(HomeActivity.this, which, new View.OnClickListener() { @Override public void onClick(View x) { showSettings(); } });
                        }
                    }).selected(SysUpdate.mode()).negative("取消", null).show();
                }
            });
            action(list, "更新前备份", BackupActivity.desc(), "选择", new View.OnClickListener() {
                @Override public void onClick(View v) { BackupActivity.choose(HomeActivity.this, new Runnable() { @Override public void run() { showSettings(); } }); }
            });
            action(list, "已备份的数据", "更新前的备份放在 Kindle 上，占多少地方、哪天做的；不要的可以删掉。", "查看", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, BackupActivity.class)); }
            });
            section(list, "设备");
            localChoice(list, "后台保留应用", "前台以外最多留几个自己装的应用在后台，多出来的（最久没用的）在换应用 3 秒后停掉，省内存。应用长按 → 保持后台 的不算。",
                Cleaner.LIMIT_KEY, Cleaner.LIMIT_L, 0, Cleaner.defaultLimit());
            hostChoice(list, host, "插上 USB 线时询问", "Android 在前台时插上数据线：问要不要切到 USB 传输（退回 Kindle，电脑上就能看到 Kindle 盘）。"
                + "只用充电器充电、不想被问就选“不询问”。", "USB_ASK", new String[] { "询问", "不询问" }, new String[] { "1", "0" }, "1");
            hostChoice(list, host, "回到 Kindle 的方式", "挂起：系统暂停在后台，再打开几秒就回到现在的界面，但 Kindle 可用内存会少一些。关闭：系统完全退出，内存全部还给 Kindle。", "RETURN_MODE", RETURN_L, RETURN_V, "freeze");
            action(list, "系统与性能", "Wi-Fi 省电、小内存模式、Kindle 界面怎么处置、应用预编译（进度和“全部优化”）。", "打开", new View.OnClickListener() {
                @Override public void onClick(View v) { HostSettingsActivity.open(HomeActivity.this, "system"); }
            });
            action(list, "锁屏密码", "开机和从 Kindle 屏保醒来时要输入 4–6 位数字密码。忘了可以用 USB 重置。", "设置", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, LockSettingsActivity.class)); }
            });
            action(list, "屏保", "休眠时显示什么：Kindle 自带的、轮换你的图片，或者只用一张。", "选择", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, ScreensaverActivity.class)); }
            });
            action(list, "存储空间", "Android 用的空间有多大、用了多少；可以调大调小，快满时自动扩大。", "查看", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, StorageActivity.class)); }
            });
            action(list, "使用统计", "本机记录电量和阅读时长，不上传。", "查看", new View.OnClickListener() {
                @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, StatsActivity.class)); }
            });
            action(list, "重启系统", "只重启系统，大约 20 秒后回到桌面，Kindle 界面不会出来。应用卡住、反应异常时可以试试。", "重启", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirm("重启系统？", "正在运行的应用会被关闭。", new Runnable() { @Override public void run() { HostLink.cmd("restart"); } });
                }
            });
            section(list, "日志");
            action(list, "保存完整系统日志", "保存 Kindle dmesg、Android 全部 logcat buffer、所有 dumpsys、getprop、settings、网络、进程和挂载信息到 Diagnostics。日志里可能有打开过的网址和书名。", "保存", new View.OnClickListener() {
                @Override public void onClick(View v) { HostLink.cmd("diag"); ((TextView) v).setText("已保存"); }
            });
            action(list, "上传完整系统日志", "保存并上传诊断包，按设备 ID 存服务器。" + DiagConsent.CONTENTS, "上传", new View.OnClickListener() {
                @Override public void onClick(View v) { watchUpload("日志已上传"); HostLink.cmd("diag-upload"); VeraToast.show(HomeActivity.this, "已开始上传日志…"); }
            });
            action(list, "桌面崩溃时上传诊断包", "当前：" + DiagConsent.label(this) + "。没选过时不上传，下次打开桌面时问你。", "更改", new View.OnClickListener() {
                @Override public void onClick(View v) { DiagConsent.ask(HomeActivity.this, false); }
            });
            section(list, "恢复与卸载");
            action(list, "恢复默认设置", "显示、手势、系统和桌面设置回到默认值。已安装的应用和数据不受影响。", "恢复", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirm("恢复默认设置？", "显示、手势、系统和桌面设置都会回到默认值。", new Runnable() { @Override public void run() {
                        prefs.edit().remove("cols").remove("sort").remove("size").remove("flashui").remove("iconstyle").remove("wallmode").remove("navmode").commit();
                        NavBarService.apply(HomeActivity.this);   // 导航方式回到“手势”：下面又把手势打开了，按键条不能还留着
                        // 不写死数值：KEY=default 让宿主删掉用户改过的值，回到宿主的默认（含机型默认，比如 PW5 的抖动关、REAGL 50%）。
                        // 以前这里写 KFB_DITHER=3 等固定值，把 PW5 的机型默认又盖回去了。
                        StringBuilder r = new StringBuilder();
                        for (String k : RESET_KEYS) r.append(k).append("=default\n");
                        HostLink.settings(r.toString());
                        handler.postDelayed(new Runnable() { @Override public void run() { showSettings(); changed(); } }, 1200);
                    } });
                }
            });
            action(list, "卸载 VeraDroid", "退出 Android，删除 VeraDroid 系统和 Android 数据；Books、书和绑定 token 保留。需要重新安装才能使用。", "卸载", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirm("卸载 VeraDroid？", "会退出 Android 并删除系统/Android 应用数据。Books 和绑定 token 保留。", new Runnable() {
                        @Override public void run() { say("正在卸载…"); HostLink.cmd("uninstall"); }
                    });
                }
            });
            break;
        default:
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            String ver = "0.0";
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
            info(list, "VeraDroid " + ver, "作者：ajie · wenjin.jp"
                + "\nAndroid " + Build.VERSION.RELEASE + " · " + Build.MODEL
                + "\n内存 " + (mi.totalMem / (1024 * 1024)) + " MB，可用 " + (mi.availMem / (1024 * 1024)) + " MB"
                + "\n\n开源字体：霞鹜文楷（SIL OFL 1.1）");
            action(list, "问题反馈", "写几句遇到的问题，连同完整系统日志一起上传给开发者。点“提交”之前不会发送任何内容。", "反馈", new View.OnClickListener() {
                @Override public void onClick(View v) { feedbackDialog(); }
            });
        }
    }

    /** 分类列表里的一行：图标、名称、一句说明、右边一个 ›；整行可点。 */
    private void category(LinearLayout list, final int idx, String name, String hint) {
        if (bs) {
            bAdd(list, Blocks.navRow(this, name, hint, new View.OnClickListener() { @Override public void onClick(View v) { if (blocks != null) blocks.openCat(idx); } }));
            return;
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(18), 0, dp(18));
        r.addView(new Icons.IconView(this, CAT_ICON[idx]), new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), 0, dp(8), 0);
        col.addView(text(name, 20, true));
        TextView h = text(hint, 14, false);
        h.setSingleLine(true);
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(h);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(new Icons.IconView(this, Icons.Kind.CHEV_R), new LinearLayout.LayoutParams(dp(22), dp(22)));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tab = idx; showSettings(); changed(); }
        });
        Ui.fast(r);
        list.addView(r);
        list.addView(Ui.rule(this, 1, Color.BLACK));
    }

    /** 设置页右上角的菜单（Kindle 设置页的三点菜单：更新、设备信息、重启）。 */
    private void settingsMenu() {
        new VeraDialog.Builder(this).items(new String[] { "检查系统更新", "设备信息", "重启系统", "回到 Kindle", "关机…" }, new VeraDialog.OnPick() {
            @Override public void onPick(int which) {
                switch (which) {
                case 0: fullUpdateDialog(); break;
                case 1: tab = CAT_ABOUT; showSettings(); changed(); break;
                case 2: confirm("重启系统？", "正在运行的应用会被关闭。", new Runnable() { @Override public void run() { HostLink.cmd("restart"); } }); break;
                case 3: HostLink.cmd("exit"); break;
                default: powerMenu(); break;
                }
            }
        }).show();
    }

    // ------------------------------------------------------------------ wireless transfer tab

    private TextView xferInfo;
    private final Runnable xferTick = new Runnable() {
        @Override public void run() {
            if (!resumed || !inSettings || tab != CAT_XFER || xferInfo == null) return;
            HostLink.cmd("status");
            handler.postDelayed(new Runnable() { @Override public void run() { updateTransferInfo(); } }, 600);
            handler.postDelayed(this, 4000);
        }
    };

    void updateTransferInfo() {
        if (xferInfo == null) return;
        Transfer t = Transfer.get(this);
        StringBuilder sb = new StringBuilder();
        if (t.running()) {
            Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
            String ip = st.get("lanip");
            sb.append(ip == null || ip.length() == 0 ? "正在获取地址…（一直这样的话，请先把 Kindle 连上 Wi-Fi）" : "http://" + ip + ":" + t.port());
            sb.append("\nPIN  ").append(t.pin());
            String work = t.statusText();
            if (work.length() > 0) sb.append("\n\n").append(work.trim());
            else if (t.received() > 0) sb.append("\n\n已收到 ").append(t.received()).append(" 个文件");
        } else {
            sb.append("已关闭");
        }
        if (!sb.toString().equals(String.valueOf(xferInfo.getText()))) xferInfo.setText(sb.toString());
    }

    private void buildTransfer(LinearLayout list) {
        final Transfer t = Transfer.get(this);
        LinearLayout r = row(list, "无线传输", "手机和 Kindle 连同一个 Wi-Fi，在手机浏览器打开下面的地址，输入 PIN，就能上传书和 APK（APK 会自动安装），也能粘贴 http(s) 直链下载。");
        Icons.Toggle.Listener xl = new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) {
                if (on) {
                    t.setIdleMinutes(prefs.getInt("xfer_idle", 30));
                    if (!t.start()) say("打不开传输服务：" + t.lastError());
                } else t.stop();
                HostLink.cmd("status");
                handler.postDelayed(new Runnable() { @Override public void run() { showSettings(); } }, 900);
            }
        };
        ((LinearLayout) r.getTag()).addView(bs ? new Blocks.Switch(this, t.running(), xl) : new Icons.Toggle(this, t.running(), xl));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(10));
        box.setBackground(Ui.frame(this, false));
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        xferInfo = bs ? Ui.text(this, "", 20, true) : text("", 22, true);
        box.addView(xferInfo);
        if (bs) { box.setBackground(null); box.setPadding(dp(12), dp(10), dp(12), dp(10)); bAdd(list, box); }
        else {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(8); bp.bottomMargin = dp(8);
            list.addView(box, bp);
            list.addView(Ui.rule(this, 1, Color.BLACK));
        }

        LinearLayout ri = row(list, "自动关闭", "没人用满这么久就自动关掉，省电也更安全。");
        final String[] idleL = { "15 分钟", "30 分钟", "1 小时" };
        final int[] idleV = { 15, 30, 60 };
        int cur = 1;
        for (int i = 0; i < idleV.length; i++) if (idleV[i] == prefs.getInt("xfer_idle", 30)) cur = i;
        if (bs) {
            ((LinearLayout) ri.getTag()).addView(bStepper(idleL, cur, new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) { prefs.edit().putInt("xfer_idle", idleV[i]).commit(); t.setIdleMinutes(idleV[i]); }
            }));
        } else {
        final int[] at = { cur };
        final ValueButton vb = valueButton(idleL[cur], null);
        vb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                at[0] = (at[0] + 1) % idleV.length;
                vb.setText(idleL[at[0]]);
                prefs.edit().putInt("xfer_idle", idleV[at[0]]).commit();
                t.setIdleMinutes(idleV[at[0]]);
            }
        });
        ((LinearLayout) ri.getTag()).addView(vb);
        }

        updateTransferInfo();
        action(list, "传书页面", "单独一页显示地址和 PIN，字更大，方便照着在手机上输入。", "打开", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, TransferActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); overridePendingTransition(0, 0); }
        });
        // 无线调试（adb）：默认关，只认传书网页里添加过的电脑公钥，30 分钟后自动关（AdbWifi / 宿主 webadmin.sh）
        LinearLayout ra = row(list, "无线调试 (adb)", AdbWifi.DESC);
        Icons.Toggle.Listener al = new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) {
                AdbWifi.set(HomeActivity.this, on, 30, new Runnable() { @Override public void run() { showSettings(); } });
            }
        };
        ((LinearLayout) ra.getTag()).addView(bs ? new Blocks.Switch(this, AdbWifi.on(), al) : new Icons.Toggle(this, AdbWifi.on(), al));
    }

    private LinearLayout row(LinearLayout list, String title, String desc) {
        if (bs) return bRow(list, title, desc);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(12), 0, dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(title, 20, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.setTag(top);
        r.addView(top);
        if (desc != null && desc.length() > 0) {
            TextView d = text(desc, 14, false);
            d.setTextColor(Color.BLACK);
            d.setPadding(0, dp(4), dp(8), 0);
            d.setMaxLines(3);   // 说明文字不再截成一行
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(d);
        }
        list.addView(r);
        list.addView(Ui.rule(this, 1, Color.BLACK));
        return r;
    }

    /** A list of gestures, each with a small line icon in front. */
    private void gestureRow(LinearLayout list, String title, Icons.Kind[] kinds, String[] texts) {
        LinearLayout r = row(list, title, null);
        for (int i = 0; i < texts.length; i++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, dp(6), 0, dp(6));
            line.addView(new Icons.IconView(this, kinds[i]), new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView t = text(texts[i], 16, false);
            t.setPadding(dp(14), 0, 0, 0);
            line.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(line);
        }
    }

    private void info(LinearLayout list, String title, String body) {
        if (bs) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(12), dp(10), dp(12), dp(10));
            c.addView(Ui.text(this, title, 20, true));
            TextView d = Ui.text(this, body, 15, false);
            d.setPadding(0, dp(6), 0, 0);
            c.addView(d);
            bAdd(list, c);
            return;
        }
        LinearLayout r = row(list, title, null);
        TextView d = text(body, 16, false);
        d.setPadding(0, dp(6), 0, 0);
        r.addView(d);
    }

    private void action(LinearLayout list, String title, String desc, String label, View.OnClickListener l) {
        LinearLayout r = row(list, title, desc);
        ((LinearLayout) r.getTag()).addView(bs ? Blocks.button(this, label, false, l) : button(label, l));
    }

    /** A choice kept in this app: tap the value for the next one; the page is drawn again so everything follows at once. */
    private void localChoice(LinearLayout list, String title, String desc, final String key, final String[] labels, final int offset, int def) {
        LinearLayout r = row(list, title, desc);
        int cur = Math.max(0, Math.min(labels.length - 1, prefs.getInt(key, def) - offset));
        if (bs) {
            ((LinearLayout) r.getTag()).addView(bStepper(labels, cur, new Blocks.Stepper.Listener() {
                @Override public void onStep(int next) { localPicked(key, next + offset); }
            }));
            return;
        }
        final ValueButton vb = valueButton(labels[cur], null);
        vb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int c = Math.max(0, prefs.getInt(key, def(key)) - offset);
                int next = (c + 1) % labels.length;
                prefs.edit().putInt(key, next + offset).apply();
                // 只有“文字与图标大小”会改变整页排版，才需要重建；其余只换按钮上的字，
                // 不再每点一次就把整页重画（墨水屏上就是一次整屏刷新）。
                if ("size".equals(key)) showSettings(); else vb.setText(labels[next]);
                // 壁纸 / 图标的样子变了：趁还在设置页，在后台先做好，回主页时直接用
                if ("wallmode".equals(key) || "wallpaperdim".equals(key)) Theme.prepareAsync(HomeActivity.this, null);
                if ("iconstyle".equals(key) || "size".equals(key)) reload();
            }
        });
        ((LinearLayout) r.getTag()).addView(vb);
    }

    /** 选了某个本应用的选项（方块风格的步进器）：和经典的点一下换下一个做同样的事。 */
    private void localPicked(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        if ("size".equals(key)) showSettings();
        if ("wallmode".equals(key) || "wallpaperdim".equals(key)) Theme.prepareAsync(HomeActivity.this, null);
        if ("iconstyle".equals(key) || "size".equals(key)) reload();
    }

    /** 界面风格：经典（照 Kindle）/ 方块（底部四个标签、卡片式首页）。 */
    private void styleChoice(LinearLayout list) {
        LinearLayout r = row(list, "界面风格", "经典：照 Kindle 的主页和书库。方块：底部 首页 / 文件 / 应用 / 我的 四个标签，首页是可以自己挑的卡片（继续阅读、天气、日历……）。");
        final boolean on = Blocks.on(this);
        if (bs) {
            ((LinearLayout) r.getTag()).addView(new Blocks.Stepper(this, new String[] { "经典", "方块" }, on ? 1 : 0, new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) { setStyle(i == 1 ? "blocks" : "classic"); }
            }).wrap());
            return;
        }
        ((LinearLayout) r.getTag()).addView(valueButton(on ? "方块" : "经典", new View.OnClickListener() {
            @Override public void onClick(View v) { setStyle(on ? "classic" : "blocks"); }
        }));
    }

    private static final String[] NAV_L = { "手势", "三大金刚键", "按键 + 手势" };

    /** 导航方式：手势（默认）/ 底部三大金刚键（返回 / 主页 / 多任务）/ 两者都要。 */
    private void navModeChoice(LinearLayout list) {
        LinearLayout r = row(list, "导航方式", "手势：从下往上划返回、从上往下划打开面板。三大金刚键：屏幕底部显示 返回 / 主页 / 多任务，应用的显示区域会让出这一条，不会被挡住。");
        final int[] at = { Math.max(0, Math.min(2, prefs.getInt("navmode", 0))) };
        if (bs) {
            ((LinearLayout) r.getTag()).addView(bStepper(NAV_L, at[0], new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) {
                    prefs.edit().putInt("navmode", i).commit();
                    HostLink.settings("KFB_GESTURES=" + (i == 1 ? "0" : "1") + "\n");
                    NavBarService.apply(HomeActivity.this);
                }
            }));
            return;
        }
        final ValueButton vb = valueButton(NAV_L[at[0]], null);
        vb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                at[0] = (at[0] + 1) % NAV_L.length;
                prefs.edit().putInt("navmode", at[0]).commit();
                vb.setText(NAV_L[at[0]]);
                // 只用按键时关掉其他手势（从最下边往上划的返回仍然保留）
                HostLink.settings("KFB_GESTURES=" + (at[0] == 1 ? "0" : "1") + "\n");
                NavBarService.apply(HomeActivity.this);
            }
        });
        ((LinearLayout) r.getTag()).addView(vb);
    }

    private int def(String key) { return "cols".equals(key) ? 3 : "size".equals(key) ? 1 : Cleaner.LIMIT_KEY.equals(key) ? Cleaner.defaultLimit() : "wallpaperdim".equals(key) ? 2 : 0; }

    private void localSwitch(LinearLayout list, String title, String desc, final String key, boolean def) {
        LinearLayout r = row(list, title, desc);
        Icons.Toggle.Listener tl = new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) {
                prefs.edit().putBoolean(key, on).commit();
                if ("library".equals(key) && !on) {
                    // 书库关了：拆掉书库页、停止监听；主页下次显示时按新的设置重搭（homeSig 里含这个开关）
                    if (libPage != null && libPage.root().getParent() != null) ((ViewGroup) libPage.root().getParent()).removeView(libPage.root());
                    libPage = null;
                    if (libReady) { Lib.removeListener(libListener); libReady = false; }
                    if (mainTab == TAB_LIB) mainTab = TAB_HOME;
                }
                if ("statusbar".equals(key)) {
                    if (on) StatusBarService.start(HomeActivity.this);
                    else { StatusBarService.setSuppressed(false); StatusBarService.stop(HomeActivity.this); }
                }
                if (Blocks.CAPS_KEY.equals(key) && blocks != null) { showSettings(); changed(); }   // 英文小字：方块的页面要重搭
            }
        };
        ((LinearLayout) r.getTag()).addView(bs ? new Blocks.Switch(this, prefs.getBoolean(key, def), tl) : new Icons.Toggle(this, prefs.getBoolean(key, def), tl));
    }

    /** A setting the host applies at once: tap the value for the next one. */
    private void hostChoice(LinearLayout list, Map<String, String> host, String title, String desc, final String key,
                            final String[] labels, final String[] values, String def) {
        LinearLayout r = row(list, title, desc);
        String cur = host.get(key);
        if (cur == null) cur = def;
        if (values == ONOFF_V) {
            Icons.Toggle.Listener tl = new Icons.Toggle.Listener() {
                @Override public void onToggle(boolean on) { if (!HostLink.settings(key + "=" + (on ? "1" : "0") + "\n")) say("保存失败"); }
            };
            ((LinearLayout) r.getTag()).addView(bs ? new Blocks.Switch(this, "1".equals(cur), tl) : new Icons.Toggle(this, "1".equals(cur), tl));
            return;
        }
        int idx = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
        if (bs) {
            ((LinearLayout) r.getTag()).addView(bStepper(labels, idx, new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) { if (!HostLink.settings(key + "=" + values[i] + "\n")) say("保存失败"); }
            }));
            return;
        }
        final int[] at = { idx };
        final ValueButton b = valueButton(labels[idx], null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                at[0] = (at[0] + 1) % values.length;
                b.setText(labels[at[0]]);
                if (!HostLink.settings(key + "=" + values[at[0]] + "\n")) say("保存失败");
            }
        });
        ((LinearLayout) r.getTag()).addView(b);
    }
}
