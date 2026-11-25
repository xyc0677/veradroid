package com.veradroid.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设置 → 设备与更新 → 锁屏密码：设 / 改 / 关。输入用页面里自己的大数字键（不弹输入法），一步一页、没有动画。
 * 改和关都要先输对当前密码（输错也计入“连续 5 次等 30 秒”）。规则和存法见 LockScreen。
 */
public class LockSettingsActivity extends Activity {
    private static final int IDLE = 0, OLD_FOR_CHANGE = 1, OLD_FOR_OFF = 2, NEW1 = 3, NEW2 = 4;
    private int step = IDLE;
    private String first;
    private final StringBuilder typed = new StringBuilder();
    private LinearLayout page;
    private TextView prompt, dots, msg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);
        render();
    }

    private int dp(int v) { return Ui.dp(this, v); }

    private void render() {
        page.removeAllViews();
        TextView title = Ui.text(this, "锁屏密码", 30, true);
        title.setPadding(0, dp(8), 0, dp(8));
        page.addView(title);
        if (Blocks.on(this)) Blocks.restyle(this, page, title, null, "Security / PIN");
        if (step == IDLE) renderIdle(); else renderPad();
    }

    private void renderIdle() {
        final boolean on = LockScreen.enabled(this);
        line(on ? "已开启" : "未开启", 22, true);
        line("开启后，VeraDroid 开机和每次从 Kindle 屏保醒来时先要输入 4–6 位数字密码。"
            + "连续输错 5 次要等 30 秒。锁屏上的“回到 Kindle”、四角长按回 Kindle 和电源键不需要密码。", 16, false);
        line("忘了密码：用 USB 连电脑，在 Kindle 存储的 veradroid 文件夹里新建一个名为 reset-pin.txt 的文件（内容随意），"
            + "拔线后再打开 VeraDroid（或从屏保醒来），密码就被清除，文件自动删掉。", 16, false);
        line("密码只以加盐哈希存在本机 VeraDroid 桌面自己的私有目录里，不上传。", 15, false);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(16), 0, 0);
        if (on) {
            actions.addView(Ui.button(this, "修改密码", 18, true, go(OLD_FOR_CHANGE)), weight());
            actions.addView(Ui.button(this, "关闭密码", 18, false, go(OLD_FOR_OFF)), weight());
        } else {
            actions.addView(Ui.button(this, "设置密码", 18, true, go(NEW1)), weight());
        }
        page.addView(actions);
        View gap = new View(this);
        page.addView(gap, new LinearLayout.LayoutParams(1, 0, 1));
        if (!Blocks.on(this)) page.addView(Ui.button(this, "返回", 18, false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View.OnClickListener go(final int s) {
        return new View.OnClickListener() { @Override public void onClick(View v) { step = s; typed.setLength(0); first = null; render(); } };
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }

    private void line(String s, float sp, boolean bold) {
        TextView t = Ui.text(this, s, sp, bold);
        t.setPadding(0, dp(6), 0, dp(6));
        page.addView(t);
    }

    private void renderPad() {
        String p;
        switch (step) {
        case OLD_FOR_CHANGE: case OLD_FOR_OFF: p = "输入当前密码"; break;
        case NEW1: p = "输入新密码（4–6 位数字）"; break;
        default: p = "再输一次新密码";
        }
        prompt = Ui.text(this, p, 22, true);
        prompt.setGravity(Gravity.CENTER);
        page.addView(prompt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dots = Ui.text(this, " ", 36, true);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(12), 0, dp(4));
        page.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        msg = Ui.text(this, " ", 16, false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, 0, 0, dp(10));
        page.addView(msg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int sh = getResources().getDisplayMetrics().heightPixels, sw = getResources().getDisplayMetrics().widthPixels;
        int keyH = Math.max(dp(60), sh / 11), keyW = Math.min((sw - dp(32)) / 3 - dp(12), dp(200));
        String[] keys = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "删除", "0", "确定" };
        for (int r = 0; r < 4; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            for (int k = 0; k < 3; k++) {
                final String key = keys[r * 3 + k];
                Button b = Ui.button(this, key, key.length() == 1 ? 30 : 20, key.equals("确定"), new View.OnClickListener() {
                    @Override public void onClick(View v) { press(key); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(keyW, keyH);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                row.addView(b, lp);
            }
            page.addView(row);
        }
        View gap = new View(this);
        page.addView(gap, new LinearLayout.LayoutParams(1, 0, 1));
        page.addView(Ui.button(this, "取消", 18, false, go(IDLE)),
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if ((step == OLD_FOR_CHANGE || step == OLD_FOR_OFF) && LockScreen.waitMs(this) > 0) waitMsg();
    }

    private void waitMsg() { msg.setText("已连续输错 " + LockScreen.fails(this) + " 次，请 " + ((LockScreen.waitMs(this) + 999) / 1000) + " 秒后再试"); }

    private void showDots() {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < typed.length(); i++) { if (i > 0) d.append(' '); d.append('*'); }
        dots.setText(d.length() == 0 ? " " : d.toString());
    }

    private void press(String key) {
        if (key.equals("删除")) { if (typed.length() > 0) typed.setLength(typed.length() - 1); showDots(); return; }
        if (!key.equals("确定")) { if (typed.length() < LockScreen.MAX) typed.append(key); showDots(); return; }
        String pin = typed.toString();
        typed.setLength(0);
        switch (step) {
        case OLD_FOR_CHANGE: case OLD_FOR_OFF:
            if (LockScreen.waitMs(this) > 0) { waitMsg(); showDots(); return; }
            if (!LockScreen.check(this, pin)) {
                if (LockScreen.waitMs(this) > 0) waitMsg(); else msg.setText("密码不对（已错 " + LockScreen.fails(this) + " 次）");
                showDots();
                return;
            }
            if (step == OLD_FOR_OFF) { LockScreen.clear(this); VeraToast.show(this, "锁屏密码已关闭"); step = IDLE; render(); return; }
            step = NEW1; render(); return;
        case NEW1:
            if (!LockScreen.valid(pin)) { msg.setText("要 4–6 位数字"); showDots(); return; }
            first = pin; step = NEW2; render(); return;
        default:
            if (!pin.equals(first)) { first = null; step = NEW1; render(); msg.setText("两次不一样，请重新输入"); return; }
            if (LockScreen.set(this, pin)) VeraToast.show(this, "锁屏密码已设置"); else VeraToast.show(this, "没能保存密码");
            step = IDLE; render();
        }
    }

    @Override public void onBackPressed() {
        if (step != IDLE) { step = IDLE; render(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() { super.onResume(); Panel.closeOnDraw(getWindow().getDecorView()); }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }
}
