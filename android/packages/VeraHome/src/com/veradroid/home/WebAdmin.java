// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.graphics.Bitmap;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The device-management part of the transfer web page: overview, file manager, apps, processes, SSH, logs, system.
 * Transfer.route() has already checked the PIN session (and the CSRF token for every POST) before calling handle().
 * Nothing here runs a shell; host-side work goes through HostReq (whitelisted verbs, see boot/lib/webadmin.sh) or
 * the existing fixed FIFO commands.
 */
final class WebAdmin {
    static final String SD = "/data/media/0";
    private final Transfer t;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final WebFiles files;

    WebAdmin(Transfer t, Context c) { this.t = t; this.ctx = c; this.files = new WebFiles(t, c); }

    /** Returns false when the path is not ours (Transfer answers 404). */
    boolean handle(Transfer.Req r, InputStream in, OutputStream out) throws Exception {
        String p = r.path;
        boolean post = "POST".equals(r.method);
        if (!post && files.handleGet(r, out)) return true;
        if (post && files.handlePost(r, in, out)) return true;
        if (!post) {
            if (p.equals("/api/overview")) { Transfer.json(out, 200, overview()); return true; }
            if (p.equals("/api/fs/list")) { Transfer.json(out, 200, fsList(r.q.get("root"), r.q.get("path"))); return true; }
            if (p.equals("/api/fs/du")) { Transfer.json(out, 200, fsDu(r.q.get("root"), r.q.get("path"))); return true; }
            if (p.equals("/api/fs/get")) { fsGet(r.q.get("root"), r.q.get("path"), out); return true; }
            if (p.equals("/api/apps2")) { Transfer.json(out, 200, apps("1".equals(r.q.get("system")))); return true; }
            if (p.equals("/api/procs")) { Transfer.json(out, 200, procs()); return true; }
            if (p.equals("/api/ssh")) { Transfer.json(out, 200, ssh(HostReq.run(ctx, "sshstate", null, null, 30000))); return true; }
            if (p.equals("/api/adb")) { Transfer.json(out, 200, adb(HostReq.run(ctx, "adbstate", null, null, 30000))); return true; }
            if (p.equals("/api/log")) { Transfer.json(out, 200, log(r.q.get("n"), r.q.get("which"))); return true; }
            if (p.equals("/api/screenshot")) { screenshot(out); return true; }
            if (p.equals("/api/sys/settings")) { Transfer.json(out, 200, sysSettings()); return true; }
            if (p.equals("/api/sys/storage")) { Transfer.json(out, 200, kvJson(HostReq.run(ctx, "storage", null, null, 20000))); return true; }
            if (p.equals("/api/sys/update")) { Transfer.json(out, 200, updateInfo()); return true; }
            if (p.equals("/api/bt")) { Transfer.json(out, 200, btState("1".equals(r.q.get("refresh")))); return true; }
            if (p.equals("/api/diag/result")) { Transfer.json(out, 200, diagResult()); return true; }
            if (p.equals("/api/du")) { Transfer.json(out, 200, kvJson(HostReq.run(ctx, "du", null, null, 180000))); return true; }
            return false;
        }
        // 只读自己的接口的请求体：上传（/api/upload）等老接口的请求体要原样留给 Transfer
        if (!(p.startsWith("/api/fs/") || p.startsWith("/api/app/") || p.startsWith("/api/ssh/") || p.startsWith("/api/adb/") || p.startsWith("/api/sys/") || p.startsWith("/api/bt/") || p.startsWith("/api/diag/") || p.equals("/api/theme/icon/clear"))) return false;
        JSONObject b;
        try { b = new JSONObject(new String(t.body(r, in, 16384), "UTF-8")); }
        catch (Exception e) { b = new JSONObject(); }
        if (p.equals("/api/fs/mkdir")) { Transfer.json(out, 200, fsMkdir(b.optString("root"), b.optString("path"), b.optString("name"))); return true; }
        if (p.equals("/api/fs/rename")) { Transfer.json(out, 200, fsRename(b.optString("root"), b.optString("path"), b.optString("name"), b.optString("to"))); return true; }
        if (p.equals("/api/fs/delete")) { Transfer.json(out, 200, fsDelete(b.optString("root"), b.optString("path"), b.optJSONArray("names"))); return true; }
        if (p.equals("/api/app/stop")) { Transfer.json(out, 200, forceStop(b.optString("pkg"))); return true; }
        if (p.equals("/api/app/clearcache")) { Transfer.json(out, 200, clearCache(b.optString("pkg"))); return true; }
        if (p.equals("/api/theme/icon/clear")) {
            PackageInfo pi = installed(b.optString("pkg"));
            if (pi == null) { Transfer.json(out, 200, Transfer.obj("ok", false, "msg", "没有这个应用")); return true; }
            Theme.clearIcon(pi.packageName);
            Transfer.json(out, 200, Transfer.obj("ok", true));
            return true;
        }
        if (p.equals("/api/app/display")) { Transfer.json(out, 200, appDisplay(b.optString("pkg"))); return true; }
        if (p.equals("/api/ssh/start")) {
            toast("管理网页正在打开 SSH（端口 2222）");
            Transfer.json(out, 200, ssh(HostReq.run(ctx, "sshstart", null, null, 150000)));
            return true;
        }
        if (p.equals("/api/ssh/stop")) { Transfer.json(out, 200, ssh(HostReq.run(ctx, "sshstop", null, null, 30000))); return true; }
        if (p.equals("/api/ssh/key")) { Transfer.json(out, 200, sshKey(b.optString("key"), b.optString("pin"))); return true; }
        if (p.equals("/api/ssh/keydel")) {
            int n = b.optInt("n", 0);
            if (n < 1 || n > 100) { Transfer.json(out, 200, Transfer.obj("ok", false, "msg", "编号不对")); return true; }
            Transfer.json(out, 200, ssh(HostReq.run(ctx, "sshkeydel", String.valueOf(n), null, 30000)));
            return true;
        }
        if (p.equals("/api/adb/start")) {
            // 开无线调试 = 让有公钥的电脑拿到 Android 的 shell（userdebug 镜像上 adb root 还能拿到 root）：和加公钥一样再输一次 PIN
            int v = t.checkPin(b.optString("pin"));
            if (v != 0) { Transfer.json(out, 200, Transfer.obj("ok", false, "msg", v == 2 ? "输错次数太多，请 1 分钟后再试" : "PIN 不对")); return true; }
            toast("管理网页打开了无线调试（adb，端口 5555）");
            Transfer.json(out, 200, adb(HostReq.run(ctx, "adbstart", String.valueOf(AdbWifi.minutes(b.optInt("min", 30))), null, 30000)));
            return true;
        }
        if (p.equals("/api/adb/stop")) { Transfer.json(out, 200, adb(HostReq.run(ctx, "adbstop", null, null, 30000))); return true; }
        if (p.equals("/api/adb/key")) { Transfer.json(out, 200, adbKey(b.optString("key"), b.optString("pin"))); return true; }
        if (p.equals("/api/adb/keydel")) {
            int n = b.optInt("n", 0);
            if (n < 1 || n > 100) { Transfer.json(out, 200, Transfer.obj("ok", false, "msg", "编号不对")); return true; }
            Transfer.json(out, 200, adb(HostReq.run(ctx, "adbkeydel", String.valueOf(n), null, 30000)));
            return true;
        }
        if (p.equals("/api/diag/upload")) {
            HostLink.cmd("diag-upload");
            Transfer.json(out, 200, Transfer.obj("ok", true, "since", System.currentTimeMillis()));
            return true;
        }
        if (p.equals("/api/sys/display")) { Transfer.json(out, 200, setDisplay(b.optString("key"), b.optString("value"))); return true; }
        if (p.equals("/api/sys/light")) { Transfer.json(out, 200, setLight(b.optString("what"), b.optInt("value", -1))); return true; }
        if (p.equals("/api/sys/autoupdate")) { Transfer.json(out, 200, setAutoUpdate(b.optString("which"), b.optBoolean("on", true))); return true; }
        // 存储空间（宿主 boot/lib/storage.sh，docs/host-settings.md “存储空间”）。在线扩大要等它做完（FAT 上要写满新增的空间）；
        // 离线调整（缩小 / 不能在线扩）宿主先答复 mode=offline，随后 Android 被关掉、调整完自动重新启动。
        if (p.equals("/api/sys/storage/resize")) {
            int mb = b.optInt("mb", -1);
            if (mb < 256 || mb > 65536) { Transfer.json(out, 200, Transfer.obj("ok", false, "error", "nan")); return true; }
            Transfer.json(out, 200, kvJson(HostReq.run(ctx, "storageresize", String.valueOf(mb), null, 900000)));
            return true;
        }
        if (p.equals("/api/sys/storage/auto")) { Transfer.json(out, 200, kvJson(HostReq.run(ctx, "storageauto", b.optBoolean("on", true) ? "1" : "0", null, 20000))); return true; }
        if (p.equals("/api/bt/cmd")) { Transfer.json(out, 200, btCmd(b.optString("op"), b.optString("mac"))); return true; }
        if (p.equals("/api/bt/set")) { Transfer.json(out, 200, btSet(b.optString("key"), b.optString("value"))); return true; }
        if (p.equals("/api/sys/clipboard")) { Transfer.json(out, 200, clipboard(b.optString("text"))); return true; }
        if (p.equals("/api/sys/refresh")) { HostLink.cmd("clear"); Transfer.json(out, 200, Transfer.obj("ok", true)); return true; }
        if (p.equals("/api/sys/restart") || p.equals("/api/sys/exit")) {
            final String cmd = p.endsWith("restart") ? "restart" : "exit";
            Transfer.json(out, 200, Transfer.obj("ok", true));
            main.postDelayed(new Runnable() { @Override public void run() { HostLink.cmd(cmd); } }, 1200);   // answer first
            return true;
        }
        return false;
    }

    private void toast(final String s) {
        main.post(new Runnable() { @Override public void run() { try { VeraToast.show(ctx, s); } catch (Throwable ignored) { } } });
    }

    // ================================================================== bluetooth

    // 和 BluetoothActivity 一样经命令 FIFO 发 "bt …" 给宿主（host/*/boot/lib/bt.sh），读 ctl/bt.list、ctl/bt.result、ctl/status。
    // 这些命令没有 root 级后果（只是 Kindle 的蓝牙配对 / 按键映射），所以不走 HostReq；
    // 宿主自己也会校验参数。这里再严格校验一次：操作白名单、MAC 必须是大写的 AA:BB:CC:DD:EE:FF。
    private static final java.util.regex.Pattern BT_MAC = java.util.regex.Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");

    /** 宿主状态 + 设备列表 + 最近一次操作结果。refresh=1 时先让宿主重写 status 和列表（页面刚打开时用，轮询时不用）。 */
    private JSONObject btState(boolean refresh) throws Exception {
        if (refresh) { HostLink.cmd("status"); HostLink.cmd("bt list"); try { Thread.sleep(700); } catch (InterruptedException ignored) { } }
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        JSONObject o = Transfer.obj("ok", true);
        String sup = st.get("btsupported");
        o.put("supported", sup == null ? "old" : "1".equals(sup) ? "yes" : "no");   // old = 宿主太旧没有这个键
        o.put("on", "1".equals(st.get("bton")));
        o.put("bridgeRunning", "1".equals(st.get("bt")));
        o.put("bridge", st.get("btbridge") == null ? "" : st.get("btbridge"));
        o.put("map", st.get("btmap") == null ? "volume" : st.get("btmap"));
        o.put("swap", "1".equals(st.get("btswap")));
        String dev = st.get("btdev");
        org.json.JSONArray used = new org.json.JSONArray();
        if (dev != null && dev.length() > 0) for (String d : dev.split(",")) {
            int c = d.indexOf(':');
            String n = c >= 0 ? d.substring(c + 1) : d;
            used.put(n.equals("kindle-input-pt") ? "Kindle 翻页器" : n);
        }
        o.put("devUsed", used);
        org.json.JSONArray devs = new org.json.JSONArray();
        for (String l : readText(HostLink.CTL + "bt.list", 262144).split("\n")) {
            String[] f = l.split("\t", -1);
            if (f.length < 4 || !BT_MAC.matcher(f[1]).matches()) continue;
            devs.put(Transfer.obj("name", f[0], "mac", f[1], "paired", "1".equals(f[2]), "connected", "1".equals(f[3])));
        }
        o.put("devices", devs);
        File rf = new File(HostLink.CTL + "bt.result");
        Map<String, String> r = HostLink.kv(rf.getPath());
        JSONObject res = new JSONObject();
        for (Map.Entry<String, String> e : r.entrySet()) res.put(e.getKey(), e.getValue());
        res.put("mtime", rf.lastModified());
        o.put("result", res);
        o.put("now", System.currentTimeMillis());
        return o;
    }

    private JSONObject btCmd(String op, String mac) throws Exception {
        boolean needMac = op.equals("bond") || op.equals("connect") || op.equals("disconnect") || op.equals("unbond");
        if (!(needMac || op.equals("scan") || op.equals("list") || op.equals("enable") || op.equals("disable")))
            return Transfer.obj("ok", false, "msg", "不支持的操作");
        if (needMac && !BT_MAC.matcher(mac).matches()) return Transfer.obj("ok", false, "msg", "设备地址不对");
        long since = System.currentTimeMillis();
        HostLink.cmd("bt " + op + (needMac ? " " + mac : ""));
        return Transfer.obj("ok", true, "since", since);
    }

    private JSONObject btSet(String key, String value) throws Exception {
        boolean good = key.equals("map") ? (value.equals("volume") || value.equals("page") || value.equals("dpad") || value.equals("raw"))
            : key.equals("swap") ? (value.equals("0") || value.equals("1"))
            : key.equals("bridge") && (value.equals("auto") || value.equals("0") || value.equals("1"));
        if (!good) return Transfer.obj("ok", false, "msg", "设置不对");
        HostLink.cmd("bt " + key + " " + value);
        return Transfer.obj("ok", true);
    }

    // ================================================================== overview

    private JSONObject overview() throws Exception {
        HostLink.cmd("status");
        long[] c0 = cpuTimes();
        JSONObject o = Transfer.obj("ok", true);
        byte[] host = sysinfo();   // also gives status_write time to finish
        long[] c1 = cpuTimes();
        if (c0 != null && c1 != null && c1[0] > c0[0]) o.put("cpu", Math.round((1000.0 * (c1[1] - c0[1])) / (c1[0] - c0[0])) / 10.0);
        boolean fresh = host != null;
        if (host == null && lastHost != null) { host = lastHost; o.put("hostAge", (System.currentTimeMillis() - lastHostAt) / 1000); }
        if (!fresh) o.put("hostBusy", true);   // 网页过几秒自己再要一次
        Map<String, String> h = HostReq.kv(host);
        o.put("host", host != null);
        JSONObject hj = new JSONObject();
        for (Map.Entry<String, String> e : h.entrySet()) if (!e.getKey().startsWith("hp") && !e.getKey().startsWith("hs") && !e.getKey().startsWith("ssh.")) hj.put(e.getKey(), e.getValue());
        o.put("hostinfo", hj);
        o.put("sshRunning", "1".equals(h.get("ssh.running")));
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        o.put("status", new JSONObject(st));
        o.put("android", android.os.Build.VERSION.RELEASE);
        o.put("abi", android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "");
        try { PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0); o.put("home", pi.versionName + " (" + pi.versionCode + ")"); } catch (Exception ignored) { }
        Map<String, Long> mi = meminfo();
        JSONObject m = new JSONObject();
        for (Map.Entry<String, Long> e : mi.entrySet()) m.put(e.getKey(), e.getValue());
        o.put("mem", m);
        o.put("load", firstLine("/proc/loadavg"));
        o.put("ncpu", Runtime.getRuntime().availableProcessors());
        String up = firstLine("/proc/uptime");
        if (up != null) try { o.put("uptime", (long) Double.parseDouble(up.split(" ")[0])); } catch (Exception ignored) { }
        o.put("data", statfs("/data"));
        o.put("kindleLib", statfs(Transfer.KINDLE));
        o.put("wifiList", readText(HostLink.CTL + "wifi.list", 4000));
        HostLink.cmd("wifilist");   // refresh for next time (takes a moment on the host)
        return o;
    }

    // 刚开机时 CPU 被占满，宿主的 sysinfo 要二十多秒：以前概览页整整等 20 秒，最后只显示“宿主没有回应”。
    // 现在问宿主放到后台线程里（最多等 90 秒，同一时间只问一次），页面最多等 6 秒；没等到就先用上一次的结果，网页随后自动重试。
    private static volatile byte[] lastHost;
    private static volatile long lastHostAt;
    private static Thread hostFetch;

    private byte[] sysinfo() {
        Thread th;
        synchronized (WebAdmin.class) {
            if (hostFetch == null || !hostFetch.isAlive()) {
                final Context c = ctx;
                hostFetch = new Thread(new Runnable() {
                    @Override public void run() {
                        byte[] b = HostReq.run(c, "sysinfo", null, null, 90000);
                        if (b != null) { lastHost = b; lastHostAt = System.currentTimeMillis(); }
                    }
                }, "wa-sysinfo");
                hostFetch.start();
            }
            th = hostFetch;
        }
        long t0 = System.currentTimeMillis();
        try { th.join(6000); } catch (InterruptedException ignored) { }
        return !th.isAlive() && lastHostAt >= t0 ? lastHost : null;
    }

    /** 进程 / 设置 / 更新页也要宿主信息：同样最多等 6 秒，等不到用上一次的。 */
    private byte[] sysinfoOrLast() { byte[] b = sysinfo(); return b != null ? b : lastHost; }

    private static JSONObject statfs(String p) {
        try {
            StatFs s = new StatFs(p);
            return Transfer.obj("total", s.getTotalBytes(), "free", s.getAvailableBytes());
        } catch (Throwable e) { return new JSONObject(); }
    }

    static Map<String, Long> meminfo() {
        Map<String, Long> m = new HashMap<String, Long>();
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    int c = line.indexOf(':');
                    if (c <= 0) continue;
                    String k = line.substring(0, c);
                    if (!k.equals("MemTotal") && !k.equals("MemFree") && !k.equals("MemAvailable") && !k.equals("Cached") && !k.equals("Buffers")
                        && !k.equals("SwapTotal") && !k.equals("SwapFree")) continue;
                    String v = line.substring(c + 1).trim().split(" ")[0];
                    try { m.put(k, Long.parseLong(v)); } catch (Exception ignored) { }
                }
            } finally { r.close(); }
        } catch (Exception ignored) { }
        // 3.0 kernels have no MemAvailable
        if (!m.containsKey("MemAvailable") && m.containsKey("MemFree")) {
            long a = m.get("MemFree") + (m.containsKey("Cached") ? m.get("Cached") : 0) + (m.containsKey("Buffers") ? m.get("Buffers") : 0);
            m.put("MemAvailable", a);
        }
        return m;
    }

    /** {total jiffies, busy jiffies} from the first line of /proc/stat. */
    private static long[] cpuTimes() {
        String l = firstLine("/proc/stat");
        if (l == null || !l.startsWith("cpu ")) return null;
        String[] f = l.trim().split(" +");
        long total = 0, idle = 0;
        for (int i = 1; i < f.length; i++) {
            try { long v = Long.parseLong(f[i]); total += v; if (i == 4 || i == 5) idle += v; } catch (Exception ignored) { }
        }
        return new long[] { total, total - idle };
    }

    static String firstLine(String p) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(p));
            try { return r.readLine(); } finally { r.close(); }
        } catch (Exception e) { return null; }
    }

    static String readText(String p, int max) {
        try {
            byte[] b = HostReq.read(new File(p), max);
            return new String(b, 0, Math.min(b.length, max), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    private static JSONObject kvJson(byte[] raw) throws Exception {
        if (raw == null) return Transfer.obj("ok", false, "msg", "宿主没有回应（VeraDroid 宿主程序是不是旧版本？）");
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : HostReq.kv(raw).entrySet()) o.put(e.getKey(), e.getValue());
        if (!o.has("ok")) o.put("ok", !o.has("error"));
        else o.put("ok", "1".equals(o.optString("ok")));
        return o;
    }

    // ================================================================== files

    /** Root of a file area, canonical. "kindle" = the Kindle's documents folder (bind-mounted into Android). */
    static File rootDir(String which) {
        try { return new File("kindle".equals(which) ? Transfer.KINDLE : SD).getCanonicalFile(); }
        catch (IOException e) { return new File("kindle".equals(which) ? Transfer.KINDLE : SD); }
    }

    /**
     * Resolve "a/b/c" under the root. No "..", no hidden segments, no symlinks anywhere on the way: the canonical path
     * has to be exactly root + the given segments, so a link (even one pointing inside the root) is refused.
     */
    static File resolve(String which, String rel) {
        File root = rootDir(which);
        if (rel == null) rel = "";
        if (rel.length() > 1024) return null;
        StringBuilder sb = new StringBuilder();
        for (String seg : rel.split("/")) {
            if (seg.length() == 0) continue;
            if (seg.equals(".") || seg.equals("..") || seg.startsWith(".") || seg.indexOf('\\') >= 0 || seg.indexOf('\0') >= 0) return null;
            for (int i = 0; i < seg.length(); i++) if (seg.charAt(i) < 0x20 || seg.charAt(i) == 0x7f) return null;
            sb.append('/').append(seg);
        }
        File f = new File(root.getPath() + sb);
        try {
            String c = f.getCanonicalPath();
            if (!c.equals(f.getPath())) return null;
        } catch (IOException e) { return null; }
        return f;
    }

    static String relOf(String which, File f) {
        String r = rootDir(which).getPath(), p = f.getPath();
        return p.length() <= r.length() ? "" : p.substring(r.length() + 1);
    }

    static boolean isLink(File f) {
        try {
            File parent = f.getParentFile();
            if (parent == null) return false;
            return !f.getCanonicalPath().equals(new File(parent.getCanonicalFile(), f.getName()).getPath());
        } catch (IOException e) { return true; }
    }

    /** Bind-mounted Kindle folders inside Android storage: skip in zip/du to avoid double-counting or packing the whole Kindle library. */
    static boolean isKindleBind(File f) {
        String p = f.getPath();
        return (SD + "/KindleLibrary").equals(p) || (SD + "/KindleFonts").equals(p);
    }

    /** Names the page may never create, change or remove anywhere: scripts, dotfiles, SSH material. */
    static boolean isProtected(String name) {
        if (Transfer.isScript(name)) return true;
        String n = name.toLowerCase(Locale.US).trim();
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1).trim();
        return n.startsWith("my-public-keys") || n.equals("authorized_keys") || n.startsWith("dropbear") || n.contains("host_key")
            || n.equals("id_rsa") || n.equals("id_ed25519") || n.equals("id_ecdsa");
    }

    private static boolean hasScriptChild(File dir) {
        String[] names = dir.list();
        if (names == null) return true;   // 读不了的文件夹不知道里面有什么：按“有脚本”处理（只读），不能放行
        for (String n : names) if (Transfer.isScript(n) && !n.startsWith(".")) return true;
        return false;
    }

    /**
     * Folders whose content the page must not change. KindleFonts is the Kindle's own system font folder. In the Kindle
     * library a folder holding a scriptlet (.sh) is read-only: scriptlets run as root and may use the files next to
     * them (the SSH diagnostics package runs the dropbear beside it) -- except the library root itself, where the books
     * are (our own scriptlets there use nothing from that folder).
     */
    static String readOnlyWhy(String which, File dir) {
        String rel = relOf(which, dir);
        if (!"kindle".equals(which)) {
            if (rel.equals("KindleFonts") || rel.startsWith("KindleFonts/")) return "Kindle 自带字体，只读";
            if (rel.equals("KindleLibrary") || rel.startsWith("KindleLibrary/")) return "Kindle 书库请在“Kindle 书库”里管理";
            return null;
        }
        if ("ro".equals(HostLink.kv(HostLink.KFB + "settings").get("KLIB_BIND"))) return "Kindle 书库当前为只读安全挂载（可在设置 → 设备与更新里改为读写）";
        if (rel.length() > 0 && hasScriptChild(dir)) return dir.list() == null ? "这个文件夹 Android 这边读不了，网页里只读" : "这个文件夹里有 Kindle 脚本，网页里只读";
        return null;
    }

    /** A new name the user typed: one path segment, nothing hidden or executable. */
    static String checkName(String n) {
        if (n == null) return null;
        n = n.trim();
        if (n.length() == 0 || n.length() > 200 || n.equals(".") || n.equals("..")) return null;
        if (n.startsWith(".") || n.endsWith(".") || n.endsWith(" ")) return null;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0) return null;
        }
        if (isProtected(n)) return null;
        return n;
    }

    private JSONObject fsList(String which, String path) throws Exception {
        File d = resolve(which, path);
        if (d == null || !d.isDirectory()) return Transfer.obj("ok", false, "msg", "文件夹不存在");
        File[] fs = d.listFiles();
        if (fs == null) fs = new File[0];
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File x, File y) {
                boolean dx = x.isDirectory(), dy = y.isDirectory();
                if (dx != dy) return dx ? -1 : 1;
                return x.getName().compareToIgnoreCase(y.getName());
            }
        });
        String ro = readOnlyWhy(which, d);
        JSONArray a = new JSONArray();
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            boolean link = isLink(f), dir = !link && f.isDirectory();
            JSONObject e = Transfer.obj("name", n, "dir", dir, "size", dir || link ? -1 : f.length(), "time", f.lastModified());
            if (link) e.put("link", true);
            if (ro != null || link || isProtected(n) || (dir && readOnlyWhy(which, f) != null)) e.put("locked", true);
            if (!"kindle".equals(which) && d.equals(rootDir(which)) && (n.equals("KindleLibrary") || n.equals("KindleFonts"))) e.put("locked", true);
            a.put(e);
        }
        JSONObject o = Transfer.obj("ok", true, "root", "kindle".equals(which) ? "kindle" : "sdcard", "path", relOf(which, d), "items", a, "free", d.getUsableSpace());
        if (ro != null) o.put("readonly", ro);
        return o;
    }

    /** Folder sizes on demand ("查占用"): every child of the folder, without following links. Bounded in time. */
    private JSONObject fsDu(String which, String path) throws Exception {
        File d = resolve(which, path);
        if (d == null || !d.isDirectory()) return Transfer.obj("ok", false, "msg", "文件夹不存在");
        long[] budget = { System.currentTimeMillis() + 40000, 400000 };
        JSONObject sizes = new JSONObject();
        long total = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (isKindleBind(f)) continue;
            long s = isLink(f) ? 0 : f.isDirectory() ? du(f, budget, 0) : f.length();
            total += s;
            if (f.isDirectory() && !f.getName().startsWith(".")) sizes.put(f.getName(), s);
        }
        return Transfer.obj("ok", true, "sizes", sizes, "total", total, "partial", budget[1] <= 0 || System.currentTimeMillis() > budget[0]);
    }

    private static long du(File d, long[] budget, int depth) {
        if (depth > 64 || budget[1] <= 0 || System.currentTimeMillis() > budget[0]) return 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        long s = 0;
        for (File f : fs) {
            if (--budget[1] <= 0) break;
            if (isLink(f) || isKindleBind(f)) continue;
            s += f.isDirectory() ? du(f, budget, depth + 1) : f.length();
        }
        return s;
    }

    private void fsGet(String which, String path, OutputStream out) throws Exception {
        File f = resolve(which, path);
        if (f == null || !f.isFile() || isProtected(f.getName())) { Transfer.send(out, 404, "text/plain; charset=utf-8", "没有这个文件".getBytes("UTF-8"), null); return; }
        long len = f.length();
        String n = f.getName();
        String ascii = n.replaceAll("[^A-Za-z0-9._ ()-]", "_");
        // 一律当附件下载、不让浏览器猜类型：上传的 .html / .svg 不能在这个网页的源里运行
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + len
            + "\r\nContent-Disposition: attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + URLEncoder.encode(n, "UTF-8").replace("+", "%20")
            + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: sandbox\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[65536]; int k; long left = len;
            while (left > 0 && (k = in.read(b, 0, (int) Math.min(b.length, left))) > 0) { out.write(b, 0, k); left -= k; }
        } finally { in.close(); }
    }

    private JSONObject fsMkdir(String which, String path, String name) throws Exception {
        File d = resolve(which, path);
        if (d == null || !d.isDirectory()) return Transfer.obj("ok", false, "msg", "文件夹不存在");
        String ro = readOnlyWhy(which, d);
        if (ro != null) return Transfer.obj("ok", false, "msg", ro);
        String n = checkName(name);
        if (n == null) return Transfer.obj("ok", false, "msg", "名字不能用（不能以点开头、不能含 / \\ : * ? \" < > |，也不能是脚本）");
        File f = new File(d, n);
        if (f.exists()) return Transfer.obj("ok", false, "msg", "已经有同名的文件或文件夹");
        if (!f.mkdir()) return Transfer.obj("ok", false, "msg", "建不了文件夹");
        f.setReadable(true, false); f.setWritable(true, false); f.setExecutable(true, false);
        return Transfer.obj("ok", true);
    }

    private JSONObject fsRename(String which, String path, String name, String to) throws Exception {
        File d = resolve(which, path);
        if (d == null || !d.isDirectory()) return Transfer.obj("ok", false, "msg", "文件夹不存在");
        String ro = readOnlyWhy(which, d);
        if (ro != null) return Transfer.obj("ok", false, "msg", ro);
        if (name == null || name.indexOf('/') >= 0 || name.startsWith(".") || isProtected(name)) return Transfer.obj("ok", false, "msg", "这个文件不能改名");
        File f = resolve(which, (path == null ? "" : path) + "/" + name);
        if (f == null || !f.exists() || isLink(f)) return Transfer.obj("ok", false, "msg", "文件不存在");
        if (f.isDirectory() && (readOnlyWhy(which, f) != null || containsLocked(f, 0, new int[] { 50000 }))) return Transfer.obj("ok", false, "msg", "这个文件夹里有 Kindle 脚本，不能改名");
        if (!"kindle".equals(which) && d.equals(rootDir(which)) && (name.equals("KindleLibrary") || name.equals("KindleFonts") || name.equals("Fonts"))) return Transfer.obj("ok", false, "msg", "这个文件夹不能改名");
        String n = checkName(to);
        if (n == null) return Transfer.obj("ok", false, "msg", "新名字不能用（不能以点开头、不能含 / \\ : * ? \" < > |，也不能是脚本）");
        File g = new File(d, n);
        if (g.exists() && !sameInode(f, g)) return Transfer.obj("ok", false, "msg", "已经有同名的文件或文件夹");
        boolean ok = f.renameTo(g);
        if (ok) {
            if (Lib.isBook(f.getName()) || Lib.isBook(g.getName())) BlocksFiles.migrateReaderPrefs(ctx, f.getAbsolutePath(), g.getAbsolutePath());
            Lib.scan(ctx, 0);
        }
        return Transfer.obj("ok", ok, "msg", "");
    }

    /** True only when a and b refer to the exact same underlying inode (e.g. case-only rename on vfat). */
    private static boolean sameInode(File a, File b) {
        try {
            android.system.StructStat sa = android.system.Os.lstat(a.getPath());
            android.system.StructStat sb = android.system.Os.lstat(b.getPath());
            return sa.st_dev == sb.st_dev && sa.st_ino == sb.st_ino;
        } catch (Throwable e) {
            return false;
        }
    }

    /** True if anything below is a script / protected name, or a folder the page treats as read-only. */
    private boolean containsLocked(File d, int depth, int[] budget) {
        if (depth > 64 || --budget[0] <= 0) return true;
        File[] fs = d.listFiles();
        if (fs == null) return true;   // 读不了：当作受保护
        for (File f : fs) {
            if (isLink(f)) continue;
            if (isProtected(f.getName())) return true;
            if (f.isDirectory() && containsLocked(f, depth + 1, budget)) return true;
        }
        return false;
    }

    private JSONObject fsDelete(String which, String path, JSONArray names) throws Exception {
        File d = resolve(which, path);
        if (d == null || !d.isDirectory()) return Transfer.obj("ok", false, "msg", "文件夹不存在");
        String ro = readOnlyWhy(which, d);
        if (ro != null) return Transfer.obj("ok", false, "msg", ro);
        if (names == null || names.length() == 0 || names.length() > 500) return Transfer.obj("ok", false, "msg", "没有选文件");
        int done = 0;
        JSONArray failed = new JSONArray();
        for (int i = 0; i < names.length(); i++) {
            String n = names.optString(i);
            if (n.length() == 0 || n.indexOf('/') >= 0 || n.startsWith(".") || isProtected(n)) { failed.put(n + "（受保护）"); continue; }
            if (!"kindle".equals(which) && d.equals(rootDir(which)) && (n.equals("KindleLibrary") || n.equals("KindleFonts") || n.equals("Fonts"))) { failed.put(n + "（受保护）"); continue; }
            File f = new File(d, n);
            if (isLink(f)) { if (f.delete()) done++; else failed.put(n); continue; }   // 只删链接本身
            File rf = resolve(which, (path == null ? "" : path) + "/" + n);
            if (rf == null || !rf.exists()) { failed.put(n); continue; }
            if (rf.isDirectory() && containsLocked(rf, 0, new int[] { 200000 })) { failed.put(n + "（里面有脚本或受保护的文件）"); continue; }
            if (deleteTree(ctx, rf, 0)) done++; else failed.put(n);
        }
        if (done > 0) Lib.scan(ctx, 0);
        return Transfer.obj("ok", failed.length() == 0, "deleted", done, "failed", failed, "msg", failed.length() == 0 ? "" : "有些没有删掉");
    }

    private static boolean deleteTree(Context ctx, File f, int depth) {
        if (depth > 64) return false;
        boolean wasDir = !isLink(f) && f.isDirectory();
        if (wasDir) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) if (!deleteTree(ctx, c, depth + 1)) return false;
        }
        if (!f.delete()) return false;
        if (!wasDir && ctx != null) Lib.purgeReaderState(ctx, f.getAbsolutePath());
        return true;
    }

    /** Upload target folder for /api/upload?to=dir&root=..&path=..; null + message if not allowed. */
    static File uploadDir(String which, String path, String[] why) {
        File d = resolve(which, path);
        if (d == null || !d.isDirectory()) { why[0] = "文件夹不存在"; return null; }
        String ro = readOnlyWhy(which, d);
        if (ro != null) { why[0] = ro; return null; }
        return d;
    }

    // ================================================================== apps

    private JSONObject apps(boolean withSystem) throws Exception {
        final PackageManager pm = ctx.getPackageManager();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Set<String> running = new HashSet<String>();
        try {
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps != null) for (ActivityManager.RunningAppProcessInfo p : ps) if (p.pkgList != null) running.addAll(Arrays.asList(p.pkgList));
        } catch (Throwable ignored) { }
        List<PackageInfo> pis = pm.getInstalledPackages(0);
        final List<PackageInfo> shown = new ArrayList<PackageInfo>();
        for (PackageInfo pi : pis) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null) continue;
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (sys && !withSystem) continue;
            shown.add(pi);
        }
        final Map<String, PackageStats> stats = new HashMap<String, PackageStats>();
        final CountDownLatch latch = new CountDownLatch(shown.size());
        for (PackageInfo pi : shown) {
            try {
                pm.getPackageSizeInfo(pi.packageName, new IPackageStatsObserver.Stub() {
                    @Override public void onGetStatsCompleted(PackageStats ps, boolean ok) {
                        if (ok && ps != null) synchronized (stats) { stats.put(ps.packageName, ps); }
                        latch.countDown();
                    }
                });
            } catch (Throwable e) { latch.countDown(); }
        }
        latch.await(8, TimeUnit.SECONDS);
        JSONArray a = new JSONArray();
        for (PackageInfo pi : shown) {
            ApplicationInfo ai = pi.applicationInfo;
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            JSONObject e = Transfer.obj("pkg", pi.packageName, "label", String.valueOf(ai.loadLabel(pm)), "version", pi.versionName == null ? "" : pi.versionName,
                "code", pi.versionCode, "system", sys, "enabled", ai.enabled, "running", running.contains(pi.packageName),
                "launch", pm.getLaunchIntentForPackage(pi.packageName) != null, "installed", pi.firstInstallTime, "updated", pi.lastUpdateTime,
                "self", pi.packageName.equals(ctx.getPackageName()), "persistent", (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0,
                "ownIcon", Theme.hasOwnIcon(pi.packageName));
            PackageStats ps;
            synchronized (stats) { ps = stats.get(pi.packageName); }
            if (ps != null) {
                e.put("apk", ps.codeSize + ps.externalCodeSize);
                e.put("data", ps.dataSize + ps.externalDataSize + ps.externalMediaSize + ps.externalObbSize);
                e.put("cache", ps.cacheSize + ps.externalCacheSize);
            } else if (ai.sourceDir != null) e.put("apk", new File(ai.sourceDir).length());
            a.put(e);
        }
        return Transfer.obj("ok", true, "apps", a);
    }

    private static boolean pkgOk(String pkg) { return pkg != null && pkg.length() < 200 && pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"); }

    private PackageInfo installed(String pkg) {
        if (!pkgOk(pkg)) return null;
        try { return ctx.getPackageManager().getPackageInfo(pkg, 0); } catch (Exception e) { return null; }
    }

    private JSONObject forceStop(String pkg) {
        PackageInfo pi = installed(pkg);
        if (pi == null) return Transfer.obj("ok", false, "msg", "没有这个应用");
        if (pkg.equals(ctx.getPackageName()) || pkg.equals("android") || (pi.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0)
            return Transfer.obj("ok", false, "msg", "系统常驻程序不能停止");
        try {
            ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).forceStopPackage(pkg);
            return Transfer.obj("ok", true);
        } catch (Throwable e) { return Transfer.obj("ok", false, "msg", "停止失败：" + e.getMessage()); }
    }

    private JSONObject clearCache(String pkg) {
        if (installed(pkg) == null) return Transfer.obj("ok", false, "msg", "没有这个应用");
        final CountDownLatch l = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        try {
            ctx.getPackageManager().deleteApplicationCacheFiles(pkg, new IPackageDataObserver.Stub() {
                @Override public void onRemoveCompleted(String p, boolean succeeded) { ok.set(succeeded); l.countDown(); }
            });
            l.await(15, TimeUnit.SECONDS);
        } catch (Throwable e) { return Transfer.obj("ok", false, "msg", "清除失败：" + e.getMessage()); }
        return Transfer.obj("ok", ok.get(), "msg", ok.get() ? "" : "清除失败");
    }

    private JSONObject appDisplay(String pkg) {
        PackageInfo pi = installed(pkg);
        if (pi == null) return Transfer.obj("ok", false, "msg", "没有这个应用");
        final Intent i = new Intent(ctx, AppDisplayActivity.class).putExtra("pkg", pkg)
            .putExtra("label", String.valueOf(pi.applicationInfo.loadLabel(ctx.getPackageManager()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        main.post(new Runnable() { @Override public void run() { try { ctx.startActivity(i); } catch (Throwable ignored) { } } });
        return Transfer.obj("ok", true);
    }

    // ================================================================== processes

    private static final class Proc { int pid, uid; String name, state; long cpu0, cpu1, rss; }

    private JSONObject procs() throws Exception {
        long[] t0 = cpuTimes();
        Map<Integer, Proc> m = new HashMap<Integer, Proc>();
        sampleProcs(m, true);
        Thread.sleep(600);
        long[] t1 = cpuTimes();
        sampleProcs(m, false);
        long dt = t0 != null && t1 != null ? t1[0] - t0[0] : 0;

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Map<Integer, ActivityManager.RunningAppProcessInfo> apps = new HashMap<Integer, ActivityManager.RunningAppProcessInfo>();
        try {
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps != null) for (ActivityManager.RunningAppProcessInfo p : ps) apps.put(p.pid, p);
        } catch (Throwable ignored) { }
        Map<Integer, Integer> pss = new HashMap<Integer, Integer>();
        if (!apps.isEmpty()) {
            int[] pids = new int[apps.size()]; int i = 0;
            for (Integer p : apps.keySet()) pids[i++] = p;
            try {
                Debug.MemoryInfo[] mi = am.getProcessMemoryInfo(pids);
                for (int k = 0; k < pids.length && k < mi.length; k++) pss.put(pids[k], mi[k].getTotalPss());
            } catch (Throwable ignored) { }
        }
        List<Proc> list = new ArrayList<Proc>(m.values());
        sortByRss(list);
        JSONArray a = new JSONArray();
        long page = 4;   // KB
        for (Proc p : list) {
            if (p.rss <= 0 && !apps.containsKey(p.pid)) continue;   // kernel threads
            JSONObject e = Transfer.obj("pid", p.pid, "uid", p.uid, "name", p.name, "state", p.state, "rss", p.rss * page);
            if (dt > 0 && p.cpu1 >= p.cpu0) e.put("cpu", Math.round(1000.0 * (p.cpu1 - p.cpu0) / dt) / 10.0);
            ActivityManager.RunningAppProcessInfo ai = apps.get(p.pid);
            if (ai != null) {
                e.put("app", true);
                e.put("importance", ai.importance);
                if (ai.pkgList != null && ai.pkgList.length > 0) e.put("pkg", ai.pkgList[0]);
                e.put("name", ai.processName);
            }
            if (pss.containsKey(p.pid)) e.put("pss", pss.get(p.pid));
            a.put(e);
        }
        JSONObject o = Transfer.obj("ok", true, "procs", a, "ncpu", Runtime.getRuntime().availableProcessors());
        Map<String, Long> mi = meminfo();
        o.put("memTotal", mi.containsKey("MemTotal") ? mi.get("MemTotal") : 0);
        o.put("memAvail", mi.containsKey("MemAvailable") ? mi.get("MemAvailable") : 0);
        // host side: read-only summary
        byte[] host = sysinfoOrLast();
        JSONArray hp = new JSONArray();
        if (host != null) {
            for (String line : new String(host, "UTF-8").split("\n")) {
                boolean script = line.startsWith("hs=");
                if (!line.startsWith("hp=") && !script) continue;
                String[] f = line.substring(3).split("\\|");
                if (f.length < 4) continue;
                hp.put(script ? Transfer.obj("pid", f[0], "name", f[1], "state", f[2], "rss", f[3], "script", true)
                              : Transfer.obj("pid", f[0], "name", f[1], "state", f[2], "rss", f[3]));
            }
            Map<String, String> h = HostReq.kv(host);
            o.put("frozen", "1".equals(h.get("frozen")));
            o.put("fwpaused", "1".equals(h.get("fwpaused")));
            o.put("mode", h.containsKey("mode") ? h.get("mode") : "");
            o.put("androidup", h.containsKey("androidup") ? h.get("androidup") : "");
        }
        o.put("host", host != null);
        o.put("hostprocs", hp);
        return o;
    }

    private static void sortByRss(List<Proc> l) {
        java.util.Collections.sort(l, new Comparator<Proc>() {
            @Override public int compare(Proc a, Proc b) { return Long.compare(b.rss, a.rss); }
        });
    }

    private static void sampleProcs(Map<Integer, Proc> m, boolean first) {
        String[] ds = new File("/proc").list();
        if (ds == null) return;
        for (String d : ds) {
            if (d.length() == 0 || !Character.isDigit(d.charAt(0))) continue;
            int pid;
            try { pid = Integer.parseInt(d); } catch (Exception e) { continue; }
            String st = firstLine("/proc/" + d + "/stat");
            if (st == null) continue;
            int l = st.indexOf('('), r = st.lastIndexOf(')');
            if (l < 0 || r < l) continue;
            String[] f = st.substring(r + 2).split(" ");
            if (f.length < 22) continue;
            long cpu;
            try { cpu = Long.parseLong(f[11]) + Long.parseLong(f[12]); } catch (Exception e) { continue; }
            Proc p = m.get(pid);
            if (first || p == null) {
                if (!first) continue;
                p = new Proc();
                p.pid = pid;
                p.name = st.substring(l + 1, r);
                String cmd = cmdline(pid);
                if (cmd.length() > 0) p.name = cmd;
                p.cpu0 = cpu;
                m.put(pid, p);
            }
            p.cpu1 = cpu;
            p.state = f[0];
            try { p.rss = Long.parseLong(f[21]); } catch (Exception ignored) { }
            if (first) p.uid = uidOf(pid);
        }
    }

    private static String cmdline(int pid) {
        try {
            byte[] b = HostReq.read(new File("/proc/" + pid + "/cmdline"), 512);
            int n = 0;
            while (n < b.length && b[n] != 0) n++;
            String s = new String(b, 0, n, "UTF-8");
            return s.length() > 120 ? s.substring(0, 120) : s;
        } catch (Exception e) { return ""; }
    }

    private static int uidOf(int pid) {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/status"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("Uid:")) return Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                }
            } finally { r.close(); }
        } catch (Exception ignored) { }
        return -1;
    }

    // ================================================================== SSH

    private static JSONObject ssh(byte[] raw) throws Exception {
        if (raw == null) return Transfer.obj("ok", false, "msg", "宿主没有回应（VeraDroid 宿主程序是不是旧版本？）");
        JSONObject o = Transfer.obj("ok", true);
        JSONArray keys = new JSONArray();
        StringBuilder outLines = new StringBuilder();
        for (String line : new String(raw, "UTF-8").split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1);
            if (k.equals("ssh.key")) {
                String[] f = v.split("\\|", -1);
                if (f.length >= 4) keys.put(Transfer.obj("n", f[0], "type", f[1], "tail", f[2], "comment", f[3]));
            } else if (k.equals("ssh.out")) outLines.append(v).append('\n');
            else if (k.startsWith("ssh.")) o.put(k.substring(4), v);
        }
        o.put("keys", keys);
        if (outLines.length() > 0) o.put("out", outLines.toString());
        if (o.has("error")) { o.put("ok", false); o.put("msg", sshError(o.optString("error"))); }
        return o;
    }

    private static String sshError(String e) {
        if (e.equals("noscript")) return "Kindle 上还没有 SSH 诊断包：把 VeraDroid-SSH 文件夹拷进 Kindle 的 documents（见说明）";
        if (e.equals("badkey")) return "公钥格式不对";
        if (e.equals("toomany")) return "公钥太多了（最多 20 把）";
        if (e.equals("symlink")) return "公钥文件是一个链接，拒绝写入";
        return "失败：" + e;
    }

    private static final String[] KEY_TYPES = { "ssh-ed25519", "ssh-rsa", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" };

    /** Exactly one OpenSSH public key line; returns the normalised line or null. */
    static String checkPublicKey(String s, String[] why) {
        why[0] = "公钥格式不对：要的是电脑上 .pub 文件里那一整行（ssh-ed25519 / ssh-rsa / ecdsa-sha2-… 开头）";
        if (s == null) return null;
        if (s.contains("PRIVATE KEY")) { why[0] = "这是私钥！私钥绝不要拷给别人或传到任何设备上，请改用 .pub 文件里的公钥"; return null; }
        s = s.replace("﻿", "").trim();
        if (s.length() > 8192 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) { why[0] = "一次只能添加一把公钥（一行）"; return null; }
        String[] f = s.split("[ \t]+");
        if (f.length < 2) return null;
        String type = f[0], b64 = f[1];
        boolean known = false;
        for (String k : KEY_TYPES) if (k.equals(type)) known = true;
        if (!known) { why[0] = "不支持的公钥类型（可以用 ssh-ed25519、ssh-rsa、ecdsa-sha2-nistp256/384/521；不接受带 command= 之类选项的行）"; return null; }
        if (!b64.matches("AAAA[A-Za-z0-9+/]+={0,3}") || b64.length() < 60) return null;
        byte[] blob;
        try { blob = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP); } catch (Exception e) { return null; }
        if (blob.length < 4) return null;
        int n = ((blob[0] & 0xff) << 24) | ((blob[1] & 0xff) << 16) | ((blob[2] & 0xff) << 8) | (blob[3] & 0xff);
        if (n <= 0 || n > 64 || 4 + n > blob.length) return null;
        String inner;
        try { inner = new String(blob, 4, n, "US-ASCII"); } catch (Exception e) { return null; }
        if (!inner.equals(type)) return null;
        if (type.equals("ssh-ed25519") && blob.length != 51) return null;
        if (type.equals("ssh-rsa") && blob.length < 270) { why[0] = "RSA 公钥太短（至少 2048 位）"; return null; }
        StringBuilder c = new StringBuilder();
        for (int i = 2; i < f.length; i++) {
            String w = f[i].replaceAll("[^A-Za-z0-9@._+:=-]", "");
            if (w.length() == 0) continue;
            if (c.length() > 0) c.append('_');
            c.append(w);
        }
        String comment = c.length() > 60 ? c.substring(0, 60) : c.toString();
        return type + " " + b64 + (comment.length() > 0 ? " " + comment : "");
    }

    private JSONObject sshKey(String key, String pin) throws Exception {
        // 加公钥 = 让这把钥匙的主人以后能 root 登录 Kindle：再输一次 PIN（和登录共用错误计数 / 锁定）
        int v = t.checkPin(pin);
        if (v == 2) return Transfer.obj("ok", false, "msg", "输错次数太多，请 1 分钟后再试");
        if (v != 0) return Transfer.obj("ok", false, "msg", "PIN 不对");
        String[] why = new String[1];
        String line = checkPublicKey(key, why);
        if (line == null) return Transfer.obj("ok", false, "msg", why[0]);
        JSONObject o = ssh(HostReq.run(ctx, "sshkeyadd", null, line, 15000));
        if (o.optBoolean("ok")) toast("管理网页添加了一把 SSH 公钥");
        return o;
    }

    // ================================================================== 无线调试（adb）

    static JSONObject adb(byte[] raw) throws Exception {
        if (raw == null) return Transfer.obj("ok", false, "msg", "宿主没有回应（VeraDroid 宿主程序是不是旧版本？）");
        JSONObject o = Transfer.obj("ok", true);
        JSONArray keys = new JSONArray();
        for (String line : new String(raw, "UTF-8").split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1);
            if (k.equals("adb.key")) {
                String[] f = v.split("\\|", -1);
                if (f.length >= 3) keys.put(Transfer.obj("n", f[0], "tail", f[1], "comment", f[2]));
            } else if (k.startsWith("adb.")) o.put(k.substring(4), v);
        }
        o.put("keys", keys);
        if (o.has("error")) { o.put("ok", false); o.put("msg", AdbWifi.error(o.optString("error"))); }
        return o;
    }

    private JSONObject adbKey(String key, String pin) throws Exception {
        int v = t.checkPin(pin);
        if (v == 2) return Transfer.obj("ok", false, "msg", "输错次数太多，请 1 分钟后再试");
        if (v != 0) return Transfer.obj("ok", false, "msg", "PIN 不对");
        String[] why = new String[1];
        String line = AdbWifi.checkKey(key, why);
        if (line == null) return Transfer.obj("ok", false, "msg", why[0]);
        JSONObject o = adb(HostReq.run(ctx, "adbkeyadd", null, line, 15000));
        if (o.optBoolean("ok")) toast("管理网页添加了一把 adb 公钥");
        return o;
    }

    // ================================================================== logs, screenshot, diagnostics

    private JSONObject log(String n, String which) throws Exception {
        int lines = 200;
        try { lines = Math.max(1, Math.min(2000, Integer.parseInt(n))); } catch (Exception ignored) { }
        if (which == null || !(which.equals("main") || which.equals("update") || which.equals("ssh") || which.equals("fsck"))) which = "main";
        byte[] raw = HostReq.run(ctx, "logtail", lines + " " + which, null, 30000);
        if (raw == null) return Transfer.obj("ok", false, "msg", "宿主没有回应");
        return Transfer.obj("ok", true, "text", new String(raw, "UTF-8"));
    }

    private static final java.util.concurrent.atomic.AtomicBoolean SHOTTING = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Capture current screen via host wa_shot (/dev/fb0 8-bit gray or 32-bit BGRA, or PNG) and encode to PNG bytes. */
    static byte[] capturePng(Context ctx) {
        byte[] raw = HostReq.run(ctx, "shot", null, null, 20000);
        if (raw == null) return null;
        if (raw.length > 8 && (raw[0] & 0xff) == 0x89 && raw[1] == 'P') return raw;
        if (raw.length > 5 && raw[0] == 'f' && raw[1] == 'b' && (raw[2] == ' ' || (raw[2] == '3' && raw[3] == '2'))) {
            try {
                boolean c32 = raw[2] == '3';
                int nl = 0;
                while (nl < raw.length && nl < 64 && raw[nl] != '\n') nl++;
                if (nl >= raw.length || raw[nl] != '\n') return null;
                String[] h = new String(raw, 0, nl, "US-ASCII").split(" ");
                if (h.length < 4) return null;
                int w = Integer.parseInt(h[1]), hh = Integer.parseInt(h[2]), stride = Integer.parseInt(h[3]);
                int off = nl + 1;
                if (w > 0 && hh > 0 && w * (c32 ? 4 : 1) <= stride && w <= 4096 && hh <= 4096 && off < raw.length) {
                    hh = Math.min(hh, (raw.length - off) / stride);
                    if (hh <= 0) return null;
                    int[] px = new int[w * hh];
                    for (int y = 0; y < hh; y++) {
                        int row = off + y * stride;
                        if (c32) for (int x = 0; x < w; x++) { int q = row + x * 4; px[y * w + x] = 0xff000000 | ((raw[q + 2] & 0xff) << 16) | ((raw[q + 1] & 0xff) << 8) | (raw[q] & 0xff); }
                        else for (int x = 0; x < w; x++) { int g = raw[row + x] & 0xff; px[y * w + x] = 0xff000000 | (g << 16) | (g << 8) | g; }
                    }
                    Bitmap bm = Bitmap.createBitmap(px, w, hh, Bitmap.Config.ARGB_8888);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try { bm.compress(Bitmap.CompressFormat.PNG, 100, bo); } finally { bm.recycle(); }
                    return bo.toByteArray();
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Save screenshot PNG to /data/media/0/Screenshots on a worker thread (fixes 0-byte screencap under KFB_HWC=1). */
    static void saveScreenshot(final Context ctx, final boolean allowHostFallback) {
        final Context app = ctx.getApplicationContext();
        if (!SHOTTING.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] png = capturePng(app);
                    if (png == null || png.length < 32) {
                        if (allowHostFallback) HostLink.cmd("screenshot");
                        else VeraToast.show(app, "截图失败，请稍后重试");
                        return;
                    }
                    File dir = new File("/data/media/0/Screenshots");
                    dir.mkdirs();
                    String name = "screen-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date()) + ".png";
                    File tmp = new File(dir, "." + name + ".tmp");
                    File out = new File(dir, name);
                    FileOutputStream fo = new FileOutputStream(tmp);
                    try {
                        fo.write(png);
                        fo.flush();
                    } finally {
                        fo.close();
                    }
                    tmp.setReadable(true, false);
                    if (!tmp.renameTo(out)) { tmp.delete(); throw new IOException("rename failed"); }
                    out.setReadable(true, false);
                    android.util.Log.i("VeraHome", "screenshot saved: " + out.getAbsolutePath() + " (" + png.length + " bytes)");
                    VeraToast.show(app, "已截图：" + name + "（Screenshots）");
                } catch (Throwable t) {
                    android.util.Log.w("VeraHome", "saveScreenshot: " + t);
                    VeraToast.show(app, "截图失败，请稍后重试");
                } finally {
                    SHOTTING.set(false);
                }
            }
        }, "vera-shot").start();
    }

    private void screenshot(OutputStream out) throws Exception {
        byte[] png = capturePng(ctx);
        if (png == null) { Transfer.send(out, 500, "text/plain; charset=utf-8", "截屏失败".getBytes("UTF-8"), null); return; }
        String name = "kindle-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date()) + ".png";
        Transfer.send(out, 200, "image/png", png, "Content-Disposition: inline; filename=\"" + name + "\"\r\n");
    }

    private JSONObject diagResult() throws Exception {
        File f = new File(HostLink.CTL + "diag.upload.result");
        JSONObject o = Transfer.obj("ok", true, "time", f.isFile() ? f.lastModified() : 0);
        for (Map.Entry<String, String> e : HostLink.kv(f.getPath()).entrySet()) o.put("r_" + e.getKey(), e.getValue());
        return o;
    }

    // ================================================================== system

    private JSONObject sysSettings() throws Exception {
        JSONObject o = Transfer.obj("ok", true);
        o.put("settings", new JSONObject(HostLink.kv(HostLink.KFB + "settings")));
        o.put("status", new JSONObject(HostLink.kv(HostLink.CTL + "status")));
        SharedPreferences p = Store.prefs(ctx, "home");
        o.put("appAutoUpdate", p.getBoolean("autoupdate", true));
        Map<String, String> h = HostReq.kv(sysinfoOrLast());
        if (h.containsKey("autoupdate")) o.put("hostAutoUpdate", "1".equals(h.get("autoupdate")));
        if (h.containsKey("vdversion")) o.put("vdversion", h.get("vdversion"));
        try { o.put("home", ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName); } catch (Exception ignored) { }
        return o;
    }

    private static final Map<String, String[]> DISPLAY = new HashMap<String, String[]>();
    static {
        DISPLAY.put("KFB_SMOOTH", new String[] { "0", "1", "2", "3", "4" });
        DISPLAY.put("KFB_MODE", new String[] { "0", "1", "2", "3" });
        DISPLAY.put("KFB_SCROLLDU", new String[] { "0", "300" });
        DISPLAY.put("KFB_SCROLLWFM", new String[] { "0", "1" });
        DISPLAY.put("KFB_IMG", new String[] { "0", "25" });
        DISPLAY.put("KFB_REAGL_MIN", new String[] { "30", "35", "50", "70", "85" });
        DISPLAY.put("KFB_CONTRAST", new String[] { "1.0", "1.3", "1.5", "1.6", "1.9", "2.0", "2.2", "2.5" });
        DISPLAY.put("KFB_FLASH", new String[] { "0", "1", "3", "6", "8", "10", "20" });
        DISPLAY.put("KFB_ANIM_MODE", new String[] { "0", "1", "2", "3", "4", "5" });
        DISPLAY.put("KFB_INVERT", new String[] { "0", "1" });
        DISPLAY.put("KFB_DITHER", new String[] { "0", "1", "2", "3" });
        DISPLAY.put("KFB_LIGHTF", new String[] { "0", "1", "2", "3" });
        DISPLAY.put("KFB_DARKEN", new String[] { "0", "1" });
    }

    private JSONObject setDisplay(String key, String value) {
        String[] ok = DISPLAY.get(key);
        if (ok == null || !Arrays.asList(ok).contains(value)) return Transfer.obj("ok", false, "msg", "不支持的设置");
        String body = "KFB_MODE".equals(key) ? "KFB_SMOOTH=0\n" + key + "=" + value + "\n" : key + "=" + value + "\n";
        return Transfer.obj("ok", HostLink.settings(body));
    }

    private JSONObject setLight(String what, int v) {
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        if ("light".equals(what)) {
            int max = HostLink.num(st, "lightmax", 24);
            if (v < 0 || v > max) return Transfer.obj("ok", false, "msg", "超出范围");
            HostLink.cmd("light " + v);
        } else if ("warmth".equals(what)) {
            if (!"1".equals(st.get("haswarmth")) || v < 0 || v > 24) return Transfer.obj("ok", false, "msg", "这台机器不能调色温");
            HostLink.cmd("warmth " + v);
        } else return Transfer.obj("ok", false, "msg", "不支持");
        HostLink.cmd("status");
        return Transfer.obj("ok", true);
    }

    private JSONObject setAutoUpdate(String which, boolean on) throws Exception {
        if ("app".equals(which)) {
            Store.prefs(ctx, "home").edit().putBoolean("autoupdate", on).commit();
            return Transfer.obj("ok", true);
        }
        if ("host".equals(which)) return kvJson(HostReq.run(ctx, "autoupdate", on ? "1" : "0", null, 30000));
        return Transfer.obj("ok", false, "msg", "不支持");
    }

    private JSONObject clipboard(final String text) {
        if (text == null || text.length() == 0 || text.length() > 20000) return Transfer.obj("ok", false, "msg", "文字为空或太长（最多 2 万字）");
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    ((ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("VeraDroid", text));
                    VeraToast.show(ctx, "电脑发来的文字已放进剪贴板");
                } catch (Throwable ignored) { }
            }
        });
        return Transfer.obj("ok", true);
    }

    /** Read-only: what the official server offers (whole package and the home app). Installs nothing. */
    private JSONObject updateInfo() throws Exception {
        JSONObject o = Transfer.obj("ok", true);
        String base = "https://veradroid.wenjin.jp";
        String latest = SysUpdate.latestVersion(ctx);
        if (latest != null && latest.length() > 0) o.put("latest", latest);
        else o.put("latestError", "连不上更新服务器");
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            o.put("home", pi.versionName);
            JSONObject j = new JSONObject(httpGet(base + "/api/latest?package=com.veradroid.home"));
            int latestCode = j.optInt("version_code", 0);
            o.put("homeUpdate", latestCode > pi.versionCode);
            if (j.has("version_name")) o.put("homeLatest", j.optString("version_name"));
        } catch (Throwable e) { o.put("homeError", "连不上更新服务器"); }
        Map<String, String> h = HostReq.kv(sysinfoOrLast());
        if (h.containsKey("vdversion")) o.put("current", h.get("vdversion"));
        return o;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000); h.setReadTimeout(10000);
        h.setRequestProperty("User-Agent", "VeraDroid/WebAdmin");
        InputStream in = null;
        try {
            in = h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0 && bo.size() < 262144) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
        }
    }
}
