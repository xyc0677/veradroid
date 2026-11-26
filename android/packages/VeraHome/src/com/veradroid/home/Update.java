// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;

/**
 * Small OTA client: asks veradroid.wenjin.jp for a newer VeraHome APK, checks SHA-256 and asks the host to
 * install it.  The host has root in the container, so the update does not need a package-installer dialog.
 * The APK is accepted only if it has the same platform signature (PackageManager enforces that).
 */
final class Update {
    private static final String BASE = "https://veradroid.wenjin.jp";
    private static final long EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final long RETRY_MS = 90 * 1000L;
    private static final Object CHECK_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean CHECKING = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long lastFailAt;

    private Update() { }

    static void maybe(final Context c) {
        final Context appCtx = c.getApplicationContext();
        final SharedPreferences p = Store.prefs(appCtx, "home");
        if (!p.getBoolean("autoupdate", true)) return;
        final long now = System.currentTimeMillis();
        if (now - p.getLong("update_checked", 0) < EVERY_MS) return;
        if (now - lastFailAt < RETRY_MS) return;
        if (!CHECKING.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    synchronized (CHECK_LOCK) { check(appCtx); }
                    p.edit().putLong("update_checked", System.currentTimeMillis()).commit();
                } catch (Throwable e) {
                    lastFailAt = System.currentTimeMillis();
                    android.util.Log.e("VeraUpdate", "check failed", e);
                } finally {
                    CHECKING.set(false);
                }
            }
        }, "VeraUpdate").start();
    }

    private static String check(Context c) throws Exception {
        int versionCode = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        String versionName = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        String device = deviceId(c);
        String token = Token.get(c);
        String url = BASE + "/api/ota/check?package=" + enc("com.veradroid.home")
            + "&version_code=" + versionCode
            + "&version_name=" + enc(versionName)
            + "&device_id=" + enc(device)
            + "&model=" + enc(model(c))
            + (token.length() > 0 ? "&token=" + enc(token) : "");
        JSONObject j = new JSONObject(get(url, c));
        if (!j.optBoolean("update", false)) return "已是最新版本";
        long size = j.optLong("size", -1);
        String sha = j.optString("sha256", "");
        String apk = j.optString("url", "");
        android.util.Log.d("VeraUpdate", "download size=" + size);
        if (apk.length() == 0 || sha.length() != 64) return "更新信息不完整";

        File dir = Transfer.tmpDir();
        if (!dir.isDirectory() && !dir.mkdirs()) { android.util.Log.e("VeraUpdate", "no dir " + dir); return "无法创建下载目录"; }
        File out = new File(dir, "ota.apk");
        File part = new File(dir, "ota.apk.part");
        part.delete();
        out.delete();   // a leftover from an older install can belong to another uid: it cannot be overwritten, only replaced
        long usable = dir.getUsableSpace();
        if (usable < 64L * 1024 * 1024 || (size > 0 && size > usable - 64L * 1024 * 1024)) {
            return "存储空间不够（剩余不足 64 MB）";
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        HttpURLConnection h = (HttpURLConnection) new URL(apk).openConnection();
        InputStream in = null;
        FileOutputStream fo = null;
        long done = 0, lastBeat = 0;
        boolean dlOk = false;
        try {
            h.setConnectTimeout(20000); h.setReadTimeout(120000);
            h.setRequestProperty("User-Agent", "VeraDroid/OTA");
            int dlCode = h.getResponseCode();
            if (dlCode == 401 || dlCode == 403) {
                String ec = "", em = BindGate.OTA_REASON_TEXT;
                try {
                    InputStream es = h.getErrorStream();
                    if (es != null) {
                        java.io.ByteArrayOutputStream ebo = new java.io.ByteArrayOutputStream();
                        byte[] eb = new byte[4096]; int en;
                        while ((en = es.read(eb)) > 0) ebo.write(eb, 0, en);
                        es.close();
                        JSONObject ej = new JSONObject(ebo.toString("UTF-8"));
                        ec = ej.optString("code", "");
                        em = ej.optString("error", em);
                    }
                } catch (Throwable ignored) { }
                if ("sig_expired".equals(ec)) throw new Exception("更新链接已过期，请再点一次应用更新");
                if ("ip_blocked".equals(ec)) throw new Exception(em);
                if ("bind_required".equals(ec) || "token_invalid".equals(ec) || (dlCode == 401 && ec.length() == 0)) {
                    Token.clear(c);
                    throw new BindGate.BindRequiredException(em);
                }
                throw new Exception(em);
            }
            if (dlCode >= 400) throw new Exception("HTTP " + dlCode);
            in = new BufferedInputStream(h.getInputStream(), 65536);
            fo = new FileOutputStream(part);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                long tnow = System.currentTimeMillis();
                if (tnow - lastBeat >= 15000) { lastBeat = tnow; HostLink.cmd("poke"); }
            }
            dlOk = true;
        } finally {
            if (fo != null) try { fo.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
            if (!dlOk) { part.delete(); out.delete(); }
        }
        if ((size > 0 && done != size) || !hex(md.digest()).equalsIgnoreCase(sha) || !part.renameTo(out)) {
            part.delete();
            out.delete();
            return "下载校验失败";
        }
        android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(out.getAbsolutePath(), 0);
        if (pi == null || !"com.veradroid.home".equals(pi.packageName)) {
            android.util.Log.e("VeraUpdate", "unexpected package: " + (pi == null ? "null" : pi.packageName));
            out.delete();
            return "安装包校验失败";
        }
        out.setReadable(true, false);
        android.util.Log.d("VeraUpdate", "downloaded " + out.length() + " installing");
        HostLink.cmd("install " + out.getAbsolutePath());   // absolute path: the staging folder is not always .upload
        return "已下载新版本，正在安装…";
    }

    /** Hardware model from the host (pw6/pw5/kt5...); Android's Build.MODEL is generic. */
    static String model(Context c) {
        for (String p : new String[] { "/dev/kfb/model", "/mnt/base-us/system/veradroid/model", "/mnt/base-us/veradroid/model" }) {
            try {
                File f = new File(p);
                if (!f.isFile()) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String s;
                try { s = r.readLine(); } finally { r.close(); }
                if (s != null) {
                    s = s.trim();
                    if (s.matches("[A-Za-z0-9_-]{2,32}")) return s;
                }
            } catch (Throwable ignored) { }
        }
        return Build.MODEL;
    }

    static String deviceId(Context c) {
        // Prefer the host-persisted id in /mnt/base-us/veradroid/device-id.
        // It survives a factory reset of /data; Android ID does not.
        for (String path : new String[] { "/data/media/0/.veradroid/device-id", "/mnt/base-us/veradroid-device-id", "/mnt/us/veradroid-device-id", "/mnt/base-us/veradroid/device-id", "/mnt/us/veradroid/device-id" }) {
            try {
                File f = new File(path);
                if (!f.isFile()) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String s;
                try { s = r.readLine(); } finally { r.close(); }
                if (s != null) {
                    s = s.trim();
                    if (s.matches("[A-Za-z0-9_-]{6,64}")) return s;
                }
            } catch (Throwable ignored) { }
        }
        String device = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (device == null || device.length() < 6) device = "unknown-" + Build.SERIAL;
        return device;
    }

    /** Check for a VeraHome APK update immediately (manual "check now"). */
    static void now(final Context c) {
        if (c instanceof android.app.Activity) {
            final android.app.Activity a = (android.app.Activity) c;
            if (!BindGate.ensureBound(a, "系统 OTA 更新", BindGate.OTA_REASON_TEXT, new Runnable() {
                @Override public void run() { now(c); }
            })) return;
        }
        if (!CHECKING.compareAndSet(false, true)) {
            VeraToast.show(c, "正在检查更新，请稍候…");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    synchronized (CHECK_LOCK) { msg = check(c); }
                } catch (final BindGate.BindRequiredException be) {
                    if (c instanceof android.app.Activity) {
                        final android.app.Activity a = (android.app.Activity) c;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                if (!a.isFinishing()) BindGate.showBindDialog(a, "系统 OTA 更新", be.getMessage(), new Runnable() {
                                    @Override public void run() { now(c); }
                                });
                            }
                        });
                        return;
                    }
                    msg = be.getMessage();
                } catch (Throwable e) {
                    android.util.Log.e("VeraUpdate", "manual check failed", e);
                    String em = e.getMessage();
                    msg = (em != null && (em.contains("过期") || em.contains("受限") || em.contains("频繁"))) ? em : "检查更新失败，请检查网络";
                } finally {
                    CHECKING.set(false);
                }
                final String fm = msg;
                final Context appCtx = c.getApplicationContext();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { VeraToast.show(appCtx, fm); }
                });
            }
        }, "VeraUpdateNow").start();
    }

    interface BindResult { void done(boolean ok, String message); }

    /** Bind this machine to the account that generated a 6-digit code on the website. */
    static void bind(final Context c, final String code, final BindResult cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                String msg = "网络错误";
                boolean ok = false;
                try {
                    JSONObject body = new JSONObject();
                    body.put("code", code);
                    body.put("device_id", deviceId(c));
                    body.put("model", model(c));
                    body.put("version_code", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode);
                    body.put("version_name", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
                    JSONObject j = postJson(BASE + "/api/device/bind", body.toString());
                    ok = j.optBoolean("ok", false);
                    if (ok) {
                        Token.put(c, j.optString("token", ""));
                        msg = "已绑定这台 Kindle";
                    } else {
                        msg = j.optString("error", "绑定失败");
                    }
                } catch (Throwable e) {
                    msg = String.valueOf(e.getMessage());
                }
                final boolean fok = ok; final String fm = msg;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { cb.done(fok, fm); }
                });
            }
        }, "VeraBind").start();
    }

    private static JSONObject postJson(String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        java.io.OutputStream o = null;
        InputStream in = null;
        int code;
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try {
            h.setConnectTimeout(15000); h.setReadTimeout(30000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            h.setRequestProperty("User-Agent", "VeraDroid/Bind");
            o = h.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            o = null;
            code = h.getResponseCode();
            in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            byte[] b = new byte[4096]; int n;
            while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
        } finally {
            if (o != null) try { o.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
        }
        String raw = bo.toString("UTF-8");
        JSONObject j;
        try {
            j = new JSONObject(raw);
        } catch (org.json.JSONException je) {
            if (code >= 400) {
                j = new JSONObject();
                j.put("ok", false);
                j.put("error", "服务器暂时不可用（HTTP " + code + "），请稍后再试");
                return j;
            }
            throw new Exception("服务器返回异常，请稍后再试");
        }
        if (code >= 400 && j.optString("error", "").length() == 0) j.put("error", "HTTP " + code);
        return j;
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }

    private static String get(String url, Context c) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        InputStream in = null;
        try {
            h.setConnectTimeout(15000); h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "VeraDroid/OTA");
            int code = h.getResponseCode();
            in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
            String raw = bo.toString("UTF-8");
            if (code == 401 || code == 403) {
                JSONObject j = null;
                try { j = new JSONObject(raw); } catch (Exception ignored) { }
                String ec = j != null ? j.optString("code", "") : "";
                String em = j != null ? j.optString("error", BindGate.OTA_REASON_TEXT) : BindGate.OTA_REASON_TEXT;
                if ("ip_blocked".equals(ec)) throw new Exception(em);
                if ("bind_required".equals(ec) || "token_invalid".equals(ec) || (code == 401 && j != null)) {
                    Token.clear(c);
                    throw new BindGate.BindRequiredException(em);
                }
                throw new Exception(em);
            }
            if (code >= 400) throw new Exception("HTTP " + code);
            return raw;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
