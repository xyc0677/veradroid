// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * 后台清理。两种用法，同一套规则：
 *
 * 1. 自动（后台保留应用，键 bg_keep：0 / 1 / 2 / 3 / 4 = 不限，默认 1）：一个用户自己装的应用到了前台，
 *    3 秒后（不和它的启动抢）看后台还活着几个用户应用，超过 N 个就把最久没用的那几个停掉。
 * 2. 手动（面板的“清后台”、电源与时间的“清理后台”）：除了前台那个，后台的用户应用全部停掉，报告清了几个、多出多少内存。
 *
 * 只动第三方（非系统）应用；不动 VeraHome、Vera 输入法、系统应用和服务、前台的应用、标了“保持后台”的应用
 * （应用长按 → 保持后台，键 bgkeep_<包名>）。用 killBackgroundProcesses，停不掉的后台进程用 killUid（见 kill），不用 forceStop：
 * 闹钟、通知、同步不会被清掉；有服务在跑、看得见的应用不动。前台应用的变化来自 HostService 的 TaskStackListener（不轮询）。
 * 每次的决定都写到 logcat（标签 VeraHome，“bgclean …”）。
 */
final class Cleaner {
    interface Done { void done(int stopped, long freedMb); }

    private Cleaner() { }

    static final String LIMIT_KEY = "bg_keep";
    static final String[] LIMIT_L = { "0 个", "1 个", "2 个", "3 个", "不限" };
    private static final String TAG = "VeraHome";

    /** 用户没选过时的默认：957 MB 的机型（PW6 / KS）2 个，512 MB 机型 1 个。一台 PW6 测试机实测（loop0 直读写，桌面 → 微信读书 → 多看 →
     *  静读天下 轮换 3 轮，docs/jank.md 6 节）：1 个 14.7 s / 轮、每次切换读盘中位 171 MB；2 个 8.4 s、24 MB；3 个 8.7 s、23 MB；
     *  MemAvailable 最低 309 / 332 / 316 MB，三种都没有 lmkd / 内核 OOM 杀进程。 */
    static int defaultLimit() {
        long kb = 0;
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"));
            try {
                String l;
                while ((l = r.readLine()) != null) if (l.startsWith("MemTotal:")) { kb = Long.parseLong(l.replaceAll("[^0-9]", "")); break; }
            } finally { r.close(); }
        } catch (Throwable ignored) { }
        return kb > 0 && kb < 700 * 1024 ? 1 : 2;
    }

    /** 后台最多留几个用户应用；-1 = 不限。 */
    static int limit(Context c) {
        int i = defaultLimit();
        try { i = Store.prefs(c, "home").getInt(LIMIT_KEY, i); } catch (Throwable ignored) { }
        return i >= 4 ? -1 : Math.max(0, i);
    }

    static boolean keepAlive(Context c, String pkg) {
        boolean local = false;
        try { local = Store.prefs(c, "home").getBoolean("bgkeep_" + pkg, false); } catch (Throwable ignored) { }
        return local || Boolean.TRUE.equals(AppProf.keepAliveOverride(pkg));
    }

    static void setKeepAlive(Context c, String pkg, boolean on) {
        try { Store.prefs(c, "home").edit().putBoolean("bgkeep_" + pkg, on).apply(); } catch (Throwable ignored) { }
        AppProf.setKeepAlive(pkg, on);
    }

    /** 用户自己装的应用（不是系统应用、不是我们自己的）。 */
    static boolean userApp(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0 || pkg.equals(c.getPackageName()) || pkg.startsWith("com.veradroid.") || pkg.equals("android") || pkg.startsWith("com.android.")) return false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0 && ai.uid >= Process.FIRST_APPLICATION_UID;
        } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // ------------------------------------------------------------------ 自动

    /** 用户应用最近到过前台的顺序（最近的在最前）。进程内记着；HostService 重启后从头记（没记过的算最久）。 */
    private static final LinkedList<String> recent = new LinkedList<String>();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static volatile String front;   // read by the VeraBgClean thread
    private static Context appCtx;
    private static final Runnable enforce = new Runnable() { @Override public void run() { enforceNow(); } };

    /** 前台应用变了（HostService 调）。 */
    static void onForeground(Context c, String pkg) {
        appCtx = c.getApplicationContext();
        front = pkg;
        if (!userApp(c, pkg)) return;   // 回到桌面 / 我们自己的页面 / 系统界面：不清理（只在用户应用到前台时）
        recent.remove(pkg);
        recent.addFirst(pkg);
        while (recent.size() > 40) recent.removeLast();
        ui.removeCallbacks(enforce);
        ui.postDelayed(enforce, 3000);
    }

    private static void enforceNow() {
        final Context c = appCtx;
        final int n = c == null ? -1 : limit(c);
        if (n < 0) return;
        final String fg = front;
        final List<String> rec = new ArrayList<String>(recent);   // 主线程上拷一份，后台线程只读拷贝
        new Thread(new Runnable() { @Override public void run() {
            List<String> bg = backgroundUserApps(c, fg);
            // 按最近用过排：记过的按记的顺序，没记过的放最后（最久）
            List<String> order = new ArrayList<String>();
            for (String p : rec) if (bg.contains(p)) order.add(p);
            for (String p : bg) if (!order.contains(p)) order.add(p);
            if (order.size() <= n) { Log.i(TAG, "bgclean: front=" + fg + " background=" + order + " limit=" + n + " -> nothing to stop"); return; }
            List<String> stop = order.subList(n, order.size());
            Log.i(TAG, "bgclean: front=" + fg + " background=" + order + " limit=" + n + " -> stop " + stop);
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            // 刚被换下去的应用在新应用画出来之前还算“看得见”，系统不让杀（模拟器上 Anki 第一次打开要十几秒）：
            // 停不掉就隔 3 秒再试，最多三次；还活着就是有前台服务之类，随它
            List<String> left = new ArrayList<String>(stop);
            for (int round = 0; round < 3 && !left.isEmpty(); round++) {
                if (round > 0) { try { Thread.sleep(3000); } catch (InterruptedException e) { return; } }
                if (front != null && !front.equals(fg)) { Log.i(TAG, "bgclean: front changed to " + front + ", stop retrying"); return; }
                for (String p : left) kill(am, p);
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                Set<String> still = runningPackages(c);
                List<String> next = new ArrayList<String>();
                for (String p : left) if (still.contains(p)) next.add(p);
                left = next;
            }
            Log.i(TAG, "bgclean: done" + (left.isEmpty() ? "" : ", still running (left alone): " + left));
        } }, "VeraBgClean").start();
    }

    // ------------------------------------------------------------------ 手动

    /** 除了 keepPkg（前台那个）以外，后台的用户应用全部停掉。回调在主线程：停了几个、MemAvailable 多了几 MB。 */
    static void run(final Context c, final String keepPkg, final Done cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                long before = memAvailableMb();
                final int n = cleanSync(c, keepPkg);
                final long freed = Math.max(0, memAvailableMb() - before);
                if (cb != null) h.post(new Runnable() { @Override public void run() { cb.done(n, freed); } });
            }
        }, "VeraCleaner").start();
    }

    /** 同上，在调用的线程里做完（不要在主线程调）：返回真的停掉了几个。 */
    static int cleanSync(Context c, String keepPkg) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<String> victims = backgroundUserApps(c, keepPkg);
        Log.i(TAG, "bgclean manual: front=" + keepPkg + " stop " + victims);
        for (String p : victims) kill(am, p);
        if (victims.isEmpty()) return 0;
        try { Thread.sleep(800); } catch (InterruptedException ignored) { }
        Set<String> still = runningPackages(c);
        int stopped = 0;
        for (String p : victims) if (!still.contains(p)) stopped++; else Log.i(TAG, "bgclean: " + p + " still running (foreground service?) - left alone");
        return stopped;
    }

    /**
     * 用户在切换应用里点了“关闭”：任务已经拿掉了，这里结束它的进程（不在主线程调）。先 killBackgroundProcesses，还活着的
     * 再 killUid（只是进程没了，不是“强行停止”：闹钟、通知、同步还在）；有前台服务的不动。不看“保持后台”（用户点的就是它）。
     * 返回进程是不是没了。
     */
    static boolean closeSync(Context c, String pkg) {
        if (pkg == null || !userApp(c, pkg)) return false;
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        Log.i(TAG, "switcher close: " + pkg);
        if (pkg.equals(front)) front = null;   // 切换器盖在它上面：任务已经拿掉，它不再是前台
        // 任务已经拿掉，但切换器是盖在它上面的半屏窗口，它的 Activity 要等切换器走了才销毁，这之前它的进程还算“前台”，
        // 所以这里不按 importance 判断。用户点的就是关它；只有在跑前台服务（音乐、下载、朗读）的不动。
        try {
            List<ActivityManager.RunningServiceInfo> svcs = am.getRunningServices(200);
            if (svcs != null) for (ActivityManager.RunningServiceInfo si : svcs)
                if (si.foreground && si.service != null && pkg.equals(si.service.getPackageName())) {
                    Log.i(TAG, "switcher close: " + pkg + " runs a foreground service - process left alone");
                    return false;
                }
        } catch (Throwable ignored) { }
        try { am.killBackgroundProcesses(pkg); } catch (Throwable t) { Log.w(TAG, "switcher close: " + pkg + ": " + t); }
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            int uid = -1;
            if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs)
                if (p.pkgList != null && java.util.Arrays.asList(p.pkgList).contains(pkg)) uid = p.uid;
            if (uid >= Process.FIRST_APPLICATION_UID) {
                Object amn = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
                amn.getClass().getMethod("killUid", int.class, int.class, String.class).invoke(amn, uid % 100000, uid / 100000, "VeraHome switcher close");
                Log.i(TAG, "switcher close: " + pkg + " process ended (killUid)");
            }
        } catch (Throwable t) { Log.w(TAG, "switcher close killUid " + pkg + ": " + t); }
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        return !runningPackages(c).contains(pkg);
    }

    // ------------------------------------------------------------------ 共用

    /** 现在有进程活着的用户应用，去掉前台的、保持后台的。 */
    private static List<String> backgroundUserApps(Context c, String fg) {
        List<String> out = new ArrayList<String>();
        for (String p : runningPackages(c)) {
            if (p.equals(fg) || !userApp(c, p)) continue;
            if (keepAlive(c, p)) { Log.i(TAG, "bgclean: " + p + " marked 保持后台 - kept"); continue; }
            out.add(p);
        }
        return out;
    }

    /** 当前正在运行前台服务的应用包名集合（听书、音乐、朗读、下载等）。 */
    static Set<String> foregroundServicePackages(Context c) {
        Set<String> out = new HashSet<String>();
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningServiceInfo> svcs = am.getRunningServices(200);
            if (svcs != null) for (ActivityManager.RunningServiceInfo si : svcs)
                if (si.foreground && si.service != null) out.add(si.service.getPackageName());
        } catch (Throwable ignored) { }
        return out;
    }

    static Set<String> runningPackages(Context c) {
        Set<String> out = new HashSet<String>();
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
            if (procs != null) for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.uid < Process.FIRST_APPLICATION_UID || p.pkgList == null) continue;
                for (String pkg : p.pkgList) out.add(pkg);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * 停一个后台应用：先 killBackgroundProcesses（7.1 上它只杀“缓存”状态的进程，刚换下去的那个“上一个应用”杀不掉）；
     * 还活着、而且只是后台状态（importance ≥ BACKGROUND：没有前台服务、不可见、不可感知）的，再用 killUid 结束它的进程——
     * 和系统内存不够时杀进程一样，只是进程没了，不是“强行停止”：闹钟、通知、同步、开机广播都还在。有服务在跑的不动。
     */
    private static void kill(ActivityManager am, String pkg) {
        // never the app in front, also when it came to the front after the list was made (user switched within the 3 s)
        if (pkg.equals(front)) { Log.i(TAG, "bgclean: " + pkg + " is in front now - left alone"); return; }
        try { am.killBackgroundProcesses(pkg); } catch (Throwable t) { Log.w(TAG, "bgclean: " + pkg + ": " + t); }
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }
        try {
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return;
            int uid = -1;
            boolean onlyBackground = true;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pkgList == null || !java.util.Arrays.asList(p.pkgList).contains(pkg)) continue;
                uid = p.uid;
                if (p.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) onlyBackground = false;
            }
            if (uid < 0) return;   // 已经停了
            if (!onlyBackground) { Log.i(TAG, "bgclean: " + pkg + " has a service / is visible - left alone"); return; }
            if (pkg.equals(front)) { Log.i(TAG, "bgclean: " + pkg + " is in front now - left alone"); return; }
            Object amn = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            amn.getClass().getMethod("killUid", int.class, int.class, String.class).invoke(amn, uid % 100000, uid / 100000, "VeraHome bgclean");
            Log.i(TAG, "bgclean: " + pkg + " process ended (killUid, background only)");
        } catch (Throwable t) { Log.w(TAG, "bgclean killUid " + pkg + ": " + t); }
    }

    /** /proc/meminfo 的可用内存（MB）；3.0 老内核没有 MemAvailable 时回退 MemFree + Buffers + Cached。 */
    static long memAvailableMb() {
        long avail = -1, free = 0, buffers = 0, cached = 0;
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"));
            try {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("MemAvailable:")) { avail = Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024; break; }
                    else if (l.startsWith("MemFree:")) free = Long.parseLong(l.replaceAll("[^0-9]", ""));
                    else if (l.startsWith("Buffers:")) buffers = Long.parseLong(l.replaceAll("[^0-9]", ""));
                    else if (l.startsWith("Cached:")) cached = Long.parseLong(l.replaceAll("[^0-9]", ""));
                }
            } finally { r.close(); }
        } catch (Throwable ignored) { }
        return avail >= 0 ? avail : (free + buffers + cached) / 1024;
    }

    /** The app the user was in before opening the panel (the task under the panel). */
    @SuppressWarnings("deprecation")
    static String previousApp(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningTaskInfo t : am.getRunningTasks(4)) {
                if (t.topActivity == null) continue;
                String pkg = t.topActivity.getPackageName();
                if (!pkg.equals(c.getPackageName())) return pkg;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
