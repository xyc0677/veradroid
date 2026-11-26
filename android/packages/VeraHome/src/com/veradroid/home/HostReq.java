// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * Requests to the host that must not be forgeable by other Android apps (host side: boot/lib/webadmin.sh).
 *
 * The command FIFO (/dev/kfb/ctl/cmd) is writable by every app in the container, so for anything with root-level
 * effects (SSH keys, starting SSH, auto-update) or private output (screen, logs) the FIFO only carries a random id:
 * the request itself sits in this app's private files dir (hostreq/<id>.req, 0700 dir), which the host checks is a
 * plain file owned by VeraHome's uid. The answer comes back in /dev/kfb/wa/<id>.out, a root-owned 0711 directory
 * where the file belongs to us with mode 0400.
 */
final class HostReq {
    static final String OUT = HostLink.KFB + "wa/";
    private static final SecureRandom RND = new SecureRandom();

    private HostReq() { }

    /** Runs one request; returns the raw answer, or null on timeout. Line 1 = "verb [arg]", line 2 = optional data. */
    static byte[] run(Context c, String verb, String arg, String data, long timeoutMs) {
        File dir = new File(c.getFilesDir(), "hostreq");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        try { android.system.Os.chmod(dir.getPath(), 0700); } catch (Throwable ignored) { }
        byte[] b = new byte[16];
        RND.nextBytes(b);
        String id = hex(b);
        File tmp = new File(dir, id + ".tmp"), req = new File(dir, id + ".req");
        try {
            FileOutputStream o = new FileOutputStream(tmp);
            String body = verb + (arg == null || arg.length() == 0 ? "" : " " + arg) + "\n" + (data == null ? "" : data) + "\n";
            o.write(body.getBytes("UTF-8"));
            o.close();
            if (!tmp.renameTo(req)) { tmp.delete(); return null; }
        } catch (Exception e) { tmp.delete(); return null; }
        HostLink.cmd("wa " + id);
        File out = new File(OUT + id + ".out");
        long until = android.os.SystemClock.uptimeMillis() + timeoutMs;
        while (android.os.SystemClock.uptimeMillis() < until) {
            if (out.isFile()) {
                try { return read(out, 16 << 20); } catch (Exception e) { return null; }
            }
            try { Thread.sleep(120); } catch (InterruptedException e) { break; }
        }
        req.delete();   // host never picked it up (not running / frozen): leave nothing behind
        return null;
    }

    static Map<String, String> kv(byte[] raw) {
        Map<String, String> m = new HashMap<String, String>();
        if (raw == null) return m;
        try {
            for (String line : new String(raw, "UTF-8").split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) { }
        return m;
    }

    static byte[] read(File f, int max) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > max) break; }
            return bo.toByteArray();
        } finally { in.close(); }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
