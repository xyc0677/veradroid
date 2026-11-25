// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;

/**
 * 传输入口（主页右上角的上传图标）：开关传输服务，显示手机/电脑要打开的地址和 PIN。
 * 上传、文件管理、应用和链接下载都在那个网页里完成；这里只负责把入口放到主页，不再埋进设置。
 */
public class TransferActivity extends Activity {
    private final Handler handler = new Handler();
    private TextView state, url, pin, tasks;
    private TextView startBtn, copyBtn;
    private Transfer transfer;

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 2000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        transfer = Transfer.get(this);
        if (!transfer.running()) transfer.start();   // 打不开时 refresh() 把原因写在屏幕上

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(20), dp(12), dp(20), dp(12));
        setContentView(page);
        Perf.measure(page, "transfer");

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Icons.bar(this, Icons.Kind.BACK, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        TextView title = Ui.text(this, "传输与管理", 26, true);
        title.setPadding(dp(12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(top);
        View topRule = Ui.rule(this, 2, Color.BLACK);
        page.addView(topRule);
        Blocks.restyleTop(this, page, top, topRule, "Transfer / web", "传输与管理", null);

        state = Ui.text(this, "", 18, true);
        state.setPadding(0, dp(12), 0, dp(4));
        page.addView(state);

        url = Ui.text(this, "", 22, true);
        url.setPadding(0, dp(4), 0, dp(4));
        page.addView(url);

        pin = Ui.text(this, "", 34, true);
        pin.setPadding(0, dp(2), 0, dp(6));
        page.addView(pin);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        startBtn = Ui.button(this, "关闭传输", 18, false, new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        buttons.addView(startBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        copyBtn = Ui.button(this, "复制地址", 18, true, new View.OnClickListener() {
            @Override public void onClick(View v) { copy(); }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        bp.leftMargin = dp(8);
        buttons.addView(copyBtn, bp);
        page.addView(buttons);
        if (Blocks.on(this)) {   // 方块风格：状态、地址、PIN 放进一个双线框，按钮在框下面
            int at = page.indexOfChild(state);
            LinearLayout card = Blocks.boxed(this, state, url, pin);
            page.addView(card, at, Blocks.match());
            ((LinearLayout.LayoutParams) buttons.getLayoutParams()).topMargin = dp(12);
        }

        TextView hint = Ui.text(this, "手机或电脑连同一个 Wi-Fi，用浏览器打开上面的地址，输入 PIN：传书和 APK、管理文件夹和应用、查看存储和内存占用、截屏、SSH 诊断和日志都在那个网页里。只在同一个 Wi-Fi 里能打开；" + idleText() + "没人用会自动关闭。", 16, false);
        hint.setPadding(0, dp(14), 0, dp(8));
        page.addView(hint);

        tasks = Ui.text(this, "", 15, false);
        tasks.setPadding(0, dp(8), 0, 0);
        page.addView(tasks);

        refresh();
    }

    private void toggle() {
        if (transfer.running()) transfer.stop();
        else if (!transfer.start()) VeraToast.show(this, "传输没打开：" + transfer.lastError());
        refresh();
    }

    /** 内容没变就不 setText：以前每 2 秒无条件写一遍，墨水屏跟着重画一遍。 */
    private static void set(TextView v, String t) { if (!t.equals(String.valueOf(v.getText()))) v.setText(t); }

    private void refresh() {
        if (transfer == null) return;
        boolean on = transfer.running();
        set(state, on ? "传输已打开" : "传输已关闭");
        set(startBtn, on ? "关闭传输" : "打开传输");
        if (on) {
            Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
            String ip = st.get("lanip");
            if (ip == null || ip.length() == 0) ip = st.get("ip");
            set(url, ip == null || ip.length() == 0 ? "正在获取 Wi-Fi 地址…（先连上 Wi-Fi）" : "http://" + ip + ":" + transfer.port());
            set(pin, "PIN  " + transfer.pin());
            StringBuilder sb = new StringBuilder();
            String t = transfer.statusText();
            if (t != null && t.length() > 0) sb.append(t);
            sb.append("已收到 ").append(transfer.received()).append(" 本\n");
            set(tasks, sb.toString());
        } else {
            String err = transfer.lastError();
            set(url, "");
            set(pin, "");
            // 以前打不开时只写“传输已关闭”，点“打开传输”也没有任何反应（老机型 0.8.10 用户报告）
            set(tasks, err.length() == 0 ? "" : "上次没打开：" + err + "。点“打开传输”再试；还不行请在设置里上传诊断日志。");
        }
    }

    private void copy() {
        String u = url.getText().toString();
        if (!u.startsWith("http")) { VeraToast.show(this, "还没有地址"); return; }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("VeraDroid 传输", u));
        VeraToast.show(this, "地址已复制");
    }

    @Override protected void onResume() {
        super.onResume();
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 2000);   // 页面刚搭好时已经填过一次
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }

    private int dp(int v) { return Ui.dp(this, v); }

    private String idleText() {
        int m = Store.prefs(this, "home").getInt("xfer_idle", 30);
        return m % 60 == 0 ? (m / 60) + " 小时" : m + " 分钟";
    }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }
}
