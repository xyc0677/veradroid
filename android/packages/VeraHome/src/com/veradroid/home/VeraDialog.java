// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** All VeraDroid dialogs are drawn here: white paper, thin black frame, no Holo/Material. */
final class VeraDialog {
    interface OnPick { void onPick(int index); }

    static final class Builder {
        private final Activity a;
        private String title, message, negative, positive;
        private String[] items;
        private int selected = -1;
        private View extra;
        private OnPick pick;
        private Runnable negativeAction, positiveAction;
        private boolean positiveKeepOpen;

        Builder(Activity a) { this.a = a; }

        Builder title(String s) { title = s; return this; }
        Builder message(String s) { message = s; return this; }
        Builder view(View v) { extra = v; return this; }
        Builder items(String[] s, OnPick p) { items = s; pick = p; return this; }
        Builder selected(int i) { selected = i; return this; }
        Builder negative(String label, Runnable action) { negative = label; negativeAction = action; return this; }
        Builder positive(String label, Runnable action) { positive = label; positiveKeepOpen = false; positiveAction = action; return this; }
        Builder positive(String label, boolean keepOpen, Runnable action) { positive = label; positiveKeepOpen = keepOpen; positiveAction = action; return this; }

        Dialog show() {
            final Dialog d = new Dialog(a);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setCanceledOnTouchOutside(true);

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(22), dp(18), dp(22), dp(16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setStroke(2, Color.BLACK);
            bg.setCornerRadius(dp(4));
            box.setBackground(bg);
            // 方块风格：对话框也是直角双线框 + 投影，选项是直角框，选中的黑底
            final boolean blk = Blocks.on(a);
            if (blk) {
                box.setBackground(Blocks.frameDouble(a));
                box.setPadding(dp(22), dp(18), dp(22) + Blocks.shade(a), dp(16) + Blocks.shade(a));
            }

            if (title != null && title.length() > 0) {
                TextView t = Ui.text(a, title, 24, true);
                t.setPadding(0, 0, 0, dp(6));
                box.addView(t);
            }

            LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            if (message != null && message.length() > 0) {
                TextView m = Ui.text(a, message, 16, false);
                m.setTextColor(blk ? Color.BLACK : Color.DKGRAY);   // 方块风格只用纯黑白
                m.setPadding(0, dp(4), 0, dp(12));
                body.addView(m);
            }
            if (extra != null) {
                // 闪烁的光标会让墨水屏每 0.5 秒刷一小块：普通 EditText 关掉系统闪烁光标，EinkEditText 自带静态不闪烁光标
                if (extra instanceof EditText) ((EditText) extra).setCursorVisible(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(12);
                body.addView(extra, lp);
            }
            if (items != null) {
                for (int i = 0; i < items.length; i++) {
                    final int at = i;
                    TextView b = Ui.text(a, items[i], 17, i == selected);
                    b.setGravity(Gravity.CENTER_VERTICAL);
                    b.setPadding(dp(14), dp(13), dp(14), dp(13));
                    b.setBackground(blk ? (i == selected ? Blocks.plainFilled(a) : Blocks.plain(a)) : Ui.frame(a, i == selected));
                    if (i == selected) b.setTextColor(Color.WHITE);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            d.dismiss();
                            if (pick != null) pick.onPick(at);
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dp(5);
                    body.addView(b, lp);
                }
            }

            final int maxBodyH = Math.max(dp(160), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.65f));
            final PageScrollView sv = new PageScrollView(a) {
                @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int mode = MeasureSpec.getMode(heightMeasureSpec);
                    int size = MeasureSpec.getSize(heightMeasureSpec);
                    int cap = (mode == MeasureSpec.UNSPECIFIED) ? maxBodyH : Math.min(size, maxBodyH);
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
                }
            };
            sv.setBackgroundColor(Color.TRANSPARENT);
            sv.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final LinearLayout pager = new LinearLayout(a);
            pager.setOrientation(LinearLayout.HORIZONTAL);
            pager.setGravity(Gravity.CENTER_VERTICAL);
            pager.setPadding(0, dp(8), 0, 0);
            pager.setVisibility(View.GONE);
            final TextView pageInfo = Ui.text(a, "", 14, false);
            pageInfo.setGravity(Gravity.CENTER);
            final View prevBtn = Ui.button(a, "▲", 14, false, new View.OnClickListener() {
                @Override public void onClick(View v) { if (sv.page(-1)) sv.firePage(); }
            });
            final View nextBtn = Ui.button(a, "▼", 14, false, new View.OnClickListener() {
                @Override public void onClick(View v) { if (sv.page(1)) sv.firePage(); }
            });
            if (blk) {
                prevBtn.setBackground(Blocks.plain(a));
                nextBtn.setBackground(Blocks.plain(a));
            }
            ((LinearLayout.LayoutParams) prevBtn.getLayoutParams()).leftMargin = 0;
            ((LinearLayout.LayoutParams) nextBtn.getLayoutParams()).leftMargin = 0;
            pager.addView(prevBtn);
            pager.addView(pageInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            pager.addView(nextBtn);
            box.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final Runnable syncPager = new Runnable() {
                @Override public void run() {
                    int pages = sv.pages();
                    boolean multi = pages > 1;
                    int vis = multi ? View.VISIBLE : View.GONE;
                    if (pager.getVisibility() != vis) pager.setVisibility(vis);
                    if (multi) {
                        int cur = sv.currentPage();
                        String label = cur + " / " + pages;
                        if (!label.contentEquals(pageInfo.getText())) pageInfo.setText(label);
                        prevBtn.setEnabled(cur > 1);
                        nextBtn.setEnabled(cur < pages);
                    }
                }
            };
            sv.setOnPage(syncPager);
            sv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    sv.post(syncPager);
                }
            });

            if (negative != null || positive != null) {
                LinearLayout row = new LinearLayout(a);
                row.setGravity(Gravity.END);
                row.setPadding(0, dp(14), 0, 0);
                if (negative != null) {
                    View b = Ui.button(a, negative, 16, false, new View.OnClickListener() {
                        @Override public void onClick(View v) { d.dismiss(); if (negativeAction != null) negativeAction.run(); }
                    });
                    if (blk) b.setBackground(Blocks.plain(a));
                    row.addView(b);
                }
                if (positive != null) {
                    View b = Ui.button(a, positive, 16, true, new View.OnClickListener() {
                        @Override public void onClick(View v) { if (!positiveKeepOpen) d.dismiss(); if (positiveAction != null) positiveAction.run(); }
                    });
                    if (blk) b.setBackground(Blocks.plainFilled(a));
                    row.addView(b);
                }
                box.addView(row);
            }

            d.setContentView(box);
            d.show();
            Window w = d.getWindow();
            if (w != null) {
                View dc = d.findViewById(android.R.id.content);
                if (dc instanceof android.widget.FrameLayout) ((android.widget.FrameLayout) dc).setForeground(null);
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setWindowAnimations(0);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);   // 墨水屏不压暗背景，黑框已经够清楚
                int width = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.86);
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setGravity(Gravity.CENTER);
            }
            return d;
        }

        private int dp(int v) { return Ui.dp(a, v); }
    }
}
