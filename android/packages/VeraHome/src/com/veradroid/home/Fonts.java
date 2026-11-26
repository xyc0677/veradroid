// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import java.io.File;

/**
 * VeraDroid 桌面自己的界面字体。
 *
 * - 设置里选了“系统字体”（霞鹜文楷之类，键 system_font_path）：一律用它，粗体是它的合成粗体（和以前一样）；
 *   方块风格界面字重选“粗”时正文也用它的合成粗体（这种字体一般只有一种字重，没有真粗体可换）。
 * - 经典风格：系统默认字体（Roboto + DroidSansFallback），和以前一样。
 * - 方块风格：界面字重（显示与刷新 → 界面字重，键 ui_weight：0 常规 / 1 中粗 / 2 粗，默认 2）。
 *   中粗 / 粗用真正的粗字形，不是把细字描粗：英文和数字用 Kindle 自带的 Amazon Ember（Medium / Bold / Heavy），
 *   中文用 Kindle 自带的华文黑体（STHeitiMedium / STHeitiBold）。这些字体只在运行时从机器上读
 *   （宿主把 /usr/java/lib/fonts 只读挂在 /data/media/0/KindleFonts），不拷进镜像、不进仓库。
 *   机器上没有这些字体时退回系统的 Roboto Bold + DroidSansFallback（中文是合成粗体）。
 *
 * Typeface 都缓存起来：以前每个 TextView 都去解析一遍 18 MB 的楷体，界面明显发卡。字体文件是 mmap 的，不占 Java 堆。
 */
final class Fonts {
    private Fonts() { }

    static final String WEIGHT_KEY = "ui_weight";
    static final String[] WEIGHT_L = { "常规", "中粗", "粗" };
    static final String KINDLE = "/data/media/0/KindleFonts/";

    /** 0 常规，1 中粗，2 粗（默认）。只在方块风格、没有选系统字体时起作用。 */
    static int weight(Context c) {
        try { return Math.max(0, Math.min(2, Store.prefs(c, "home").getInt(WEIGHT_KEY, 2))); } catch (Throwable t) { return 2; }
    }

    private static volatile String cachedKey;
    private static volatile Typeface cached, cachedBold;
    /** 用的是拼起来的粗体（Amazon Ember 的行高比 Roboto 大）：去掉字体自带的上下留白，行高和以前差不多，页面不因为换字体多出一页。 */
    private static volatile boolean tight;
    /**
     * 正文 / 粗体角色要不要“描粗”（Skia 的 fake bold，画字时每一笔加一圈）。只有一种字重的字体（选的系统字体、
     * 没有华文黑体时的 DroidSansFallback）靠它变粗。Typeface.create(tf, BOLD) 在 7.1 上对这种从文件读的字体并不总会描粗
     * （真机上实测正文没变粗），所以直接在画笔上开。
     */
    private static volatile boolean bodyFake, boldFake;
    private static volatile boolean lastRealCjk;

    private static synchronized void load(Context c) {
        String p = "";
        boolean blocks = false;
        int w = 0;
        try {
            p = Store.prefs(c, "home").getString("system_font_path", "");
            blocks = Blocks.on(c);
            w = weight(c);
        } catch (Throwable ignored) { }
        if (p == null) p = "";
        String key = p + "|" + blocks + "|" + w;
        if (key.equals(cachedKey) && cached != null) return;
        cachedKey = key;
        cached = null; cachedBold = null; tight = false; bodyFake = false; boldFake = false;
        if (p.length() > 0) {
            try { cached = Typeface.createFromFile(p); } catch (Throwable ignored) { }
            // 选了系统字体（霞鹜文楷之类，只有一种字重）：方块风格里粗体描粗；界面字重“粗”时正文也描粗
            if (cached != null && blocks) { boldFake = w > 0; bodyFake = w == 2; if (w > 0) cachedBold = cached; }
        } else if (blocks && w > 0) {
            // 正文 / 粗体两种角色各用哪一档字形
            cached = family(w == 2 ? "Bold" : "Medium", w == 2 ? "STHeitiBold" : "STHeitiMedium");
            bodyFake = cached != null && !lastRealCjk && w == 2;
            cachedBold = family(w == 2 ? "Heavy" : "Bold", "STHeitiBold");
            boldFake = cachedBold != null && !lastRealCjk;
            tight = cached != null;
        }
        if (cached == null) { cached = Typeface.DEFAULT; bodyFake = false; }
        if (cachedBold == null) { cachedBold = Typeface.create(cached, Typeface.BOLD); boldFake = false; }
    }

    /**
     * 一个由几套字体拼起来的 Typeface：先英文（Amazon Ember 的某一档，没有就 Roboto Bold），再中文（华文黑体的某一档），
     * 后面接系统的后备字体（日文、符号……）。逐字找第一个有这个字的字体。
     * Android 7.1 的 FontFamily / createFromFamiliesWithDefault 是隐藏接口，用反射调（我们是平台签名的系统应用）。
     * 机器上没有华文黑体时 lastRealCjk = false，调用方给这一角色开描粗（中文是 DroidSansFallback）。
     */
    private static Typeface family(String emberWeight, String heiti) {
        lastRealCjk = false;
        try {
            String latin = KINDLE + "Amazon-Ember-" + emberWeight + ".ttf";
            if (!new File(latin).canRead()) latin = "/system/fonts/Roboto-Bold.ttf";
            String cjk = KINDLE + heiti + ".ttf";
            boolean realCjk = new File(cjk).canRead();
            Class<?> ff = Class.forName("android.graphics.FontFamily");
            java.lang.reflect.Method add = ff.getMethod("addFont", String.class, int.class);
            java.util.List<Object> fams = new java.util.ArrayList<Object>();
            Object a = ff.newInstance();
            if ((Boolean) add.invoke(a, latin, 0)) fams.add(a);
            if (realCjk) { Object b = ff.newInstance(); if ((Boolean) add.invoke(b, cjk, 0)) fams.add(b); else realCjk = false; }
            if (fams.isEmpty()) return null;
            Object arr = java.lang.reflect.Array.newInstance(ff, fams.size());
            for (int i = 0; i < fams.size(); i++) java.lang.reflect.Array.set(arr, i, fams.get(i));
            Typeface t = (Typeface) Typeface.class.getMethod("createFromFamiliesWithDefault", arr.getClass()).invoke(null, arr);
            lastRealCjk = realCjk;
            return t;
        } catch (Throwable e) {
            android.util.Log.w("VeraHome", "ui font: " + e);
            return null;
        }
    }

    static Typeface typeface(Context c) { load(c); return cached; }

    /**
     * 换系统字体（设置里的“系统字体”和字体商店都走这里）：桌面 / 设置 / 阅读器一起换；开着“应用到所有应用”时
     * 告诉宿主（SYSTEM_FONT），和宿主现在用着的不一样就问要不要重启 Android。path = null：回到系统默认。
     */
    static void setSystem(final android.app.Activity a, String path, String name) {
        if (path != null) {
            String base = new File(path).getName();
            if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\0') >= 0
                    || base.length() == 0 || base.indexOf('\n') >= 0 || base.indexOf('\r') >= 0 || base.indexOf('/') >= 0) {
                VeraToast.show(a, "字体文件名无效");
                return;
            }
        }
        android.content.SharedPreferences prefs = Store.prefs(a, "home");
        prefs.edit().putString("system_font_path", path).putString("system_font_name", name)
            .putString("reader_font_path", path).putString("reader_font_name", name).commit();
        if (!prefs.getBoolean("system_font_all", true)) return;
        HostLink.settings("SYSTEM_FONT=" + (path == null ? "default" : path) + "\n");
        String live = HostLink.kv(HostLink.KFB + "settings").get("SYSTEM_FONT_LIVE");
        if ((path == null ? "" : path).equals(live == null ? "" : live)) return;
        new VeraDialog.Builder(a).title("重启 Android").message("需要重启 Android 才能让其他应用也用这个字体（约 15 秒）。桌面和阅读器已经换好了。")
            .negative("稍后", null).positive("现在重启", new Runnable() { @Override public void run() { HostLink.cmd("restart"); } }).show();
    }

    /** 只换阅读器的正文字体（阅读器自己的设置里存的那一份也改掉，不然它还用上次选的）。path = null：系统默认。 */
    static void setReader(Context c, String path, String name) {
        Store.prefs(c, "home").edit().putString("reader_font_path", path).putString("reader_font_name", name).commit();
        Store.prefs(c, "reader").edit().putString("font", path).putString("fontName", name).commit();
    }

    static Typeface bold(Context c) { load(c); return cachedBold; }

    static void apply(TextView t, boolean bold) {
        load(t.getContext());
        t.setTypeface(bold ? cachedBold : cached);
        boolean fake = bold ? boldFake : bodyFake;
        if (fake || t.getPaint().isFakeBoldText()) t.getPaint().setFakeBoldText(fake);
        if (tight) t.setIncludeFontPadding(false);
    }
}
