package com.veradroid.home;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast downloads for the store (docs/download-speed.md): several Range connections at once and mirrors.
 *  - Mirrors: base URLs from the server ("mirrors" in /api/store/apps; missing = {@link #DEF_MIRRORS}, [] = none).
 *    A file on a mirror is base + the path of the main-site URL (e.g. https://dl.wenjin.jp/api/store/download/15).
 *    Only the main-site URL carries the device id / token; mirrors get the bare path.
 *  - Before each try every candidate gets a 256 KB Range request; the fastest one that answers 206 wins
 *    (the main site also counts with a plain 200, then without segments).
 *  - Files of 8 MB or more are split into {@link #SEGS} pieces, each on its own connection, kept in out.seg/N and
 *    resumed from where they stopped on the next try; the pieces are joined in order when all are complete.
 *  - The caller always checks SHA-256; on a mismatch it calls again with mirrors off.
 *  - Slow source (2026-11-21 field report: "einkbro 11%, 8 KB/s, 镜像"; measured: direct connections to dl.wenjin.jp from
 *    China in the evening had a median of 32 KB/s, via Cloudflare 142 KB/s): the 256 KB probe can pick a source that then
 *    crawls. After {@link #WARM_MS} of a try, if the last {@link #WINDOW_MS} averaged under {@link #SLOW_BPS} and another
 *    candidate is left, the try stops with {@link Slow}; the caller passes the same {@code slow} set to the next try, which
 *    then skips that source and resumes (Range) from the bytes already on the disk. The last remaining source is never dropped.
 */
final class Dl {
    static final String[] DEF_MIRRORS = {"https://dl.wenjin.jp"};
    static final int SEGS = 4;                 // dl.wenjin.jp allows 8 connections per IP; stay well under it
    static final long SEG_MIN = 2L << 20;      // was 8 MB: a 4 MB app (EinkBro) went over one slow connection
    static volatile long SLOW_BPS = 40L << 10;  // under this (bytes/s, over WINDOW_MS) another source is tried
    static volatile long WARM_MS = 15000, WINDOW_MS = 12000;

    /** This try was stopped because src was too slow while another source is left; resume with src in the slow set. */
    static final class Slow extends java.io.IOException {
        final String src; final long bps;
        Slow(String src, long bps) { super("下载太慢（" + (bps >> 10) + " KB/s），换一个下载源"); this.src = src; this.bps = bps; }
    }

    /** Throughput over a sliding window; slow() once warm and the window average is under SLOW_BPS. */
    static final class Meter {
        final long t0 = System.currentTimeMillis();
        final long[] ts = new long[64], bs = new long[64]; int n;
        volatile boolean cancel;
        synchronized void sample(long total) { long now = System.currentTimeMillis(); ts[n % 64] = now; bs[n % 64] = total; n++; }
        /** Bytes/s over the last WINDOW_MS, or -1 while still warming up / not enough samples. */
        synchronized long rate() {
            if (n < 2) return -1;
            long now = ts[(n - 1) % 64];
            if (now - t0 < WARM_MS) return -1;
            int i = n - 1;
            while (i > 0 && i > n - 64 && now - ts[(i - 1) % 64] <= WINDOW_MS) i--;
            long dt = now - ts[i % 64];
            if (dt < WINDOW_MS / 2) return -1;
            return (bs[(n - 1) % 64] - bs[i % 64]) * 1000 / dt;
        }
        boolean slow() { long r = rate(); return r >= 0 && r < SLOW_BPS; }
    }

    interface Progress { void on(long done, long total, boolean mirror); }

    private static boolean sameRootDomain(String a, String b) {
        String ha = host(a).toLowerCase(java.util.Locale.US);
        String hb = host(b).toLowerCase(java.util.Locale.US);
        if (ha.isEmpty() || hb.isEmpty()) return false;
        if (ha.equals(hb)) return true;
        String[] pa = ha.split("\\."), pb = hb.split("\\.");
        if (pa.length < 2 || pb.length < 2) return false;
        String ta = pa[pa.length - 1], tb = pb[pb.length - 1];
        if (ta.matches("\\d+") || tb.matches("\\d+")) return false;
        return ta.equals(tb) && pa[pa.length - 2].equals(pb[pb.length - 2]);
    }

    /** Candidate URLs: mirrors in order, the main site (with its query) last. */
    static List<String> candidates(String origin, String query, List<String> mirrors) {
        List<String> c = new ArrayList<String>();
        String path = origin.replaceFirst("^https?://[^/]+/", "");
        String signedQ = null;
        int q = path.indexOf('?');
        if (q >= 0) {
            String qs = path.substring(q);
            // Same-domain direct origin (e.g. dl.wenjin.jp for veradroid.wenjin.jp) validates the same short-lived
            // HMAC signature; never forward raw token= or send queries to third-party mirror domains.
            if (qs.contains("sig=") && !qs.contains("token=")) signedQ = qs;
            path = path.substring(0, q);
        }
        if (mirrors != null) for (String m : mirrors) {
            if (m == null || !m.startsWith("https://")) continue;
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (m.contains("{name}")) {
                c.add(m.replace("{name}", name));
            } else {
                String u = m.replaceAll("/+$", "") + "/" + path;
                if (signedQ != null && sameRootDomain(origin, m)) u += signedQ;
                c.add(u);
            }
        }
        c.add(origin + (query == null || query.isEmpty() ? "" : (origin.contains("?") ? "&" : "?") + query));
        return c;
    }

    /** {url, segments}: segments is SEGS (Range works), 1 (no Range) or 0 (nothing answered: try the main site anyway). */
    static Object[] pick(List<String> cands) { return pick(cands, null); }

    /** Candidates not in slow (all of them when every one is in slow; never mutates caller's slow set). */
    static List<String> usable(List<String> cands, java.util.Set<String> slow) {
        List<String> u = new ArrayList<String>();
        for (String c : cands) if (slow == null || !slow.contains(c)) u.add(c);
        if (u.isEmpty()) u.addAll(cands);
        return u;
    }

    static Object[] pick(List<String> all, java.util.Set<String> slow) {
        String best = null; long bestSpeed = -1; int segs = 0; int reachable = 0;
        String main = all.get(all.size() - 1);
        List<String> cands = usable(all, slow);
        List<String> failed = new ArrayList<String>();
        for (String u : cands) {
            HttpURLConnection h = null;
            try {
                long t0 = System.nanoTime();
                h = (HttpURLConnection) new URL(u).openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(10000);
                h.setRequestProperty("User-Agent", "VeraDroid/Store");
                h.setRequestProperty("Range", "bytes=0-262143");
                int code = h.getResponseCode();
                if (code != 206 && !(code == 200 && u.equals(main))) {
                    android.util.Log.i("VeraStore", "probe " + host(u) + " HTTP " + code);
                    failed.add(u);
                    continue;
                }
                InputStream in = h.getInputStream();
                byte[] b = new byte[65536]; long n = 0; int r;
                while (n < 262144 && (r = in.read(b)) > 0) n += r;
                in.close();
                long sp = n * 1000000000L / Math.max(1, System.nanoTime() - t0);
                android.util.Log.i("VeraStore", "probe " + host(u) + " HTTP " + code + " " + (sp >> 10) + " KB/s");
                reachable++;
                if (sp > bestSpeed) { bestSpeed = sp; best = u; segs = code == 206 ? SEGS : 1; }
            } catch (Exception e) {
                android.util.Log.i("VeraStore", "probe " + host(u) + " failed: " + e);
                failed.add(u);
            } finally { if (h != null) h.disconnect(); }
        }
        if (slow != null && best != null) {
            for (String u : failed) slow.add(u);
        }
        if (best == null) return new Object[] {main, 0, 0};
        return new Object[] {best, segs, reachable};
    }

    static String host(String u) { try { return new URL(u).getHost(); } catch (Exception e) { return u; } }

    /** Bytes already on the disk for out (the whole file plus any pieces). */
    static long have(File out) {
        long n = out.isFile() ? out.length() : 0;
        File[] s = new File(out.getPath() + ".seg").listFiles();
        if (s != null) for (File f : s) if (f.getName().matches("\\d+")) n += f.length();
        return n;
    }

    static void clear(File out) {
        out.delete();
        File d = new File(out.getPath() + ".seg");
        File[] s = d.listFiles();
        if (s != null) for (File f : s) f.delete();
        d.delete();
    }

    /** One try: pick a source, then download with one connection (resume) or with segments. Throws on failure; the
     *  caller retries (pieces and partial files stay for the next try). */
    static void fetchOnce(List<String> cands, final File out, final long size, final Progress p) throws Exception {
        fetchOnce(cands, out, size, p, null);
    }

    /** As above; slow (may be null) collects sources that were too slow: they are skipped by the next try. */
    static void fetchOnce(List<String> cands, final File out, final long size, final Progress p, java.util.Set<String> slow) throws Exception {
        Object[] pk = pick(cands, slow);
        final String src = (String) pk[0];
        int segs = (Integer) pk[1];
        int reachable = pk.length > 2 ? (Integer) pk[2] : usable(cands, slow).size();
        final boolean mirror = !src.equals(cands.get(cands.size() - 1));
        // another reachable source left to switch to? Only then a slow source is given up.
        final Meter meter = (reachable > 1 && usable(cands, slow).size() > 1) ? new Meter() : null;
        try { fetchFrom(src, segs, mirror, out, size, p, meter); }
        catch (Slow e) { if (slow != null) slow.add(src); throw e; }
    }

    private static void fetchFrom(final String src, int segs, final boolean mirror, final File out, final long size, final Progress p, final Meter meter) throws Exception {
        File dir = new File(out.getPath() + ".seg");
        File plan = new File(dir, "plan");
        int planned = 0;
        if (plan.isFile()) {
            boolean valid = false;
            if (size > 0) try {
                String[] parts = new String(read(plan), "UTF-8").trim().split(" ");
                if (parts.length >= 2 && Long.parseLong(parts[0]) == size) {
                    int pn = Integer.parseInt(parts[1]);
                    if (pn > 1) { planned = pn; valid = true; }
                }
            } catch (Exception e) { }
            if (!valid) clear(out);
        }
        if (planned > 1 && segs <= 1) { clear(out); planned = 0; }          // the source stopped doing Range (or all probes failed): start again whole
        int n = (size > 0 && planned > 1) ? planned : (segs > 1 && size >= SEG_MIN && !(out.isFile() && out.length() > 0)) ? segs : 1;
        if (n <= 1) { single(src, out, size, p, mirror, meter); return; }

        if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
        if (planned != n) { clear(out); dir.mkdirs(); write(plan, (size + " " + n).getBytes("UTF-8")); }
        final long len = (size + n - 1) / n;
        final Exception[] err = new Exception[1];
        final HttpURLConnection[] activeConns = new HttpURLConnection[n];
        List<Thread> ts = new ArrayList<Thread>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final long a = i * len, b = Math.min(size, a + len) - 1;
            final File f = new File(dir, String.valueOf(i));
            if (f.length() > b - a + 1) f.delete();
            if (a + f.length() > b) continue;
            Thread t = new Thread(new Runnable() { @Override public void run() {
                try { range(src, f, a + f.length(), b, mirror, meter, activeConns, idx); }
                catch (Exception e) { synchronized (err) { if (err[0] == null) err[0] = e; } }
            }}, "StoreSeg" + i);
            t.start(); ts.add(t);
        }
        boolean alive = true;
        while (alive) {
            alive = false;
            for (Thread t : ts) { t.join(1000); if (t.isAlive()) { alive = true; break; } }
            long h = have(out);
            if (p != null) p.on(h, size, mirror);
            if (meter != null && !meter.cancel) {
                meter.sample(h);
                if (meter.slow()) {
                    synchronized (activeConns) { meter.cancel = true; }
                    abortActive(activeConns, ts);
                }
            }
        }
        if (meter != null && meter.cancel) {
            long r = Math.max(0, meter.rate());
            android.util.Log.i("VeraStore", "slow source " + host(src) + " " + (r >> 10) + " KB/s, switching (" + (have(out) >> 10) + " KB kept)");
            throw new Slow(src, r);
        }
        for (int i = 0; i < n; i++) {
            long a = i * len, b = Math.min(size, a + len) - 1;
            if (new File(dir, String.valueOf(i)).length() != b - a + 1) {
                if (err[0] != null) throw err[0];
                throw new java.io.IOException("连接中断（" + (have(out) >> 20) + " / " + (size >> 20) + " MB）");
            }
        }
        // join: append the others to piece 0, deleting each after it is appended
        File first = new File(dir, "0");
        long firstLen = first.length();
        int deleted = 0;
        FileOutputStream fo = new FileOutputStream(first, true);
        try {
            byte[] buf = new byte[65536]; int r;
            for (int i = 1; i < n; i++) {
                File f = new File(dir, String.valueOf(i));
                InputStream in = new java.io.FileInputStream(f);
                try { while ((r = in.read(buf)) > 0) fo.write(buf, 0, r); } finally { in.close(); }
                fo.flush();
                f.delete();
                deleted++;
            }
        } catch (Exception e) {
            if (deleted == 0) {
                try { fo.getChannel().truncate(firstLen); } catch (Throwable ignored) { }
            }
            throw e;
        } finally { fo.close(); }
        if (first.length() != size) { clear(out); throw new Exception("文件大小不对"); }
        out.delete();
        if (!first.renameTo(out)) throw new Exception("无法保存下载的文件");
        File[] rest = dir.listFiles(); if (rest != null) for (File f : rest) f.delete();
        dir.delete();
    }

    private static void abortActive(HttpURLConnection[] activeConns, List<Thread> ts) {
        HttpURLConnection[] snap;
        synchronized (activeConns) {
            snap = activeConns.clone();
        }
        for (HttpURLConnection c : snap) {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
        for (Thread t : ts) {
            try { t.interrupt(); } catch (Throwable ignored) { }
        }
    }

    /** Bytes from..to (inclusive) of url appended to f. */
    private static void range(String url, File f, long from, long to, boolean mirror, Meter meter, HttpURLConnection[] activeConns, int idx) throws Exception {
        if (meter != null && meter.cancel) return;
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        synchronized (activeConns) {
            if (meter != null && meter.cancel) {
                try { h.disconnect(); } catch (Throwable ignored) { }
                return;
            }
            activeConns[idx] = h;
        }
        try {
            h.setConnectTimeout(20000); h.setReadTimeout(60000);
            h.setRequestProperty("User-Agent", "VeraDroid/Store");
            h.setRequestProperty("Range", "bytes=" + from + "-" + to);
            int code = h.getResponseCode();
            if (code != 206) throw new HttpError(code, h, mirror);
            InputStream in = h.getInputStream();
            FileOutputStream fo = new FileOutputStream(f, true);
            long left = to - from + 1;
            try {
                byte[] buf = new byte[65536]; int r;
                while (left > 0 && (r = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                    fo.write(buf, 0, r); left -= r;
                    if (meter != null && meter.cancel) return;      // the monitor gave up this source; the piece is resumed elsewhere
                }
            } finally {
                try { fo.close(); } catch (Exception ignored) { }
                try { in.close(); } catch (Exception ignored) { }
            }
            if (left > 0) throw new java.io.IOException("unexpected end of stream");
        } finally {
            synchronized (activeConns) { activeConns[idx] = null; }
            try { h.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** One connection, resuming after what out already holds. */
    private static void single(String url, File out, long size, Progress p, boolean mirror, Meter meter) throws Exception {
        long have = out.isFile() ? out.length() : 0;
        if (size > 0 && have > size) { out.delete(); have = 0; }
        if (size > 0 && have == size) return;
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(20000); h.setReadTimeout(60000);
            h.setRequestProperty("User-Agent", "VeraDroid/Store");
            if (have > 0) h.setRequestProperty("Range", "bytes=" + have + "-");
            int code = h.getResponseCode();
            if (code == 416 && have > 0) { out.delete(); throw new java.io.IOException("HTTP 416，正在重新下载"); }
            if (code != 200 && code != 206) throw new HttpError(code, h, mirror);
            boolean append = have > 0 && code == 206;
            if (!append) have = 0;   // server ignored the Range: start again from zero
            InputStream in = new java.io.BufferedInputStream(h.getInputStream(), 65536);
            FileOutputStream fo = new FileOutputStream(out, append);
            byte[] buf = new byte[65536]; int n; long done = have, last = 0;
            try {
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n); done += n;
                    long now = System.currentTimeMillis();
                    if (now - last >= 1000) {
                        last = now;
                        if (p != null) p.on(done, size, mirror);
                        if (meter != null) {
                            meter.sample(done);
                            // the bytes so far stay: the next source resumes after them (Range), or starts over if it cannot
                            if (meter.slow()) {
                                long r = Math.max(0, meter.rate());
                                android.util.Log.i("VeraStore", "slow source " + host(url) + " " + (r >> 10) + " KB/s, switching (" + (done >> 10) + " KB kept)");
                                throw new Slow(url, r);
                            }
                        }
                    }
                }
            } finally { fo.close(); in.close(); }
            if (size > 0 && out.length() > size) { out.delete(); throw new Exception("文件大小不对"); }
            if (size > 0 && out.length() < size) throw new java.io.IOException("连接中断（" + (out.length() >> 20) + " / " + (size >> 20) + " MB）");
        } finally { h.disconnect(); }
    }

    /** An HTTP error status from the server; carries both its JSON "error" message and optional JSON "code" (e.g. sig_expired / bind_required / rate_limited). */
    static final class HttpError extends Exception {
        final int code;
        final String errCode;
        final boolean fromMirror;
        HttpError(int code, HttpURLConnection h) { this(code, h, false); }
        HttpError(int code, HttpURLConnection h, boolean fromMirror) { this(code, parse(code, h), fromMirror); }
        private HttpError(int code, String[] parsed, boolean fromMirror) { super(parsed[0]); this.code = code; this.errCode = parsed[1]; this.fromMirror = fromMirror; }
        private static String[] parse(int code, HttpURLConnection h) {
            String msg = "HTTP " + code, ec = "";
            InputStream in = null;
            try {
                in = h.getErrorStream();
                if (in == null) return new String[] { msg, ec };
                byte[] b = readLimited(in, 8192);
                org.json.JSONObject j = new org.json.JSONObject(new String(b, "UTF-8"));
                msg = j.optString("error", msg);
                ec = j.optString("code", "");
            } catch (Exception ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) { }
            }
            return new String[] { msg, ec };
        }
    }

    static byte[] read(File f) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try { return readAll(in); } finally { in.close(); }
    }
    static byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[Math.min(4096, Math.max(1, maxBytes))];
        int left = maxBytes, n;
        while (left > 0 && (n = in.read(b, 0, Math.min(b.length, left))) > 0) {
            bo.write(b, 0, n);
            left -= n;
        }
        return bo.toByteArray();
    }
    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }
    static void write(File f, byte[] b) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        try { fo.write(b); } finally { fo.close(); }
    }

    private Dl() { }
}
