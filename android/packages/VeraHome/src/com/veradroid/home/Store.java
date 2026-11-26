// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * Persistent state for VeraHome that survives reboots.
 *
 * Why: the container runs without an SELinux policy, so installd's create_app_data() fails on every
 * boot (getfilecon -> ENODATA). For *system* apps PackageManager then "recovers" by DESTROYING the
 * app data and creating it again. VeraHome is a system app, so its /data/data directory was wiped on
 * every boot: settings, reading progress and the cover cache were all lost.
 *
 * Everything that must survive now lives in /data/media/0/.vdhome/state, a directory created and owned by
 * VeraHome's uid with mode 0700 (no sdcardfs in this container, so Unix permissions really apply and
 * other apps cannot read it). Old values from the internal location are copied over once.
 */
final class Store {
    // Not under /data/media/0/.veradroid: the installer creates that one as root with mode 0700.
    static final String DIR = "/data/media/0/.vdhome/state";

    private Store() { }

    private static volatile boolean ready;

    /** Google Drive was removed by user request; erase any refresh/access token left by an older build. */
    static void purgeLegacyCloud() {
        try { new File(DIR, "cloud.json").delete(); } catch (Throwable ignored) { }
    }

    /** Creates DIR once with mode 0700 in one step (Os.chmod), never touches permissions again:
     *  toggling them on every call let another thread see "not writable" in between. */
    private static synchronized File dir(String sub) {
        File root = new File(DIR);
        if (!ready) {
            if (!root.isDirectory()) root.mkdirs();
            if (root.isDirectory()) {
                try { android.system.Os.chmod(root.getAbsolutePath(), 0700); } catch (Exception ignored) { }
            }
            ready = root.isDirectory() && root.canWrite();
        }
        File d = sub == null ? root : new File(root, sub);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    @SuppressWarnings("unchecked")
    /**
     * 已经迁移好、放在 DIR 下面的那几份偏好：同一个对象，进程里留着直接给（系统本来也缓存它）。以前每次调用都要
     * 查一遍目录（stat + access）、进一次系统的同步锁、再读一次 __migrated；主页搭一页要调上百次。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, SharedPreferences> PREFS = new java.util.concurrent.ConcurrentHashMap<String, SharedPreferences>();

    static SharedPreferences prefs(Context c, String name) {
        SharedPreferences hit = PREFS.get(name);
        if (hit != null) return hit;
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        File d = dir(null);
        if (!d.canWrite()) return app.getSharedPreferences(name, Context.MODE_PRIVATE);   // fallback: never crash
        SharedPreferences sp = app.getSharedPreferences(new File(d, name + ".xml"), Context.MODE_PRIVATE);
        if (!sp.getBoolean("__migrated", false)) {
            SharedPreferences old = app.getSharedPreferences(name, Context.MODE_PRIVATE);
            SharedPreferences.Editor e = sp.edit();
            for (Map.Entry<String, ?> en : old.getAll().entrySet()) {
                if (sp.contains(en.getKey())) continue;
                Object v = en.getValue();
                if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
                else if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
                else if (v instanceof Float) e.putFloat(en.getKey(), (Float) v);
                else if (v instanceof String) e.putString(en.getKey(), (String) v);
                else if (v instanceof java.util.Set) e.putStringSet(en.getKey(), (java.util.Set<String>) v);
            }
            e.putBoolean("__migrated", true).commit();
        }
        PREFS.put(name, sp);
        return sp;
    }

    /** Replacement for getFilesDir(); copies an old internal file over the first time it is asked for. */
    static File file(Context c, String name) {
        File d = dir("files");
        if (!d.canWrite()) return new File(c.getFilesDir(), name);
        File f = new File(d, name);
        if (!f.exists()) {
            File old = new File(c.getFilesDir(), name);
            if (old.isFile()) copy(old, f);
        }
        return f;
    }

    /** Replacement for getCacheDir() for caches that are expensive to rebuild (book covers). */
    static File cache(String sub) { return dir("cache/" + sub); }

    private static void copy(File from, File to) {
        try {
            FileInputStream in = new FileInputStream(from);
            FileOutputStream out = new FileOutputStream(to);
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close(); out.close();
            to.setReadable(false, false); to.setReadable(true, true);
        } catch (Exception ignored) { }
    }
}
