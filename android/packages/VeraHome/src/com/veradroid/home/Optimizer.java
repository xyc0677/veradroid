package com.veradroid.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 下拉面板的“优化机器状态”：按顺序做三件事，然后给一张结果卡片。
 *
 * 1. 清理后台：和“清后台”同一套（{@link Cleaner#cleanSync}：前台的、保持后台的不动）。
 * 2. 提升运行速度：请还活着的非前台应用释放缓存（trim memory，平台签名的隐藏接口）；请宿主丢掉干净的页缓存、整理内存
 *    （宿主命令 optimize，写 ctl/optimize.result；老宿主没有这条命令就只做 Android 这边）；有应用等着预编译、又在充电，
 *    宿主顺手开始编，否则只报“N 个应用待优化（充电时自动进行）”。
 * 3. 提升省电：只做看不出变化、也不违背用户选择的事——Wi-Fi 省电按宿主原来的自动规则恢复，传书网页 5 分钟没人用就关。
 *    亮度、自动休眠、Wi-Fi 开关这些用户能看到的，不偷偷改，列成建议，每条一个“调整”。
 */
final class Optimizer {
    private Optimizer() { }

    static final class Suggestion {
        final String text, action;
        final Runnable act;
        Suggestion(String text, String action, Runnable act) { this.text = text; this.action = action; this.act = act; }
    }

    static final class Result {
        int stopped, trimmed;
        long before, after;
        boolean hostOk;
        int compilePending; boolean compileKicked;
        String wifiPs = "";
        boolean xferStopped, xferBusy;
        final List<Suggestion> tips = new ArrayList<Suggestion>();

        /** 一行总结：“已清理 2 个应用 · 可用内存 +120 MB · 省电已开”。 */
        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(stopped == 0 ? "后台没有要清理的应用" : "已清理 " + stopped + " 个应用");
            long d = after - before;
            sb.append(" · 可用内存 ").append(d >= 0 ? "+" : "").append(d).append(" MB");
            if ("on".equals(wifiPs)) sb.append(" · 省电已开");
            return sb.toString();
        }

        /** 第二行起的细节。 */
        List<String> details() {
            List<String> l = new ArrayList<String>();
            l.add("现在可用内存 " + after + " MB" + (trimmed > 0 ? "，已请 " + trimmed + " 个后台进程释放缓存" : ""));
            if (!hostOk) l.add("这一版宿主不支持整理内存（更新 VeraDroid 后就有）");
            if (compileKicked) l.add("正在优化 " + compilePending + " 个应用（充电中）");
            else if (compilePending > 0) l.add(compilePending + " 个应用待优化（充电时自动进行）");
            if (xferStopped) l.add("传书网页 5 分钟没人用，已关掉");
            if ("busy".equals(wifiPs) || ("off".equals(wifiPs) && xferBusy)) l.add("Wi-Fi 省电暂时关着（正在传书或大量下载）");
            else if ("off".equals(wifiPs)) l.add("Wi-Fi 省电当前未开启");
            return l;
        }
    }

    interface Done { void done(Result r); }

    static void run(final Context c, final String front, final Done cb) {
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() { @Override public void run() {
            final Result r = new Result();
            r.before = Cleaner.memAvailableMb();
            // 1. 清理后台
            try { r.stopped = Cleaner.cleanSync(c, front); } catch (Throwable t) { Log.w("VeraHome", "optimize clean: " + t); }
            // 2. 运行速度：请后台进程释放缓存，再请宿主整理内存
            r.trimmed = trimBackground(c, front);
            File res = new File(HostLink.CTL + "optimize.result");
            res.delete();
            HostLink.cmd("optimize");
            for (int i = 0; i < 40 && !res.exists(); i++) { try { Thread.sleep(150); } catch (InterruptedException ignored) { } }
            Map<String, String> hr = HostLink.kv(HostLink.CTL + "optimize.result");
            r.hostOk = res.exists();
            r.compilePending = HostLink.num(hr, "compile_pending", 0);
            r.compileKicked = "1".equals(hr.get("compile_kicked"));
            r.wifiPs = hr.get("wifips") == null ? "" : hr.get("wifips");
            if (!r.hostOk) {   // 老宿主：至少看看预编译队列（只读）
                Map<String, String> cs = HostLink.kv(HostLink.CTL + "compile.status");
                if ("waiting".equals(cs.get("state")) || "paused".equals(cs.get("state"))) r.compilePending = Math.max(1, HostLink.num(cs, "total", 1));
            }
            // 3. 省电：传书网页没人用就关
            Transfer t = Transfer.get(c);
            if (t.running() && t.idleMs() >= 5 * 60 * 1000L) { t.stop(); r.xferStopped = true; HostLink.cmd("status"); }
            r.xferBusy = t.running();
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            r.after = Cleaner.memAvailableMb();
            suggest(c, r);
            Log.i("VeraHome", "optimize: " + r.summary() + " | " + r.details());
            h.post(new Runnable() { @Override public void run() { cb.done(r); } });
        } }, "VeraOptimize").start();
    }

    /** 用户看得见的耗电项：只给建议，点“调整”才改。 */
    private static void suggest(final Context c, Result r) {
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        final int max = Math.max(1, HostLink.num(st, "lightmax", 24));
        int light = HostLink.num(st, "light", 0);
        if (HostLink.num(st, "haslight", 1) == 1 && light * 2 > max && HostLink.num(st, "lightauto", 0) == 0) {
            r.tips.add(new Suggestion("前光 " + (light * 100 / max) + "%：调到 40% 能省不少电", "调到 40%", new Runnable() {
                @Override public void run() { HostLink.cmd("light " + Math.round(max * 0.4f)); HostLink.cmd("status"); } }));
        }
        int sleep = HostLink.num(st, "sleep", 600);
        if (HostLink.num(st, "hassleeptime", 1) == 1 && (sleep <= 0 || sleep > 1800)) {
            r.tips.add(new Suggestion(sleep <= 0 ? "自动休眠关着" : "自动休眠要等 " + (sleep / 60) + " 分钟", "改成 10 分钟", new Runnable() {
                @Override public void run() { HostLink.cmd("sleeptime 600"); HostLink.cmd("status"); } }));
        }
        if (HostLink.num(st, "wifi", 1) == 1 && !Transfer.get(c).running()) {
            r.tips.add(new Suggestion("Wi-Fi 开着：不用网络时关掉更省电", "关 Wi-Fi", new Runnable() {
                @Override public void run() { HostLink.cmd("wifi 0"); HostLink.cmd("status"); } }));
        }
        if (HostLink.num(st, "bton", 0) == 1) {
            String dev = st.get("btdev");
            if (dev == null || dev.length() == 0) r.tips.add(new Suggestion("蓝牙开着，但没有连着翻页器", "去看看", new Runnable() {
                @Override public void run() { BluetoothActivity.open(c); } }));
        }
    }

    /** 宿主 BG_FREEZE 对后台进程发 SIGSTOP 后处于内核 T 态，无法响应异步 trimMemory。 */
    private static boolean isStoppedProcess(int pid) {
        if (pid <= 0) return false;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid + "/stat"));
            String line = r.readLine();
            if (line == null) return false;
            int rp = line.lastIndexOf(')');
            if (rp < 0 || rp + 2 >= line.length()) return false;
            char st = line.charAt(rp + 2);
            return st == 'T' || st == 't';
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
    }

    /** 请前台以外的应用进程释放缓存（TRIM_MEMORY_MODERATE）。只请，不杀；跳过 BG_FREEZE 停止态进程，返回实际请了几个。 */
    private static int trimBackground(Context c, String front) {
        int n = 0;
        try {
            Object am = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            java.lang.reflect.Method m = am.getClass().getMethod("setProcessMemoryTrimLevel", String.class, int.class, int.class);
            List<android.app.ActivityManager.RunningAppProcessInfo> procs = ((android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getRunningAppProcesses();
            if (procs == null) return 0;
            for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.uid < Process.FIRST_APPLICATION_UID || p.processName == null || p.processName.startsWith(c.getPackageName())) continue;
                if (front != null && p.pkgList != null && java.util.Arrays.asList(p.pkgList).contains(front)) continue;
                if (p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) continue;
                if (isStoppedProcess(p.pid)) continue;
                try { if ((Boolean) m.invoke(am, p.processName, 0, 60)) n++; } catch (Throwable ignored) { }
            }
        } catch (Throwable t) { Log.w("VeraHome", "optimize trim: " + t); }
        return n;
    }
}
