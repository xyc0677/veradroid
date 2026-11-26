package com.veradroid.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/**
 * 以前只有宿主 / 传书网页才能改的设置，在设备上给一个入口。两页，Intent extra "page"：
 *   display  显示与刷新：旋转（KFB_ROTATE）、显示档位（KFB_SMOOTH）、局部刷新（KFB_MODE）、快速滑动（KFB_SCROLLDU）、图片灰度（KFB_IMG）、整页刷新（KFB_REAGL_MIN）、整屏闪刷（KFB_FLASH）、
 *            浅色过滤（KFB_LIGHTF）、深色加深（KFB_DARKEN）、网页内核（WEBVIEW，只在宿主发 WEBVIEW_ALT_OK=1 时出现）
 *   system   系统与性能：Wi-Fi 省电（WIFI_PS）、小内存模式（LOWMEM）、Kindle 界面（KINDLE_UI）、应用预编译（APP_COMPILE + ctl/compile.status + 全部优化）
 * 每项一行“< 值 >”步进：点一下只改那一行的字（一次小刷新），同时经 HostLink.settings 写给宿主（ctl/settings.new + apply → settings.conf）。
 * 不搭列表、不滚动：一页放得下；小屏放不下时用 PageScrollView 整页翻。
 */
public class HostSettingsActivity extends Activity {
    static void open(Context c, String page) {
        c.startActivity(new Intent(c, HostSettingsActivity.class).putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    private final Handler handler = new Handler();
    private Map<String, String> host;
    private LinearLayout list, customRefreshBox;
    private TextView compileView, smoothNote, webviewView;
    private android.widget.Button webviewBtn;
    private String webviewAct;
    private CtlWatch.Listener compileWatch, webviewWatch;
    private boolean system, webviewOnly;
    private boolean rotatePending;
    private Runnable pendingRotate = new Runnable() { @Override public void run() { } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        String pageArg = getIntent().getStringExtra("page");
        system = "system".equals(pageArg);
        webviewOnly = "webview".equals(pageArg);
        host = HostLink.kv(HostLink.KFB + "settings");
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);
        TextView title = Ui.text(this, webviewOnly ? "实验性网页内核" : system ? "系统与性能" : "显示与刷新", 30, true);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);
        final PageScrollView sv = new PageScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.addView(Ui.button(this, "返回", 17, false, new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }), weight());
        nav.addView(Ui.button(this, "上一屏", 17, false, new View.OnClickListener() { @Override public void onClick(View v) { sv.page(-1); } }), weight());
        nav.addView(Ui.button(this, "下一屏", 17, false, new View.OnClickListener() { @Override public void onClick(View v) { sv.page(1); } }), weight());
        page.addView(nav);
        if (Blocks.on(this)) {   // 方块风格：方块页头；“上一屏 / 下一屏 / 返回”换成翻页条（返回在页头右边）
            Blocks.restyle(this, page, title, null, webviewOnly ? "WebView / Chromium 119" : system ? "System / performance" : "Display / host");
            page.removeView(nav);
            page.removeView(sv);
            sv.removeView(list);
            page.addView(Blocks.paged(this, list), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        if (webviewOnly) buildWebviewOnly(); else if (system) buildSystem(); else buildDisplay();
    }

    private int dp(int v) { return Ui.dp(this, v); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }

    private void section(String s) {
        if (Blocks.on(this)) { list.addView(Blocks.sectionTitle(this, s, 15)); return; }
        TextView h = Ui.text(this, s, 15, true);
        h.setPadding(0, dp(16), 0, dp(4));
        list.addView(h);
        list.addView(Ui.rule(this, 2, Color.BLACK));
    }

    interface OnSet { void set(String v); }

    /** 一行：标题、说明、下面“< 值 >”。值不在选项里（手改过 settings.conf）时显示原值，按 ‹ › 跳到最近的一项。 */
    private void stepper(String title, String desc, final String key, final String[] labels, final String[] values, String def, final OnSet after) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(Ui.text(this, title, 20, true));
        if (desc != null) {
            TextView d = Ui.text(this, desc, 15, false);
            d.setPadding(0, dp(2), 0, dp(6));
            row.addView(d);
        }
        String cur = host.get(key);
        if (cur == null || cur.length() == 0) cur = def;
        int at = -1;
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) at = i;
        final int[] idx = { at };
        final TextView val = Ui.text(this, at >= 0 ? labels[at] : cur, 20, false);
        val.setGravity(Gravity.CENTER);
        final boolean blk = Blocks.on(this);
        if (blk) {   // 方块风格：值是黑底白字的方块，允许两行显示，避免长文案被截断
            Fonts.apply(val, true);
            val.setTextSize(16);
            val.setTextColor(Color.WHITE);
            val.setBackground(Blocks.plainFilled(this));
            val.setSingleLine(false);
            val.setMaxLines(2);
            val.setPadding(dp(6), dp(4), dp(6), dp(4));
        }
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        View.OnClickListener step = null;
        final int[] committed = { idx[0] >= 0 ? idx[0] : 0 };
        for (int dir = -1; dir <= 1; dir += 2) {
            final int d = dir;
            View.OnClickListener l = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int n = idx[0] < 0 ? (d > 0 ? 0 : values.length - 1) : idx[0] + d;
                    if (key.equals("KFB_ROTATE")) n = (n + values.length) % values.length;
                    if (n < 0 || n >= values.length) return;   // 到头了：不绕回去，免得一下跳到另一端
                    final String line = key + "=" + values[n] + "\n";
                    if (key.equals("KFB_ROTATE")) {
                        int curDeg = Integer.parseInt(values[committed[0]]);
                        int newDeg = Integer.parseInt(values[n]);
                        if (newDeg % 180 != curDeg % 180) {
                            handler.removeCallbacks(pendingRotate);
                            rotatePending = false;
                            final int target = n;
                            idx[0] = target;
                            val.setText(labels[target]);
                            android.app.Dialog dlg = new VeraDialog.Builder(HostSettingsActivity.this)
                                .title("切换屏幕方向")
                                .message("切换为「" + labels[target] + "」将重启 Android，后台未保存的内容会丢失。确定继续吗？")
                                .negative("取消", new Runnable() {
                                    @Override public void run() {
                                        idx[0] = committed[0];
                                        val.setText(labels[committed[0]]);
                                        host.put(key, values[committed[0]]);
                                    }
                                })
                                .positive("确认切换", new Runnable() {
                                    @Override public void run() {
                                        committed[0] = target;
                                        idx[0] = target;
                                        val.setText(labels[target]);
                                        host.put(key, values[target]);
                                        if (after != null) after.set(values[target]);
                                        UiMode.guide(HostSettingsActivity.this, UiMode.keyEffect(key, values[target], labels[target]));
                                        HostLink.settings(line);
                                    }
                                }).show();
                            dlg.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                                @Override public void onCancel(android.content.DialogInterface di) {
                                    idx[0] = committed[0];
                                    val.setText(labels[committed[0]]);
                                    host.put(key, values[committed[0]]);
                                }
                            });
                            return;
                        }
                        committed[0] = n;
                        idx[0] = n;
                        val.setText(labels[n]);
                        host.put(key, values[n]);
                        if (after != null) after.set(values[n]);
                        UiMode.guide(HostSettingsActivity.this, UiMode.keyEffect(key, values[n], labels[n]));
                        handler.removeCallbacks(pendingRotate);
                        rotatePending = false;
                        HostLink.settings(line);
                        return;
                    }
                    idx[0] = n;
                    val.setText(labels[n]);
                    host.put(key, values[n]);
                    if (after != null) after.set(values[n]);
                    UiMode.guide(HostSettingsActivity.this, UiMode.keyEffect(key, values[n], labels[n]));
                    HostLink.settings(line);
                }
            };
            if (dir < 0) {
                line.addView(Ui.button(this, "<", 26, false, l), new LinearLayout.LayoutParams(dp(72), dp(56)));
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, blk ? dp(56) : ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (blk) { vp.leftMargin = dp(6); vp.rightMargin = dp(6); }
                line.addView(val, vp);
            } else {
                line.addView(Ui.button(this, ">", 26, false, l), new LinearLayout.LayoutParams(dp(72), dp(56)));
            }
        }
        row.addView(line);
        list.addView(row);
    }

    // ---- 显示与刷新 ----------------------------------------------------------------------------------------------

    private void buildDisplay() {
        section("方向");
        stepper("屏幕旋转", "画面顺时针转。同向翻转（0°↔180° / 90°↔270°）立即生效；横竖互换会弹窗确认后重启 Android（约 20 秒）。",
            "KFB_ROTATE", new String[] { "竖屏（0°）", "倒过来（180°）", "横屏（90°，会重启）", "横屏（270°，会重启）" },
            new String[] { "0", "180", "90", "270" }, "0", null);
        section("刷新模式（与单应用优化统一，全部自带去残影）");
        stepper("全局刷新模式", "高清：最锐利 · 均衡：推荐 · 快速：快刷+停手补刷 · 极速：A2 最跟手+自动全清残影。选“自定义”才展开下方细项。",
            "KFB_SMOOTH", new String[] { "自定义", "快速（DU）", "均衡（推荐）", "高清（画质）", "极速（A2）" }, new String[] { "0", "1", "2", "3", "4" }, "2",
            new OnSet() { @Override public void set(String v) { smoothNote(v); } });
        smoothNote = Ui.text(this, "", 14, false);
        list.addView(smoothNote);
        LinearLayout prevList = list;
        customRefreshBox = new LinearLayout(this);
        customRefreshBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(customRefreshBox);
        list = customRefreshBox;
        section("自定义细项（仅“自定义”模式生效）");
        stepper("局部刷新方式", "均衡：快，残影少；灰度最准：最清晰，稍慢；最快：黑白快刷+自动补刷；自动：驱动自动选。",
            "KFB_MODE", new String[] { "均衡", "灰度最准", "最快", "自动（同 Kindle）" }, new String[] { "0", "1", "2", "3" }, "0", null);
        stepper("快速滑动", "滚动、拖动列表时先用黑白快刷跟上手指，停下约 0.3 秒后再用 16 级灰度补刷清残影。",
            "KFB_SCROLLDU", new String[] { "关（默认）", "开" }, new String[] { "0", "300" }, "0", null);
        stepper("滚动更跟手（极速 A2）", "手指拖动列表时改用更快的 A2 波形（PW6 约 6~7 帧/秒），停手自动清残影。",
            "KFB_SCROLLWFM", new String[] { "标准（默认）", "极速（A2）" }, new String[] { "0", "1" }, "0", null);
        stepper("图片灰度", "均衡方式下，小块变化里是图片的部分用 16 级灰度刷，文字照旧快刷。",
            "KFB_IMG", new String[] { "关（默认）", "开" }, new String[] { "0", "25" }, "0", null);
        stepper("多大算整页", "一次变化超过屏幕的这么多，就用 16 级灰度的整页波形；小于它按局部刷新。",
            "KFB_REAGL_MIN", new String[] { "三成", "翻页（默认）", "一半", "七成", "接近整屏" }, new String[] { "30", "35", "50", "70", "85" }, "35", null);
        list = prevList;
        smoothNote(host.get("KFB_SMOOTH"));
        section("通用显示与去残影");
        stepper("界面刷新节奏", "30 Hz 最省电；60 Hz 触摸更跟手（耗电略增）。重启 Android 后生效。",
            "KFB_HZ", new String[] { "30 Hz（省电）", "60 Hz（更跟手）" }, new String[] { "30", "60" }, "30", null);
        stepper("整屏闪刷周期", "每翻这么多页，整个屏幕黑白闪一下，彻底清掉残影。",
            "KFB_FLASH", new String[] { "不按页数闪", "每页", "每 3 页", "每 6 页", "每 8 页", "每 10 页", "每 20 页" },
            new String[] { "0", "1", "3", "6", "8", "10", "20" }, "8", null);
        section("画面");
        stepper("浅色过滤", Panel.LIGHTF_DESC, "KFB_LIGHTF", Panel.LIGHTF_L, Panel.LIGHTF_V, "0", null);
        stepper("深色加深", Panel.DARKEN_DESC, "KFB_DARKEN", new String[] { "关", "开" }, new String[] { "0", "1" }, "0", null);
        // 实验性新网页内核（WEBVIEW，docs/webview-alt.md）：宿主只在 1 GB 机型上发 WEBVIEW_ALT_OK=1（512 MB 机型这一段不出现）。
        // 打开时宿主在后台下载约 80 MB 的包（ctl/webview.status 报进度），下好以后重启 Android 才换；关掉以后下次重启换回 52 并删掉下载的包。
        if ("1".equals(host.get("WEBVIEW_ALT_OK"))) {
            section("网页内核（实验）");
            stepper("网页内核", "新内核（Chromium 119）能打开更多新网页（晋江验证码、新版登录页、网页书源）。打开后自动下载约 45 MB（占用约 80 MB 空间），"
                + "重启 Android 后生效。每个用网页的应用多占几十到一百多 MB 内存，打开网页也慢一点。两个内核的网页登录各自保存："
                + "第一次换到新内核要在网页里重新登录；关掉后重启 Android 换回自带内核（原来的登录还在），并删掉下载的新内核和它的网页数据。",
                "WEBVIEW", new String[] { "自带（Chromium 52，默认）", "新内核（Chromium 119）" }, new String[] { "stock", "alt" }, "stock",
                new OnSet() { @Override public void set(String v) {
                    if ("stock".equals(v)) HostLink.cmd("webview cancel");
                    handler.postDelayed(new Runnable() { @Override public void run() { showWebview(); } }, 1500);
                } });
            webviewView = Ui.text(this, "", 17, false);
            webviewView.setPadding(0, dp(6), 0, dp(6));
            list.addView(webviewView);
            LinearLayout wr = new LinearLayout(this);
            webviewBtn = Ui.button(this, "", 17, true, new View.OnClickListener() {
                @Override public void onClick(View v) { webviewAction(); }
            });
            wr.addView(webviewBtn, weight());
            list.addView(wr);
            showWebview();
            webviewWatch = new CtlWatch.Listener() {
                @Override public void onCtl(final String path) {
                    if ("webview.status".equals(path)) handler.post(new Runnable() { @Override public void run() { showWebview(); } });
                }
            };
            CtlWatch.add(webviewWatch);
        }
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(12), 0, 0);
        r.addView(Ui.button(this, "立即整屏刷新一次", 17, false, new View.OnClickListener() {
            @Override public void onClick(View v) { HostLink.cmd("clear"); }
        }), weight());
        list.addView(r);
    }

    private void smoothNote(String v) {
        if (smoothNote == null) return;
        boolean preset = v != null && !v.equals("0") && v.length() > 0;
        smoothNote.setText(preset ? "已按当前模式自动配好刷新与去残影参数（单应用可在“应用优化”里单独覆盖）；如需微调底层参数请切回“自定义”。" : "");
        smoothNote.setVisibility(preset ? View.VISIBLE : View.GONE);
        if (customRefreshBox != null) customRefreshBox.setVisibility(preset ? View.GONE : View.VISIBLE);
    }

    private void buildWebviewOnly() {
        section("网页内核（实验）");
        if (!"1".equals(host.get("WEBVIEW_ALT_OK"))) {
            TextView note = Ui.text(this, "当前机型不支持实验性新网页内核（需 1 GB 内存机型及支持的宿主版本），将继续使用自带内核（Chromium 52）。", 17, false);
            note.setPadding(0, dp(8), 0, dp(8));
            list.addView(note);
            return;
        }
        stepper("网页内核", "新内核（Chromium 119）能打开更多现代网页（晋江验证码、新版登录页、网页书源）。打开后自动下载约 45 MB（占约 80 MB 空间），"
            + "重启 Android 后生效。每个用网页的应用多占几十到一百多 MB 内存。切回自带内核后重启即可恢复并清理下载包。",
            "WEBVIEW", new String[] { "自带（Chromium 52，省内存）", "新内核（Chromium 119）" }, new String[] { "stock", "alt" }, "stock",
            new OnSet() { @Override public void set(String v) {
                if ("stock".equals(v)) HostLink.cmd("webview cancel");
                handler.postDelayed(new Runnable() { @Override public void run() { showWebview(); } }, 1500);
            } });
        webviewView = Ui.text(this, "", 17, false);
        webviewView.setPadding(0, dp(6), 0, dp(6));
        list.addView(webviewView);
        LinearLayout wr = new LinearLayout(this);
        webviewBtn = Ui.button(this, "", 17, true, new View.OnClickListener() {
            @Override public void onClick(View v) { webviewAction(); }
        });
        wr.addView(webviewBtn, weight());
        list.addView(wr);
        showWebview();
        webviewWatch = new CtlWatch.Listener() {
            @Override public void onCtl(final String path) {
                if ("webview.status".equals(path)) handler.post(new Runnable() { @Override public void run() { showWebview(); } });
            }
        };
        CtlWatch.add(webviewWatch);
    }

    // ---- 系统与性能 ----------------------------------------------------------------------------------------------

    private void buildSystem() {
        section("Wi-Fi");
        stepper("Wi-Fi 省电", "自动（推荐）：平时省电，传书或大量下载时自动提速；始终省电：最省电但下载慢；运行时关闭：下载快、耗电多。回到 Kindle 和屏保时一律还给 Kindle。",
            "WIFI_PS", new String[] { "自动（推荐）", "始终省电", "运行时关闭" }, new String[] { "auto", "on", "off" }, "auto", null);
        section("省电与待机");
        if (host.containsKey("IDLE_FREEZE")) {
            stepper("看书空闲自动省电冻结", "看书不动屏幕且画面不变超过此时间，自动冻住安卓；一碰屏幕恢复，下载中自动跳过。默认关闭（日常翻页时频繁冻解冻反而增加唤醒，长停顿阅读可按需开启）。",
                "IDLE_FREEZE", new String[] { "关（推荐）", "20 秒", "30 秒", "60 秒", "120 秒" }, new String[] { "0", "20", "30", "60", "120" }, "0", null);
        }
        if (host.containsKey("SAVER_EXIT_H")) {
            stepper("长时间锁屏自动回 Kindle", "在安卓里锁屏放置超过此时间，下次唤醒时直接退回 Kindle 释放内存，避免隔夜耗电。默认已开 8 小时。",
                "SAVER_EXIT_H", new String[] { "不自动退出", "4 小时", "8 小时（推荐）", "24 小时" }, new String[] { "0", "4", "8", "24" }, "8", null);
        }
        section("内存");
        stepper("切应用自动冻结后台", "默认关闭：避免微信读书等阅读应用切到后台后因系统广播超时被强杀、下载中断或丢失缓存。仅在极度缺内存且不需要后台下载时按需开启（切走 15 秒后冻结，在“应用优化”里设了“保持后台”的应用不受影响）。",
            "BG_FREEZE", new String[] { "关（推荐）", "开（实验）" }, new String[] { "0", "1" }, "0", null);
        stepper("小内存模式", "自动：内存不到约 700 MB 的机型（PW5、KT5、KT6）自动开。开着时 Kindle 的书库程序停掉腾内存、回 Kindle 完全退出（再进要重新开机）。下次启动 Android 生效。",
            "LOWMEM", new String[] { "自动", "开", "关" }, new String[] { "auto", "on", "off" }, "auto", null);
        stepper("Android 运行时 Kindle 界面", "只暂停：回 Kindle 最快；冻结：再把 Kindle 的后台程序冻住；停掉：腾出最多内存，回 Kindle 约一分钟。自动：小内存模式用“停掉”，否则“冻结”。下次启动 Android 生效。",
            "KINDLE_UI", new String[] { "自动", "只暂停", "冻结", "停掉" }, new String[] { "auto", "keep", "freeze", "stop" }, "auto", null);
        if (host.containsKey("KLIB_BIND") && "emulator-only".equals(HostLink.kv(HostLink.KFB + "ctl/status").get("tested"))) {
            section("Kindle 书库");
            stepper("挂载 Kindle 原机书库（实验机型）", "实验机型默认不挂载原机 documents 以保护书籍；选“只读挂载”可在 VeraHome 和 KOReader 里浏览和阅读原机书籍，且内核级只读绝不改动原文件。修改后重启 Android 生效。",
                "KLIB_BIND", new String[] { "不挂载（默认）", "只读挂载（安全推荐）", "读写挂载" }, new String[] { "0", "ro", "1" }, "0", null);
        }
        section("应用预编译");
        stepper("自动优化新装的应用", "装好后在后台把应用编译成机器码，打开更快（微信读书冷启动约快一倍）。自动优化等你 5 分钟不碰屏幕才开始；"
            + "点下面的“立即优化”马上开始。电量低、内存不够、应用在前台、Android 冻结时都会先等。",
            "APP_COMPILE", new String[] { "开", "关" }, new String[] { "auto", "off" }, "auto", null);
        compileView = Ui.text(this, "", 17, false);
        compileView.setPadding(0, dp(6), 0, dp(6));
        list.addView(compileView);
        LinearLayout r = new LinearLayout(this);
        r.addView(Ui.button(this, "立即优化", 17, true, new View.OnClickListener() {
            @Override public void onClick(View v) { HostLink.cmd("compile all"); compileView.setText("已收到，正在检查要优化的应用…"); }
        }), weight());
        list.addView(r);
        showCompile();
        // 进度文件一变就改那一行字（inotify，不轮询）
        compileWatch = new CtlWatch.Listener() {
            @Override public void onCtl(final String path) {
                if ("compile.status".equals(path)) handler.post(new Runnable() { @Override public void run() { showCompile(); } });
            }
        };
        CtlWatch.add(compileWatch);
        section("输入法");
        LinearLayout ir = new LinearLayout(this);
        ir.setPadding(0, dp(6), 0, dp(6));
        ir.addView(Ui.button(this, "输入法切换", 17, false, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(HostSettingsActivity.this, ImeSettingsActivity.class)); }
        }), weight());
        list.addView(ir);
    }

    @Override protected void onResume() {
        super.onResume();
        StatusBarService.suppress(this, true);
        StatusBarService.refresh(this);
    }

    @Override protected void onPause() {
        super.onPause();
        if (rotatePending) { handler.removeCallbacks(pendingRotate); rotatePending = false; }
    }

    @Override protected void onStop() {
        StatusBarService.suppress(this, false);
        StatusBarService.refresh(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        StatusBarService.suppress(this, false);
        if (compileWatch != null) CtlWatch.remove(compileWatch);
        if (webviewWatch != null) CtlWatch.remove(webviewWatch);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private String appName(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    private static String mb(long b) { return String.format(java.util.Locale.US, "%.1f", b / 1048576.0); }

    /** 网页内核那一行下面的状态和按钮：下载进度 / 校验 / 下好了要重启 / 出错重试 / 关掉后要重启。 */
    private void showWebview() {
        if (webviewView == null) return;
        Map<String, String> set = HostLink.kv(HostLink.KFB + "settings");
        Map<String, String> st = HostLink.kv(HostLink.CTL + "webview.status");
        String sel = host.get("WEBVIEW"); if (sel == null || sel.length() == 0) sel = "stock";
        String live = set.get("WEBVIEW_LIVE"), state = st.get("state");
        boolean have = "1".equals(set.get("WEBVIEW_HAVE")) || "ready".equals(state);
        long got = HostLink.num(st, "got", 0), total = HostLink.num(st, "total", 0);
        String text, act = null, label = null;
        if ("alt".equals(sel)) {
            if ("alt".equals(live)) text = "正在用新内核（Chromium 119）。";
            else if (have) { text = "新内核已下载。重启 Android 后生效（约 1 分钟，正在运行的应用会被关闭）。"; act = "restart"; label = "现在重启 Android"; }
            else if ("downloading".equals(state)) {
                text = "正在下载新内核：" + mb(got) + " / " + mb(total) + " MB" + (total > 0 ? "（" + (got * 100 / total) + "%）" : "")
                    + (st.get("src") != null ? "\n来源：" + st.get("src") : "") + "\n下完以后重启 Android 才会换。可以离开这一页，下载在后台继续。";
                act = "cancel"; label = "取消下载";
            } else if ("verifying".equals(state)) text = "下载完成，正在校验…";
            else if ("error".equals(state)) { text = "没有下载成功：" + String.valueOf(st.get("msg")); act = "get"; label = "重试"; }
            else { text = "等待开始下载（约 45 MB）…"; act = "get"; label = "开始下载"; }
        } else {
            if ("alt".equals(live)) { text = "重启 Android 后换回自带内核，并删掉下载的新内核（约 80 MB）和它的网页数据。"; act = "restart"; label = "现在重启 Android"; }
            else text = "";
        }
        webviewView.setText(text);
        webviewView.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
        webviewAct = act;
        if (label != null) webviewBtn.setText(label);
        webviewBtn.setVisibility(act != null ? View.VISIBLE : View.GONE);
    }

    private void webviewAction() {
        if ("restart".equals(webviewAct)) {
            new VeraDialog.Builder(this).title("重启 Android").message("现在重启 Android 换网页内核？正在运行的应用会被关闭，约 1 分钟。")
                .negative("稍后", null).positive("现在重启", new Runnable() { @Override public void run() { HostLink.cmd("restart"); } }).show();
        } else if (webviewAct != null) {
            HostLink.cmd("webview " + webviewAct);
            handler.postDelayed(new Runnable() { @Override public void run() { showWebview(); } }, 1500);
        }
    }

    private void showCompile() {
        if (compileView == null) return;
        Map<String, String> m = HostLink.kv(HostLink.CTL + "compile.status");
        String st = m.get("state");
        String s;
        if (st == null) s = "状态：还没有记录（开机 1 分钟后开始检查）";
        else if (st.equals("running")) s = "正在优化 " + appName(String.valueOf(m.get("pkg"))) + "（" + m.get("index") + " / " + m.get("total") + "）";
        else if (st.equals("waiting") || st.equals("paused")) {
            String r = m.get("reason"), msg = m.get("msg"), code = m.get("code"), pkg = m.get("pkg");
            String why = "battery".equals(r) ? "电量低" : "memory".equals(r) ? "内存不够" : "frozen".equals(r) ? "Android 冻结中" : "off".equals(r) ? "已关闭" : String.valueOf(r);
            // 等的是哪个应用（前台 / 还在后台跑）写上名字
            String who = pkg != null && ("foreground".equals(code) || "running".equals(code)) ? appName(pkg) + (("foreground".equals(code)) ? " 在前台，" : " ") : "";
            String head = "starting".equals(code) ? "" : st.equals("paused") ? "暂停：" : "等待：";
            // 宿主给了现成的一句话（等什么、什么时候开始）就直接用
            if (msg != null && msg.length() > 0) s = head + who + msg;
            else s = head + why + "，条件满足后继续";
            if ("1".equals(m.get("manual")) && !"starting".equals(code)) s = "已按“立即优化”。" + s;
            long n = HostLink.num(m, "total", 0);
            if (n > 0) s += "\n还有 " + Math.max(1, n - HostLink.num(m, "index", 0)) + " 个应用要优化";
        } else {
            String msg = m.get("msg");
            s = msg != null && msg.length() > 0 ? msg : "空闲：没有要优化的应用";
        }
        String last = m.get("last");
        if (last != null && last.length() > 0) {
            String[] a = last.split(" ");
            if (a.length >= 2) s += "\n上一个：" + appName(a[0]) + (a[1].equals("ok") ? " 完成" : " 失败") + (a.length >= 3 ? "（" + a[2] + " 秒）" : "");
        }
        compileView.setText(s);
    }
}
