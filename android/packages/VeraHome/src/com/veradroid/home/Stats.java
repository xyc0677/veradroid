// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small local usage ledger: battery samples and reading time/pages. */
final class Stats {
    private static final String P = "usage_stats";

    private Stats() { }

    private static SharedPreferences p(Context c) { return Store.prefs(c, P); }
    private static String day() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }

    static void sampleBattery(Context c) {
        int pct = -1; boolean charging = false;
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                pct = Math.min(100, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                charging = bm.isCharging();
            }
        } catch (Throwable ignored) { }
        sampleBattery(c, pct, charging);
    }

    static void sampleBattery(Context c, int pct, boolean charging) {
        if (pct < 0) return;
        SharedPreferences sp = p(c);
        String d = day();
        long now = System.currentTimeMillis();
        int start = sp.getInt("batt." + d + ".start", -1);
        long startTs = sp.getLong("batt." + d + ".startTs", 0);
        if (charging) {
            start = -1;
            startTs = 0;
        } else if (start < 0) {
            start = pct;
            startTs = now;
        }
        sp.edit()
          .putInt("batt." + d + ".start", start)
          .putLong("batt." + d + ".startTs", startTs)
          .putInt("batt." + d + ".last", pct)
          .putLong("batt." + d + ".lastTs", now)
          .putBoolean("batt." + d + ".charging", charging)
          .apply();
    }

    static String[] batteryLines(Context c) {
        int pct = -1; boolean charging = false;
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                pct = Math.min(100, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                charging = bm.isCharging();
            }
        } catch (Throwable ignored) { }
        sampleBattery(c, pct, charging);
        SharedPreferences sp = p(c);
        String d = day();
        int start = sp.getInt("batt." + d + ".start", -1);
        long startTs = sp.getLong("batt." + d + ".startTs", 0);
        int last = sp.getInt("batt." + d + ".last", pct);
        long lastTs = sp.getLong("batt." + d + ".lastTs", 0);
        String drain = "今天还没有耗电记录";
        String rate = "插电时暂停统计";
        if (!charging && start >= 0 && last >= 0 && lastTs > startTs) {
            int used = Math.max(0, start - last);
            double hours = (lastTs - startTs) / 3600000.0;
            drain = "今日耗电 " + used + "%（从 " + start + "% 到 " + last + "%）";
            if (hours >= 0.15) rate = "约 " + String.format(Locale.US, "%.1f", used / hours) + "% / 小时";
        }
        return new String[] {
            pct >= 0 ? "当前电量 " + pct + "%" : "当前电量未知",
            charging ? "正在充电 · 耗电统计暂停" : "使用中",
            drain, rate
        };
    }

    static void addReading(Context c, long ms, int pages) {
        if (ms <= 0 && pages <= 0) return;
        SharedPreferences sp = p(c);
        String d = day();
        long todayMs = sp.getLong("read." + d + ".ms", 0) + Math.max(0, ms);
        int todayPages = sp.getInt("read." + d + ".pages", 0) + Math.max(0, pages);
        sp.edit()
          .putLong("read." + d + ".ms", todayMs)
          .putInt("read." + d + ".pages", todayPages)
          .putLong("read.total.ms", sp.getLong("read.total.ms", 0) + Math.max(0, ms))
          .putInt("read.total.pages", sp.getInt("read.total.pages", 0) + Math.max(0, pages))
          .apply();
    }

    static String[] readingLines(Context c) {
        SharedPreferences sp = p(c);
        String d = day();
        long todayMs = sp.getLong("read." + d + ".ms", 0);
        int todayPages = sp.getInt("read." + d + ".pages", 0);
        long weekMs = 0; int weekPages = 0;
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            String k = f.format(new Date(now - i * 86400000L));
            weekMs += sp.getLong("read." + k + ".ms", 0);
            weekPages += sp.getInt("read." + k + ".pages", 0);
        }
        return new String[] {
            "今日 " + duration(todayMs) + " · " + todayPages + " 页",
            "本周 " + duration(weekMs) + " · " + weekPages + " 页",
            "累计 " + duration(sp.getLong("read.total.ms", 0)) + " · " + sp.getInt("read.total.pages", 0) + " 页"
        };
    }

    static String duration(long ms) {
        long min = Math.max(0, ms) / 60000L;
        if (min < 60) return min + " 分钟";
        return (min / 60) + " 小时 " + (min % 60) + " 分";
    }

    /** 最近 days 天（含今天）的阅读毫秒数（方块主页“本周阅读时长”、“我的”页本月）。 */
    static long readingMs(Context c, int days) {
        SharedPreferences sp = p(c);
        // 方块主页 / “我的”的签名每次换页、回到前台都要算（本月最多 31 次日期格式化）：同一天、阅读记录没变就用上次的
        if (readL == null) {
            readL = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String k) { if (k == null || k.startsWith("read.")) READ_MEMO.clear(); }
            };
            sp.registerOnSharedPreferenceChangeListener(readL);
        }
        String key = days + "|" + (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86400000L;
        Long memo = READ_MEMO.get(key);
        if (memo != null) return memo;
        long ms = readingMs0(sp, days);
        if (READ_MEMO.size() > 8) READ_MEMO.clear();
        READ_MEMO.put(key, ms);
        return ms;
    }
    private static final java.util.Map<String, Long> READ_MEMO = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static SharedPreferences.OnSharedPreferenceChangeListener readL, bmL;
    private static volatile int bmMemo = -1;

    private static long readingMs0(SharedPreferences sp, int days) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        long now = System.currentTimeMillis(), ms = 0;
        for (int i = 0; i < days; i++) ms += sp.getLong("read." + f.format(new Date(now - i * 86400000L)) + ".ms", 0);
        return ms;
    }

    /** 本周（周一起）的阅读毫秒数。 */
    static long weekMs(Context c) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;   // 周一 = 0
        return readingMs(c, dow + 1);
    }

    /** 本月的阅读毫秒数。 */
    static long monthMs(Context c) { return readingMs(c, java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)); }

    /** “12h 19m” 这种短写法（方块风格的统计格）。 */
    static String shortDuration(long ms) {
        long min = Math.max(0, ms) / 60000L;
        return min < 60 ? min + "m" : (min / 60) + "h " + (min % 60) + "m";
    }

    /** 所有书的书签总数（阅读器存在 reader 偏好的 bm:<路径>，分号隔开）。 */
    static int bookmarkCount(Context c) {
        // 以前每次都把阅读器偏好整个复制一遍（每本书好几项）：签名里要用，换页、回到前台、预搭时一再调用。书签变了才重数
        SharedPreferences rp = Store.prefs(c, "reader");
        if (bmL == null) {
            bmL = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String k) { if (k == null || k.startsWith("bm:")) bmMemo = -1; }
            };
            rp.registerOnSharedPreferenceChangeListener(bmL);
        }
        int memo = bmMemo;
        if (memo >= 0) return memo;
        int n = 0;
        try {
            for (java.util.Map.Entry<String, ?> e : rp.getAll().entrySet())
                if (e.getKey().startsWith("bm:") && e.getValue() instanceof String)
                    for (String x : ((String) e.getValue()).split(";")) if (x.length() > 0) n++;
        } catch (Throwable ignored) { }
        bmMemo = n;
        return n;
    }

    // ------------------------------------------------------------------ pruning
    // 两个偏好文件以前只增不删：reader 每打开过一本书留下十来个键（p: t: s: c: o: e: ch: st: fl: r: bm: d:），书删了也还在；
    // usage_stats 每天 5 个 batt.<日期>.* 和 2 个 read.<日期>.*。SharedPreferences 每次 apply() 都整份重写文件，Activity 的
    // onPause / onStop 和服务启动还要等这些写完：文件越大，翻页存进度、离开阅读器 / 主页时卡得越久。一天最多清一次，在后台线程：
    //  - batt.<日期>.*、read.<日期>.* 只留最近 62 天（只用到今天的电量和最近 31 天的阅读时长；累计数在 read.total.* 里，不动）；
    //  - reader 里书已经不在的那些键：路径是绝对路径、文件不存在，而且它所在的文件夹在、不是空的（书库没挂上时挂载点是空的，
    //    那时什么都不删），并且 60 天没打开过。还在的书的进度一个键都不动。
    private static final String READER_PFX[] = { "p:", "t:", "s:", "c:", "o:", "e:", "ch:", "st:", "fl:", "r:", "bm:", "d:" };

    static void pruneAsync(final Context c) {
        final Context a = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        new Thread(new Runnable() { @Override public void run() {
            Ui.lowPriority();
            try { prune(a); } catch (Throwable t) { android.util.Log.w("VeraHome", "prefs prune failed", t); }
        } }, "prefs-prune").start();
    }

    static synchronized void prune(Context c) {
        SharedPreferences sp = p(c);
        String today = day();
        if (today.equals(sp.getString("__pruned", ""))) return;
        long t0 = android.os.SystemClock.uptimeMillis();
        String cut = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(System.currentTimeMillis() - 62L * 86400000L));
        SharedPreferences.Editor e = sp.edit();
        int nu = 0;
        for (String k : new java.util.ArrayList<String>(sp.getAll().keySet())) {
            if (!(k.startsWith("batt.") || k.startsWith("read."))) continue;
            int a = k.indexOf('.') + 1, b = k.indexOf('.', a);
            if (b - a != 8) continue;                               // read.total.* 等不是日期的不动
            String d = k.substring(a, b);
            if (d.compareTo(cut) < 0) { e.remove(k); nu++; }
        }
        e.putString("__pruned", today).apply();

        SharedPreferences rp = Store.prefs(c, "reader");
        java.util.Map<String, ?> all = rp.getAll();
        java.util.Map<String, java.util.List<String>> byPath = new java.util.HashMap<String, java.util.List<String>>();
        for (String k : all.keySet())
            for (String px : READER_PFX)
                if (k.startsWith(px)) {
                    String path = k.substring(px.length());
                    java.util.List<String> l = byPath.get(path);
                    if (l == null) { l = new java.util.ArrayList<String>(); byPath.put(path, l); }
                    l.add(k);
                    break;
                }
        long old = System.currentTimeMillis() - 60L * 86400000L;
        SharedPreferences.Editor re = rp.edit();
        int nb = 0, nk = 0;
        for (java.util.Map.Entry<String, java.util.List<String>> en : byPath.entrySet()) {
            String path = en.getKey();
            if (!path.startsWith("/")) continue;
            java.io.File f = new java.io.File(path);
            if (f.exists()) continue;
            java.io.File dir = f.getParentFile();
            String[] sib = dir == null ? null : dir.list();
            if (sib == null || sib.length == 0) continue;            // 文件夹不在 / 是空的（书库没挂上）：不能断定书没了
            Object r = all.get("r:" + path);
            if (r instanceof Long && (Long) r > old) continue;        // 最近读过：可能只是暂时挪开了
            for (String k : en.getValue()) { re.remove(k); nk++; }
            nb++;
        }
        if (nk > 0) re.apply();
        android.util.Log.i("VeraHome", "prefs prune: " + nu + " old daily stats keys, " + nb + " missing books (" + nk + " keys), "
            + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
    }
}
