// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Battery and reading summary (built-in + 3rd-party readers + optional same-account multi-device sync). */
public class StatsActivity extends Activity {
    private LinearLayout list;
    private TextView sub;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);

        TextView title = Ui.text(this, "使用统计", 30, true);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);

        sub = Ui.text(this, Stats.syncStatusLine(this), 14, false);
        sub.setTextColor(Color.DKGRAY);
        sub.setPadding(0, 0, 0, dp(8));
        page.addView(sub);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(Ui.button(this, "返回", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(Ui.button(this, "同步/刷新", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Stats.syncEnabled(StatsActivity.this)) {
                    triggerSync(true);
                } else {
                    render();
                }
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        Blocks.restyle(this, page, title, actions, "Stats / reading & battery");
        render();
        if (Stats.syncEnabled(this)) {
            Stats.syncAsync(this, false, false, new Stats.SyncCallback() {
                @Override public void onResult(boolean ok, String msg) {
                    runOnUiThread(new Runnable() { @Override public void run() { if (!isFinishing()) render(); } });
                }
            });
        }
    }

    private void triggerSync(final boolean toast) {
        if (toast) VeraToast.show(this, "正在同步阅读统计…");
        Stats.syncAsync(this, true, false, new Stats.SyncCallback() {
            @Override public void onResult(final boolean ok, final String msg) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    render();
                    if (toast) VeraToast.show(StatsActivity.this, msg);
                } });
            }
        });
    }

    private void render() {
        sub.setText(Stats.syncStatusLine(this));
        list.removeAllViews();
        section("电量", Stats.batteryLines(this));
        section("阅读总览（含第三方阅读应用）", Stats.readingLines(this));
        section("按应用统计", Stats.appLines(this));

        list.addView(Blocks.sectionTitle(this, "多设备互通与管理", 22));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(8));

        final boolean bound = Stats.isBound(this);
        final boolean on = Stats.syncEnabled(this);
        String syncLabel = !bound ? "绑定账号开启互通" : (on ? "多设备互通：已开" : "多设备互通：已关");
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp1.rightMargin = dp(8);
        row.addView(Ui.button(this, syncLabel, 15, on, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Stats.isBound(StatsActivity.this)) {
                    BindGate.ensureBound(
                        StatsActivity.this,
                        "阅读统计多设备互通",
                        "绑定 VeraDroid 账号后，同一账号下的多台 Kindle 阅读时长将自动汇总互通，且可随时在此开关。",
                        new Runnable() { @Override public void run() {
                            Stats.setSyncEnabled(StatsActivity.this, true);
                            triggerSync(true);
                        } }
                    );
                } else {
                    boolean next = !Stats.syncEnabled(StatsActivity.this);
                    Stats.setSyncEnabled(StatsActivity.this, next);
                    render();
                    if (next) triggerSync(true);
                    else VeraToast.show(StatsActivity.this, "已切换为仅本机统计");
                }
            }
        }), lp1);

        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        row.addView(Ui.button(this, "重置本机阅读统计", 15, false, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReset(); }
        }), lp2);
        list.addView(row);

        TextView tip = Ui.text(this, "说明：仅统计点亮使用中的有效阅读时长，合盖休眠与锁屏期间不再计入。如旧版本残留异常数据，可点右侧重置。", 13, false);
        tip.setTextColor(Color.DKGRAY);
        tip.setPadding(0, dp(2), 0, dp(10));
        list.addView(tip);
    }

    private void confirmReset() {
        new VeraDialog.Builder(this)
            .title("重置本机阅读统计")
            .message("将清空本机的今日、本周、累计及各应用阅读时长记录（不影响书签和阅读进度）。确定继续？")
            .negative("取消", null)
            .positive("确定重置", new Runnable() {
                @Override public void run() {
                    Stats.resetReading(StatsActivity.this, new Runnable() {
                        @Override public void run() {
                            runOnUiThread(new Runnable() { @Override public void run() {
                                if (isFinishing()) return;
                                render();
                                VeraToast.show(StatsActivity.this, "本机阅读统计已重置");
                            } });
                        }
                    });
                }
            })
            .show();
    }

    private void section(String title, String[] lines) {
        list.addView(Blocks.sectionTitle(this, title, 22));
        for (String line : lines) {
            TextView t = Ui.text(this, line, 16, false);
            t.setPadding(0, dp(5), 0, dp(5));
            list.addView(t);
            list.addView(Blocks.sep(this));
        }
    }

    private int dp(int v) { return Ui.dp(this, v); }

    @Override protected void onResume() {
        super.onResume();
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
        Stats.sampleBattery(this);
        render();
    }
    @Override protected void onPause() {
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }
}
