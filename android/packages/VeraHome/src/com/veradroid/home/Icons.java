// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Thin black line icons in the style of the Kindle's own toolbar (house, chevrons, gear, sun ...), drawn with a canvas
 * so they stay crisp at any size and need no image files. Each icon is designed on a 24 x 24 grid.
 */
final class Icons {
    private Icons() { }

    enum Kind {
        HOME, GRID, BACK, CHEV_R, CHEV_U, CHEV_D, GEAR, POWER, SUN_S, SUN_L, WARM, WIFI, INVERT, REFRESH, SWITCH, EYE,
        SLIDERS, INFO, CLOSE, PLUS, MINUS, BELL, MOON, FONT, SWIPE, BOOK, UPLOAD, CLOUD, SEARCH, STORE, CHART, BLUETOOTH, CAMERA,
        EXIT, MORE, LIST, PLANE, BOOKMARK, TOC,
        FOLDER, USER, DEVICE, IMAGE, NOTE, LAYOUT, CLOCK, CALENDAR, WIDGET, CLEAN, LOCK, UNLOCK
    }

    static void draw(Canvas c, Kind k, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float s = size / 24f;
        p.setStrokeWidth(Math.max(2.2f, 1.9f * s));
        c.save();
        c.scale(s, s);
        p.setStrokeWidth(p.getStrokeWidth() / s);
        switch (k) {
        case HOME: {
            Path h = new Path();
            h.moveTo(3, 12); h.lineTo(12, 3.5f); h.lineTo(21, 12);
            h.moveTo(5.5f, 10); h.lineTo(5.5f, 20.5f); h.lineTo(18.5f, 20.5f); h.lineTo(18.5f, 10);
            h.moveTo(10, 20.5f); h.lineTo(10, 14.5f); h.lineTo(14, 14.5f); h.lineTo(14, 20.5f);
            c.drawPath(h, p);
            break; }
        case GRID:
            c.drawRoundRect(new RectF(4, 4, 10.5f, 10.5f), 1.5f, 1.5f, p);
            c.drawRoundRect(new RectF(13.5f, 4, 20, 10.5f), 1.5f, 1.5f, p);
            c.drawRoundRect(new RectF(4, 13.5f, 10.5f, 20), 1.5f, 1.5f, p);
            c.drawRoundRect(new RectF(13.5f, 13.5f, 20, 20), 1.5f, 1.5f, p);
            break;
        case BACK: poly(c, p, 15, 4, 7, 12, 15, 20); break;
        case CHEV_R: poly(c, p, 9, 4, 17, 12, 9, 20); break;
        case CHEV_U: poly(c, p, 4, 15, 12, 7, 20, 15); break;
        case CHEV_D: poly(c, p, 4, 9, 12, 17, 20, 9); break;
        case CAMERA: {
            c.drawRect(4, 8, 20, 19, p);
            c.drawCircle(12, 14, 4, p);
            c.drawRect(8, 5, 16, 8, p);
            break; }
        case BLUETOOTH: {
            Path b = new Path();
            b.moveTo(7, 7); b.lineTo(17, 17); b.lineTo(12, 21); b.lineTo(12, 3);
            b.lineTo(17, 7); b.lineTo(7, 17);
            c.drawPath(b, p);
            break; }
        case GEAR: {
            c.drawCircle(12, 12, 3f, p);
            c.drawCircle(12, 12, 6.6f, p);
            for (int i = 0; i < 6; i++) {
                double a = Math.PI / 3 * i + Math.PI / 6;
                c.drawLine(12 + (float) Math.cos(a) * 6.6f, 12 + (float) Math.sin(a) * 6.6f,
                    12 + (float) Math.cos(a) * 9.8f, 12 + (float) Math.sin(a) * 9.8f, p);
            }
            break; }
        case POWER:
            c.drawArc(new RectF(4, 4.5f, 20, 20.5f), 310, 280, false, p);
            c.drawLine(12, 3, 12, 11.5f, p);
            break;
        case SUN_S: sun(c, p, 2.4f, 4.4f, 7f); break;
        case SUN_L: sun(c, p, 4.6f, 7.2f, 10.5f); break;
        case WARM: {
            c.drawCircle(12, 14.5f, 6.5f, p);
            poly(c, p, 6.4f, 11.5f, 12, 2.5f, 17.6f, 11.5f);
            break; }
        case WIFI: {
            for (float r : new float[] { 4.5f, 8.5f, 12.5f }) c.drawArc(new RectF(12 - r, 19 - r, 12 + r, 19 + r), 225, 90, false, p);
            Paint f = new Paint(p); f.setStyle(Paint.Style.FILL);
            c.drawCircle(12, 19, 1.5f, f);
            break; }
        case INVERT: {
            c.drawCircle(12, 12, 8.5f, p);
            Paint f = new Paint(p); f.setStyle(Paint.Style.FILL);
            c.drawArc(new RectF(3.5f, 3.5f, 20.5f, 20.5f), 90, 180, true, f);
            break; }
        case REFRESH: {
            c.drawArc(new RectF(4.5f, 4.5f, 19.5f, 19.5f), -50, 290, false, p);
            poly(c, p, 15, 2.5f, 17.8f, 5.6f, 14, 7.8f);
            break; }
        case SWITCH:
            c.drawRoundRect(new RectF(3.5f, 8, 16, 20.5f), 2, 2, p);
            c.drawRoundRect(new RectF(8, 3.5f, 20.5f, 16), 2, 2, p);
            break;
        case EYE:
            c.drawOval(new RectF(2.5f, 6.5f, 21.5f, 17.5f), p);
            c.drawCircle(12, 12, 3, p);
            break;
        case SLIDERS: {
            c.drawLine(4, 6, 20, 6, p); c.drawLine(4, 12, 20, 12, p); c.drawLine(4, 18, 20, 18, p);
            Paint w = new Paint(p); w.setStyle(Paint.Style.FILL); w.setColor(color == Color.WHITE ? Color.BLACK : Color.WHITE);
            for (float[] kn : new float[][] { { 8, 6 }, { 16, 12 }, { 10, 18 } }) { c.drawCircle(kn[0], kn[1], 2.4f, w); c.drawCircle(kn[0], kn[1], 2.4f, p); }
            break; }
        case INFO: {
            c.drawCircle(12, 12, 9, p);
            c.drawLine(12, 11, 12, 17, p);
            Paint f = new Paint(p); f.setStyle(Paint.Style.FILL);
            c.drawCircle(12, 7.6f, 1.3f, f);
            break; }
        case CLOSE: c.drawLine(6, 6, 18, 18, p); c.drawLine(18, 6, 6, 18, p); break;
        case CLEAN:   // 清后台：后面一摞应用（两个框的边），前面一个框里打叉
            c.drawLine(8, 3.5f, 20.5f, 3.5f, p); c.drawLine(20.5f, 3.5f, 20.5f, 16, p);
            c.drawRoundRect(new RectF(3.5f, 8, 16, 20.5f), 2, 2, p);
            c.drawLine(7, 11.5f, 12.5f, 17, p); c.drawLine(12.5f, 11.5f, 7, 17, p);
            break;
        case PLUS: c.drawLine(12, 5, 12, 19, p); c.drawLine(5, 12, 19, 12, p); break;
        case LOCK:     // 锁：锁身 + 合上的锁梁（切换应用里“全部关闭”不关它）
            c.drawRoundRect(new RectF(5, 10.5f, 19, 20.5f), 2, 2, p);
            c.drawArc(new RectF(8, 3.5f, 16, 11.5f), 180, 180, false, p);
            c.drawLine(8, 7.5f, 8, 10.5f, p); c.drawLine(16, 7.5f, 16, 10.5f, p);
            c.drawLine(12, 14, 12, 17, p);
            break;
        case UNLOCK:   // 开着的锁：锁梁右边抬起来
            c.drawRoundRect(new RectF(5, 10.5f, 19, 20.5f), 2, 2, p);
            c.drawArc(new RectF(8, 2.5f, 16, 10.5f), 180, 180, false, p);
            c.drawLine(8, 6.5f, 8, 10.5f, p);
            c.drawLine(12, 14, 12, 17, p);
            break;
        case MINUS: c.drawLine(5, 12, 19, 12, p); break;
        case BELL: {
            c.drawArc(new RectF(6, 4.5f, 18, 16.5f), 180, 180, false, p);
            c.drawLine(6, 10.5f, 5, 17.5f, p); c.drawLine(18, 10.5f, 19, 17.5f, p);
            c.drawLine(4, 17.5f, 20, 17.5f, p);
            c.drawArc(new RectF(10, 18.5f, 14, 22f), 0, 180, false, p);
            break; }
        case MOON: {
            Path a = new Path(), b = new Path();
            a.addCircle(12, 12, 8.5f, Path.Direction.CW);
            b.addCircle(16.5f, 8.5f, 7.5f, Path.Direction.CW);
            a.op(b, Path.Op.DIFFERENCE);
            c.drawPath(a, p);
            break; }
        case FONT: {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setColor(color); t.setTextSize(15); t.setFakeBoldText(true); t.setTextAlign(Paint.Align.CENTER);
            c.drawText("Aa", 12, 17, t);
            break; }
        case SWIPE:
            c.drawLine(3.5f, 12, 20, 12, p);
            poly(c, p, 15, 6.5f, 20.5f, 12, 15, 17.5f);
            c.drawLine(3.5f, 6.5f, 3.5f, 17.5f, p);
            break;
        case CHART: {
            c.drawLine(4, 20, 20, 20, p);
            c.drawRect(new RectF(6, 13, 9, 20), p);
            c.drawRect(new RectF(10.5f, 8, 13.5f, 20), p);
            c.drawRect(new RectF(15, 4, 18, 20), p);
            break; }
        case STORE: {
            c.drawRoundRect(new RectF(4, 8, 20, 21), 2, 2, p);
            c.drawArc(new RectF(8, 3.5f, 16, 12.5f), 180, 180, false, p);
            c.drawLine(9, 12, 9, 21, p);
            c.drawLine(15, 12, 15, 21, p);
            break; }
        case UPLOAD:
            c.drawLine(12, 15, 12, 4.5f, p);
            poly(c, p, 7.5f, 9, 12, 4.5f, 16.5f, 9);
            poly(c, p, 4, 14, 4, 20, 20, 20, 20, 14);
            break;
        case CLOUD: {
            Path cl = new Path();
            cl.moveTo(7, 19); cl.cubicTo(3, 19, 2.5f, 13, 7.5f, 12.5f); cl.cubicTo(8, 7, 16, 6.5f, 17, 12);
            cl.cubicTo(22, 11.5f, 22.5f, 19, 17.5f, 19); cl.close();
            c.drawPath(cl, p);
            break; }
        case BOOK:
            c.drawRoundRect(new RectF(5, 3.5f, 19, 20.5f), 1.8f, 1.8f, p);
            c.drawLine(9, 3.5f, 9, 20.5f, p);
            c.drawLine(12, 8, 16, 8, p); c.drawLine(12, 11.5f, 16, 11.5f, p);
            break;
        case EXIT:   // 回 Kindle：一扇门 + 向外的箭头
            poly(c, p, 13, 4, 5, 4, 5, 20, 13, 20);
            c.drawLine(10, 12, 20.5f, 12, p);
            poly(c, p, 16.5f, 8, 20.5f, 12, 16.5f, 16);
            break;
        case MORE: {
            Paint f = new Paint(p); f.setStyle(Paint.Style.FILL);
            c.drawCircle(12, 5, 1.7f, f); c.drawCircle(12, 12, 1.7f, f); c.drawCircle(12, 19, 1.7f, f);
            break; }
        case LIST: {
            Paint f = new Paint(p); f.setStyle(Paint.Style.FILL);
            for (float y : new float[] { 6, 12, 18 }) { c.drawCircle(4.5f, y, 1.3f, f); c.drawLine(9, y, 20, y, p); }
            break; }
        case PLANE: {
            Path a = new Path();
            a.moveTo(12, 2.5f); a.lineTo(13.6f, 9.5f); a.lineTo(21.5f, 14); a.lineTo(21.5f, 15.8f); a.lineTo(13.6f, 13.8f);
            a.lineTo(13.2f, 18.5f); a.lineTo(15.5f, 20.3f); a.lineTo(15.5f, 21.5f); a.lineTo(12, 20.6f);
            a.lineTo(8.5f, 21.5f); a.lineTo(8.5f, 20.3f); a.lineTo(10.8f, 18.5f); a.lineTo(10.4f, 13.8f);
            a.lineTo(2.5f, 15.8f); a.lineTo(2.5f, 14); a.lineTo(10.4f, 9.5f); a.close();
            c.drawPath(a, p);
            break; }
        case BOOKMARK: poly(c, p, 6.5f, 3.5f, 17.5f, 3.5f, 17.5f, 20.5f, 12, 15.5f, 6.5f, 20.5f, 6.5f, 3.5f); break;
        case TOC:
            c.drawLine(4, 6, 20, 6, p); c.drawLine(4, 12, 15, 12, p); c.drawLine(4, 18, 18, 18, p);
            break;
        case FOLDER: poly(c, p, 3, 6.5f, 9.5f, 6.5f, 11.5f, 8.5f, 21, 8.5f, 21, 19.5f, 3, 19.5f, 3, 6.5f); break;
        case USER:
            c.drawCircle(12, 8.5f, 4, p);
            poly(c, p, 4.5f, 20.5f, 4.5f, 18, 8, 14.5f, 16, 14.5f, 19.5f, 18, 19.5f, 20.5f);
            break;
        case DEVICE:
            c.drawRect(6, 2.5f, 18, 21.5f, p);
            c.drawRect(8.5f, 5.5f, 15.5f, 16, p);
            c.drawLine(10.5f, 18.8f, 13.5f, 18.8f, p);
            break;
        case IMAGE:
            c.drawRect(3.5f, 5, 20.5f, 19, p);
            poly(c, p, 5.5f, 17, 10, 11.5f, 13.5f, 15, 15.5f, 13, 18.5f, 17);
            c.drawCircle(15.5f, 9, 1.5f, p);
            break;
        case NOTE:
            poly(c, p, 5, 3.5f, 15, 3.5f, 19, 7.5f, 19, 20.5f, 5, 20.5f, 5, 3.5f);
            c.drawLine(8, 11, 16, 11, p); c.drawLine(8, 14.5f, 16, 14.5f, p); c.drawLine(8, 18, 13, 18, p);
            break;
        case LAYOUT:
            c.drawLine(4, 5, 20, 5, p); c.drawLine(7, 10, 20, 10, p); c.drawLine(4, 15, 20, 15, p); c.drawLine(7, 20, 16, 20, p);
            break;
        case CLOCK:
            c.drawCircle(12, 12, 8.5f, p);
            poly(c, p, 12, 7, 12, 12, 15.5f, 14);
            break;
        case CALENDAR:
            c.drawRect(3.5f, 5.5f, 20.5f, 20.5f, p);
            c.drawLine(3.5f, 10, 20.5f, 10, p);
            c.drawLine(8, 3, 8, 7.5f, p); c.drawLine(16, 3, 16, 7.5f, p);
            break;
        case WIDGET:
            c.drawRect(3.5f, 3.5f, 20.5f, 11, p);
            c.drawRect(3.5f, 14, 10.5f, 20.5f, p);
            c.drawRect(13.5f, 14, 20.5f, 20.5f, p);
            break;
        case SEARCH:
            c.drawCircle(10.5f, 10.5f, 5.5f, p);
            c.drawLine(14.8f, 14.8f, 20, 20, p);
            break;
        }
        c.restore();
    }

    private static void poly(Canvas c, Paint p, float... xy) {
        Path path = new Path();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        c.drawPath(path, p);
    }

    private static void sun(Canvas c, Paint p, float r, float r1, float r2) {
        c.drawCircle(12, 12, r, p);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            c.drawLine(12 + (float) Math.cos(a) * r1, 12 + (float) Math.sin(a) * r1,
                12 + (float) Math.cos(a) * r2, 12 + (float) Math.sin(a) * r2, p);
        }
    }

    /** A view that draws one icon; turns white while its parent is pressed (parent state is mirrored) or when inverted. */
    static class IconView extends View {
        private Kind kind;
        private boolean inverted;

        IconView(Context c, Kind k) {
            super(c);
            kind = k;
            setDuplicateParentStateEnabled(true);
        }

        void setKind(Kind k) { kind = k; invalidate(); }
        void setInverted(boolean on) { inverted = on; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            boolean white = isPressed() ? !inverted : inverted;
            Icons.draw(c, kind, Math.min(getWidth(), getHeight()), white ? Color.WHITE : Color.BLACK);
        }
    }

    /** Icon + small caption in a framed tile (like the Kindle quick-settings tiles). Pressed = inverted. */
    static LinearLayout tile(Context c, Kind k, String caption, float sp, boolean filled, View.OnClickListener l) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 6));
        t.setBackground(Ui.frame(c, filled));
        IconView iv = new IconView(c, k);
        iv.setInverted(filled);
        t.addView(iv, new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        if (caption != null) {
            TextView tv = new TextView(c);
            tv.setTypeface(Fonts.typeface(c));
            tv.setText(caption);
            tv.setTextSize(sp);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setDuplicateParentStateEnabled(true);
            tv.setTextColor(new android.content.res.ColorStateList(new int[][] { { android.R.attr.state_pressed }, {} },
                new int[] { filled ? Color.BLACK : Color.WHITE, filled ? Color.WHITE : Color.BLACK }));
            t.addView(tv);
        }
        t.setClickable(true);
        if (l != null) t.setOnClickListener(l);
        Ui.fast(t);
        return t;
    }

    /** Frameless icon button for toolbars (just the glyph, a rounded block appears while pressed). */
    static View bar(Context c, Kind k, int dp, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(android.view.Gravity.CENTER);
        int pad = Ui.dp(c, 8);
        b.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        pressed.setColor(Color.BLACK);
        pressed.setCornerRadius(Ui.dp(c, 8));
        bg.addState(new int[] { android.R.attr.state_pressed }, pressed);
        b.setBackground(bg);
        b.addView(new IconView(c, k), new LinearLayout.LayoutParams(Ui.dp(c, dp), Ui.dp(c, dp)));
        b.setClickable(true);
        if (l != null) b.setOnClickListener(l);
        Ui.fast(b);
        return b;
    }

    /** An on / off switch drawn as a thin capsule with a round knob. */
    static class Toggle extends View {
        interface Listener { void onToggle(boolean on); }
        private boolean on;
        private Listener listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context c, boolean on, Listener l) {
            super(c);
            this.on = on;
            listener = l;
            setClickable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Toggle.this.on = !Toggle.this.on; invalidate(); if (listener != null) listener.onToggle(Toggle.this.on); }
            });
        }

        void set(boolean v) { on = v; invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(Ui.dp(getContext(), 64), Ui.dp(getContext(), 36));
        }

        private final boolean square = Blocks.on(getContext());
        @Override protected void onDraw(Canvas c) {
            if (square) {   // 方块风格：和 Blocks.Switch 一样的方框开关
                int w = getWidth(), h = getHeight(), st = Blocks.stroke(getContext()), g = Ui.dp(getContext(), 5);
                p.setAntiAlias(false); p.setColor(Color.BLACK); p.setStyle(Paint.Style.FILL);
                c.drawRect(0, 0, w, st, p); c.drawRect(0, h - st, w, h, p); c.drawRect(0, 0, st, h, p); c.drawRect(w - st, 0, w, h, p);
                int side = h - 2 * g;
                if (on) c.drawRect(w - g - side, g, w - g, h - g, p);
                else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(st); c.drawRect(g + st / 2f, g + st / 2f, g + side - st / 2f, h - g - st / 2f, p); }
                return;
            }
            float w = getWidth(), h = getHeight(), r = h / 2f - 2;
            p.setStyle(on ? Paint.Style.FILL : Paint.Style.STROKE);
            p.setStrokeWidth(3);
            p.setColor(Color.BLACK);
            c.drawRoundRect(new RectF(2, 2, w - 2, h - 2), h / 2f, h / 2f, p);
            float cx = on ? w - h / 2f : h / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Color.WHITE : Color.BLACK);
            c.drawCircle(cx, h / 2f, r - (on ? 4 : 6), p);
            if (!on) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(Color.BLACK); }
        }
    }
}
