package com.veradroid.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 桌面崩溃时上传诊断包：以前直接上传，现在先问。
 *
 * 偏好 crash_upload（Store.prefs "home"）：always = 以后崩溃都自动上传；never = 不上传；没设 = 还没问过，**不上传**。
 * 崩溃那一刻进程马上要没了，弹不了框：只记一个“有一次崩溃没处理”（私有目录里的 crash_pending，同步写盘），
 * 下次桌面起来时问一次：总是上传 / 仅这次 / 不上传。“仅这次”只传这一回，下次崩溃还会再问。
 * 设置 → 设备与更新 → 日志 里能改。手动“上传完整系统日志”和“问题反馈”不受影响（本来就是用户点的）。
 */
final class DiagConsent {
    private DiagConsent() { }

    static final String KEY = "crash_upload";
    /** 诊断包里有什么（和宿主 cmd.sh / watchdog.sh 的 diag_save 一致）。设置页和询问框都用这一段。 */
    static final String CONTENTS = "诊断包包含：机型、固件和 VeraDroid 版本；Kindle 的内核日志（dmesg、last_kmsg）、启动参数（/proc/cmdline）、"
        + "CPU 信息（/proc/cpuinfo，含 CPU 序列号）、系统日志 /var/log/messages、内存 / 挂载 / 进程列表；VeraDroid、显示、输入和安装日志；"
        + "Android 的 logcat、dumpsys、getprop、settings 和崩溃记录（里面可能有打开过的网址和书名）。不含书籍文件、阅读进度和 Wi-Fi 密码。"
        + "经 HTTPS 上传到 VeraDroid 的服务器（veradroid.wenjin.jp），附设备标识和版本，仅用于排查问题；本地也会在 Diagnostics 文件夹留一份。";

    private static SharedPreferences priv(Context c) {
        Context d = c.getApplicationContext();
        try { d = d.createDeviceProtectedStorageContext(); } catch (Throwable ignored) { }
        return d.getSharedPreferences("diag", Context.MODE_PRIVATE);
    }

    static String choice(Context c) { return Store.prefs(c, "home").getString(KEY, ""); }

    static void set(Context c, String v) { Store.prefs(c, "home").edit().putString(KEY, v).apply(); }

    static String label(Context c) {
        String v = choice(c);
        return "always".equals(v) ? "总是上传" : "never".equals(v) ? "不上传" : "每次先问（还没选）";
    }

    /** 在崩溃处理里调：只做最少的事（同步写盘标记，交由重启后的 askIfPending 处理，避免异步线程被 killProcess 截断）。 */
    static void onCrash(Context c) {
        try {
            if ("never".equals(choice(c))) return;
            priv(c).edit().putBoolean("crash_pending", true).commit();
        } catch (Throwable ignored) { }
    }

    /** 桌面起来时调：上次崩溃了、又还没选过，就问一次。 */
    static void askIfPending(final Activity a) {
        final SharedPreferences p = priv(a);
        if (!p.getBoolean("crash_pending", false)) return;
        p.edit().remove("crash_pending").commit();
        String v = choice(a);
        if ("always".equals(v)) { HostLink.cmd("diag-upload"); return; }
        if ("never".equals(v)) return;
        ask(a, true);
    }

    /** crashed = 刚崩溃过（有“仅这次”）；否则是从设置里改选择。 */
    static void ask(final Activity a, final boolean crashed) {
        String[] items = crashed ? new String[] { "总是上传（以后崩溃自动上传）", "仅这次上传", "不上传" }
                                 : new String[] { "总是上传", "每次先问", "不上传" };
        new VeraDialog.Builder(a)
            .title(crashed ? "桌面刚才出错重启了，上传诊断包吗？" : "桌面崩溃时上传诊断包")
            .message(CONTENTS)
            .items(items, new VeraDialog.OnPick() {
                @Override public void onPick(int which) {
                    if (which == 0) { set(a, "always"); if (crashed) HostLink.cmd("diag-upload"); }
                    else if (which == 1) { if (crashed) HostLink.cmd("diag-upload"); else Store.prefs(a, "home").edit().remove(KEY).apply(); }
                    else set(a, "never");
                    if (crashed && which < 2) VeraToast.show(a, "已开始上传诊断包");
                }
            })
            .negative(crashed ? "以后再说" : "取消", null)
            .show();
    }
}
