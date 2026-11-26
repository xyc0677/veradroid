package com.veradroid.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 整机系统更新（宿主的 bootstrap，不是 VeraHome 自己的 APK 更新 {@link Update}）：
 * “检查系统更新”先问服务器最新版本，有新版本就把这之间每一版的更新内容列出来，用户自己选“更新”或“以后再说”，
 * 选了更新才让宿主开始（cmd fullupdate）。以前一点就直接开始下载、退出 Android。
 *
 * 线路按内核分（和宿主 auto-update.sh 一样）：5.x = PW6 包（/api/bootstrap），4.9 = 其他机型（bootstrap-k49.json），
 * 3.x / 4.1 = 老机型（bootstrap-legacy.json）；装的是按类别编的系统（$VD/image-class）时先看那个类别的版本。
 * 更新内容来自 /api/changelog?line=…&since=当前版本（服务器上的 changelog.json）；接口还没有时只显示版本号。
 *
 * 设置里“自动更新：自动安装 / 只提醒 / 关闭”存在宿主的 $VD/autoupdate（1 / notify / 0）。只提醒时宿主发现新版本
 * 只写 $VD/update.available，桌面回到前台时（{@link #maybeNotify}）弹出同一个对话框，每个版本只弹一次。
 * 容器里看不到 $VD：版本、类别、自动更新方式、发现的新版本都从宿主状态 ctl/status 读（status.sh 的 sysversion 等）。
 */
final class SysUpdate {
    /** 测试时可以让它问别的服务器（root 才能设：setprop debug.vd.updatebase http://测试服务器:端口），平时就是官网。 */
    private static String base() {
        try {
            String b = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, "debug.vd.updatebase", "");
            if (b != null && b.matches("https?://[A-Za-z0-9.:-]+")) return b;
        } catch (Throwable ignored) { }
        return "https://veradroid.wenjin.jp";
    }
    private static final String VD = "/mnt/base-us/veradroid";
    static final String[] MODE_L = { "自动安装", "只提醒", "关闭" };
    static final String[] MODE_V = { "1", "notify", "0" };
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean(false);

    private SysUpdate() { }

    static String readLine(String path) {
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try { String s = r.readLine(); return s == null ? null : s.trim(); } finally { r.close(); }
        } catch (Throwable t) { return null; }
    }

    /** 宿主写的状态（ctl/status）：容器里看不到 $VD，系统版本、自动更新方式都从这里来。 */
    private static String st(String key) {
        String v = HostLink.kv(HostLink.CTL + "status").get(key);
        return v == null ? "" : v.trim();
    }

    /** 现在装的系统版本（宿主状态里的 sysversion = $VD/version）；老宿主没有这一项时返回 null。 */
    static String current() {
        String v = st("sysversion");
        return v.matches("[0-9]+(\\.[0-9]+){0,3}") ? v : null;
    }

    /** pw6 / k49 / legacy：按内核版本，和宿主 auto-update.sh 的判断一样。 */
    static String line() {
        String k = System.getProperty("os.version", "");
        if (k.startsWith("3.") || k.startsWith("4.1.")) return "legacy";
        if (k.startsWith("4.9.") || k.startsWith("4.")) return "k49";
        return "pw6";
    }

    private static String appendAuth(String url, Context ctx) {
        if (ctx == null) return url;
        try {
            StringBuilder sb = new StringBuilder(url);
            String did = Update.deviceId(ctx);
            if (did != null && did.length() > 0) {
                sb.append(sb.indexOf("?") >= 0 ? "&device_id=" : "?device_id=").append(StoreNet.enc(did));
            }
            String tok = Token.get(ctx);
            if (tok != null && tok.length() > 0) {
                sb.append(sb.indexOf("?") >= 0 ? "&token=" : "?token=").append(StoreNet.enc(tok));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return url;
        }
    }

    private static String metaUrl(Context ctx, String line) {
        long t = System.currentTimeMillis() / 1000;
        if ("legacy".equals(line)) return appendAuth(base() + "/static/bootstrap-legacy.json?t=" + t, ctx);
        if ("k49".equals(line)) return appendAuth(base() + "/static/bootstrap-k49.json?t=" + t, ctx);
        return appendAuth(base() + "/api/bootstrap?t=" + t, ctx);
    }

    /** 点分版本号：a 比 b 新。 */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = 0, q = 0;
            try { p = i < x.length ? Integer.parseInt(x[i]) : 0; } catch (NumberFormatException ignored) { }
            try { q = i < y.length ? Integer.parseInt(y[i]) : 0; } catch (NumberFormatException ignored) { }
            if (p != q) return p > q;
        }
        return false;
    }

    private static String get(Context ctx, String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", "VeraDroid/SysUpdate");
        InputStream in = null;
        try {
            int code = h.getResponseCode();
            in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while (in != null && (n = in.read(b)) > 0 && bo.size() < 512 * 1024) bo.write(b, 0, n);
            String raw = bo.toString("UTF-8");
            if (code == 401 || code == 403) {
                JSONObject j = null;
                try { j = new JSONObject(raw); } catch (Throwable ignored) { }
                String ec = j != null ? j.optString("code", "") : "";
                String em = j != null ? j.optString("error", BindGate.OTA_REASON_TEXT) : BindGate.OTA_REASON_TEXT;
                if ("ip_blocked".equals(ec)) throw new Exception(em);
                if ("bind_required".equals(ec) || "token_invalid".equals(ec) || code == 401) {
                    if (ctx != null) Token.clear(ctx);
                    throw new BindGate.BindRequiredException(em);
                }
                throw new Exception(em);
            }
            if (code >= 400) return null;
            return raw;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
        }
    }

    static final class Info {
        String current, latest, line, error, bindError;
        List<JSONObject> entries;   // null = 服务器还没有 /api/changelog 或读不到
    }

    /** 后台线程：仅查最新系统版本号（不拉 changelog），供 WebAdmin.updateInfo() 复用一致的 imageclass + 内核线路逻辑。 */
    static String latestVersion(Context ctx) {
        String icJson = null, famJson = null;
        String ic = st("imageclass");
        if (ic.matches("[A-Za-z0-9_-]{1,32}")) {
            try { icJson = get(ctx, appendAuth(base() + "/static/bootstrap-" + ic + ".json?t=" + System.currentTimeMillis() / 1000, ctx)); }
            catch (Throwable ignored) { }
        }
        try { famJson = get(ctx, metaUrl(ctx, line())); }
        catch (Throwable ignored) { }
        String icVer = "", famVer = "";
        if (icJson != null) try { icVer = new JSONObject(icJson).optString("version", ""); } catch (Throwable ignored) { }
        if (famJson != null) try { famVer = new JSONObject(famJson).optString("version", ""); } catch (Throwable ignored) { }
        if (icVer.length() > 0 && (famVer.length() == 0 || !newer(famVer, icVer))) return icVer;
        return famVer.length() > 0 ? famVer : null;
    }

    /** 后台线程：最新版本 + 两版之间的更新内容。 */
    static Info fetch(Context ctx) {
        Info i = new Info();
        i.current = current();
        i.line = line();
        try {
            String icJson = null, famJson = null;
            BindGate.BindRequiredException bindReq = null;
            String ic = st("imageclass");
            if (ic.matches("[A-Za-z0-9_-]{1,32}")) {
                try { icJson = get(ctx, appendAuth(base() + "/static/bootstrap-" + ic + ".json?t=" + System.currentTimeMillis() / 1000, ctx)); }
                catch (BindGate.BindRequiredException be) { bindReq = be; }
                catch (Throwable ignored) { }
            }
            try { famJson = get(ctx, metaUrl(ctx, i.line)); }
            catch (BindGate.BindRequiredException be) { bindReq = be; }
            catch (Throwable ignored) { }
            String icVer = "", famVer = "";
            if (icJson != null) try { icVer = new JSONObject(icJson).optString("version", ""); } catch (Throwable ignored) { }
            if (famJson != null) try { famVer = new JSONObject(famJson).optString("version", ""); } catch (Throwable ignored) { }
            if (icJson == null && famJson == null) {
                if (bindReq != null) { i.bindError = bindReq.getMessage(); return i; }
                i.error = "连不上更新服务器";
                return i;
            }
            // 同时核对按类别清单与家族清单，取版本更新者，避免类别清单滞后导致误报“已是最新版本”
            if (icVer.length() > 0 && (famVer.length() == 0 || !newer(famVer, icVer))) i.latest = icVer;
            else i.latest = famVer;
            if (i.latest.length() == 0) { i.error = "服务器没有给出版本号"; return i; }
            if (i.current != null && !newer(i.latest, i.current)) return i;
            if (i.current == null) return i;   // 老宿主不报版本：只能给出最新版本号
            try {
                String cl = get(ctx, appendAuth(base() + "/api/changelog?line=" + i.line + "&since=" + java.net.URLEncoder.encode(i.current, "UTF-8"), ctx));
                if (cl != null) {
                    JSONArray a = new JSONObject(cl).optJSONArray("entries");
                    if (a != null) {
                        i.entries = new ArrayList<JSONObject>();
                        for (int k = 0; k < a.length(); k++) {
                            JSONObject e = a.optJSONObject(k);
                            if (e != null && !newer(e.optString("version", "0"), i.latest)) i.entries.add(e);
                        }
                    }
                }
            } catch (Throwable ignored) { }
        } catch (Throwable t) { i.error = "检查失败，请检查网络"; }
        return i;
    }

    /** “检查系统更新”：查完再弹对话框（有新版本：更新内容 + 更新 / 以后再说；没有：说一声）。 */
    static void check(final Activity a) {
        if (running()) { progress(a, null); return; }   // 已经在更新：不再查一遍、不再开第二个安装器，直接看进度
        if (!BindGate.ensureBound(a, "系统更新", BindGate.OTA_REASON_TEXT, new Runnable() {
            @Override public void run() { check(a); }
        })) return;
        if (!BUSY.compareAndSet(false, true)) { VeraToast.show(a, "正在检查更新…"); return; }
        VeraToast.show(a, "正在检查系统更新…");
        new Thread(new Runnable() { @Override public void run() {
            final Info i;
            try { i = fetch(a.getApplicationContext()); } finally { BUSY.set(false); }
            new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
                if (a.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
                if (i.bindError != null) {
                    BindGate.showBindDialog(a, "系统更新", i.bindError, new Runnable() {
                        @Override public void run() { check(a); }
                    });
                    return;
                }
                if (i.error != null) { VeraToast.show(a, i.error); return; }
                if (i.current != null && !newer(i.latest, i.current)) {
                    new VeraDialog.Builder(a).title("已是最新版本").message("现在装的是 " + i.current + "，服务器上最新的也是 " + i.latest + "。").positive("好", null).show();
                    return;
                }
                show(a, i);
            } });
        } }, "sysupdate-check").start();
    }

    /** 自动更新选了“只提醒”、宿主发现了新版本：回到桌面时弹一次（同一个版本点过“以后再说”就不再弹）。 */
    static void maybeNotify(final Activity a) {
        final String v = st("update_available"), cur = current();
        if (v.length() == 0 || cur == null || !newer(v, cur) || !"notify".equals(st("autoupdate"))) return;
        final SharedPreferences p = Store.prefs(a, "home");
        if (v.equals(p.getString("sysupdate_later", ""))) return;
        if (!BUSY.compareAndSet(false, true)) return;
        new Thread(new Runnable() { @Override public void run() {
            final Info i;
            try { i = fetch(a.getApplicationContext()); } finally { BUSY.set(false); }
            new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
                if (a.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed())
                    || i.bindError != null || i.error != null || i.latest == null || i.current == null || !newer(i.latest, i.current)) return;
                if (i.latest.equals(p.getString("sysupdate_later", ""))) return;
                show(a, i);
            } });
        } }, "sysupdate-notify").start();
    }

    private static void show(final Activity a, final Info i) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        if (i.entries == null || i.entries.isEmpty()) {
            box.addView(text(a, "更新内容暂时拿不到，可以在官网的“更新日志”页看。", 16, false, 0));
        } else {
            for (JSONObject e : i.entries) {
                String title = e.optString("title", "");
                int colon = title.indexOf('：');
                String head = e.optString("version", "") + "  ·  " + e.optString("date", "");
                box.addView(text(a, head, 17, true, 10));
                if (colon > 0 && colon + 1 < title.length()) box.addView(text(a, title.substring(colon + 1), 16, true, 2));
                JSONArray items = e.optJSONArray("items");
                if (items != null) for (int k = 0; k < items.length(); k++) box.addView(text(a, "· " + items.optString(k), 15, false, 4));
            }
        }
        box.addView(text(a, "更新时会短暂退出 Android；数据、书架、绑定账号都保留。有增量补丁时只下载变了的部分。", 13, false, 14));
        // 更新前备份关着（设置 → 设备与更新 → 更新前备份）：更新前说清楚
        if (BackupActivity.off()) box.addView(text(a, "更新前备份已关闭：这次不备份，更新出问题时无法恢复。可以在 设置 → 设备与更新 → 更新前备份 里打开。", 15, true, 10));
        // 更新内容放在固定高度的一块里一页一页翻（划动或点“上一页 / 下一页”），“更新 / 以后再说”一直在下面看得见
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        final PageScrollView sv = new PageScrollView(a);
        sv.addView(box);
        int h = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5f);
        wrap.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h));
        LinearLayout nav = new LinearLayout(a);
        nav.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView pg = Ui.text(a, "", 14, false);
        pg.setGravity(android.view.Gravity.CENTER);
        final Runnable label = new Runnable() { @Override public void run() {
            int n = sv.pages();
            pg.setText(n > 1 ? sv.currentPage() + " / " + n + " 页" : "");
        } };
        nav.addView(Ui.button(a, "上一页", 15, false, new View.OnClickListener() { @Override public void onClick(View v) { if (sv.page(-1)) sv.firePage(); } }));
        nav.addView(pg, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(Ui.button(a, "下一页", 15, false, new View.OnClickListener() { @Override public void onClick(View v) { if (sv.page(1)) sv.firePage(); } }));
        wrap.addView(nav);
        sv.setOnPage(label);
        sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() { label.run(); }
        });
        new VeraDialog.Builder(a)
            .title("新版本 " + i.latest)
            .message((i.current == null ? "这次更新的内容：" : "现在是 " + i.current + "。这次更新的内容：") + (BackupActivity.off() ? "\n（更新前备份已关闭，更新出问题时无法恢复）" : ""))
            .view(wrap)
            .negative("以后再说", new Runnable() { @Override public void run() {
                Store.prefs(a, "home").edit().putString("sysupdate_later", i.latest).apply();
            } })
            .positive("更新", new Runnable() { @Override public void run() {
                if (!BindGate.ensureBound(a, "系统更新", BindGate.OTA_REASON_TEXT, new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
                        HostLink.cmd("fullupdate");
                        progress(a, i.latest);
                    }
                })) return;
                HostLink.cmd("fullupdate");
                progress(a, i.latest);
            } }).show();
    }

    /** 系统更新正在进行（宿主的 ctl/update.status 一小时内有进展）。 */
    static boolean running() {
        Map<String, String> m = TaskCards.read(TaskCards.UPDATE);
        return TaskCards.active(m.get("state")) && TaskCards.now() - TaskCards.num(m, "time", 0) < 3600;
    }

    /**
     * 点了“更新”以后马上给看得见的进度（用户诊断，KT6：以前只弹一句“几秒后会退出”，可安装器要先在后台
     * 下载、核对，慢网下二三十分钟 Android 都不退出，看上去就是“点了更新一直没反应”，有人就去重装了）。
     * 对话框里放的就是“设备与关于”页那张进度卡片（跟着 ctl/update.status 走，出错时写明代码和原因）。
     */
    static void progress(final Activity a, String latest) {
        if (a.isFinishing()) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(a, (latest == null ? "系统更新" : "更新到 " + latest) + "：先在后台下载和核对（网速慢时要十几分钟，这期间可以照常用），"
            + "都好了才退出 Android 安装，装完自动回来。关掉这个窗口不影响更新，进度也可以在 设置 → 设备与关于 里看。", 15, false, 0));
        box.addView(new TaskCards.Update(a, false), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new VeraDialog.Builder(a).title("正在更新").view(box).positive("在后台继续", null).show();
    }

    private static TextView text(Activity a, String s, float sp, boolean bold, int topDp) {
        TextView t = Ui.text(a, s, sp, bold);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, Ui.dp(a, topDp), 0, 0);
        return t;
    }

    // ------------------------------------------------------------------ 设置：自动更新（宿主的 $VD/autoupdate）

    /** 现在的自动更新方式（MODE_V 里的下标）；读不到按“自动安装”（宿主默认）。 */
    static int mode() {
        String v = st("autoupdate");
        if ("0".equals(v)) return 2;
        if ("notify".equals(v)) return 1;
        return 0;
    }

    /** 选自动更新方式：交给宿主（管理请求 autoupdate，只认 VeraHome 自己的请求）。done 在主线程跑，参数是结果说明。 */
    static void setMode(final Activity a, final int idx, final View.OnClickListener done) {
        new Thread(new Runnable() { @Override public void run() {
            byte[] r = HostReq.run(a, "autoupdate", MODE_V[idx], null, 15000);
            final boolean ok = r != null && new String(r).contains("ok=1");
            new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
                VeraToast.show(a.getApplicationContext(), ok ? "自动更新：" + MODE_L[idx] : "没能改自动更新设置（宿主没有回应）");
                HostLink.cmd("status");
                if (done != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() { if (!a.isFinishing()) done.onClick(null); }
                    }, 1200);
                }
            } });
        } }, "sysupdate-mode").start();
    }
}
