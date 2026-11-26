// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Device credential survives app-data clears caused by full-image/package upgrades. */
final class Token {
    private static final File LEGACY_FILE = new File("/data/media/0/.veradroid/device_token");
    private Token() { }

    private static File file(Context c) {
        return Store.file(c, "device_token");
    }

    private static String readFile(File f) {
        try {
            if (f == null || !f.isFile()) return "";
            long len = f.length();
            if (len <= 0 || len > 4096) return "";
            byte[] b = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            try {
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
            } finally {
                try { in.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) { return ""; }
    }

    static String get(Context c) {
        String t = Store.prefs(c, "home").getString("device_token", "");
        if (t != null && t.length() > 0) return t;
        t = readFile(file(c));
        if (t.length() > 0) return t;
        return readFile(LEGACY_FILE);
    }

    static void put(Context c, String t) {
        Store.prefs(c, "home").edit().putString("device_token", t).commit();
        try {
            File target = file(c);
            File dir = target.getParentFile();
            if (dir == null) return;
            if (!dir.isDirectory()) dir.mkdirs();
            if (!dir.isDirectory()) return;
            try { android.system.Os.chmod(dir.getAbsolutePath(), 0700); } catch (Exception ignored) { }
            File tmp = new File(dir, ".device_token.tmp." + android.os.Process.myPid() + "." + Thread.currentThread().getId());
            boolean ok = false;
            try {
                java.io.FileDescriptor fd = android.system.Os.open(
                    tmp.getAbsolutePath(),
                    android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_CREAT | android.system.OsConstants.O_TRUNC,
                    0600);
                try {
                    FileOutputStream out = new FileOutputStream(fd);
                    out.write(t.getBytes("UTF-8"));
                    out.flush();
                    try { fd.sync(); } catch (Exception ignored) { }
                } finally {
                    try { android.system.Os.close(fd); } catch (Exception ignored) { }
                }
                try { android.system.Os.chmod(tmp.getAbsolutePath(), 0600); } catch (Exception ignored) { }
                ok = tmp.renameTo(target);
            } finally {
                if (!ok) tmp.delete();
            }
        } catch (Exception ignored) { }
    }

    static void clear(Context c) {
        Store.prefs(c, "home").edit().remove("device_token").commit();
        try { file(c).delete(); } catch (Exception ignored) { }
        try { LEGACY_FILE.delete(); } catch (Exception ignored) { }
    }
}
