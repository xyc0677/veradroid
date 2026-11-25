package com.veradroid.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每个应用的显示设置，宿主那一半在 host/{pw6,k49,legacy}/boot/lib/appprof.sh（三份相同）。
 *
 * 宿主管设置：内置推荐（boot/appprofiles.conf）+ 用户自己存的（$SYS/settings.apps，和 settings.conf 放在一起，升级不动），
 * 合并后的表发布在 /dev/kfb/appprof（"包名 b|u|ub spec"，b 内置、u 用户、ub 用户的盖住了内置的，spec 为 "-" = 全跟随全局）。
 * 桌面只负责：前台应用变了告诉宿主（"fg 包名"）、点开应用前先说一声（"fg" + 显示桥的 LAUNCH）、把用户改的存过去（"appset"）。
 *
 * 老宿主没有 /dev/kfb/appprof：退回以前的做法（桌面自己存 appdisp_包名，打开应用时发 appdisp）。
 */
final class AppProf {
    private AppProf() { }

    static final String TABLE = HostLink.KFB + "appprof";
    /** 冷启动时显示桥最多替应用挡多久的纯色启动窗口（epd.c LAUNCH） */
    static final int LAUNCH_MS = 5000;

    /** 宿主认得每个应用的显示设置（appprof.sh）。 */
    static boolean host() { return new File(TABLE).exists(); }

    private static long tableMtime = -1, tableLen = -1;
    private static Map<String, String[]> tableCache;

    /** 合并后的表：包名（可以以 * 结尾 = 前缀）→ {来源 b|u, spec} */
    static synchronized Map<String, String[]> table() {
        File f = new File(TABLE);
        long mt = f.lastModified(), ln = f.length();
        if (tableCache != null && mt == tableMtime && ln == tableLen) return tableCache;
        Map<String, String[]> m = new LinkedHashMap<String, String[]>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                String[] a = line.trim().split("\\s+");
                if (a.length >= 3) m.put(a[0], new String[] { a[1], a[2] });
            }
            r.close();
        } catch (Exception ignored) { }
        tableMtime = mt;
        tableLen = ln;
        tableCache = m;
        return m;
    }

    /** 这个应用现在的设置：{来源 b|u|"", spec（"" = 跟随全局）}；用户的 > 内置精确 > 内置前缀（最长的） */
    static String[] lookup(Map<String, String[]> t, String pkg) {
        String[] hit = t.get(pkg);
        if (hit == null) {
            int best = -1;
            for (Map.Entry<String, String[]> e : t.entrySet()) {
                String k = e.getKey();
                if (!k.endsWith("*")) continue;
                String pre = k.substring(0, k.length() - 1);
                if (pkg.startsWith(pre) && pre.length() > best) { best = pre.length(); hit = e.getValue(); }
            }
        }
        if (hit == null) return new String[] { "", "" };
        return new String[] { hit[0], "-".equals(hit[1]) ? "" : hit[1] };
    }

    /** 内置推荐里有没有这个应用（用户全选“跟随”时要存成 "-"，否则内置的会回来） */
    static boolean builtin(Map<String, String[]> t, String pkg) {
        String[] b = t.get(pkg);
        if (b != null && b[0].indexOf('b') >= 0) return true;
        for (Map.Entry<String, String[]> e : t.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("*") && e.getValue()[0].indexOf('b') >= 0 && pkg.startsWith(k.substring(0, k.length() - 1))) return true;
        }
        return false;
    }

    static Map<String, String> parse(String spec) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        if (spec == null) return m;
        for (String p : spec.split(";")) {
            int eq = p.indexOf('=');
            if (eq > 0) m.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return m;
    }

    /** 一行说明：灰度最准 · 每 6 页全刷 · 快速滑动 */
    static String summary(String spec) {
        Map<String, String> m = parse(spec);
        List<String> l = new ArrayList<String>();
        String sm = m.get("KFB_SMOOTH");
        if (sm != null && !sm.equals("0")) l.add("3".equals(sm) ? "高清" : "2".equals(sm) ? "均衡" : "1".equals(sm) ? "快速" : "4".equals(sm) ? "极速" : "自定义");
        String md = m.get("KFB_MODE");
        if (md != null && (sm == null || sm.equals("0"))) l.add("0".equals(md) ? "均衡" : "1".equals(md) ? "灰度最准" : "2".equals(md) ? "最快" : "自动");
        String f = m.get("KFB_FLASH");
        if (f != null) l.add("0".equals(f) ? "不全刷" : "1".equals(f) ? "每页全刷" : "每 " + f + " 页全刷");
        if (m.containsKey("KFB_CONTRAST")) l.add("对比度 " + m.get("KFB_CONTRAST"));
        String s = m.get("KFB_SCROLLDU");
        if (s != null) l.add("0".equals(s) ? "快速滑动关" : "快速滑动");
        String im = m.get("KFB_IMG");
        if (im != null) l.add("0".equals(im) ? "图片灰度关" : "图片灰度");
        if (m.containsKey("KFB_LIGHTF")) l.add("浅色过滤");
        if (m.containsKey("KFB_DARKEN")) l.add("深色加深");
        if (m.containsKey("KFB_ANIM_MODE")) l.add("0".equals(m.get("KFB_ANIM_MODE")) ? "无翻页动画" : "翻页动画");
        if (l.isEmpty()) return "跟随全局";
        StringBuilder b = new StringBuilder();
        for (String x : l) { if (b.length() > 0) b.append(" · "); b.append(x); }
        return b.toString();
    }

    static void fg(String pkg) { HostLink.cmd("fg " + pkg); }

    /** 桌面自己要打开一个应用（点图标 / 切换应用 / 宿主的 ctl/app）：先换好显示参数，再让显示桥挡住纯色的启动窗口。 */
    static void launching(String pkg) { launching(pkg, false); }
    static void launching(String pkg, boolean warm) {
        if (!host()) return;
        HostLink.epd("LAUNCH " + (warm ? 0 : LAUNCH_MS));
        fg(pkg);
        // 记下宿主现在按哪个应用设置的：任务栈通知漏了（或应用秒退）时，回桌面照样会发 "fg home" 把密度换回来
        // （以前这里不记，profPkg 还是 "home"，回来时以为没变，多看的密度留了 30 秒）
        HostService.profPkg = pkg;
        if (wantsDensity(pkg)) awaitHost(pkg);
    }

    /** 宿主要给这个应用换显示密度（appprof.sh 发布的 density.apps：内置表里带 DENSITY 的包名，可以以 * 结尾）。老宿主没有这个文件。 */
    static boolean wantsDensity(String pkg) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(HostLink.KFB + "density.apps"));
            try {
                String k;
                while ((k = r.readLine()) != null) {
                    k = k.trim();
                    if (k.equals(pkg) || (k.endsWith("*") && pkg.startsWith(k.substring(0, k.length() - 1)))) return true;
                }
            } finally { r.close(); }
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * 等宿主处理完这次 "fg"（它把包名写进 density.fg）再 startActivity：系统密度在应用的 Activity 建起来之前就换好，
     * 不然 Activity 先按旧密度建、马上又整个重建一次（多看冷启动多出一帧黑屏、慢 1–2 秒，PW6 实测）。平时约 0.1 秒，最多等 0.5 秒。
     */
    static void awaitHost(String pkg) {
        long end = android.os.SystemClock.uptimeMillis() + 500;
        File f = new File(HostLink.KFB + "density.fg");
        while (android.os.SystemClock.uptimeMillis() < end) {
            try {
                BufferedReader r = new BufferedReader(new FileReader(f));
                String v = r.readLine();
                r.close();
                if (v != null && v.trim().equals(pkg)) return;
            } catch (Exception ignored) { }
            android.os.SystemClock.sleep(10);
        }
    }

    /** spec："" = 删掉用户的（回到内置推荐 / 全局），"-" = 全跟随，否则 KEY=v;KEY=v */
    static void save(String pkg, String spec) { HostLink.cmd("appset " + pkg + "|" + spec); }

    /** 老版本桌面存在自己偏好里的 appdisp_包名：交给宿主一次，之后删掉（宿主的文件升级不动，桌面数据清了也还在） */
    static void migrate(Context c) {
        if (!host()) return;
        try {
            SharedPreferences p = Store.prefs(c, "home");
            SharedPreferences.Editor e = p.edit();
            int n = 0;
            for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
                String k = en.getKey();
                if (!k.startsWith("appdisp_")) continue;
                Object v = en.getValue();
                String spec = v == null ? "" : String.valueOf(v);
                if (spec.length() > 0) { save(k.substring(8), spec); n++; }
                e.remove(k);
            }
            if (n > 0 || p.getAll().size() > 0) e.commit();
            if (n > 0) android.util.Log.i("VeraHome", "appprof: moved " + n + " per-app display settings to the host");
        } catch (Throwable t) { android.util.Log.w("VeraHome", "appprof migrate: " + t); }
    }
}
