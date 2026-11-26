// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local + optional account-synced usage ledger: battery samples, built-in & third-party reading app time/pages.
 * Uses uptimeMillis() + /dev/kfb/ctl/sleep + per-tick caps so deep sleep / screensaver / idle never inflate reading time.
 */
final class Stats {
    private static final String TAG = "VeraStats";
    private static final String P = "usage_stats";
    static final String BUILTIN_PKG = "com.veradroid.home";
    private static final File SLEEP_FILE = new File(HostLink.CTL + "sleep");
    private static final File TOUCH_STAMP_FILE = new File(HostLink.KFB + "lasttouch");
    private static final long IDLE_TOUCH_CUTOFF_MS = 5L * 60_000L; // pause 3rd-party reading timer after 5 min without any touch
    private static final long MAX_SINGLE_TICK_MS = 90_000L;   // fgPoll runs every 30s; gap > 90s means suspend/freeze
    private static final long FLUSH_MS = 60_000L;             // flush 3rd-party reading to prefs once per minute
    private static final long MIN_FLUSH_MS = 5_000L;          // ignore sub-5s accidental launches
    private static final long MAX_ADD_CALL_MS = 14_400_000L;  // sanity cap per single addReading call (4h)
    private static final long AUTO_SYNC_INTERVAL_MS = 30L * 60_000L;

    private static final Set<String> READING_EXACT = new HashSet<String>();
    private static final String[] READING_PREFIXES = {
        "com.tencent.weread",
        "com.duokan.",
        "com.dragon.read",
        "com.xs.fm",
        "com.qidian.",
        "com.jjwxc.",
        "com.kmxs.",
        "com.chaozh.iReader",
        "com.zhangyue.",
        "com.jd.app.reader",
        "com.jingdong.app.reader",
        "net.cpwxw.cpfiction",
        "com.flyersoft.moonreader",
        "org.koreader.",
        "io.legado.",
        "com.legado.",
        "com.gedoor.monkeybook",
        "com.shuqi.",
        "com.qq.reader",
        "com.douban.book.reader",
        "com.i.miniread",
        "com.neverland.alreader",
        "org.readera",
        "com.foobnix.",
        "org.geometerplus.",
        "com.obreey.reader",
        "com.amazon.kindle",
        "com.netease.snailread",
        "com.luojilab.player",
        "com.fanqie.",
        "app.mihon",
        "eu.kanade.tachiyomi",
        "xyz.jmir.tachiyomi",
        "com.ichi2.anki",
        "cn.com.langeasy.LangEasyLexis",
        "com.shanbay.",
        "com.maimemo.",
        "org.kiwix.",
        "com.aldiko.",
        "com.faultexception.reader",
        "com.prestigio.ereader",
        "com.kobo.android",
        "com.google.android.apps.books",
        "com.bambook.",
        "com.dangdang.reader",
        "com.ophone.reader",
        "com.migu.read",
        "com.huawei.hnreader",
        "com.zuoyebang.read",
        "com.ximalaya.",
        "com.zhihu.android",
        "com.nononsenseapps.feeder",
        "net.frju.flym",
        "com.readwise.",
        "com.instapaper.",
        "com.ideashower.readitlater"
    };
    private static final Map<String, String> BUILTIN_LABELS = new HashMap<String, String>();
    static {
        BUILTIN_LABELS.put(BUILTIN_PKG, "内置阅读器");
        BUILTIN_LABELS.put("com.tencent.weread.eink", "微信读书（墨水屏版）");
        BUILTIN_LABELS.put("com.tencent.weread", "微信读书");
        BUILTIN_LABELS.put("com.duokan.einkreader", "多看阅读（墨水屏版）");
        BUILTIN_LABELS.put("com.duokan.reader", "多看阅读");
        BUILTIN_LABELS.put("com.dragon.read", "番茄免费小说");
        BUILTIN_LABELS.put("com.qidian.QDReader", "起点读书");
        BUILTIN_LABELS.put("com.jjwxc.reader", "晋江文学城");
        BUILTIN_LABELS.put("com.kmxs.reader", "七猫免费小说");
        BUILTIN_LABELS.put("com.jd.app.reader", "京东读书");
        BUILTIN_LABELS.put("com.jingdong.app.reader.campus", "京东读书专业版");
        BUILTIN_LABELS.put("net.cpwxw.cpfiction", "长佩阅读");
        BUILTIN_LABELS.put("com.flyersoft.moonreader", "静读天下");
        BUILTIN_LABELS.put("com.flyersoft.moonreaderp", "静读天下专业版");
        BUILTIN_LABELS.put("org.koreader.launcher", "KOReader");
        BUILTIN_LABELS.put("org.koreader.launcher.fdroid", "KOReader");
        BUILTIN_LABELS.put("io.legado.app.release", "阅读（Legado）");
        BUILTIN_LABELS.put("io.legado.ink", "阅读（墨水屏版）");
        BUILTIN_LABELS.put("com.chaozh.iReader", "掌阅");
        BUILTIN_LABELS.put("com.zhangyue.read.iReader.eink", "掌阅（墨水屏版）");
        BUILTIN_LABELS.put("com.shuqi.controller", "书旗小说");
        BUILTIN_LABELS.put("com.qq.reader", "QQ阅读");
        BUILTIN_LABELS.put("com.douban.book.reader", "豆瓣阅读");
        BUILTIN_LABELS.put("com.i.miniread.eink", "迷你阅读");
        BUILTIN_LABELS.put("com.neverland.alreaderext", "AlReader");
        BUILTIN_LABELS.put("com.ichi2.anki", "AnkiDroid");
    }

    private Stats() { }

    private static SharedPreferences p(Context c) { return Store.prefs(c, P); }
    private static String day() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }
    private static String dayOffset(int daysAgo) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_MONTH, -daysAgo);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.getTime());
    }

    /** True if pkg is a third-party reading / book / study app tracked via HostService foreground events. */
    static boolean isThirdPartyReadingApp(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        if (BUILTIN_PKG.equals(pkg) || pkg.startsWith("com.veradroid.") || pkg.equals("android") || pkg.startsWith("com.android.")) return false;
        if (READING_EXACT.contains(pkg)) return true;
        for (String pfx : READING_PREFIXES) {
            if (pkg.startsWith(pfx)) return true;
        }
        String low = pkg.toLowerCase(Locale.US);
        return low.contains(".reader") || low.contains(".ebook") || low.contains(".ereader")
            || low.contains("weread") || low.contains("koreader") || low.contains("legado")
            || low.contains("duokan") || low.contains("moonreader") || low.contains("ireader")
            || low.contains("novel") || low.contains("book");
    }

    // ------------------------------------------------------------------ foreground 3rd-party reading tracker

    private static final Object FG_LOCK = new Object();
    private static String fgPkg = null;
    private static long fgUp = 0;
    private static long fgWall = 0;
    private static long fgPendingMs = 0;

    /** True if host touch-stamp exists and shows no touch/key activity for IDLE_TOUCH_CUTOFF_MS (e.g. left open on desk). */
    private static boolean isTouchIdle(long nowWall) {
        try {
            long lm = TOUCH_STAMP_FILE.lastModified();
            if (lm > 0 && nowWall > lm && (nowWall - lm) > IDLE_TOUCH_CUTOFF_MS) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Called by HostService on TaskStackListener events and 30s fgPoll. Zero extra wakeups. */
    static void onForeground(Context c, String pkg, String cls) {
        if (c == null) return;
        long nowUp = SystemClock.uptimeMillis();
        long nowWall = System.currentTimeMillis();
        boolean sleeping = SLEEP_FILE.exists() || isTouchIdle(nowWall);
        String flushPkg = null;
        long flushMs = 0;

        String effective = (pkg != null && isThirdPartyReadingApp(pkg)) ? pkg : null;

        synchronized (FG_LOCK) {
            if (fgPkg != null && fgUp > 0 && !sleeping) {
                long du = nowUp - fgUp;
                long dw = nowWall - fgWall;
                long dt = Math.min(du, dw);
                if (dt > 0 && dt <= MAX_SINGLE_TICK_MS) {
                    fgPendingMs += dt;
                }
            }

            if (effective == null || !effective.equals(fgPkg)) {
                if (fgPkg != null && fgPendingMs >= MIN_FLUSH_MS) {
                    flushPkg = fgPkg;
                    flushMs = fgPendingMs;
                }
                fgPendingMs = 0;
                fgPkg = effective;
            } else if (fgPendingMs >= FLUSH_MS) {
                flushPkg = fgPkg;
                flushMs = fgPendingMs;
                fgPendingMs = 0;
            }

            if (sleeping || fgPkg == null) {
                fgUp = 0;
                fgWall = 0;
            } else {
                fgUp = nowUp;
                fgWall = nowWall;
            }
        }

        if (flushPkg != null && flushMs > 0) {
            addReading(c, flushMs, 0, flushPkg);
        }
    }

    /** Flush any buffered third-party reading time immediately (on sleep, screen-off, home resume, or stats view). */
    static void flushForeground(Context c) {
        if (c == null) return;
        long nowUp = SystemClock.uptimeMillis();
        long nowWall = System.currentTimeMillis();
        boolean sleeping = SLEEP_FILE.exists() || isTouchIdle(nowWall);
        String flushPkg = null;
        long flushMs = 0;
        synchronized (FG_LOCK) {
            if (fgPkg != null && fgUp > 0 && !sleeping) {
                long du = nowUp - fgUp;
                long dw = nowWall - fgWall;
                long dt = Math.min(du, dw);
                if (dt > 0 && dt <= MAX_SINGLE_TICK_MS) {
                    fgPendingMs += dt;
                }
            }
            if (fgPkg != null && fgPendingMs >= MIN_FLUSH_MS) {
                flushPkg = fgPkg;
                flushMs = fgPendingMs;
                fgPendingMs = 0;
            }
            fgUp = 0;
            fgWall = 0;
        }
        if (flushPkg != null && flushMs > 0) {
            addReading(c, flushMs, 0, flushPkg);
        }
    }

    // ------------------------------------------------------------------ battery

    static void sampleBattery(Context c) {
        int pct = -1; boolean charging = false;
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                charging = bm.isCharging();
            }
        } catch (Throwable ignored) { }
        sampleBattery(c, pct, charging);
    }

    static void sampleBattery(Context c, int pct, boolean charging) {
        if (pct < 0) return;
        if (pct > 100) pct = 100;
        SharedPreferences sp = p(c);
        String d = day();
        long now = System.currentTimeMillis();
        int start = sp.getInt("batt." + d + ".start", -1);
        long startTs = sp.getLong("batt." + d + ".startTs", 0);
        if (charging) {
            start = -1;
            startTs = 0;
        } else if (start < 0 || pct > start) {
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
                pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                charging = bm.isCharging();
            }
        } catch (Throwable ignored) { }
        if (pct > 100) pct = 100;
        sampleBattery(c, pct, charging);
        SharedPreferences sp = p(c);
        String d = day();
        int start = sp.getInt("batt." + d + ".start", -1);
        long startTs = sp.getLong("batt." + d + ".startTs", 0);
        int last = sp.getInt("batt." + d + ".last", pct);
        long lastTs = sp.getLong("batt." + d + ".lastTs", 0);
        String drain = "今天还没有耗电记录";
        String rate = charging ? "插电时暂停统计" : "使用满 10 分钟后显示耗电速率";
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

    // ------------------------------------------------------------------ reading ledger

    static void addReading(Context c, long ms, int pages) {
        addReading(c, ms, pages, BUILTIN_PKG);
    }

    static void addReading(Context c, long ms, int pages, String pkg) {
        long cleanMs = Math.max(0L, Math.min(MAX_ADD_CALL_MS, ms));
        int cleanPages = Math.max(0, Math.min(5000, pages));
        if (cleanMs <= 0 && cleanPages <= 0) return;
        String safePkg = sanitizePkg(pkg);
        SharedPreferences sp = p(c);
        String d = day();
        long todayMs = sp.getLong("read." + d + ".ms", 0) + cleanMs;
        int todayPages = sp.getInt("read." + d + ".pages", 0) + cleanPages;
        SharedPreferences.Editor e = sp.edit()
          .putLong("read." + d + ".ms", todayMs)
          .putInt("read." + d + ".pages", todayPages)
          .putLong("read.total.ms", sp.getLong("read.total.ms", 0) + cleanMs)
          .putInt("read.total.pages", sp.getInt("read.total.pages", 0) + cleanPages);
        if (safePkg.length() > 0 && cleanMs > 0) {
            String akDay = "read.app." + safePkg + "." + d + ".ms";
            String akTot = "read.app." + safePkg + ".total.ms";
            e.putLong(akDay, sp.getLong(akDay, 0) + cleanMs);
            e.putLong(akTot, sp.getLong(akTot, 0) + cleanMs);
            String known = sp.getString("read.apps", "");
            if (!("," + known + ",").contains("," + safePkg + ",")) {
                e.putString("read.apps", known.length() == 0 ? safePkg : known + "," + safePkg);
            }
        }
        e.apply();
    }

    private static String sanitizePkg(String pkg) {
        if (pkg == null) return "";
        String t = pkg.trim();
        if (t.length() < 2 || t.length() > 80) return "";
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-')) return "";
        }
        return t;
    }

    // ------------------------------------------------------------------ cross-device sync state

    static boolean isBound(Context c) {
        String t = Token.get(c);
        return t != null && t.length() > 0;
    }

    static boolean syncEnabled(Context c) {
        if (!isBound(c)) return false;
        return p(c).getBoolean("sync.enabled", true);
    }

    static void setSyncEnabled(Context c, boolean on) {
        p(c).edit().putBoolean("sync.enabled", on).apply();
        READ_MEMO.clear();
    }

    static String syncStatusLine(Context c) {
        if (!isBound(c)) return "仅本机统计（绑定账号后可开启同账号多台 Kindle 阅读互通）";
        SharedPreferences sp = p(c);
        if (!sp.getBoolean("sync.enabled", true)) return "同账号多设备互通已关闭（仅显示本机统计）";
        String user = sp.getString("sync.user", "");
        int devs = sp.getInt("sync.devices", 1);
        long lastOk = sp.getLong("sync.last_ok", 0);
        StringBuilder sb = new StringBuilder();
        sb.append("同账号互通已开启");
        if (user.length() > 0) sb.append("（").append(user).append("）");
        if (devs > 1) sb.append(" · 合并 ").append(devs).append(" 台设备");
        if (lastOk > 0) {
            sb.append(" · 上次同步 ").append(new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(lastOk)));
        } else {
            sb.append(" · 尚未同步");
        }
        return sb.toString();
    }

    private static long dayMs(SharedPreferences sp, String d, boolean includeOther) {
        long ms = sp.getLong("read." + d + ".ms", 0);
        if (includeOther) ms += sp.getLong("sync.other." + d + ".ms", 0);
        return ms;
    }

    private static int dayPages(SharedPreferences sp, String d, boolean includeOther) {
        int pg = sp.getInt("read." + d + ".pages", 0);
        if (includeOther) pg += sp.getInt("sync.other." + d + ".pages", 0);
        return pg;
    }

    static String[] readingLines(Context c) {
        flushForeground(c);
        SharedPreferences sp = p(c);
        boolean inc = syncEnabled(c);
        String d = day();
        long todayMs = dayMs(sp, d, inc);
        int todayPages = dayPages(sp, d, inc);
        long weekMs = 0; int weekPages = 0;
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            String k = f.format(cal.getTime());
            weekMs += dayMs(sp, k, inc);
            weekPages += dayPages(sp, k, inc);
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        long totMs = sp.getLong("read.total.ms", 0) + (inc ? sp.getLong("sync.other.total.ms", 0) : 0);
        int totPages = sp.getInt("read.total.pages", 0) + (inc ? sp.getInt("sync.other.total.pages", 0) : 0);
        return new String[] {
            formatSummary("今日", todayMs, todayPages),
            formatSummary("本周", weekMs, weekPages),
            formatSummary("累计", totMs, totPages)
        };
    }

    private static String formatSummary(String label, long ms, int pages) {
        if (pages > 0) return label + " " + duration(ms) + " · 内置翻页 " + pages + " 页";
        return label + " " + duration(ms);
    }

    /** Per-app reading breakdown lines (built-in + third-party reading apps, merged with synced devices if enabled). */
    static String[] appLines(Context c) {
        flushForeground(c);
        SharedPreferences sp = p(c);
        boolean inc = syncEnabled(c);
        String d = day();
        Set<String> pkgs = new HashSet<String>();
        collectPkgList(sp.getString("read.apps", ""), pkgs);
        if (inc) collectPkgList(sp.getString("sync.other.apps", ""), pkgs);

        final Map<String, Long> totals = new HashMap<String, Long>();
        final Map<String, Long> todays = new HashMap<String, Long>();
        for (String pk : pkgs) {
            long tm = sp.getLong("read.app." + pk + ".total.ms", 0) + (inc ? sp.getLong("sync.other.app." + pk + ".total.ms", 0) : 0);
            long dm = sp.getLong("read.app." + pk + "." + d + ".ms", 0) + (inc ? sp.getLong("sync.other.app." + pk + "." + d + ".ms", 0) : 0);
            if (tm > 0 || dm > 0) {
                totals.put(pk, Math.max(tm, dm));
                todays.put(pk, dm);
            }
        }
        if (totals.isEmpty()) {
            return new String[] { "打开内置阅读器或微信读书、多看、番茄、晋江等阅读应用后会自动记录时长" };
        }
        List<String> order = new ArrayList<String>(totals.keySet());
        Collections.sort(order, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                long ta = todays.get(a), tb = todays.get(b);
                if (ta != tb) return tb > ta ? 1 : -1;
                long la = totals.get(a), lb = totals.get(b);
                return lb > la ? 1 : (lb < la ? -1 : a.compareTo(b));
            }
        });
        int limit = Math.min(order.size(), 10);
        String[] out = new String[limit];
        PackageManager pm = c.getPackageManager();
        for (int i = 0; i < limit; i++) {
            String pk = order.get(i);
            String name = appLabel(pm, pk);
            long dm = todays.get(pk);
            long tm = totals.get(pk);
            out[i] = name + " · 今日 " + duration(dm) + " · 累计 " + duration(tm);
        }
        return out;
    }

    private static void collectPkgList(String csv, Set<String> dst) {
        if (csv == null || csv.length() == 0) return;
        for (String s : csv.split(",")) {
            String t = sanitizePkg(s);
            if (t.length() > 0) dst.add(t);
        }
    }

    private static String appLabel(PackageManager pm, String pkg) {
        String known = BUILTIN_LABELS.get(pkg);
        if (known != null) return known;
        if (pm != null) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null && cs.length() > 0) return cs.toString();
            } catch (Throwable ignored) { }
        }
        return pkg;
    }

    static String duration(long ms) {
        long s = Math.max(0, ms) / 1000L;
        if (s > 0 && s < 60) return "不足 1 分钟";
        long min = s / 60L;
        if (min < 60) return min + " 分钟";
        return (min / 60) + " 小时 " + (min % 60) + " 分";
    }

    /** 最近 days 天（含今天）的阅读毫秒数（方块主页“本周阅读时长”、“我的”页本月）。 */
    static long readingMs(Context c, int days) {
        SharedPreferences sp = p(c);
        if (readL == null) {
            readL = new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String k) {
                    if (k == null || k.startsWith("read.") || k.startsWith("sync.")) READ_MEMO.clear();
                }
            };
            sp.registerOnSharedPreferenceChangeListener(readL);
        }
        boolean inc = syncEnabled(c);
        String key = days + "|" + (inc ? 1 : 0) + "|" + (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86400000L;
        Long memo = READ_MEMO.get(key);
        if (memo != null) return memo;
        long ms = readingMs0(sp, days, inc);
        if (READ_MEMO.size() > 16) READ_MEMO.clear();
        READ_MEMO.put(key, ms);
        return ms;
    }
    private static final java.util.Map<String, Long> READ_MEMO = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static SharedPreferences.OnSharedPreferenceChangeListener readL, bmL;
    private static volatile int bmMemo = -1;

    private static long readingMs0(SharedPreferences sp, int days, boolean inc) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        long ms = 0;
        for (int i = 0; i < days; i++) {
            String d = f.format(cal.getTime());
            ms += dayMs(sp, d, inc);
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
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

    // ------------------------------------------------------------------ reset & cloud sync

    /** Clear all local reading stats (e.g. if user had old sleep-inflated data) and optionally sync reset to cloud. */
    static void resetReading(final Context c, final Runnable onDone) {
        synchronized (FG_LOCK) {
            fgPendingMs = 0;
            fgUp = 0;
            fgWall = 0;
        }
        SharedPreferences sp = p(c);
        SharedPreferences.Editor e = sp.edit();
        for (String k : new ArrayList<String>(sp.getAll().keySet())) {
            if (k.startsWith("read.")) e.remove(k);
        }
        e.apply();
        READ_MEMO.clear();
        if (syncEnabled(c)) {
            syncAsync(c, true, true, onDone);
        } else if (onDone != null) {
            onDone.run();
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean SYNCING = new java.util.concurrent.atomic.AtomicBoolean(false);

    static void maybeAutoSync(Context c) {
        if (!syncEnabled(c)) return;
        long last = p(c).getLong("sync.last_try", 0);
        if (System.currentTimeMillis() - last < AUTO_SYNC_INTERVAL_MS) return;
        syncAsync(c, false, false, (SyncCallback) null);
    }

    interface SyncCallback { void onResult(boolean ok, String msg); }

    static void syncAsync(final Context c, final boolean force, final boolean resetCloud, final Runnable onDone) {
        syncAsync(c, force, resetCloud, new SyncCallback() {
            @Override public void onResult(boolean ok, String msg) {
                if (onDone != null) onDone.run();
            }
        });
    }

    static void syncAsync(final Context c, final boolean force, final boolean resetCloud, final SyncCallback cb) {
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        if (!syncEnabled(app)) {
            if (cb != null) cb.onResult(false, "未开启多设备同步");
            return;
        }
        if (!SYNCING.compareAndSet(false, true)) {
            if (cb != null) cb.onResult(false, "正在同步中");
            return;
        }
        flushForeground(app);
        p(app).edit().putLong("sync.last_try", System.currentTimeMillis()).apply();
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String msg = "同步失败";
                try {
                    String err = doSyncBlocking(app, resetCloud);
                    if (err == null) { ok = true; msg = "已与同账号设备同步"; }
                    else msg = err;
                } catch (Throwable t) {
                    Log.w(TAG, "sync failed", t);
                    msg = "网络不可用，已保留本机记录";
                } finally {
                    SYNCING.set(false);
                }
                if (cb != null) cb.onResult(ok, msg);
            }
        }, "stats-sync").start();
    }

    private static String doSyncBlocking(Context c, boolean resetCloud) throws Exception {
        String token = Token.get(c);
        if (token == null || token.length() == 0) return "尚未绑定账号";
        String did = Update.deviceId(c);
        SharedPreferences sp = p(c);

        Set<String> knownPkgs = new HashSet<String>();
        collectPkgList(sp.getString("read.apps", ""), knownPkgs);

        JSONArray daysArr = new JSONArray();
        if (!resetCloud) {
            for (int i = 0; i < 62; i++) {
                String d = dayOffset(i);
                long ms = sp.getLong("read." + d + ".ms", 0);
                int pg = sp.getInt("read." + d + ".pages", 0);
                JSONObject appsObj = new JSONObject();
                for (String pk : knownPkgs) {
                    long ams = sp.getLong("read.app." + pk + "." + d + ".ms", 0);
                    if (ams > 0) appsObj.put(pk, ams);
                }
                if (ms > 0 || pg > 0 || appsObj.length() > 0) {
                    JSONObject item = new JSONObject();
                    item.put("day", d);
                    item.put("ms", ms);
                    item.put("pages", pg);
                    if (appsObj.length() > 0) item.put("apps", appsObj);
                    daysArr.put(item);
                }
            }
        }

        JSONObject req = new JSONObject();
        req.put("device_id", did);
        req.put("token", token);
        if (resetCloud) req.put("reset", true);
        req.put("days", daysArr);

        HttpURLConnection h = (HttpURLConnection) new URL(StoreNet.base() + "/api/stats/sync").openConnection();
        InputStream in = null;
        int code;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setConnectTimeout(12000);
            h.setReadTimeout(20000);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            h.setRequestProperty("User-Agent", "VeraDroid/Stats");
            byte[] body = req.toString().getBytes("UTF-8");
            h.setFixedLengthStreamingMode(body.length);
            OutputStream os = h.getOutputStream();
            try { os.write(body); } finally { os.close(); }
            code = h.getResponseCode();
            in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
        }
        JSONObject resp = new JSONObject(bo.toString("UTF-8"));
        if (code >= 400 || !resp.optBoolean("ok", false)) {
            return resp.optString("error", "同步失败 (HTTP " + code + ")");
        }

        SharedPreferences.Editor e = sp.edit();
        for (String k : new ArrayList<String>(sp.getAll().keySet())) {
            if (k.startsWith("sync.other.")) e.remove(k);
        }
        e.putString("sync.user", resp.optString("username", ""));
        e.putInt("sync.devices", Math.max(1, resp.optInt("device_count", 1)));
        e.putLong("sync.other.total.ms", Math.max(0L, resp.optLong("other_total_ms", 0)));
        e.putInt("sync.other.total.pages", Math.max(0, resp.optInt("other_total_pages", 0)));

        Set<String> otherPkgs = new HashSet<String>();
        JSONObject otherAppsTot = resp.optJSONObject("other_apps_total");
        if (otherAppsTot != null) {
            Iterator<String> it = otherAppsTot.keys();
            while (it.hasNext()) {
                String pk = sanitizePkg(it.next());
                long v = otherAppsTot.optLong(pk, 0);
                if (pk.length() > 0 && v > 0) {
                    otherPkgs.add(pk);
                    e.putLong("sync.other.app." + pk + ".total.ms", v);
                }
            }
        }
        JSONObject otherDays = resp.optJSONObject("other_days");
        if (otherDays != null) {
            Iterator<String> dit = otherDays.keys();
            while (dit.hasNext()) {
                String d = dit.next();
                if (d == null || d.length() != 8) continue;
                JSONObject dobj = otherDays.optJSONObject(d);
                if (dobj == null) continue;
                long dms = Math.max(0L, dobj.optLong("ms", 0));
                int dpg = Math.max(0, dobj.optInt("pages", 0));
                if (dms > 0) e.putLong("sync.other." + d + ".ms", dms);
                if (dpg > 0) e.putInt("sync.other." + d + ".pages", dpg);
                JSONObject dapps = dobj.optJSONObject("apps");
                if (dapps != null) {
                    Iterator<String> ait = dapps.keys();
                    while (ait.hasNext()) {
                        String pk = sanitizePkg(ait.next());
                        long ams = dapps.optLong(pk, 0);
                        if (pk.length() > 0 && ams > 0) {
                            otherPkgs.add(pk);
                            e.putLong("sync.other.app." + pk + "." + d + ".ms", ams);
                        }
                    }
                }
            }
        }
        StringBuilder csv = new StringBuilder();
        for (String pk : otherPkgs) {
            if (csv.length() > 0) csv.append(',');
            csv.append(pk);
        }
        e.putString("sync.other.apps", csv.toString());
        e.putLong("sync.last_ok", System.currentTimeMillis());
        e.apply();
        READ_MEMO.clear();
        return null;
    }

    /** 所有书的书签总数（阅读器存在 reader 偏好的 bm:<路径>，分号隔开）。 */
    static int bookmarkCount(Context c) {
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
    private static final String READER_PFX[] = { "p:", "t:", "s:", "c:", "o:", "e:", "ch:", "st:", "fl:", "r:", "bm:", "d:" };

    static void pruneAsync(final Context c) {
        final Context a = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        new Thread(new Runnable() { @Override public void run() {
            Ui.lowPriority();
            try { prune(a); } catch (Throwable t) { android.util.Log.w("VeraHome", "prefs prune failed", t); }
        } }, "prefs-prune").start();
    }

    private static String extractDailyKey(String k) {
        // Matches batt.<YYYYMMDD>.*, read.<YYYYMMDD>.*, read.app.<pkg>.<YYYYMMDD>.ms, sync.other.<YYYYMMDD>.*, sync.other.app.<pkg>.<YYYYMMDD>.ms
        String[] parts = k.split("\\.");
        for (int i = 1; i < parts.length; i++) {
            String s = parts[i];
            if (s.length() == 8 && s.charAt(0) == '2') {
                boolean digits = true;
                for (int j = 0; j < 8; j++) if (s.charAt(j) < '0' || s.charAt(j) > '9') { digits = false; break; }
                if (digits) return s;
            }
        }
        return null;
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
            if (!(k.startsWith("batt.") || k.startsWith("read.") || k.startsWith("sync.other."))) continue;
            String d = extractDailyKey(k);
            if (d != null && d.compareTo(cut) < 0) { e.remove(k); nu++; }
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
            if (sib == null || sib.length == 0) continue;
            Object r = all.get("r:" + path);
            if (r instanceof Long && (Long) r > old) continue;
            for (String k : en.getValue()) { re.remove(k); nk++; }
            nb++;
        }
        if (nk > 0) re.apply();
        android.util.Log.i("VeraHome", "prefs prune: " + nu + " old daily stats keys, " + nb + " missing books (" + nk + " keys), "
            + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
    }
}
