// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wireless file transfer: a small web server inside the system. A phone or computer on the same Wi-Fi opens
 * http://<kindle-ip>:8080, types the PIN shown on the Kindle, and can
 *   - upload books (they land in Books/ on the Android storage or in the Kindle library) and APK files (installed at once),
 *   - see / delete the files, see / open / uninstall the installed apps,
 *   - paste a direct http(s) link to have the Kindle download the file itself.
 * It is off until you switch it on (settings -> transfer, or the panel), protected by a PIN, and switches itself off
 * after a while without use. The port is opened to the Wi-Fi by the host scripts (slirp4netns port forward) only
 * while the server is on.
 */
final class Transfer {
    static final int PORT = 8080;
    /** 8080 被别的程序占着时依次试这几个（共用 Kindle 网络的老机型上，Kindle 自己的程序也在同一个网络里）。 */
    static final int[] PORTS = { PORT, 8081, 8090 };
    static final String BOOKS = "/data/media/0/Books";
    static final String KINDLE = "/data/media/0/KindleLibrary";
    static final String TMP = "/data/media/0/.upload";   // staging for apk / downloads (/data/local/tmp is not writable for the system uid)

    /** Our own staging folder: always writable for us, whatever happened to the shared storage. The host installs as root and can read it. */
    static final String TMP_OWN = "/data/data/com.veradroid.home/files/upload";

    /** Can we really create a file here? File.canWrite() only looks at the mode bits of the folder itself; a parent we
     * cannot enter, a folder left behind by an older install with another owner, or an encrypted / read-only mount all
     * pass that check and then fail with "Permission denied" on the first write (user report: KT6, firmware 5.17). */
    static boolean writable(File d) {
        if (!d.isDirectory()) return false;
        File probe = new File(d, ".w" + android.os.Process.myPid() + "-" + System.nanoTime());
        try {
            new FileOutputStream(probe).close();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            probe.delete();
        }
    }

    /** Where apk uploads / downloads are staged: the shared folder the host prepares, or - when that one is not
     * writable for us - a folder inside our own data directory. Never fails because of the shared storage. */
    static File tmpDir() {
        File a = new File(TMP);
        if (!a.isDirectory() && a.mkdirs()) { a.setReadable(true, false); a.setWritable(true, false); a.setExecutable(true, false); }
        if (writable(a)) return a;
        File b = new File(TMP_OWN);
        if (!b.isDirectory()) b.mkdirs();
        b.setReadable(true, false); b.setExecutable(true, false);
        File files = b.getParentFile();
        if (files != null) files.setExecutable(true, false);
        return b;
    }

    private static Transfer inst;

    static synchronized Transfer get(Context c) {
        if (inst == null) inst = new Transfer(c.getApplicationContext());
        return inst;
    }

    // ------------------------------------------------------------------ state

    final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final SecureRandom rnd = new SecureRandom();
    private ServerSocket server;
    private volatile boolean running;
    private int port = PORT;
    private String lastError = "";
    private String pin = "";
    private String session = "";
    private String csrf = "";      // 每个会话一个：所有 POST 都要在 X-VD-Token 头里带上（别的网站拿不到它，没法替你点删除）
    private WebAdmin admin;
    private long idleMs = 30 * 60 * 1000L;
    private long lastUse, lastPoke;
    private int failures;
    private long lockedUntil;
    final List<Task> tasks = new ArrayList<Task>();
    private int taskSeq = 0;
    private int received;
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (System.currentTimeMillis() - lastUse >= idleMs) { stop(); return; }
            main.postDelayed(this, 30000);
        }
    };

    private Transfer(Context c) { ctx = c; }

    static class Task {
        int id; String kind, name, state = "running", msg = ""; long done, total;
        JSONObject json() {
            JSONObject o = new JSONObject();
            try { o.put("id", id); o.put("kind", kind); o.put("name", name); o.put("state", state); o.put("msg", msg); o.put("done", done); o.put("total", total); } catch (Exception ignored) { }
            return o;
        }
    }

    synchronized Task newTask(String kind, String name) {
        Task t = new Task();
        t.id = ++taskSeq; t.kind = kind; t.name = name;
        tasks.add(t);
        while (tasks.size() > 30) tasks.remove(0);
        return t;
    }

    boolean running() { return running; }
    /** 上次有人用传书网页到现在多久（毫秒）；没开着时是 -1。 */
    long idleMs() { return running ? System.currentTimeMillis() - lastUse : -1; }
    String pin() { return pin; }
    /** 正在用的端口（8080 被占时是备用端口）。 */
    int port() { return port; }
    /** 上次打不开的原因（给屏幕看的中文）；打开成功后是空的。 */
    String lastError() { return lastError; }
    int received() { return received; }
    void setIdleMinutes(int m) { idleMs = Math.max(5, m) * 60 * 1000L; }

    /** Text lines for the Kindle screen. */
    synchronized String statusText() {
        StringBuilder sb = new StringBuilder();
        for (Task t : tasks) {
            if ("running".equals(t.state)) {
                sb.append(t.kind.equals("install") ? "安装 " : t.kind.equals("download") ? "下载 " : "接收 ").append(t.name);
                if (t.total > 0) sb.append(" ").append(t.done * 100 / t.total).append("%");
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ start / stop

    synchronized boolean start() {
        if (running) return true;
        try {
            new File(BOOKS).mkdirs(); new File(BOOKS).setWritable(true, false);
            new File(KINDLE).mkdirs();
            tmpDir();   // create / check the staging folder now, not at the first upload
        } catch (Throwable e) {
            android.util.Log.w("Transfer", "staging folders", e);   // 上传时再报；不挡住服务本身
        }
        IOException err = null;
        server = null;
        for (int p : PORTS) {
            try { server = open(p); port = p; break; }
            catch (IOException e) { err = e; android.util.Log.w("Transfer", "port " + p + ": " + e); }
        }
        if (server == null) {
            lastError = reason(err);
            android.util.Log.e("Transfer", "cannot start: " + lastError, err);
            return false;
        }
        lastError = "";
        pin = String.format(Locale.US, "%04d", rnd.nextInt(10000));
        session = Long.toHexString(rnd.nextLong()) + Long.toHexString(rnd.nextLong());
        csrf = Long.toHexString(rnd.nextLong()) + Long.toHexString(rnd.nextLong());
        if (admin == null) admin = new WebAdmin(this, ctx);
        failures = 0; lockedUntil = 0;
        // “自动关闭”的设置：以前只有从设置页打开时才读，从下拉面板 / 传书页打开一律 30 分钟
        try { setIdleMinutes(Store.prefs(ctx, "home").getInt("xfer_idle", 30)); } catch (Throwable ignored) { }
        running = true;
        lastUse = System.currentTimeMillis();
        pool.execute(new Runnable() { @Override public void run() { acceptLoop(); } });
        HostLink.cmd("fwd " + port);
        HostLink.cmd("status");
        main.postDelayed(idleCheck, 30000);
        return true;
    }

    /** 监听 port。先按 Android 的默认（"::"，IPv6 双栈）绑；老 Kindle 内核（3.0 / 4.1）没有 IPv6，这样绑一定是
     * 失败（PW4 模拟器 strace：socket 已经是 AF_INET，bind 在 Java 里转换地址时就抛了异常，根本没发 bind 系统调用），以前 start() 就默默返回 false：传书页一直
     * “传输已关闭”，点“打开传输”没反应（用户诊断：PW4 / Oasis，0.8.10）。这时改绑 IPv4 的 0.0.0.0。 */
    static ServerSocket open(int p) throws IOException {
        IOException first;
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(p));
            return s;
        } catch (IOException e) {
            first = e;
            try { s.close(); } catch (Exception ignored) { }
        }
        if (first instanceof java.net.BindException && !afNoSupport(first)) throw first;   // 真被占了：换端口
        android.util.Log.i("Transfer", "port " + p + ": default bind failed (" + first + "), binding 0.0.0.0");
        ServerSocket s4 = new ServerSocket();
        try {
            s4.setReuseAddress(true);
            s4.bind(new InetSocketAddress(java.net.InetAddress.getByAddress(new byte[] { 0, 0, 0, 0 }), p));
            return s4;
        } catch (IOException e) {
            try { s4.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private static boolean afNoSupport(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("EAFNOSUPPORT") || m.contains("Address family not supported")) return true;
        }
        return false;
    }

    private static String reason(IOException e) {
        if (e == null) return "未知原因";
        if (e instanceof java.net.BindException || String.valueOf(e.getMessage()).contains("EADDRINUSE"))
            return "端口 " + PORT + " 等都被别的程序占用了";
        return "网络服务打不开（" + e.getMessage() + "）";
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        try { server.close(); } catch (Exception ignored) { }
        server = null;
        main.removeCallbacks(idleCheck);
        HostLink.cmd("fwdoff");
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(new Runnable() { @Override public void run() { handle(s); } });
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    static class Req {
        String method, path, query = "", ip;
        Map<String, String> q = new HashMap<String, String>();
        Map<String, String> h = new HashMap<String, String>();
        long length = -1;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = s.getOutputStream();
            Req r = readHead(in);
            if (r == null) return;
            r.ip = s.getInetAddress().getHostAddress();
            lastUse = System.currentTimeMillis();
            if (lastUse - lastPoke > 20000) { lastPoke = lastUse; HostLink.cmd("poke"); }   // somebody is using it: keep the Kindle from going to sleep
            route(r, in, out);
            out.flush();
        } catch (Throwable e) {
            android.util.Log.e("Transfer", "request failed", e);
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private Req readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) >= 0) {
            bo.write(c);
            if (bo.size() > 32768) return null;
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) { state++; if (state == 4) break; }
            else state = 0;
        }
        if (c < 0) return null;
        String[] lines = bo.toString("UTF-8").split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Req r = new Req();
        r.method = first[0];
        String target = first[1];
        int qm = target.indexOf('?');
        r.path = qm >= 0 ? target.substring(0, qm) : target;
        if (qm >= 0) {
            r.query = target.substring(qm + 1);
            for (String kv : r.query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) try { r.q.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"), URLDecoder.decode(kv.substring(eq + 1), "UTF-8")); } catch (Exception ignored) { }
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int col = lines[i].indexOf(':');
            if (col > 0) r.h.put(lines[i].substring(0, col).trim().toLowerCase(Locale.US), lines[i].substring(col + 1).trim());
        }
        String cl = r.h.get("content-length");
        if (cl != null) try { r.length = Long.parseLong(cl); } catch (Exception ignored) { }
        return r;
    }

    // 页面只从自己这里加载东西；不许被别的网站嵌进框架里（点击劫持）
    private static final String SEC_HEADERS = "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
        + "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'\r\n";

    static void send(OutputStream out, int code, String type, byte[] body, String extra) throws IOException {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : code == 404 ? "Not Found"
            : code == 413 ? "Payload Too Large" : code == 429 ? "Too Many Requests" : "Error") + "\r\n"
            + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n" + SEC_HEADERS
            + (extra == null ? "" : extra) + "\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
    }

    static void json(OutputStream out, int code, JSONObject o) throws IOException {
        send(out, code, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"), null);
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]); } catch (Exception ignored) { }
        return o;
    }

    private boolean authed(Req r) {
        String ck = r.h.get("cookie");
        if (ck == null) return false;
        for (String part : ck.split(";")) {
            String p = part.trim();
            if (p.startsWith("vdt=") && constEq(p.substring(4), session)) return true;
        }
        return false;
    }

    static boolean hostOk(String host) {
        if (host == null) return true;   // HTTP/1.0 客户端（curl -0 等）不带 Host
        String h = host.trim().toLowerCase(Locale.US);
        if (h.startsWith("[")) return h.matches("\\[[0-9a-f:.]+\\](:[0-9]{1,5})?");
        int c = h.lastIndexOf(':');
        if (c >= 0) { if (!h.substring(c + 1).matches("[0-9]{1,5}")) return false; h = h.substring(0, c); }
        return h.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || h.equals("localhost") || h.matches("[a-z0-9-]{1,63}\\.local");
    }

    private static boolean constEq(String a, String b) {
        if (a.length() != b.length()) return false;
        int d = 0;
        for (int i = 0; i < a.length(); i++) d |= a.charAt(i) ^ b.charAt(i);
        return d == 0;
    }

    byte[] body(Req r, InputStream in, int max) throws IOException {
        long n = r.length < 0 ? 0 : r.length;
        if (n > max) throw new IOException("too large");
        byte[] b = new byte[(int) n];
        int off = 0;
        while (off < n) { int k = in.read(b, off, (int) n - off); if (k < 0) throw new IOException("eof"); off += k; }
        return b;
    }

    private void route(Req r, InputStream in, OutputStream out) throws Exception {
        // 只认用 IP 地址（或 localhost / .local）打开的请求：挡住 DNS 重绑定——恶意网站把自己的域名解析到 Kindle 的 IP，
        // 浏览器就会让那个网站的脚本来访问这里（比如慢慢试 PIN）。Kindle 屏幕上给的地址本来就是 IP。
        if (!hostOk(r.h.get("host"))) { send(out, 403, "text/plain; charset=utf-8", "请用 Kindle 屏幕上显示的 IP 地址打开".getBytes("UTF-8"), null); return; }
        if (r.method.equals("GET") && (r.path.equals("/") || r.path.equals("/index.html"))) {
            send(out, 200, "text/html; charset=utf-8", page(), null);
            return;
        }
        if (r.method.equals("POST") && r.path.equals("/login")) {
            String given = new String(body(r, in, 256), "UTF-8").trim();
            if (given.startsWith("pin=")) given = given.substring(4);
            int verdict = checkPin(given);
            if (verdict == 2) json(out, 429, obj("ok", false, "msg", "输错次数太多，请 1 分钟后再试"));
            else if (verdict == 0) send(out, 200, "application/json", "{\"ok\":true}".getBytes("UTF-8"), "Set-Cookie: vdt=" + session + "; Path=/; HttpOnly; SameSite=Strict\r\n");
            else json(out, 401, obj("ok", false, "msg", "PIN 不对"));
            return;
        }
        if (!authed(r)) { json(out, 401, obj("ok", false, "login", true)); return; }
        // 改动类请求一律 POST，并带上会话的 CSRF 令牌（/api/state 发给已登录的页面）
        if (!r.method.equals("GET") && !r.method.equals("POST")) { json(out, 405, obj("ok", false, "msg", "不支持")); return; }
        if (r.method.equals("POST")) {
            String tok = r.h.get("x-vd-token");
            if (tok == null || !constEq(tok, csrf)) { json(out, 403, obj("ok", false, "msg", "页面已过期，请刷新", "csrf", true)); return; }
        }
        if (admin != null && admin.handle(r, in, out)) return;

        if (r.path.equals("/api/state")) { json(out, 200, state()); return; }
        if (r.path.equals("/api/files")) { json(out, 200, files()); return; }
        if (r.path.equals("/api/apps")) { json(out, 200, apps()); return; }
        if (r.path.equals("/api/tasks")) { json(out, 200, tasksJson()); return; }
        if (r.path.equals("/api/icon")) { icon(r.q.get("pkg"), out); return; }
        if (r.method.equals("POST") && r.path.equals("/api/upload")) { upload(r, in, out); return; }
        if (r.method.equals("POST") && r.path.equals("/api/theme/reset")) { Theme.reset(); StatusBarService.refresh(ctx); json(out, 200, obj("ok", true)); return; }
        if (r.method.equals("POST") && r.path.equals("/api/delete")) {
            JSONObject b = new JSONObject(new String(body(r, in, 4096), "UTF-8"));
            json(out, 200, delete(b.optString("dir"), b.optString("name")));
            return;
        }
        if (r.method.equals("POST") && r.path.equals("/api/open")) {
            JSONObject b = new JSONObject(new String(body(r, in, 4096), "UTF-8"));
            json(out, 200, openApp(b.optString("pkg")));
            return;
        }
        if (r.method.equals("POST") && r.path.equals("/api/uninstall")) {
            JSONObject b = new JSONObject(new String(body(r, in, 4096), "UTF-8"));
            json(out, 200, uninstall(b.optString("pkg")));
            return;
        }
        if (r.method.equals("POST") && r.path.equals("/api/link")) {
            JSONObject b = new JSONObject(new String(body(r, in, 8192), "UTF-8"));
            json(out, 200, importLink(b.optString("url"), b.optString("to", "books")));
            return;
        }
        send(out, 404, "text/plain; charset=utf-8", "not found".getBytes("UTF-8"), null);
    }

    private byte[] pageBytes;

    private synchronized byte[] page() throws IOException {
        if (pageBytes == null) {
            InputStream in = ctx.getAssets().open("web.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            pageBytes = bo.toByteArray();
        }
        return pageBytes;
    }

    // ------------------------------------------------------------------ api

    private JSONObject state() {
        File d = new File("/data");
        String ver = "";
        try { ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        return obj("ok", true, "name", Update.model(ctx), "free", d.getUsableSpace(), "received", received,
            "idleMin", idleMs / 60000, "books", BOOKS, "kindle", KINDLE, "token", csrf, "home", ver);
    }

    private JSONObject tasksJson() {
        JSONArray a = new JSONArray();
        synchronized (this) { for (Task t : tasks) a.put(t.json()); }
        return obj("ok", true, "tasks", a);
    }

    private static File dirFor(String which) { return "kindle".equals(which) ? new File(KINDLE) : new File(BOOKS); }

    private JSONObject files() {
        JSONArray a = new JSONArray();
        for (String which : new String[] { "books", "kindle" }) {
            File[] fs = dirFor(which).listFiles();
            if (fs == null) continue;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File x, File y) { return Long.compare(y.lastModified(), x.lastModified()); }
            });
            for (File f : fs) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                a.put(obj("dir", which, "name", f.getName(), "size", f.length(), "time", f.lastModified()));
            }
        }
        return obj("ok", true, "files", a);
    }

    private final Object loginLock = new Object();

    static boolean isKindleRoot(File d) {
        try { return d != null && d.getCanonicalPath().equals(new File(KINDLE).getCanonicalPath()); } catch (IOException e) { return false; }
    }

    /**
     * 0 = PIN 对，1 = 不对，2 = 锁定中。检查锁定、计数、比对在同一把锁里：以前并发连接都能先通过锁定检查再各试一个 PIN，
     * 4 位 PIN 几秒就能试完。登录和“加 SSH 公钥”前的再次确认共用这一个计数。
     */
    int checkPin(String given) {
        if (given == null) given = "";
        synchronized (loginLock) {
            if (System.currentTimeMillis() < lockedUntil) return 2;
            if (pin.length() == 4 && constEq(given, pin)) { failures = 0; return 0; }
            if (++failures >= 5) { lockedUntil = System.currentTimeMillis() + 60000; failures = 0; }
            return 1;
        }
    }

    // Kindle 的书库会把 documents 里的 .sh 当成“书”，点一下就以 root 运行（VeraDroid 自己的启动入口就是这样）。
    // 传书网页绝不能上传、覆盖或删除这类文件：否则拿到 PIN 的人删掉启动入口再传一个同名脚本，就拿到了整台 Kindle。
    static boolean isScript(String name) {
        String n = name.toLowerCase(Locale.US).trim();
        // vfat 会悄悄去掉结尾的点和空格：“a.sh.” 落盘就是 a.sh
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1).trim();
        return n.endsWith(".sh") || n.startsWith(".") || name.trim().startsWith(".");
    }

    private JSONObject delete(String which, String name) {
        if (name.contains("/") || name.contains("..") || name.isEmpty()) return obj("ok", false, "msg", "文件名不对");
        if (isScript(name)) return obj("ok", false, "msg", "这个文件不能在这里删除");
        File f = new File(dirFor(which), name);
        if (!f.isFile()) return obj("ok", false, "msg", "文件不存在");
        if (!f.delete()) return obj("ok", false, "msg", "删除失败（文件受保护或书库为只读挂载）");
        if (Lib.isBook(name)) Lib.scan(ctx, 0);
        return obj("ok", true);
    }

    private JSONObject apps() {
        PackageManager pm = ctx.getPackageManager();
        JSONArray a = new JSONArray();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (ctx.getPackageName().equals(pkg)) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue;
            String ver = "";
            try { PackageInfo pi = pm.getPackageInfo(pkg, 0); ver = pi.versionName == null ? "" : pi.versionName; } catch (Exception ignored) { }
            a.put(obj("pkg", pkg, "label", String.valueOf(ri.loadLabel(pm)), "version", ver));
        }
        return obj("ok", true, "apps", a);
    }

    private void icon(String pkg, OutputStream out) throws Exception {
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            Bitmap bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                Canvas c = new Canvas(bm);
                c.drawColor(0xFFFFFFFF);
                d.setBounds(0, 0, 96, 96);
                d.draw(c);
                bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
            } finally {
                bm.recycle();
            }
            send(out, 200, "image/png", bo.toByteArray(), "Cache-Control: max-age=60\r\n");
        } catch (Exception e) {
            send(out, 404, "text/plain", "no icon".getBytes("UTF-8"), null);
        }
    }

    private JSONObject openApp(final String pkg) {
        if (LockScreen.showing()) return obj("ok", false, "msg", "设备已锁屏，请先在 Kindle 上解锁");
        if (pkg == null || pkg.length() == 0) return obj("ok", false, "msg", "参数无效");
        final Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return obj("ok", false, "msg", "这个应用不能打开");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        main.post(new Runnable() { @Override public void run() {
            if (LockScreen.showing()) return;
            try {
                AppProf.prepareLaunch(ctx, pkg);
                ctx.startActivity(i);
            } catch (Exception ignored) { }
        } });
        return obj("ok", true);
    }

    private JSONObject uninstall(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9._]+")) return obj("ok", false, "msg", "包名无效");
        HostLink.cmd("uninstallapp " + pkg);
        return obj("ok", true, "msg", "已交给宿主卸载");
    }

    // ------------------------------------------------------------------ files in

    static String cleanName(String n) {
        if (n == null) n = "file";
        int s = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (s >= 0) n = n.substring(s + 1);
        n = n.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (n.startsWith(".")) n = "_" + n.substring(1);
        if (n.isEmpty()) n = "file";
        if (n.length() > 120) n = n.substring(n.length() - 120);
        return n;
    }

    static File uniqueFile(File dir, String name) {
        dir.mkdirs();
        String cn = cleanName(name);
        while (cn.endsWith(".") || cn.endsWith(" ")) cn = cn.substring(0, cn.length() - 1);
        if (cn.isEmpty()) cn = "file";
        if (isScript(cn) || WebAdmin.isProtected(cn)) cn = (cn.startsWith(".") ? "file" + cn : cn) + ".blocked";   // 链接下载等其他入口也不能落成可执行的书库脚本 / SSH 公钥文件
        File f = new File(dir, cn);
        if (!f.exists()) return f;
        String base = f.getName(), ext = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0) { ext = base.substring(dot); base = base.substring(0, dot); }
        for (int i = 1; i < 1000; i++) { File g = new File(dir, base + " (" + i + ")" + ext); if (!g.exists()) return g; }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static boolean isApk(String name) { return name.toLowerCase(Locale.US).endsWith(".apk"); }
    private static boolean isFont(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
    }

    /** What to do with a finished file: apk -> install, anything else stays where it is. */
    private void afterFile(File f, Task t) {
        received++;
        if (isApk(f.getName())) install(f, t);
        else if (isFont(f.getName())) { t.state = "done"; t.msg = "字体已导入，去设置→显示选择"; }
        else { t.state = "done"; t.msg = "已保存"; if (Lib.isBook(f.getName())) Lib.scan(ctx, 0); }
        f.setReadable(true, false);
    }

    private final Object installLock = new Object();

    /**
     * Install through the host: "pm install" refuses to run as the system user (only root / shell may), and the host
     * scripts can run it as root inside the container. We stage the apk under a plain name, ask the host, and wait for
     * the result file it writes next to the control files.
     */
    /** How long we wait for the host's install result. A big APK right after an update can take > 8 min on a 512 MB device
     *  (Android's own dexopt holds the install lock), so 10 min reported false failures; 30 min leaves room. */
    static final long INSTALL_WAIT_MS = 30 * 60 * 1000L;

    interface InstallProgress { void minutes(int n); }

    /**
     * Ask the host to install {@code staged} (a world-readable file in a staging folder) and wait for the result file the host
     * writes (ctl/inst.<name>). Returns the host's output ("Success" or the reason), or null when nothing came back in
     * INSTALL_WAIT_MS. {@code p} gets the elapsed minutes once a minute.
     */
    static String hostInstall(File staged, InstallProgress p) {
        File res = new File(HostLink.CTL + "inst." + staged.getName());
        boolean hadOld = res.isFile();
        long oldMod = hadOld ? res.lastModified() : 0L;
        long oldLen = hadOld ? res.length() : -1L;
        boolean sawGone = !hadOld || res.delete();
        HostLink.cmd("install " + staged.getAbsolutePath());   // absolute: the host accepts the shared and our own staging folder
        long start = System.currentTimeMillis(), until = start + INSTALL_WAIT_MS;
        int shown = 0;
        while (System.currentTimeMillis() < until) {
            try { Thread.sleep(1500); } catch (InterruptedException e) { return null; }
            if (!res.isFile()) {
                sawGone = true;
            } else if (sawGone || res.lastModified() != oldMod || res.length() != oldLen) {
                String out = "";
                java.io.BufferedReader r = null;
                try {
                    r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(res), "UTF-8"));
                    String line; StringBuilder sb = new StringBuilder();
                    while ((line = r.readLine()) != null) sb.append(line).append(' ');
                    out = sb.toString().trim();
                } catch (Exception ignored) { }
                finally { if (r != null) try { r.close(); } catch (Exception ignored) { } }
                res.delete();
                return out.length() == 0 ? "宿主没有给出结果" : out;
            }
            int m = (int) ((System.currentTimeMillis() - start) / 60000L);
            if (m != shown && p != null) { shown = m; p.minutes(m); }
        }
        return null;
    }

    /** Package name and versionCode of an APK file, or null. */
    static android.content.pm.PackageInfo apkInfo(Context c, File apk) {
        try { return c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0); } catch (Exception e) { return null; }
    }

    /** After a wait without a result: is that package (at least) at that version now? */
    static boolean installedNow(Context c, android.content.pm.PackageInfo want) {
        if (want == null || want.packageName == null) return false;
        try { return c.getPackageManager().getPackageInfo(want.packageName, 0).versionCode >= want.versionCode; } catch (Exception e) { return false; }
    }

    private void install(final File apk, final Task t) {
        pool.execute(new Runnable() {
            @Override public void run() {
                synchronized (installLock) {
                    t.kind = "install"; t.state = "running"; t.msg = "正在安装…（大应用可能要好几分钟）";
                    String safe = "up" + t.id + "-" + (System.currentTimeMillis() % 100000L) + ".apk";
                    File staged = new File(tmpDir(), safe);
                    if (!apk.renameTo(staged)) { t.state = "error"; t.msg = "安装失败：不能整理安装包"; return; }
                    staged.setReadable(true, false);
                    android.content.pm.PackageInfo want = apkInfo(ctx, staged);
                    String out = hostInstall(staged, new InstallProgress() {
                        @Override public void minutes(int n) { t.msg = "正在安装… 已用 " + n + " 分钟"; }
                    });
                    if (out != null && out.contains("Success")) { t.state = "done"; t.msg = "已安装"; staged.delete(); }
                    else if (out != null) { t.state = "error"; t.msg = "安装失败：" + out; staged.delete(); }
                    else if (installedNow(ctx, want)) { t.state = "done"; t.msg = "已安装"; staged.delete(); }
                    else { t.state = "error"; t.msg = "安装 " + (INSTALL_WAIT_MS / 60000) + " 分钟还没有结果（系统是不是被冻结了？稍后可以在应用列表里看是否已装上）"; }
                }
            }
        });
    }

    private File targetDir(String name, String to) {
        if (isApk(name)) return tmpDir();
        if (isFont(name)) {
            File f = new File("/data/media/0/Fonts");
            f.mkdirs();
            return f;
        }
        return dirFor(to);
    }

    // -- multipart upload -------------------------------------------------------------------------------------------

    /** Read and drop a small refused request body first: closing with unread data makes the browser see a reset, not our answer. */
    private static void drain(InputStream in, long n) {
        if (n <= 0 || n > 8L * 1024 * 1024) return;
        try { byte[] b = new byte[65536]; while (n > 0) { int k = in.read(b, 0, (int) Math.min(b.length, n)); if (k < 0) break; n -= k; } } catch (IOException ignored) { }
    }

    private void upload(Req r, InputStream in, OutputStream out) throws Exception {
        String ct = r.h.get("content-type");
        if (ct == null || !ct.toLowerCase(Locale.US).startsWith("multipart/form-data")) { json(out, 200, obj("ok", false, "msg", "需要 multipart 上传")); return; }
        String boundary = parseBoundary(ct);
        if (boundary == null || boundary.isEmpty()) { json(out, 200, obj("ok", false, "msg", "需要 multipart 上传")); return; }
        String to = r.q.containsKey("to") ? r.q.get("to") : "books";
        // 文件管理里的“上传到当前文件夹”：原样放进那个文件夹（APK 也只是保存，不安装）
        File folder = null;
        if ("dir".equals(to)) {
            String[] why = new String[1];
            folder = WebAdmin.uploadDir(r.q.get("root"), r.q.get("path"), why);
            if (folder == null) { drain(in, r.length); json(out, 200, obj("ok", false, "msg", why[0])); return; }
        } else if ("gallery".equals(to)) {
            // 桌面的壁纸图库（/sdcard/VeraDroid/wallpaper，Theme.USER_WALL）：只收图片
            folder = new File(Theme.USER_WALL);
            folder.mkdirs();
            folder.setReadable(true, false); folder.setWritable(true, false); folder.setExecutable(true, false);
        }
        // 先看空间：不能把 Kindle 写满（Android 和 Kindle 共用这块存储，写满了两边都会出问题）
        if (r.length < 0) { json(out, 411, obj("ok", false, "msg", "需要 Content-Length")); return; }
        File space = folder != null ? folder : "kindle".equals(to) ? new File(KINDLE) : new File("/data");
        if (!space.exists()) space = new File("/data");
        long usable = space.getUsableSpace();
        if (usable < 64L * 1024 * 1024 || r.length > usable - 64L * 1024 * 1024) { drain(in, r.length); json(out, 413, obj("ok", false, "msg", "空间不够：剩余 " + (usable >> 20) + " MB（至少要留 64 MB）")); return; }
        JSONArray results = new JSONArray();
        Multipart mp = new Multipart(in, boundary, r.length);
        Multipart.Part part;
        while ((part = mp.next()) != null) {
            if (part.filename == null || part.filename.length() == 0) { mp.skip(); continue; }
            if (isScript(cleanName(part.filename))) {
                mp.skip();
                results.put(obj("ok", false, "name", part.filename, "msg", "不支持上传脚本文件"));
                continue;
            }
            if ("gallery".equals(to) && !cleanName(part.filename).toLowerCase(Locale.US).matches(".*\\.(jpe?g|png|webp)")) {
                mp.skip();
                results.put(obj("ok", false, "name", part.filename, "msg", "壁纸图库只收 jpg / png / webp 图片"));
                continue;
            }
            // 主题文件（壁纸 / 图标 / statusbar.json / .zip 主题包）先放到暂存目录，再交给 Theme 导入
            File destDir = folder != null ? folder : "theme".equals(to) ? tmpDir() : targetDir(part.filename, to);
            File destSpace = destDir.exists() ? destDir : new File("/data");
            long destUsable = destSpace.getUsableSpace();
            long rem = Math.max(0L, mp.remaining) + (mp.lim - mp.pos);
            if (destUsable < 64L * 1024 * 1024 || rem > destUsable - 64L * 1024 * 1024) {
                mp.skip();
                results.put(obj("ok", false, "name", part.filename, "msg", "空间不够：剩余 " + (destUsable >> 20) + " MB（至少要留 64 MB）"));
                continue;
            }
            Task t = newTask("upload", part.filename);
            File dest = uniqueFile(destDir, part.filename);
            t.total = -1;
            // 放进文件夹的先写成隐藏的临时文件、写完再改名：桌面按目录修改时间发现新图片，不能让它读到半个文件
            final File part0 = folder != null ? new File(folder, ".vdpart-" + t.id + "-" + System.currentTimeMillis()) : dest;
            FileOutputStream fo;
            File lib = null;      // 加密的 Kindle 书库：先收进自己的私有目录，再请宿主（root，有密钥）代写（webadmin.sh libput）
            String libId = null;
            try {
                fo = new FileOutputStream(part0);
            } catch (IOException e) {
                part0.delete();   // 有的错误（比如加密的书库）是文件已经建出来了才失败
                String m = String.valueOf(e.getMessage());
                if (m.contains("Required key not available") && isKindleRoot(dest.getParentFile())) {
                    byte[] rb = new byte[16]; rnd.nextBytes(rb); libId = HostReq.hex(rb);
                    File ld = new File(ctx.getFilesDir(), "libput");
                    ld.mkdirs();
                    try { android.system.Os.chmod(ld.getPath(), 0700); } catch (Throwable ignored) { }
                    lib = new File(ld, libId + ".part");
                    fo = new FileOutputStream(lib);
                } else {
                // 5.20 的 Scribe 等机型上 Kindle 书库是 fscrypt 加密的，密钥只在 root 的钥匙串里：Android 的应用写不进去
                t.state = "error"; t.msg = m.contains("Required key not available") ? "这台 Kindle 的书库是加密的，这个文件夹写不进去（书请放到书库最外层，或用数据线拷）" : "不能写入：" + m;
                // 跳过这一个、接着收后面的：以前直接答复并关连接，请求体剩下的没读，浏览器看到的是连接被重置，前面传好的结果也没了
                mp.skip();
                results.put(obj("ok", false, "name", part.filename, "msg", t.msg));
                continue;
                }
            }
            try {
                long n = mp.copyTo(fo, t);
                t.done = n;
            } catch (IOException e) {
                try { fo.close(); } catch (Exception ignored) { }
                part0.delete();
                if (lib != null) lib.delete();
                t.state = "error"; t.msg = "上传中断";
                json(out, 200, obj("ok", false, "msg", "上传中断"));
                return;
            }
            fo.close();
            if (lib != null) {
                java.util.Map<String, String> kv = HostReq.kv(HostReq.run(ctx, "libput", libId, dest.getName(), 600000));
                lib.delete();
                if ("1".equals(kv.get("ok"))) { received++; t.state = "done"; t.msg = "已由宿主写进加密的 Kindle 书库：" + kv.get("name"); Lib.scan(ctx, 0); }
                else { t.state = "error"; t.msg = "写进加密的 Kindle 书库失败（" + (kv.isEmpty() ? "宿主没有回应" : kv.get("error")) + "）"; }
                results.put(obj("ok", "done".equals(t.state), "name", kv.containsKey("name") ? kv.get("name") : dest.getName(), "task", t.id, "state", t.state, "msg", t.msg));
                continue;
            }
            if (part0 != dest && !part0.renameTo(dest)) { part0.delete(); t.state = "error"; t.msg = "保存失败"; results.put(obj("ok", false, "name", dest.getName(), "msg", t.msg)); continue; }
            if ("theme".equals(to)) {
                received++;
                t.msg = Theme.importFile(ctx, dest);
                t.state = t.msg.startsWith("导入失败") || t.msg.startsWith("不认识") || t.msg.contains("没有可用") ? "error" : "done";
            } else if (folder != null) { received++; dest.setReadable(true, false); t.state = "done"; t.msg = "已保存到 " + dest.getName(); if (Lib.isBook(dest.getName())) Lib.scan(ctx, 0); }
            else afterFile(dest, t);
            results.put(obj("name", dest.getName(), "task", t.id, "state", t.state, "msg", t.msg));
        }
        json(out, 200, obj("ok", true, "results", results));
    }

    /** Reads multipart/form-data from a stream, one part at a time, without keeping a file in memory. */
    static final class Multipart {
        static final class Part { String name, filename; }

        private final InputStream in;
        private final byte[] delim;
        private byte[] buf = new byte[1 << 16];
        private int pos, lim;
        private boolean first = true, done;
        private long remaining;

        Multipart(InputStream in, String boundary, long length) throws IOException {
            this.in = in;
            this.delim = ("\r\n--" + boundary).getBytes("ISO-8859-1");
            this.remaining = length;
            // pretend the body is preceded by CRLF so the first boundary matches the same way as the others
            System.arraycopy("\r\n".getBytes(), 0, buf, 0, 2);
            lim = 2;
        }

        private boolean fill() throws IOException {
            if (pos > 0) { System.arraycopy(buf, pos, buf, 0, lim - pos); lim -= pos; pos = 0; }
            if (lim >= buf.length) return true;
            int want = buf.length - lim;
            if (remaining >= 0 && remaining < want) want = (int) remaining;
            if (want <= 0) return false;
            int n = in.read(buf, lim, want);
            if (n <= 0) return false;
            lim += n;
            if (remaining >= 0) remaining -= n;
            return true;
        }

        private int indexOfDelim() {
            outer:
            for (int i = pos; i + delim.length <= lim; i++) {
                for (int j = 0; j < delim.length; j++) if (buf[i + j] != delim[j]) continue outer;
                return i;
            }
            return -1;
        }

        Part next() throws IOException {
            if (done) return null;
            // find the boundary that starts this part
            while (true) {
                int i = indexOfDelim();
                if (i >= 0) { pos = i + delim.length; break; }
                if (lim - pos > delim.length) pos = lim - delim.length;
                if (!fill()) { done = true; return null; }
            }
            while (lim - pos < 2) if (!fill()) { done = true; return null; }
            if (buf[pos] == '-' && buf[pos + 1] == '-') { done = true; return null; }
            pos += 2;   // CRLF after the boundary
            // headers until a blank line
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            while (true) {
                int end = -1;
                for (int i = pos; i + 3 < lim; i++) if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') { end = i; break; }
                if (end >= 0) { hb.write(buf, pos, end - pos); pos = end + 4; break; }
                if (lim - pos > 16384) throw new IOException("part header too large");
                if (!fill()) throw new IOException("eof in headers");
            }
            Part p = new Part();
            String head = hb.toString("UTF-8");
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(Locale.US).startsWith("content-disposition")) {
                    p.name = attr(line, "name");
                    p.filename = attr(line, "filename");
                }
            }
            return p;
        }

        private static String attr(String line, String key) {
            int i = 0;
            String lower = line.toLowerCase(Locale.US);
            while ((i = lower.indexOf(key + "=\"", i)) >= 0) {
                if (i == 0 || lower.charAt(i - 1) == ' ' || lower.charAt(i - 1) == ';') {
                    int s = i + key.length() + 2, e = line.indexOf('"', s);
                    return e > s ? line.substring(s, e) : "";
                }
                i++;
            }
            return null;
        }

        void skip() throws IOException { copyTo(null, null); }

        /** Copy the current part's content up to (not including) the next boundary. Returns the number of bytes. */
        long copyTo(OutputStream o, Task t) throws IOException {
            long total = 0;
            while (true) {
                int i = indexOfDelim();
                if (i >= 0) {
                    if (o != null) o.write(buf, pos, i - pos);
                    total += i - pos;
                    pos = i;   // leave the boundary for next()
                    return total;
                }
                int safe = lim - pos - (delim.length - 1);
                if (safe > 0) {
                    if (o != null) o.write(buf, pos, safe);
                    total += safe;
                    pos += safe;
                    if (t != null) t.done = total;
                }
                if (!fill()) throw new IOException("eof in part");
            }
        }
    }

    // ------------------------------------------------------------------ direct links

    private JSONObject importLink(final String url, final String to) {
        final Task t = newTask("download", url.length() > 60 ? url.substring(0, 60) + "…" : url);
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    File f = Cloud.download(url, null, tmpDir(), null, progress(t));
                    finishDownload(f, to, t);
                } catch (Exception e) { t.state = "error"; t.msg = String.valueOf(e.getMessage()); }
            }
        });
        return obj("ok", true, "task", t.id);
    }

    private Cloud.Progress progress(final Task t) {
        return new Cloud.Progress() { @Override public void update(long done, long total) { t.done = done; t.total = total; } };
    }

    /** Safely extract multipart boundary (handles quoted values, trailing parameters like '; charset=utf-8', and malformed quotes). */
    static String parseBoundary(String ct) {
        if (ct == null) return null;
        String lower = ct.toLowerCase(Locale.US);
        int idx = lower.indexOf("boundary=");
        if (idx < 0) return null;
        String v = ct.substring(idx + 9).trim();
        if (v.startsWith("\"")) {
            int end = v.indexOf('"', 1);
            return end > 1 ? v.substring(1, end) : null;
        }
        int semi = v.indexOf(';');
        if (semi >= 0) v = v.substring(0, semi).trim();
        return v.isEmpty() ? null : v;
    }

    /** A download lands in TMP first; move it to the target folder (fonts -> Fonts/, books -> Books/KindleLibrary/, apk stays and is installed). */
    private void finishDownload(File f, String to, Task t) {
        t.name = f.getName();
        if (isScript(cleanName(f.getName())) || WebAdmin.isProtected(f.getName())) {
            f.delete();
            t.state = "error"; t.msg = "不支持下载脚本文件";
            return;
        }
        if (!isApk(f.getName())) {
            File dest = uniqueFile(targetDir(f.getName(), to), f.getName());
            if (!f.renameTo(dest)) {
                File tmp = new File(dest.getParentFile(), ".vdpart-" + t.id + "-" + System.nanoTime());
                FileOutputStream o = null;
                InputStream i = null;
                try {
                    i = new java.io.FileInputStream(f);
                    o = new FileOutputStream(tmp);
                    byte[] b = new byte[65536]; int n;
                    while ((n = i.read(b)) > 0) o.write(b, 0, n);
                    o.close(); o = null;
                    i.close(); i = null;
                    if (!tmp.renameTo(dest)) {
                        tmp.delete(); f.delete();
                        t.state = "error"; t.msg = "保存失败";
                        return;
                    }
                    f.delete();
                } catch (IOException e) {
                    tmp.delete();
                    if (o != null) { try { o.close(); } catch (Exception ignored) { } o = null; }
                    if (i != null) { try { i.close(); } catch (Exception ignored) { } i = null; }
                    if (isKindleRoot(dest.getParentFile())) {
                        libputFromFile(f, dest.getName(), t);
                    } else {
                        t.state = "error"; t.msg = "保存失败：" + e.getMessage();
                    }
                    f.delete();
                    return;
                } finally {
                    if (o != null) try { o.close(); } catch (Exception ignored) { }
                    if (i != null) try { i.close(); } catch (Exception ignored) { }
                }
            }
            f = dest;
        }
        afterFile(f, t);
    }

    /** 加密的 Kindle 书库（KT6/Scribe fscrypt）代写通道：拷进私有目录后请宿主（root）写进 Kindle 书库根目录，返回写入后的最终文件名，失败返回 null。 */
    static String libputFile(Context ctx, File src, String destName) {
        if (ctx == null || src == null || !src.isFile() || destName == null || destName.length() == 0) return null;
        byte[] rb = new byte[16];
        new SecureRandom().nextBytes(rb);
        String libId = HostReq.hex(rb);
        File ld = new File(ctx.getFilesDir(), "libput");
        ld.mkdirs();
        try { android.system.Os.chmod(ld.getPath(), 0700); } catch (Throwable ignored) { }
        File lib = new File(ld, libId + ".part");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(lib);
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close(); out = null;
            in.close(); in = null;
            if (lib.length() != src.length()) return null;
            java.util.Map<String, String> kv = HostReq.kv(HostReq.run(ctx, "libput", libId, destName, 600000));
            if ("1".equals(kv.get("ok"))) {
                String outName = kv.get("name");
                return outName != null && outName.length() > 0 ? outName : destName;
            }
            return null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            lib.delete();
        }
    }

    /** 加密的 Kindle 书库（KT6/Scribe fscrypt）回退路径：先拷进私有目录，再请宿主（root）代写。 */
    private void libputFromFile(File src, String destName, Task t) {
        byte[] rb = new byte[16]; rnd.nextBytes(rb);
        String libId = HostReq.hex(rb);
        File ld = new File(ctx.getFilesDir(), "libput");
        ld.mkdirs();
        try { android.system.Os.chmod(ld.getPath(), 0700); } catch (Throwable ignored) { }
        File lib = new File(ld, libId + ".part");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(lib);
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close(); out = null;
            in.close(); in = null;
            java.util.Map<String, String> kv = HostReq.kv(HostReq.run(ctx, "libput", libId, destName, 600000));
            if ("1".equals(kv.get("ok"))) {
                received++;
                t.state = "done";
                t.msg = "已由宿主写进加密的 Kindle 书库：" + kv.get("name");
            } else {
                t.state = "error";
                t.msg = "写进加密的 Kindle 书库失败（" + (kv.isEmpty() ? "宿主没有回应" : kv.get("error")) + "）";
            }
        } catch (IOException e) {
            t.state = "error";
            t.msg = "保存失败：" + e.getMessage();
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            lib.delete();
        }
    }
}
