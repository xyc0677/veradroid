// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ITaskStackListener;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Always-on listener for what the host sends: "open this app" (ctl/app) and gesture actions (ctl/act.panel, act.home,
 * act.switch, act.back, act.appopt). Uses inotify, so it costs nothing while idle (no polling).
 */
public class HostService extends Service {
    private final Handler handler = new Handler();
    private CtlWatch.Listener observer;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) { return START_STICKY; }

    @Override public void onCreate() {
        super.onCreate();
        observer = new CtlWatch.Listener() {
            @Override public void onCtl(final String path) {
                if (path == null) return;
                handler.post(new Runnable() { @Override public void run() { handle(path); } });
            }
        };
        CtlWatch.add(observer);
        handler.post(new Runnable() { @Override public void run() { handle("app"); } });
        // 宿主在桌面起来之前就写好的提示（开机时 apps 文件夹里的 APK 装不上）
        handler.post(new Runnable() { @Override public void run() {
            boolean lockReq = false;
            String[] l = new File(HostLink.CTL).list();
            if (l != null) for (String n : l) {
                if (n.startsWith("notice.")) handle(n);
                else if (n.equals("act.lock")) { File lf = new File(HostLink.CTL + n); if (isRootRegularFile(lf)) { deleteCtl(lf); lockReq = true; } }
                // 桌面还没开始听时宿主留下的手势（开机时下划、桌面进程被杀重启的那几秒）：已经过时，删掉、不执行。
                // 以前留着不管：老的宿主脚本用 touch 发手势，文件一直在就再也收不到事件，之后每次下划都“没有响应”
                else if (n.startsWith("act.")) { deleteCtl(new File(HostLink.CTL + n)); android.util.Log.i("VeraHome", "ctl: dropped stale " + n); }
            }
            // 锁屏密码（LockScreen）：USB 上放了重置文件就先清掉；仅在收到宿主上锁请求（act.lock）或本次开机尚未解锁时盖上锁屏，
            // 避免 512MB 机型 LMK 杀掉桌面后 START_STICKY 重建在阅读中途误弹锁屏。
            handle("lock.reset");
            if (lockReq || !LockScreen.isUnlocked(HostService.this)) LockScreen.show(HostService.this);
        } });
        watchForeground();
        AppProf.migrate(this);
        keepAnimationsOff();
        // LMK 杀掉 com.veradroid.home 后系统按 START_STICKY 只重建 HostService（不重建 HomeActivity）：
        // 若用户开了“三大金刚键”，必须在此重新拉起 NavBarService，防止 512 MB 机型阅读时三大金刚丢失。
        try { NavBarService.apply(this); } catch (Throwable t) { android.util.Log.w("VeraHome", "navbar apply: " + t); }
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(panelCloser, f);
        } catch (Throwable t) { android.util.Log.w("VeraHome", "panel closer: " + t); }
    }

    // 前台应用的包名写到 /dev/kfb/fg，宿主的 veradroid-input 据此在某些应用里关掉“底边上划返回”（微信读书上划 = 工具栏）。
    // 用 TaskStackListener（任务栈一变就通知），不轮询。
    private String lastFg = null, lastFgAct = null;
    /** 显示设置按哪个应用算（"home" = 桌面 / 我们自己的页面）；桌面刚替哪个应用发过 LAUNCH（任务栈通知到了就不再发一次） */
    static volatile String profPkg = null, launchedPkg = null;
    static volatile String fgAct;   // 现在前台的 activity（不算 PanelActivity），Panel 打开时记下
    // HOME 键（系统发 CLOSE_SYSTEM_DIALOGS，reason=homekey / recentapps）和屏幕关掉：收起下拉面板
    private final android.content.BroadcastReceiver panelCloser = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) Stats.flushForeground(HostService.this);
            if (Panel.isOpen()) { android.util.Log.i("VeraHome", "panel: " + i.getAction() + " " + i.getStringExtra("reason") + " - closing"); Panel.close(); }
        }
    };
    private final Runnable fgCheck = new Runnable() { @Override public void run() { writeForeground(); } };
    private final Runnable fgCheckLate = new Runnable() { @Override public void run() { writeForeground(); } };
    // 冷启动很慢的应用（模拟器上微信读书 / EinkBro 要 5–15 秒才画出第一屏）：1.5 秒时栈顶还是上一个，之后系统不一定再通知，
    // 后台保留应用就一直看不到它到了前台（0.91 门禁：连开三个应用，一次清理都没触发）。再在 5 秒、15 秒各看一次。
    private final Runnable fgCheck5 = new Runnable() { @Override public void run() { writeForeground(); } };
    private final Runnable fgCheck15 = new Runnable() { @Override public void run() { writeForeground(); } };
    // 兜底：任务栈通知在模拟器上会漏（连开三个应用，有的那次一次通知都没收到，后台保留应用就没动作）。
    // 每 30 秒看一眼栈顶（一次 getRunningTasks(1)，没变就什么都不做），减少离线阅读时的跨进程唤醒。
    private final Runnable fgPoll = new Runnable() { @Override public void run() { writeForeground(); handler.postDelayed(this, 30000); } };
    private static volatile HostService activeService;
    private static ITaskStackListener taskStackListener;
    private static boolean taskStackRegistered;

    private void onTaskStackEvent() {
        handler.removeCallbacks(fgCheck); handler.postDelayed(fgCheck, 30);
        // 启动慢的应用：30 ms 时栈顶有时还是上一个，之后不一定再有变化通知——1.5 秒后再看一次（只在栈变过以后，不是轮询）
        handler.removeCallbacks(fgCheckLate); handler.postDelayed(fgCheckLate, 1500);
        handler.removeCallbacks(fgCheck5); handler.postDelayed(fgCheck5, 5000);
        handler.removeCallbacks(fgCheck15); handler.postDelayed(fgCheck15, 15000);
    }

    private void watchForeground() {
        activeService = this;
        try {
            synchronized (HostService.class) {
                if (!taskStackRegistered) {
                    if (taskStackListener == null) {
                        taskStackListener = new ITaskStackListener.Stub() {
                            @Override public void onTaskStackChanged() {
                                HostService s = activeService;
                                if (s != null) s.onTaskStackEvent();
                            }
                            @Override public void onActivityPinned() { }
                            @Override public void onPinnedActivityRestartAttempt() { }
                            @Override public void onPinnedStackAnimationEnded() { }
                            @Override public void onActivityForcedResizable(String packageName, int taskId) { }
                            @Override public void onActivityDismissingDockedStack() { }
                        };
                    }
                    ActivityManagerNative.getDefault().registerTaskStackListener(taskStackListener);
                    taskStackRegistered = true;
                }
            }
        } catch (Throwable t) { android.util.Log.w("VeraHome", "foreground listener: " + t); }
        handler.post(fgCheck);
        handler.postDelayed(fgPoll, 30000);
    }
    /** 现在真正在前台的 activity（包名/类名）；拿不到就是 null。fgAct 要等下一次前台检查才更新，可能还是上一个页面。 */
    static String topActivity(Context c) {
        try {
            List<ActivityManager.RunningTaskInfo> l = ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(1);
            if (l != null && !l.isEmpty() && l.get(0).topActivity != null)
                return l.get(0).topActivity.getPackageName() + "/" + l.get(0).topActivity.getClassName();
        } catch (Throwable ignored) { }
        return null;
    }
    /** 桌面（HomeActivity）到了前台：和任务栈通知看到桌面时一样发 "fg home"，只是不用等通知（见 HomeActivity.onResume）。 */
    static void foregroundHome() {
        if ("home".equals(profPkg)) return;
        launchedPkg = null;
        profPkg = "home";
        AppProf.fg("home");
    }

    private void writeForeground() {
        String pkg = "", cls = "";
        try {
            List<ActivityManager.RunningTaskInfo> l = ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getRunningTasks(1);
            if (l != null && !l.isEmpty() && l.get(0).topActivity != null) {
                pkg = l.get(0).topActivity.getPackageName();
                cls = l.get(0).topActivity.getClassName();
            }
        } catch (Throwable ignored) { }
        // 每个应用单独的字号：前台应用（或我们自己的哪个页面）变了才看一次；AppFont 只在值真的变了时才写设置
        if (!(pkg + "/" + cls).equals(lastFgAct)) {
            lastFgAct = pkg + "/" + cls; AppFont.forForeground(this, pkg, cls);
            if (!cls.equals("com.veradroid.home.PanelActivity")) fgAct = lastFgAct;
            Panel.onForeground(lastFgAct);   // 下拉面板：前台换了 activity 就收起
        }
        // 每个应用的显示设置（宿主 appprof.sh）：前台应用变了告诉宿主。我们自己盖在应用上面的页面（面板、设置、显示设置……）
        // 不算换应用：底下那个应用的设置照旧。换到别的应用（不是桌面）时，显示桥替它挡住纯色的启动窗口（冷启动的白屏 / 黑屏）。
        String prof = pkg.length() == 0 ? null : !pkg.equals(getPackageName()) ? pkg : AppFont.overlay(cls) ? null : "home";
        if (prof != null && !prof.equals(profPkg)) {
            if (AppProf.host()) {
                if (!prof.equals("home") && !prof.equals(launchedPkg)) HostLink.epd("LAUNCH " + AppProf.LAUNCH_MS);
                AppProf.fg(prof);
            }
            launchedPkg = null;
            profPkg = prof;
        }
        Stats.onForeground(this, pkg, cls);
        if (pkg.equals(lastFg)) return;
        Cleaner.onForeground(this, pkg);   // 后台保留应用：用户应用到了前台，3 秒后按设置清掉多出来的
        try {
            byte[] fgBytes = (pkg + "\n").getBytes("UTF-8");
            File fgFile = new File(HostLink.KFB + "fg");
            if (HostLink.writeAtomic(fgFile, fgBytes) || writeFileInPlace(fgFile, fgBytes)) lastFg = pkg;
        } catch (Exception ignored) { }
    }

    /**
     * 窗口 / 转场 / 动画时长缩放一直保持 0（墨水屏上每一帧动画都是一次刷新）。开机时 init.kindle.rc 的 vd_setup 设成 0，
     * 但有的应用（和“开发者选项”一类的工具）会改回 1：盯着这三个设置，一变就改回 0。ContentObserver，不轮询。
     */
    private static final String[] ANIM_KEYS = { android.provider.Settings.Global.WINDOW_ANIMATION_SCALE,
        android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE };
    private android.database.ContentObserver animObserver;
    private void keepAnimationsOff() {
        animObserver = new android.database.ContentObserver(handler) {
            @Override public void onChange(boolean self) { animationsOff(); }
        };
        try {
            for (String k : ANIM_KEYS)
                getContentResolver().registerContentObserver(android.provider.Settings.Global.getUriFor(k), false, animObserver);
        } catch (Throwable t) { android.util.Log.w("VeraHome", "animation scale observer: " + t); }
        animationsOff();
    }
    private void animationsOff() {
        for (String k : ANIM_KEYS) {
            try {
                float v = android.provider.Settings.Global.getFloat(getContentResolver(), k, 1f);
                if (v != 0f) {
                    android.provider.Settings.Global.putFloat(getContentResolver(), k, 0f);
                    android.util.Log.i("VeraHome", "animation scale " + k + " was " + v + " - set back to 0");
                }
            } catch (Throwable t) { android.util.Log.w("VeraHome", "animation scale " + k + ": " + t); }
        }
    }

    @Override public void onDestroy() {
        Stats.flushForeground(this);
        handler.removeCallbacksAndMessages(null);
        if (noticeDialog != null) { try { if (noticeDialog.isShowing()) noticeDialog.dismiss(); } catch (Throwable ignored) { } noticeDialog = null; noticeText = null; }
        if (usbDialog != null) { try { if (usbDialog.isShowing()) usbDialog.dismiss(); } catch (Throwable ignored) { } usbDialog = null; }
        try { unregisterReceiver(panelCloser); } catch (Throwable ignored) { }
        try { if (animObserver != null) getContentResolver().unregisterContentObserver(animObserver); } catch (Throwable ignored) { }
        if (activeService == this) activeService = null;
        synchronized (HostService.class) {
            if (taskStackRegistered && taskStackListener != null) {
                try {
                    Object am = ActivityManagerNative.getDefault();
                    Method m = am.getClass().getMethod("unregisterTaskStackListener", ITaskStackListener.class);
                    m.invoke(am, taskStackListener);
                    taskStackRegistered = false;
                } catch (Throwable ignored) { }
            }
        }
        if (observer != null) CtlWatch.remove(observer);
        super.onDestroy();
    }

    private static boolean writeFileInPlace(File f, byte[] data) {
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open(f.getPath(),
                    android.system.OsConstants.O_WRONLY
                            | android.system.OsConstants.O_TRUNC
                            | android.system.OsConstants.O_NOFOLLOW,
                    0);
            int off = 0;
            while (off < data.length) {
                int w = android.system.Os.write(fd, data, off, data.length - off);
                if (w <= 0) return false;
                off += w;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (fd != null) try { android.system.Os.close(fd); } catch (Throwable ignored) { }
        }
    }

    private static void deleteCtl(File f) {
        if (f == null) return;
        if (f.delete() || !f.exists()) return;
        String name = f.getName();
        if (name != null && name.length() > 0 && name.indexOf('/') < 0 && !name.equals(".") && !name.equals("..")) {
            HostLink.cmd("ctlack " + name);
        }
    }

    private static boolean isRootRegularFile(File f) {
        try {
            android.system.StructStat st = android.system.Os.lstat(f.getPath());
            if (st != null && st.st_uid == 0 && android.system.OsConstants.S_ISREG(st.st_mode)) return true;
            android.util.Log.w("VeraHome", "ctl rejected (untrusted owner/type): " + f.getName());
            f.delete();
            return false;
        } catch (android.system.ErrnoException e) {
            if (e.errno != android.system.OsConstants.ENOENT) {
                android.util.Log.w("VeraHome", "ctl stat failed: " + f.getName() + ": " + e);
                f.delete();
            }
            return false;
        } catch (Throwable t) {
            android.util.Log.w("VeraHome", "ctl stat failed: " + f.getName() + ": " + t);
            f.delete();
            return false;
        }
    }

    private void handle(String name) {
        if ("sleep".equals(name)) { Stats.flushForeground(this); return; }
        boolean isHostCtl = name.equals("app") || name.equals("storage.notice")
                || (name.startsWith("notice.") && !name.endsWith(".tmp"))
                || name.equals("usb.ask") || name.equals("usb.gone")
                || name.equals("lock.reset") || name.startsWith("act.");
        if (!isHostCtl) return;
        File f = new File(HostLink.CTL + name);
        if (!isRootRegularFile(f)) return;
        if (name.equals("app")) {
            String want = null;
            try {
                BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                try { want = r.readLine(); } finally { r.close(); }
            } catch (Exception ignored) { }
            deleteCtl(f);
            if (want == null) return;
            want = want.trim();
            if (want.length() == 0) return;
            if (want.equals("home")) { goHome(false); return; }
            if (AppProf.host()) { launchedPkg = want; AppProf.launching(want); } else applyAppDisplay(want);
            AppFont.forForeground(this, want, null);   // 启动前就换好字号，免得应用画完一遍再重排一遍
            HostLink.cmd("appdirs " + want);
            Intent i = getPackageManager().getLaunchIntentForPackage(want);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(i);
            }
        } else if (name.equals("storage.notice")) {
            // 宿主调整了存储空间（自动扩容、离线调整的结果、断电后的收尾）：告诉用户一声（boot/lib/storage.sh 的 st_notify）
            String l = null;
            try {
                BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                try { l = r.readLine(); } finally { r.close(); }
            } catch (Exception ignored) { }
            deleteCtl(f);
            String msg = storageNotice(l);
            if (msg != null) VeraToast.show(this, msg);
        } else if (name.startsWith("notice.")) {
            if (name.endsWith(".tmp")) return;
            String title = null; StringBuilder body = new StringBuilder();
            try {
                BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                try {
                    title = r.readLine();
                    String line;
                    while ((line = r.readLine()) != null && body.length() < 1000) { if (body.length() > 0) body.append('\n'); body.append(line); }
                } finally { r.close(); }
            } catch (Exception ignored) { }
            deleteCtl(f);
            if (title == null || title.trim().length() == 0) return;
            notice(title.trim(), body.toString().trim());
        } else if (name.equals("usb.ask")) {
            // 插上了 USB 线（宿主 boot/lib/usb.sh 的 usb_ask）：问要不要切到 USB 传输
            deleteCtl(f);
            usbAsk();
        } else if (name.equals("usb.gone")) {
            // 线拔了：还开着的插线提示收起来
            deleteCtl(f);
            if (usbDialog != null) { try { usbDialog.dismiss(); } catch (Throwable ignored) { } usbDialog = null; }
        } else if (name.equals("lock.reset")) {
            deleteCtl(f);
            if (LockScreen.enabled(this)) { LockScreen.clear(this); VeraToast.show(this, "锁屏密码已清除"); }
        } else if (name.startsWith("act.")) {
            deleteCtl(f);
            Perf.begin();   // 宿主手势到达：从这里算面板 / 切换器打开用了多久
            String act = name.substring(4);
            if (act.equals("lock")) { LockScreen.show(this); return; }
            if (LockScreen.showing()) return;   // 锁着时不让手势打开面板 / 切换应用 / 回桌面（回 Kindle 由宿主自己做，不经过这里）
            if (act.equals("panel")) { if (Panel.isOpen()) Panel.close(); else Panel.open(this); }
            else if (Panel.isOpen() && act.equals("back")) Panel.close();
            else if (act.equals("switch")) { Panel.close(); start(SwitcherActivity.class); }
            else if (act.equals("appopt")) appOpt();
            else if (act.equals("home")) { Panel.close(); goHome(false); }
            else if (act.equals("back")) key(KeyEvent.KEYCODE_BACK);
            else if (act.equals("screenshot")) {
                Panel.close();
                // Wait 900ms for any 800ms gesture badge or closing panel to disappear before capturing /dev/fb0.
                handler.postDelayed(new Runnable() { @Override public void run() { WebAdmin.saveScreenshot(HostService.this, false); } }, 900);
            }
        }
    }

    static String storageNotice(String l) {
        if (l == null) return null;
        String[] a = l.trim().split(" ");
        try {
            if (a[0].equals("grown") && a.length >= 3) return "存储空间快满了，已自动扩大到 " + StorageActivity.size(Integer.parseInt(a[2]));
            if (a[0].equals("pending") && a.length >= 2) return "存储空间快满了，下次启动 VeraDroid 时会扩大到 " + StorageActivity.size(Integer.parseInt(a[1]));
            if (a[0].equals("full")) return "存储空间快满了，已经不能再扩大（" + (a.length > 1 && a[1].equals("fat") ? "FAT 格式单个文件最大 4 GB" : "Kindle 没有剩余空间了") + "）。请删掉不用的应用或书。";
            if (a[0].equals("result") && a.length >= 5) {
                String op = a[2].equals("grow") ? "扩大" : a[2].equals("shrink") ? "缩小" : "调整";
                if (a[1].equals("ok")) return "存储空间已" + op + "到 " + StorageActivity.size(Integer.parseInt(a[4]));
                if (a[1].equals("rolledback")) return "存储空间" + op + "被中断，已恢复原样，数据没有变化";
                return "存储空间没有" + op + "，数据没有变化（设置 → 设备与更新 → 存储空间 里有原因）";
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * 宿主的提示（ctl/notice.*：第一行标题，其余正文）：通知栏里留一条（下拉面板的“通知”能再看到），
     * 屏幕上再弹一个框，用户点“知道了”才消失。现在只有一种：apps 文件夹里的 APK 装不上（afterboot.sh 的 apk_failed）。
     */
    private int noticeId = 7100;
    private android.app.Dialog noticeDialog;
    private android.widget.TextView noticeText;
    private android.app.Dialog usbDialog;
    /**
     * Android 在前台时插上 USB 线：Kindle 的 USB（MTP / 大容量存储）这时停着，电脑上什么都不出现（不能让 Android 和电脑同时写
     * 用户存储）。问一句要不要切到 USB 传输：切换 = 回到 Kindle（宿主 usbswitch，和“返回 Kindle”同一套），Kindle 接着进 USB 传输；
     * 拔线后在书库点“VeraDroid 启动”回来。充电器也会触发（分不出来），所以有“不用”和“不再询问”。锁屏时不问。
     */
    private void usbAsk() {
        if (LockScreen.showing()) return;
        if (usbDialog != null && usbDialog.isShowing()) return;
        try {
            Context c = new android.view.ContextThemeWrapper(this, android.R.style.Theme_Holo_Light_NoActionBar);
            final android.app.Dialog d = new android.app.Dialog(c);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            android.widget.LinearLayout box = new android.widget.LinearLayout(c);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int p = Ui.dp(c, 20);
            box.setPadding(p, p, p, p);
            box.setBackground(Ui.frame(c, false));
            android.widget.TextView t = Ui.text(c, "已连接电脑：要切换到 USB 传输吗？", 18, true);
            t.setPadding(0, 0, 0, Ui.dp(c, 10));
            box.addView(t);
            android.widget.TextView m = Ui.text(c, "会退回 Kindle，电脑上就能看到 Kindle 盘；拔线后在书库点“VeraDroid 启动”，一键回到安卓。"
                + "\n\n不想离开安卓：用“传书”（设置 → 传书，电脑浏览器打开网页传文件）或 adb。只是在充电就点“不用”。", 16, false);
            m.setPadding(0, 0, 0, Ui.dp(c, 16));
            box.addView(m);
            android.widget.LinearLayout row = new android.widget.LinearLayout(c);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams w1 = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            android.widget.LinearLayout.LayoutParams w2 = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            android.widget.LinearLayout.LayoutParams w3 = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            w2.leftMargin = Ui.dp(c, 8); w3.leftMargin = Ui.dp(c, 8);
            row.addView(Ui.button(c, "不再询问", 15, false, new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { try { d.dismiss(); } catch (Throwable ignored) { } HostLink.cmd("usbnoask");
                    VeraToast.show(HostService.this, "以后插线不再询问（设置 → 设备 里可以改回来）"); }
            }), w1);
            row.addView(Ui.button(c, "不用", 15, false, new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { try { d.dismiss(); } catch (Throwable ignored) { } }
            }), w2);
            row.addView(Ui.button(c, "切换", 15, true, new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { try { d.dismiss(); } catch (Throwable ignored) { } HostLink.cmd("usbswitch"); }
            }), w3);
            box.addView(row);
            d.setContentView(box);
            android.view.Window w = d.getWindow();
            if (w != null) {
                w.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                w.setWindowAnimations(0);
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);   // 墨水屏上压暗 = 整屏闪两次
            }
            d.show();
            if (w != null) w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            usbDialog = d;
        } catch (Throwable e) { android.util.Log.w("VeraHome", "usb dialog: " + e); }
    }

    private void notice(String title, String text) {
        try {
            android.app.Notification n = new android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build();
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(noticeId++, n);
        } catch (Throwable t) { android.util.Log.w("VeraHome", "notice: " + t); }
        // 已经有一个提示框开着（开机时几个 APK 一起失败）：接在同一个框里，不叠好几层
        String item = title + "\n" + text;
        if (noticeDialog != null && noticeDialog.isShowing() && noticeText != null) {
            noticeText.setText(noticeText.getText() + "\n\n" + item);
            return;
        }
        try {
            Context c = new android.view.ContextThemeWrapper(this, android.R.style.Theme_Holo_Light_NoActionBar);
            final android.app.Dialog d = new android.app.Dialog(c);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            android.widget.LinearLayout box = new android.widget.LinearLayout(c);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            int p = Ui.dp(c, 20);
            box.setPadding(p, p, p, p);
            box.setBackground(Ui.frame(c, false));
            android.widget.TextView m = Ui.text(c, item, 16, false);
            m.setPadding(0, 0, 0, Ui.dp(c, 14));
            android.widget.ScrollView sv = new android.widget.ScrollView(c);
            sv.addView(m);
            box.addView(sv, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            box.addView(Ui.button(c, "知道了", 16, true, new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) { try { d.dismiss(); } catch (Throwable ignored) { } }
            }));
            d.setContentView(box);
            android.view.Window w = d.getWindow();
            if (w != null) {
                w.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                w.setWindowAnimations(0);
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);   // 墨水屏上压暗 = 整屏闪两次
            }
            d.show();
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.72f);
            if (w != null) w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.86), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            sv.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            final int limitH = maxH;
            final android.widget.ScrollView fsv = sv;
            sv.post(new Runnable() { @Override public void run() {
                if (fsv.getHeight() > limitH) { fsv.getLayoutParams().height = limitH; fsv.requestLayout(); }
            } });
            noticeDialog = d; noticeText = m;
        } catch (Throwable t) { android.util.Log.w("VeraHome", "notice dialog: " + t); }
    }

    /**
     * 底边左段上划（宿主 action.sh 的 appopt）：前台应用的“应用优化”页
     * （刷新方式、全刷、对比度、字号……只对它生效）。前台是桌面或我们自己的页面时没有“当前应用”，打开下拉面板（全局的显示设置）。
     * 我们盖在应用上面的页面（面板、切换器、设置）不算：按底下那个应用算（profPkg）。
     */
    private void appOpt() {
        String top = topActivity(this), pkg = null;
        if (top != null) {
            String tp = top.substring(0, top.indexOf('/')), tc = top.substring(top.indexOf('/') + 1);
            if (tc.endsWith(".AppDisplayActivity")) return;          // 已经开着
            if (!tp.equals(getPackageName())) pkg = tp;
            else if (AppFont.overlay(tc) && profPkg != null && !profPkg.equals("home")) pkg = profPkg;
        }
        if (pkg == null) { if (!Panel.isOpen()) Panel.open(this); return; }
        Panel.close();
        String label = pkg;
        try { label = String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); } catch (Throwable ignored) { }
        Intent i = new Intent(this, AppDisplayActivity.class);
        i.putExtra("pkg", pkg);
        i.putExtra("label", label);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    private void start(Class<?> c) {
        Intent i = new Intent(this, c);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    private void applyAppDisplay(String pkg) {
        String spec = Store.prefs(this, "home").getString("appdisp_" + pkg, "");
        if (spec == null || spec.length() == 0) HostLink.cmd("appdisp home");
        else HostLink.cmd("appdisp " + pkg + "|" + spec);
    }

    private void goHome(boolean settings) {
        if (settings) { SettingsActivity.open(this); return; }   // 设置盖在当前应用上面，返回回到它（不切到桌面）
        if (AppProf.host()) foregroundHome(); else HostLink.cmd("appdisp home");
        Intent i = HomeActivity.homeIntent(this);
        startActivity(i);
    }

    private void key(int code) {
        try {
            InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
            Method m = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
            long now = SystemClock.uptimeMillis();
            m.invoke(im, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), 0);
            m.invoke(im, new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), 0);
        } catch (Throwable ignored) { }
    }
}
