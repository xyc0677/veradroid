package com.veradroid.home;

import android.content.Context;
import android.content.Intent;

/**
 * 盖在当前应用上面的“设置”（下拉面板的 设置 / 全部设置、宿主的 settings 动作）：单独的任务（taskAffinity
 * com.veradroid.home.settings，不进最近任务），用 NEW_TASK 打开。在根页按返回或点右上角 ✕ 就 finish，
 * 回到原来在前台的那个应用——那个应用没有被切到桌面、也不会重开。
 *
 * 界面和桌面里“我的”下面的设置是同一套代码：它就是 HomeActivity 换了个模式（standalone），只搭“我的”和点开的设置页
 * （方块风格），或者经典的设置分类（经典风格）；不搭首页 / 文件 / 应用、不预先搭别的页、不挂小部件宿主、不做桌面的那些事。
 */
public class SettingsActivity extends HomeActivity {
    @Override protected void onCreate(android.os.Bundle s) {
        setTheme(android.R.style.Theme_Holo_Light_NoActionBar);
        super.onCreate(s);
        getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
    }

    static void open(Context c) {
        Perf.begin();
        Intent i = new Intent(c, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        i.putExtra("settings", true);
        try { c.startActivity(i); } catch (Throwable t) { android.util.Log.w("VeraHome", "settings: " + t); }
    }

    @Override public void finish() {
        closingToBack = true;
        StatusBarService.setSuppressed(false);
        StatusBarService.sync(this);
        HostLink.epdHint(800, 20);
        if (!moveTaskToBack(true)) super.finish();
        overridePendingTransition(0, 0);
    }
}
