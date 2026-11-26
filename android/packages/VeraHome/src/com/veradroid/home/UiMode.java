package com.veradroid.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 双模式与效果引导（小白模式 / 资深玩家模式）：
 * - 小白模式（默认）：隐藏底层 DIY 参数，开机推荐均衡+自动清残影，只保留清晰场景预设与常用设置。
 * - 资深模式：开放全部底层刷新、单应用 11 项优化、手势、内存与实验功能。
 * - 无论哪种模式，修改任何设置都给出通俗易懂的即时效果说明（{@link #guide}）。
 */
final class UiMode {
    static final String KEY_EXPERT = "ui_expert";
    static final String KEY_CHOSEN = "ui_mode_v";
    static final int CUR_VER = 2;
    private static boolean oobeShowing;

    private UiMode() { }

    static boolean isExpert(Context c) {
        try { return Store.prefs(c, "home").getBoolean(KEY_EXPERT, false); } catch (Throwable t) { return false; }
    }

    static void setExpert(Context c, boolean expert, boolean applyDefault) {
        SharedPreferences p = Store.prefs(c, "home");
        p.edit().putBoolean(KEY_EXPERT, expert).putInt(KEY_CHOSEN, CUR_VER).commit();
        if (!expert && applyDefault) {
            // 小白模式自动套用开箱即用推荐配置：均衡刷新 + 标准对比度 1.3 + 每 6 页清残影 + 8 小时锁屏自动回 Kindle（空闲冻结默认关，省电更稳）
            HostLink.settings("KFB_SMOOTH=2\nKFB_CONTRAST=1.3\nKFB_LIGHTF=0\nKFB_DARKEN=0\nKFB_FLASH=6\nIDLE_FREEZE=0\nSAVER_EXIT_H=8\n");
        }
    }

    /** 首次开机 3 步墨水屏引导页（OOBE），引导完成后写入 CUR_VER 不再弹出。 */
    static void maybeAsk(final HomeActivity a) {
        if (a == null || a.standalone || a.isFinishing() || oobeShowing) return;
        SharedPreferences p = Store.prefs(a, "home");
        if (p.getInt(KEY_CHOSEN, 0) >= CUR_VER) return;
        showOobeStep(a, 1);
    }

    private static void showOobeStep(final HomeActivity a, final int step) {
        if (a == null || a.isFinishing()) { oobeShowing = false; return; }
        oobeShowing = true;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int dp8 = Ui.dp(a, 8), dp12 = Ui.dp(a, 12);
        final android.content.DialogInterface.OnDismissListener clearShowing = new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) { oobeShowing = false; }
        };

        if (step == 1) {
            TextView intro = Ui.text(a, "欢迎使用 VeraDroid！10 秒上手墨水屏三大核心用法：", 15, true);
            intro.setPadding(0, 0, 0, dp12);
            box.addView(intro);
            box.addView(modeCard(a, "1. 顶部下滑：一键切换 4 档刷新",
                "• 高清（纯看书）/ 均衡（默认推荐）/ 快速（A2 丝滑刷网页）/ 极速（漫画）\n"
                + "• 旁边的「全刷」可随时一键清屏，消除图文网页残影"));
            box.addView(new View(a), new LinearLayout.LayoutParams(1, dp8));
            box.addView(modeCard(a, "2. 自动按应用记忆刷新模式",
                "• 在微信读书选「高清」、在浏览器选「快速」，切来切去系统会自动记住，不用重复调"));
            android.app.Dialog d1 = new VeraDialog.Builder(a)
                .title("新手引导（1/3）· 刷新模式")
                .view(box)
                .negative("跳过引导", new Runnable() {
                    @Override public void run() {
                        setExpert(a, false, true);
                        a.onUiModeChanged();
                        guide(a, "已启用开箱即用默认配置，随时可在「我的」调整");
                    }
                })
                .positive("下一步（2/3）", new Runnable() {
                    @Override public void run() { showOobeStep(a, 2); }
                })
                .show();
            if (d1 != null) d1.setOnDismissListener(clearShowing);
            return;
        }

        if (step == 2) {
            TextView intro = Ui.text(a, "已为你默认开启最省电、防卡死的 Kindle 专属策略：", 15, true);
            intro.setPadding(0, 0, 0, dp12);
            box.addView(intro);
            box.addView(modeCard(a, "自动省电与待机策略（已默认开启）",
                "• 看书不动屏超过 30 秒自动冻结安卓省电，一碰屏瞬间唤醒（下载中自动跳过）\n"
                + "• 锁屏放置超过 8 小时自动退回 Kindle 释放内存，避免隔夜耗电"));
            box.addView(new View(a), new LinearLayout.LayoutParams(1, dp8));
            box.addView(modeCard(a, "随时回到 Kindle（防卡死逃生）",
                "• 常规：顶部下滑面板点「回到 Kindle」\n"
                + "• 应急：任意界面「长按左下角 2 秒」可强制退回 Kindle"));
            android.app.Dialog d2 = new VeraDialog.Builder(a)
                .title("新手引导（2/3）· 省电与回 Kindle")
                .view(box)
                .negative("上一步", new Runnable() {
                    @Override public void run() { showOobeStep(a, 1); }
                })
                .positive("下一步（3/3）", new Runnable() {
                    @Override public void run() { showOobeStep(a, 3); }
                })
                .show();
            if (d2 != null) d2.setOnDismissListener(clearShowing);
            return;
        }

        TextView intro = Ui.text(a, "遇到灰底、淡字的第三方 App？下拉面板点「当前应用的优化」即可一键开启浅色漂白与深色加深（返回自动保存）。", 14, false);
        intro.setPadding(0, 0, 0, dp12);
        box.addView(intro);

        LinearLayout c1 = modeCard(a, "墨水屏小白模式（推荐）",
            "• 开箱即用：均衡刷新 + 30 秒空闲省电 + 8 小时锁屏回 Kindle + 常见阅读/浏览器专属预设\n"
            + "• 界面清爽：只保留刷新、漂白、加黑、亮度、字号等常用项，不折腾");
        box.addView(c1);
        box.addView(new View(a), new LinearLayout.LayoutParams(1, dp8));

        LinearLayout c2 = modeCard(a, "资深玩家模式（深度 DIY）",
            "• 全部开放：底层刷新波形细项、单应用 15+ 项文石级微调、手势与内存高级项\n"
            + "• 随时可在「我的 → 使用模式」自由切换");
        box.addView(c2);

        final android.app.Dialog[] ref = { null };
        c1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setExpert(a, false, true);
                if (ref[0] != null) ref[0].dismiss();
                a.onUiModeChanged();
                guide(a, "引导完成！已启用小白模式与开箱即用推荐配置");
            }
        });
        c2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setExpert(a, true, false);
                if (ref[0] != null) ref[0].dismiss();
                a.onUiModeChanged();
                guide(a, "引导完成！已启用资深模式，开放全部高级自定义项");
            }
        });
        ref[0] = new VeraDialog.Builder(a)
            .title("新手引导（3/3）· 单应用优化与模式")
            .view(box)
            .positive("完成（小白模式推荐）", new Runnable() {
                @Override public void run() {
                    setExpert(a, false, true);
                    a.onUiModeChanged();
                    guide(a, "引导完成！已启用开箱即用配置，祝阅读愉快");
                }
            })
            .negative("进入资深模式", new Runnable() {
                @Override public void run() {
                    setExpert(a, true, false);
                    a.onUiModeChanged();
                    guide(a, "引导完成！已启用资深模式");
                }
            })
            .show();
        if (ref[0] != null) ref[0].setOnDismissListener(clearShowing);
    }

    private static LinearLayout modeCard(Context c, String title, String desc) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Blocks.on(c) ? Blocks.frame(c) : Ui.frame(c, false));
        int p = Ui.dp(c, 12);
        l.setPadding(p, p, p + Ui.dp(c, 4), p + Ui.dp(c, 4));
        l.addView(Ui.text(c, title, 18, true));
        TextView d = Ui.text(c, desc, 14, false);
        d.setPadding(0, Ui.dp(c, 6), 0, 0);
        l.addView(d);
        l.setClickable(true);
        return l;
    }

    /** 改完设置后的即时通俗效果说明（小白和资深模式通用）。 */
    static void guide(Context c, String msg) {
        if (c != null && msg != null && msg.length() > 0) VeraToast.show(c, msg);
    }

    static String smoothEffect(int mode) {
        switch (mode) {
        case 1: return "已切到快速模式（A2 极速跟手）：滑动整页丝滑移动，停手 0.3 秒无闪补刷变清晰";
        case 2: return "已切到均衡模式（推荐默认）：翻页高清、滑动自动快刷，停手 0.35 秒无闪补刷清残影";
        case 3: return "已切到高清模式：文字最锐利、残影最少，最适合纯看书精读";
        case 4: return "已切到极速模式（A2 常驻）：高频滚动/漫画最跟手，停手自动补刷、周期温和清残影";
        default: return "已切到自定义模式：已展开下方底层刷新细项，可按需手动微调";
        }
    }

    static String keyEffect(String key, String val, String label) {
        if ("KFB_SMOOTH".equals(key)) {
            try { return smoothEffect(Integer.parseInt(val)); } catch (Exception e) { return "已更新刷新模式：" + label; }
        }
        if ("KFB_COLOR".equals(key)) return "0".equals(val) ? "已切换为黑白模式：关闭彩色滤镜，纯文字更锐利、刷新更快" : "2".equals(val) ? "已切换为鲜艳彩色（4096 色增强饱和度）：适合漫画、封面与彩色批注" : "已切换为标准彩色（Colorsoft 原生 4096 色 CFA）";
        if ("KFB_CONTRAST".equals(key)) return "对比度已设为 " + label + "：数值越高淡字越黑，背景浅灰也会略加深";
        if ("KFB_LIGHTF".equals(key)) return "0".equals(val) ? "已关闭浅色过滤：保留原始浅灰背景" : "浅色过滤已设为「" + label + "」：浅灰/米色底自动漂白成纯白，减少残影";
        if ("KFB_DARKEN".equals(key)) return "1".equals(val) ? "已开启深色加深：把偏灰细字统一加黑，对比更清晰" : "已关闭深色加深：恢复原图灰阶";
        if ("KFB_INVERT".equals(key)) return "1".equals(val) ? "已开启黑底白字反色模式（夜间阅读更护眼）" : "已恢复白底黑字正常显示";
        if ("KFB_FLASH".equals(key)) return "0".equals(val) ? "已关闭按页自动闪刷：不再定时黑白闪屏（可随时手动清残影）" : "已设为" + label + "整屏闪刷一次：定期彻底清除累积残影";
        if ("KFB_HZ".equals(key)) return "60".equals(val) ? "已选 60 Hz 刷新节奏：重启安卓后生效，滑动更跟手（耗电略增）" : "已选 30 Hz 省电节奏：重启安卓后生效，续航更长";
        if ("KFB_ROTATE".equals(key)) return "0".equals(val) ? "2.5 秒后恢复竖屏显示" : "180".equals(val) ? "2.5 秒后倒置 180° 显示" : "2.5 秒后自动重启安卓切换横屏（约 20 秒）";
        if ("RETURN_MODE".equals(key)) return "freeze".equals(val) ? "回到 Kindle 设为挂起：1 秒切回原系统，再进安卓免重启" : "回到 Kindle 设为关闭：完全释放内存，最省电";
        if ("WIFI_PS".equals(key)) return "Wi-Fi 省电已设为「" + label + "」：下载或传书时自动提速，空闲省电";
        if ("KFB_MODE".equals(key)) return "局部刷新已设为「" + label + "」：" + ("2".equals(val) ? "黑白最快响应，停手 0.45 秒自动补刷清残影" : "1".equals(val) ? "16 级灰度最清晰，速度稍慢" : "兼顾速度与清晰度");
        if ("KFB_SCROLLWFM".equals(key)) return "1".equals(val) ? "已开启 A2 极速滚动：拖动更跟手，停手自动清残影" : "已切回标准 DU 滚动：拖动更清晰";
        if ("KFB_SCROLLDU".equals(key)) return "0".equals(val) ? "已关闭快速滑动：拖动列表保持高灰度" : "已开启快速滑动：拖动走黑白快刷，停手自动补刷高清";
        if ("KFB_IMG".equals(key)) return "0".equals(val) ? "已关闭图片灰度增强：整页统一节奏刷新" : "已开启图片灰度：漫画与封面自动走 16 级灰度，文字照常快刷";
        if ("KFB_REAGL_MIN".equals(key)) return "整页判定阈值已设为「" + label + "」：单次变化超过此面积时走高清整页刷新";
        if ("KFB_ANIM_MODE".equals(key)) return "0".equals(val) ? "已关闭硬件翻页滑动动画：翻页直出画面更快" : "已开启硬件翻页滑动动画";
        if ("BG_FREEZE".equals(key)) return "1".equals(val) ? "已开启实验性后台冻结（切走 15 秒后冻结）：若遇后台下载中断或缓存丢失请改回关闭" : "已关闭自动冻结后台：后台应用正常保活，避免广播超时强杀与缓存丢失";
        if ("KLIB_BIND".equals(key)) return "ro".equals(val) ? "已设为只读挂载 Kindle 原机书库：重启 Android 后可安全查看与阅读原机书籍" : "1".equals(val) ? "已设为读写挂载 Kindle 原机书库：重启 Android 后生效" : "已关闭实验机型 Kindle 书库挂载：重启 Android 后生效";
        if ("IDLE_FREEZE".equals(key)) return "0".equals(val) ? "已关闭阅读空闲冻结：看书期间保持安卓常驻" : "已开启阅读空闲省电（" + label + "）：看书不动屏自动冻住安卓省电，一碰屏瞬间唤醒";
        if ("SAVER_EXIT_H".equals(key)) return "0".equals(val) ? "已关闭长时间锁屏自动回 Kindle" : "已设为锁屏超过 " + label + " 自动退回 Kindle，避免隔夜耗电";
        if ("LOWMEM".equals(key)) return "小内存模式已设为「" + label + "」：下次重启安卓后生效，内存更充裕";
        if ("KINDLE_UI".equals(key)) return "Kindle 界面策略已设为「" + label + "」：下次启动安卓生效（停掉腾内存最多，挂起切回最快）";
        if ("APP_COMPILE".equals(key)) return "auto".equals(val) ? "已开启自动应用预编译：闲置时自动优化新装应用，启动更快" : "已关闭自动应用预编译：不再在后台自动编译";
        if ("WEBVIEW".equals(key)) return "alt".equals(val) ? "已选择 Chromium 119 新内核：下载完成并重启安卓后兼容更多现代网页" : "已切回自带精简内核：重启安卓后生效，更省内存";
        if ("KEEPALIVE".equals(key)) return "1".equals(val) ? "开启后台保活：切走后不会自动清理该应用，下次秒回原页" : "关闭后台保活：切走闲置时按需回收内存";
        if ("BGSTOP".equals(key) || "SLIM".equals(key)) return "0".equals(val) ? "已恢复正常后台策略" : "已开启后台省电瘦身：减少无用后台服务，省电省内存";
        if (key != null && key.startsWith("OEC_")) {
            if (key.contains("DPI")) return "应用缩放已选「" + label + "」：调整该应用整体界面和控件大小";
            if (key.contains("FLING") || key.contains("SCROLL")) return "滑动惯性已选「" + label + "」：在墨水屏上减少多余惯性滑动与残影";
            if (key.contains("DARK") || key.contains("BLACK") || key.contains("BOLD") || key.contains("TEXT")) return "文字增强已选「" + label + "」：让偏浅字体更黑更清晰";
            if (key.contains("WHITE") || key.contains("BG")) return "背景漂白已选「" + label + "」：把浅灰或彩色底色漂白成纯白";
            if (key.contains("ANIM")) return "应用动画已选「" + label + "」：去掉过渡动画可减少闪烁与残影";
            if (key.contains("BORDER") || key.contains("DIALOG") || key.contains("EDGE")) return "界面边框/边缘已选「" + label + "」：让白底按钮与弹窗轮廓更分明";
            if (key.contains("W")) return "网页显示优化已选「" + label + "」：下次进入该应用网页生效";
            return "应用专属微调已选「" + label + "」：点“完成”后切入该应用生效";
        }
        return "已修改为「" + label + "」并立即生效";
    }
}
