// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Send-to-Kindle inbox: shows the device email and pulls pending files to Books/. */
public class InboxActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout list;
    private View syncBtn;
    private volatile boolean syncing;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);

        TextView title = Ui.text(this, "邮箱收件", 30, true);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);

        TextView addr = Ui.text(this, "d-" + Update.deviceId(this) + "@wenjin.jp", 14, false);
        addr.setTextColor(Color.DKGRAY);
        addr.setPadding(0, 0, 0, dp(4));
        page.addView(addr);

        status = Ui.text(this, "正在检查邮箱…", 15, false);
        status.setPadding(0, dp(4), 0, dp(6));
        page.addView(status);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(6), 0, dp(2));
        actions.addView(Ui.button(this, "返回", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        syncBtn = Ui.button(this, "同步", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) { sync(); }
        });
        actions.addView(syncBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        Blocks.restyle(this, page, title, actions, "Inbox / mail to Kindle");

        sync();
    }

    private void sync() {
        if (syncing) return;
        syncing = true;
        if (syncBtn != null) syncBtn.setEnabled(false);
        status.setText("正在同步到 Books…");
        new Thread(new Runnable() {
            @Override public void run() {
                InboxSync.sync(InboxActivity.this, new InboxSync.Result() {
                    @Override public void done(final int count, final String error) {
                        if (count > 0) {
                            try { Lib.scan(InboxActivity.this, 0); } catch (Throwable ignored) { }
                        }
                        ui.post(new Runnable() { @Override public void run() {
                            syncing = false;
                            if (isFinishing()) return;
                            if (syncBtn != null) syncBtn.setEnabled(true);
                            if (error != null) { status.setText("同步失败"); say(error); return; }
                            status.setText(count > 0 ? "已收 " + count + " 个文件到 Books" : "没有待收文件");
                            VeraToast.show(InboxActivity.this, count > 0 ? "已放到书架" : "邮箱是空的");
                        }});
                    }
                });
            }
        }, "InboxUI").start();
    }

    private void say(String s) { VeraToast.show(this, s); }
    private int dp(int v) { return Ui.dp(this, v); }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
    }

    @Override protected void onPause() {
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }
}
