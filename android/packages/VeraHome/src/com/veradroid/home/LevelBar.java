// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** A horizontal level bar (brightness / warmth). Tap or drag anywhere on it; the value is committed when the finger lifts. */
class LevelBar extends View {
    interface Listener { void onCommit(int value); }

    private final Paint paint = new Paint();
    private int max = 24, value = 0, shown = -1;
    private Listener listener;

    LevelBar(Context c) {
        super(c);
        paint.setAntiAlias(false);
    }

    void setListener(Listener l) { listener = l; }
    void setMax(int m) { max = Math.max(1, m); invalidate(); }
    void setValue(int v) { value = Math.max(0, Math.min(max, v)); shown = -1; invalidate(); }
    int getValue() { return value; }
    int getMax() { return max; }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int v = shown >= 0 ? shown : value;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(Color.BLACK);
        c.drawRect(2, 2, w - 2, h - 2, paint);
        paint.setStyle(Paint.Style.FILL);
        int fill = (int) ((w - 8L) * v / max);
        c.drawRect(4, 4, 4 + fill, h - 4, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;   // 只当进度条用（主页“继续阅读”）
        int w = Math.max(1, getWidth());
        int v = Math.round(Math.max(0f, Math.min(1f, e.getX() / w)) * max);
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        case MotionEvent.ACTION_MOVE:
            if (v != (shown >= 0 ? shown : value)) { shown = v; invalidate(); }
            return true;
        case MotionEvent.ACTION_UP:
            boolean dirty = value != v || shown >= 0;
            value = v; shown = -1;
            if (dirty) invalidate();
            if (listener != null) listener.onCommit(v);
            return true;
        case MotionEvent.ACTION_CANCEL:
            if (shown >= 0) { shown = -1; invalidate(); }
            return true;
        }
        return true;
    }
}
