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

/** Battery and reading summary, in the same plain e-ink list style as the store. */
public class StatsActivity extends Activity {
    private LinearLayout list;

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

        TextView sub = Ui.text(this, "只在本机统计，不上传。", 14, false);
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
        actions.addView(Ui.button(this, "刷新", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) { render(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        Blocks.restyle(this, page, title, actions, "Stats / on this device");
        render();
    }

    private void render() {
        list.removeAllViews();
        section("电量", Stats.batteryLines(this));
        section("阅读", Stats.readingLines(this));
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
    }
    @Override protected void onPause() {
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }
}
