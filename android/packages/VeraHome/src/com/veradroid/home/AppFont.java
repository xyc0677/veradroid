// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * 每个应用单独的字号。Android 7.1 只有一个全局的 Settings.System.FONT_SCALE，所以做法是：
 * 前台换成设了字号的应用时把全局值改成它的，回到桌面（或换到没设的应用）时改回用户自己的全局值。
 *
 * - 用户自己的全局值存在 home 偏好 font_user；第一次要改之前从系统里记下来。
 * - font_active = 当前全局值是被哪个应用改的（空 = 没改）。开机后系统里留着的可能是上次某个应用的值，
 *   靠这个标记才不会把它当成用户的值记下来。
 * - 只在值真的变了时才写：写一次 FONT_SCALE = 前台应用整个重新排版，墨水屏上就是一次整屏重画。
 */
final class AppFont {
    static final String[] LABELS = { "跟随", "小", "标准", "大", "特大" };
    static final float[] VALUES = { 0f, 0.85f, 1.0f, 1.4f, 2.0f };

    /** 我们自己盖在别的应用上面的页面：它们到前台时不动字号（否则一开面板应用就要重排一次）。 */
    private static final String[] OVERLAYS = {
        ".PanelActivity", ".SwitcherActivity", ".WifiActivity", ".BluetoothActivity",
        ".SettingsActivity", ".HostSettingsActivity", ".AppDisplayActivity",
        ".ImeSettingsActivity", ".UnknownSourcesActivity", ".FilePickerActivity",
        ".StorageActivity", ".BackupActivity", ".TransferActivity",
        ".ScreensaverActivity", ".LockSettingsActivity", ".FontStoreActivity",
        ".StoreActivity", ".StatsActivity", ".InboxActivity", ".TouchTestActivity"
    };

    private AppFont() { }

    /** cls 是我们自己盖在别的应用上面的页面 */
    static boolean overlay(String cls) {
        if (cls == null) return false;
        for (String o : OVERLAYS) if (cls.endsWith(o)) return true;
        return false;
    }

    private static SharedPreferences prefs(Context c) { return Store.prefs(c, "home"); }

    /** 这个应用设的字号；0 = 跟随全局。 */
    static float get(Context c, String pkg) {
        try { return prefs(c).getFloat("appfont_" + pkg, 0f); } catch (Throwable t) { return 0f; }
    }

    static int index(float v) {
        for (int i = 1; i < VALUES.length; i++) if (Math.abs(VALUES[i] - v) < 0.01f) return i;
        return 0;
    }

    static void set(Context c, String pkg, float v) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (v <= 0f) e.remove("appfont_" + pkg); else e.putFloat("appfont_" + pkg, v);
        e.commit();
    }

    /** 用户在面板 / 设置里改了全局字号：记成用户的值，并且立刻生效。 */
    static synchronized void setUser(Context c, float v) {
        prefs(c).edit().putFloat("font_user", v).putString("font_active", "").commit();
        write(c, v);
    }

    static float current(Context c) {
        try { return Settings.System.getFloat(c.getContentResolver(), Settings.System.FONT_SCALE, 1.0f); } catch (Throwable t) { return 1.0f; }
    }

    /** 用户自己的全局字号。 */
    static synchronized float user(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains("font_user")) return p.getFloat("font_user", 1.0f);
        float cur = current(c);
        if (p.getString("font_active", "").length() > 0) return 1.0f;   // 系统里是某个应用的值，不能当成用户的
        p.edit().putFloat("font_user", cur).commit();
        return cur;
    }

    /** 前台换了：pkg / cls 是新的前台应用和它最上面的 Activity。 */
    static synchronized void forForeground(Context c, String pkg, String cls) {
        if (pkg == null || pkg.length() == 0) return;
        if (pkg.equals(c.getPackageName())) {
            if (overlay(cls)) return;
            restore(c);
            return;
        }
        float want = get(c, pkg);
        if (want <= 0f) { restore(c); return; }
        user(c);                                        // 先把用户的值记下来（没记过的话）
        prefs(c).edit().putString("font_active", pkg).commit();
        write(c, want);
    }

    /** 回到用户自己的全局值（只在之前被某个应用改过时才写）。 */
    static synchronized void restore(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getString("font_active", "").length() == 0) return;
        float u = user(c);
        p.edit().putString("font_active", "").commit();
        write(c, u);
    }

    private static void write(Context c, float v) {
        if (Math.abs(current(c) - v) < 0.001f) return;
        try {
            ContentResolver cr = c.getContentResolver();
            Settings.System.putFloat(cr, Settings.System.FONT_SCALE, v);
        } catch (Throwable t) { android.util.Log.w("VeraHome", "font scale: " + t); }
    }
}
