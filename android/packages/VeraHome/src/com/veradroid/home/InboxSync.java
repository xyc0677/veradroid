// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;

/** Background Send-to-Kindle pickup: download pending mail attachments into Books/. */
final class InboxSync {
    static final String BASE = "https://veradroid.wenjin.jp";
    private static final long EVERY = 15 * 60 * 1000L;
    private static final long RETRY = 90 * 1000L;
    private static final Object SYNC_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean SYNCING = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long lastFailAt;

    interface Result { void done(int count, String error); }

    private InboxSync() { }

    static void maybe(final Context c) {
        final Context appCtx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (Token.get(appCtx).length() == 0) return;
        final SharedPreferences p = Store.prefs(appCtx, "home");
        long now = System.currentTimeMillis();
        if (now - p.getLong("inbox_checked", 0) < EVERY) return;
        if (now - lastFailAt < RETRY) return;
        if (!SYNCING.compareAndSet(false, true)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    boolean ok;
                    synchronized (SYNC_LOCK) { ok = syncLocked(appCtx, null); }
                    if (ok) p.edit().putLong("inbox_checked", System.currentTimeMillis()).commit();
                    else lastFailAt = System.currentTimeMillis();
                } finally {
                    SYNCING.set(false);
                }
            }
        }, "InboxSync").start();
    }

    static void sync(Context c, Result cb) {
        synchronized (SYNC_LOCK) {
            syncLocked(c, cb);
        }
    }

    private static boolean syncLocked(Context c, Result cb) {
        String token = Token.get(c);
        String device = Update.deviceId(c);
        if (token.length() == 0) { if (cb != null) cb.done(0, "还没有绑定账号"); return false; }
        int n = 0;
        try {
            String base = BASE + "/api/inbox?device_id=" + enc(device) + "&token=" + enc(token);
            JSONObject list = get(base);
            JSONArray files = list.optJSONArray("files");
            if (files == null) { if (cb != null) cb.done(0, null); return true; }
            File dir = new File(Transfer.BOOKS);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("不能创建 Books 目录");
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                int id = f.optInt("id", 0);
                if (id > 0 && isSeen(c, id)) {
                    ack(device, token, id);
                    continue;
                }
                String name = safe(f.optString("filename", "book.epub"));
                File out = unique(new File(dir, name));
                String dl = f.optString("download", "");
                String url = dl + (dl.indexOf('?') >= 0 ? "&" : "?") + "device_id=" + enc(device) + "&token=" + enc(token);
                download(url, out, f.optString("sha256", ""), f.optLong("size", -1));
                if (id > 0) markSeen(c, id);
                n++;
                ack(device, token, id);
            }
            if (cb != null) cb.done(n, null);
            return true;
        } catch (Exception e) {
            android.util.Log.w("InboxSync", "sync failed: " + e.getMessage());
            if (cb != null) cb.done(n, e.getMessage());
            return false;
        } finally {
            if (n > 0) try { Lib.scan(c, 0); } catch (Throwable ignored) { }
        }
    }

    private static boolean isAllowedUrl(URL u) {
        if (u == null || !"https".equalsIgnoreCase(u.getProtocol())) return false;
        String host = u.getHost();
        return "veradroid.wenjin.jp".equalsIgnoreCase(host) || "dl.wenjin.jp".equalsIgnoreCase(host);
    }

    private static void download(String url, File out, String sha, long size) throws Exception {
        if (sha == null || !sha.matches("[0-9a-fA-F]{64}")) throw new Exception("缺少有效的 SHA-256 校验值");
        File dir = out.getParentFile();
        long usable = dir.getUsableSpace();
        if (usable < 64L * 1024 * 1024 || (size > 0 && size > usable - 64L * 1024 * 1024))
            throw new Exception("存储空间不够（剩余不足 64 MB）");
        HttpURLConnection h = null;
        InputStream in = null;
        FileOutputStream fo = null;
        File part = new File(dir, "." + out.getName() + ".part");
        boolean ok = false;
        try {
            URL cur = new URL(url);
            int code = -1;
            for (int hop = 0; hop < 4; hop++) {
                if (!isAllowedUrl(cur)) throw new Exception("拒绝非受信任下载域名");
                h = (HttpURLConnection) cur.openConnection();
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(15000); h.setReadTimeout(180000);
                h.setRequestProperty("User-Agent", "VeraDroid/Inbox");
                code = h.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = h.getHeaderField("Location");
                    h.disconnect(); h = null;
                    if (loc == null || loc.length() == 0) throw new Exception("收件下载重定向无效");
                    cur = new URL(cur, loc);
                    continue;
                }
                break;
            }
            if (code != 200 || h == null) throw new Exception("收件下载失败 HTTP " + code);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new BufferedInputStream(h.getInputStream(), 65536);
            fo = new FileOutputStream(part);
            byte[] b = new byte[65536]; int n; long done = 0, nextCheck = 1024 * 1024;
            while ((n = in.read(b)) > 0) {
                fo.write(b, 0, n); md.update(b, 0, n); done += n;
                if (done >= nextCheck) {
                    nextCheck = done + 1024 * 1024;
                    if (dir.getUsableSpace() < 64L * 1024 * 1024) throw new Exception("存储空间不够（剩余不足 64 MB）");
                }
            }
            fo.close(); fo = null;
            if (size > 0 && done != size) throw new Exception("文件大小不对");
            if (!hex(md.digest()).equalsIgnoreCase(sha)) throw new Exception("SHA-256 校验失败");
            part.setReadable(true, false);
            if (!part.renameTo(out)) throw new Exception("保存收件文件失败");
            out.setReadable(true, false);
            ok = true;
        } finally {
            if (fo != null) try { fo.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            if (h != null) try { h.disconnect(); } catch (Exception ignored) { }
            if (!ok) part.delete();
        }
    }

    private static boolean isSeen(Context c, int id) {
        String s = Store.prefs(c, "home").getString("inbox_seen", "");
        if (s.length() == 0) return false;
        String needle = "," + id + ",";
        return ("," + s + ",").contains(needle);
    }

    private static void markSeen(Context c, int id) {
        SharedPreferences p = Store.prefs(c, "home");
        String cur = p.getString("inbox_seen", "");
        String[] parts = cur.length() == 0 ? new String[0] : cur.split(",");
        StringBuilder sb = new StringBuilder();
        int keep = Math.max(0, parts.length - 199);
        for (int i = keep; i < parts.length; i++) {
            if (parts[i].length() == 0 || parts[i].equals(String.valueOf(id))) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(parts[i]);
        }
        if (sb.length() > 0) sb.append(',');
        sb.append(id);
        p.edit().putString("inbox_seen", sb.toString()).commit();
    }

    private static void ack(String device, String token, int id) throws Exception {
        HttpURLConnection h = null;
        OutputStream o = null;
        try {
            byte[] body = ("device_id=" + enc(device) + "&token=" + enc(token)).getBytes("UTF-8");
            h = (HttpURLConnection) new URL(BASE + "/api/inbox/" + id + "/ack").openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(10000); h.setReadTimeout(10000);
            h.setDoOutput(true); h.setRequestMethod("POST");
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            o = h.getOutputStream(); o.write(body); o.close(); o = null;
            int code = h.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("收件确认失败 HTTP " + code);
        } finally {
            if (o != null) try { o.close(); } catch (Exception ignored) { }
            if (h != null) try { h.disconnect(); } catch (Exception ignored) { }
        }
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection h = null;
        InputStream in = null;
        int code;
        String body;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(15000); h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "VeraDroid/Inbox");
            code = h.getResponseCode();
            in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
            body = bo.toString("UTF-8");
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            if (h != null) try { h.disconnect(); } catch (Exception ignored) { }
        }
        if (code >= 400) {
            String msg = "HTTP " + code;
            try {
                String e = new JSONObject(body).optString("error", "");
                if (e.length() > 0) msg = e;
            } catch (Exception ignored) { }
            throw new Exception(msg);
        }
        try {
            return new JSONObject(body);
        } catch (Exception je) {
            throw new Exception("服务器返回格式异常");
        }
    }

    private static File unique(File f) {
        if (!f.exists()) return f;
        String n = f.getName(); int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 2; ; i++) { File x = new File(f.getParentFile(), base + "-" + i + ext); if (!x.exists()) return x; }
    }

    private static String safe(String s) {
        String n = (s == null ? "" : s).replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]", "_");
        if (n.length() > 100) n = n.substring(n.length() - 100);
        while (n.startsWith(".")) n = n.substring(1);
        if (n.length() == 0) n = "book.epub";
        return n;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
}
