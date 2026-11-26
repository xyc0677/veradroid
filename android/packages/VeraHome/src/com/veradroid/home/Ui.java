// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Plain black-and-white widgets for the e-ink panel: thin black frames, pressed = inverted, no shadows or ripples. */
final class Ui {
    /** 时间格式：设置 → 外观里可以选 12 小时制（默认 24 小时）。 */
    static String clockPattern(Context c) {
        try { return Store.prefs(c, "home").getBoolean("clock12", false) ? "a h:mm" : "HH:mm"; } catch (Throwable t) { return "HH:mm"; }
    }
    static String clock(Context c) {
        return new java.text.SimpleDateFormat(clockPattern(c), java.util.Locale.CHINA).format(new java.util.Date());
    }

    private Ui() { }

    /**
     * 后台线程（扫书库、读应用列表、解码图标 / 封面 / 壁纸……）一开始调一下：降成后台优先级。单核的老机型（PW4 i.MX6SLL）上
     * 后台线程和主线程同优先级时各分一半 CPU，主页的第一帧和点按后的重画都要多等一倍；降下来以后主线程闲着时它照样全速跑。
     */
    /** 单核机型（PW4 i.MX6SLL、KT4 等）：主线程上能省的预先工作（预搭设置页之类）少做一些。 */
    static final boolean ONE_CORE = Runtime.getRuntime().availableProcessors() <= 1;

    static void lowPriority() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) { }
    }

    /** 后台优先级的单线程执行器（{@link #lowPriority}）。 */
    static java.util.concurrent.ExecutorService lowExecutor(final String name) {
        return java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(final Runnable r) {
                Thread t = new Thread(new Runnable() { @Override public void run() { lowPriority(); r.run(); } }, name);
                t.setDaemon(true);
                return t;
            }
        });
    }

    static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static TextView text(Context c, String s, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.BLACK);
        Fonts.apply(t, bold);
        return t;
    }

    static Drawable frame(Context c, boolean filled) {
        // 方块风格：直角黑框（墨水屏上不画按下状态，见 fast()），和 Blocks 的控件一样
        if (Blocks.on(c)) return filled ? Blocks.plainFilled(c) : Blocks.plain(c);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(filled ? Color.BLACK : Color.WHITE);
        normal.setStroke(2, Color.BLACK);
        normal.setCornerRadius(dp(c, 6));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(filled ? Color.WHITE : Color.BLACK);
        pressed.setStroke(2, Color.BLACK);
        pressed.setCornerRadius(dp(c, 6));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, pressed);
        s.addState(new int[] {}, normal);
        return s;
    }

    static Button button(Context c, String label, float sp, boolean filled, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(sp);
        b.setTypeface(Fonts.typeface(c));
        b.setAllCaps(false);
        b.setMinWidth(dp(c, 56));
        b.setMinimumWidth(dp(c, 56));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        int idle = filled ? Color.WHITE : Color.BLACK, press = filled ? Color.BLACK : Color.WHITE;
        b.setTextColor(new ColorStateList(new int[][] { { android.R.attr.state_pressed }, {} }, new int[] { press, idle }));
        b.setBackground(frame(c, filled));
        if (Blocks.on(c)) { b.setTextColor(idle); Fonts.apply(b, true); b.setMinHeight(dp(c, 48)); b.setMinimumHeight(dp(c, 48)); }
        if (l != null) b.setOnClickListener(l);
        fast(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 点按在抬手时立刻生效，不先画“按下”状态：墨水屏上按下高亮是多出来的一帧（显示桥还要为这块小变化等 70 ms），
     * 以前的流程是 按下→高亮帧→抬起→（排队）点击→新页面。可以长按的控件不动，仍走系统的处理。
     * 手指移出控件（或被外面的滚动接管）就不算点击。
     */
    static void fast(View v) {
        final int slop = android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            private boolean armed;
            @Override public boolean onTouch(View view, android.view.MotionEvent e) {
                if (view.isLongClickable() || !view.isEnabled()) return false;
                switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    armed = true;
                    Perf.touch(e);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (e.getX() < -slop || e.getY() < -slop || e.getX() > view.getWidth() + slop || e.getY() > view.getHeight() + slop) armed = false;
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (armed) { armed = false; clickAfterDispatch(view); }
                    return true;
                default:
                    armed = false;
                    return true;
                }
            }
        });
    }

    /**
     * 抬手的点按排到这次触摸分发之后再执行（和系统 View.onTouchEvent 的 PerformClick 一样 post 出去）。
     * 点按常常重搭页面（removeAllViews / 换页）：在分发途中同步改控件树，ViewGroup 正在遍历的 TouchTarget 会指向已回收的
     * 对象，resetCancelNextUpFlag 空指针，主页崩溃（KT5 上有卡住的第二个触点时）。只是往后排一条消息，不会变慢。
     */
    static void clickAfterDispatch(final View v) {
        if (!v.post(new Runnable() { @Override public void run() { if (v.isAttachedToWindow()) v.performClick(); } })) v.performClick();
    }

    static View rule(Context c, int px, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px));
        return v;
    }

    /**
     * E-ink friendly EditText: disables Android's 500 ms blinking cursor (zero idle EPD refreshes)
     * and draws a crisp static solid-black insertion caret whenever focused.
     */
    static final class EinkEditText extends EditText {
        private final Paint caret = new Paint();

        EinkEditText(Context c) {
            super(c);
            setCursorVisible(false);
            caret.setColor(Color.BLACK);
            caret.setStyle(Paint.Style.FILL);
            caret.setAntiAlias(false);
        }

        @Override protected void onSelectionChanged(int selStart, int selEnd) {
            super.onSelectionChanged(selStart, selEnd);
            invalidate();
        }

        @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previouslyFocusedRect) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!isFocused() || !isEnabled()) return;
            int start = getSelectionStart(), end = getSelectionEnd();
            if (start < 0 || start != end) return;
            Layout layout = getLayout();
            if (layout == null) return;
            int len = getText() == null ? 0 : getText().length();
            if (start > len) start = len;
            int line = layout.getLineForOffset(start);
            float cw = Math.max(2f, dp(getContext(), 2));
            float x = getCompoundPaddingLeft() + layout.getPrimaryHorizontal(start);
            int minX = getScrollX() + getCompoundPaddingLeft();
            int maxX = getScrollX() + getWidth() - getCompoundPaddingRight() - (int) Math.ceil(cw);
            if (maxX >= minX) {
                if (x < minX) x = minX;
                else if (x > maxX) x = maxX;
            }
            int topPad = getExtendedPaddingTop();
            int vSpace = getHeight() - topPad - getExtendedPaddingBottom();
            int vGrav = getGravity() & android.view.Gravity.VERTICAL_GRAVITY_MASK;
            int vOff = 0;
            if (vGrav == android.view.Gravity.BOTTOM) vOff = Math.max(0, vSpace - layout.getHeight());
            else if (vGrav == android.view.Gravity.CENTER_VERTICAL) vOff = Math.max(0, (vSpace - layout.getHeight()) / 2);
            float top = topPad + vOff + layout.getLineTop(line) + 1f;
            float bot = topPad + vOff + layout.getLineBottom(line) - 1f;
            if (bot <= top + 4f) bot = top + Math.max(12f, getTextSize());
            canvas.drawRect(x, top, x + cw, bot, caret);
        }
    }

    static EditText editText(Context c) {
        return new EinkEditText(c);
    }
}
