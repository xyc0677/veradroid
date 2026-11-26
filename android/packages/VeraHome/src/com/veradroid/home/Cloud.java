// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Map;

/** Generic HTTP download used by the transfer page's "paste a link" box. No cloud accounts. */
final class Cloud {
    private Cloud() { }

    interface Progress { void update(long done, long total); }

    private static HttpURLConnection open(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(20000);
        h.setReadTimeout(40000);
        h.setRequestMethod("GET");
        h.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1.1) VeraDroid");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) h.setRequestProperty(e.getKey(), e.getValue());
        return h;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while (in != null && (n = in.read(b)) > 0) { bo.write(b, 0, n); if (bo.size() > 4 * 1024 * 1024) break; }
        return bo.toString("UTF-8");
    }

    private static boolean isBlockedHost(String h) {
        if ("localhost".equals(h) || h.endsWith(".localhost") || "0.0.0.0".equals(h)
                || "10.0.2.2".equals(h) || "::1".equals(h) || "::".equals(h)
                || "0:0:0:0:0:0:0:1".equals(h) || "0:0:0:0:0:0:0:0".equals(h)) return true;
        if (h.startsWith("127.") || h.startsWith("169.254.") || h.startsWith("fe80:")
                || h.startsWith("::ffff:127.") || h.startsWith("::ffff:169.254.")
                || "::ffff:10.0.2.2".equals(h) || "::ffff:0.0.0.0".equals(h)) return true;
        return false;
    }

    /** 仅允许 http/https，并拦截回环/链路本地/模拟器宿主地址（防 SSRF）。 */
    private static URL validateUrl(String raw) throws Exception {
        URL u = new URL(raw);
        String scheme = u.getProtocol() == null ? "" : u.getProtocol().toLowerCase(java.util.Locale.US);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new Exception("只支持 http / https 链接");
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) throw new Exception("链接地址无效");
        String h = host.toLowerCase(java.util.Locale.US);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        int pct = h.indexOf('%');
        if (pct >= 0) h = h.substring(0, pct);
        if (isBlockedHost(h)) throw new Exception("不允许访问本机或链路本地地址");
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(h);
            for (java.net.InetAddress a : addrs) {
                if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || "10.0.2.2".equals(a.getHostAddress())) {
                    throw new Exception("不允许访问本机或链路本地地址");
                }
            }
        } catch (java.net.UnknownHostException ignored) { }
        return u;
    }

    /** Download url into dir (redirects allowed; never forwards auth headers to a different host). */
    static File download(String url, Map<String, String> headers, File dir, String nameHint, Progress p) throws Exception {
        URL curUrl = validateUrl(url);
        String cur = curUrl.toString();
        Map<String, String> hdr = headers;
        String firstHost = curUrl.getHost();
        for (int hop = 0; hop < 10; hop++) {
            HttpURLConnection h = open(cur, hdr);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                String next = new URL(new URL(cur), h.getHeaderField("Location")).toString();
                h.disconnect();
                URL nextUrl = validateUrl(next);
                if (!nextUrl.getHost().equals(firstHost)) hdr = null;
                cur = nextUrl.toString();
                continue;
            }
            if (code != 200) {
                String b = readAll(h.getErrorStream());
                h.disconnect();
                throw new Exception("HTTP " + code + (b.length() > 0 ? " " + b.substring(0, Math.min(120, b.length())) : ""));
            }
            String type = h.getContentType() == null ? "" : h.getContentType().toLowerCase();
            String name = nameFrom(h.getHeaderField("Content-Disposition"), nameHint, cur);
            if (type.startsWith("text/html") && !name.toLowerCase().endsWith(".html")) {
                h.disconnect();
                throw new Exception("对方返回的是网页，不是文件（链接可能没有公开，或需要登录）");
            }
            long total = -1;
            String cl = h.getHeaderField("Content-Length");
            if (cl != null) try { total = Long.parseLong(cl.trim()); } catch (Exception ignored) { }
            long usable = dir.getUsableSpace();
            if (usable < 64L * 1024 * 1024 || (total > 0 && total > usable - 64L * 1024 * 1024)) {
                h.disconnect();
                throw new Exception("存储空间不够：剩余 " + (usable >> 20) + " MB（至少需预留 64 MB）");
            }
            File out = Transfer.uniqueFile(dir, name);
            InputStream in = new BufferedInputStream(h.getInputStream(), 65536);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            long done = 0, nextCheck = 1L << 20;
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n); done += n;
                    if (done >= nextCheck) {
                        nextCheck = done + (1L << 20);
                        if (dir.getUsableSpace() < 64L * 1024 * 1024) throw new Exception("存储空间不够（剩余不足 64 MB）");
                    }
                    if (p != null) p.update(done, total);
                }
            } catch (Exception e) {
                try { fo.close(); } catch (Exception ignored) { }
                out.delete();   // 下载中断：不留半个文件
                throw e;
            } finally { fo.close(); in.close(); h.disconnect(); }
            return out;
        }
        throw new Exception("重定向太多次");
    }

    /** Decode %XX UTF-8 sequences without turning literal '+' into spaces (safe for URL paths and RFC 5987 filename*). */
    private static String pctDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String nameFrom(String disposition, String hint, String url) {
        try {
            if (disposition != null) {
                int i = disposition.toLowerCase().indexOf("filename*=");
                if (i >= 0) {
                    String v = disposition.substring(i + 10).split(";")[0].trim();
                    int q = v.indexOf("''");
                    if (q >= 0) v = v.substring(q + 2);
                    return Transfer.cleanName(pctDecode(v.replace("\"", "")));
                }
                i = disposition.toLowerCase().indexOf("filename=");
                if (i >= 0) {
                    String v = disposition.substring(i + 9).split(";")[0].trim().replace("\"", "");
                    return Transfer.cleanName(new String(v.getBytes("ISO-8859-1"), "UTF-8"));
                }
            }
        } catch (Exception ignored) { }
        if (hint != null && hint.length() > 0) return Transfer.cleanName(hint);
        try {
            String path = new URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (last.length() > 0) return Transfer.cleanName(pctDecode(last));
        } catch (Exception ignored) { }
        return "download-" + System.currentTimeMillis();
    }
}
