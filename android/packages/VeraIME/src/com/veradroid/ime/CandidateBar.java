// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The candidate row, drawn by one view of fixed size:  [<]  candidate candidate ...  [>]
 * Paged, never scrolled: a page holds as many candidates as fit; the arrows (always in the same place, grey when
 * there is nothing that way) go to the next / previous page.  A change is one invalidate of this view only, and
 * none at all when the page on screen would look the same; a tap inverts the touched box until the finger lifts.
 * No animation, no relayout of the keyboard.
 */
final class CandidateBar extends View {
    interface Source {
        /** true when candidate i exists (the source may make it on demand) */
        boolean has(int i);
        String get(int i);
    }

    interface Listener { void onPick(int index); }

    private static final int NONE = -1, PREV = -2, NEXT = -3;

    private final float density;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), box = new Paint(Paint.ANTI_ALIAS_FLAG),
        fill = new Paint(), arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Source source;
    private Listener listener;
    private final List<Integer> pageStarts = new ArrayList<Integer>();
    private int page, first;
    private final List<String> items = new ArrayList<String>();
    private final List<RectF> rects = new ArrayList<RectF>();
    private boolean hasPrev, hasNext;
    private int pressed = NONE;
    /** uptime of the last onDraw (latency logging) */
    long drawnAt;

    CandidateBar(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        text.setColor(Color.BLACK);
        text.setTextSize(24 * c.getResources().getDisplayMetrics().scaledDensity);
        text.setTextAlign(Paint.Align.CENTER);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(dp(2));
        box.setColor(Color.BLACK);
        fill.setColor(Color.BLACK);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeWidth(dp(3));
        arrow.setStrokeCap(Paint.Cap.ROUND);
        setBackgroundColor(Color.WHITE);
        setSoundEffectsEnabled(false);
    }

    private float dp(float v) { return v * density; }

    void setListener(Listener l) { listener = l; }

    /** New candidates (null = empty bar), back to the first page.  Redraws only if the page looks different. */
    void setSource(Source s) {
        List<String> old = new ArrayList<String>(items);
        boolean oldPrev = hasPrev, oldNext = hasNext;
        source = s;
        pageStarts.clear();
        pageStarts.add(0);
        page = 0;
        pressed = NONE;
        layoutPage();
        if (!old.equals(items) || oldPrev != hasPrev || oldNext != hasNext) invalidate();
    }

    boolean isEmpty() { return items.isEmpty(); }

    /** the candidates on screen (tests / logging) */
    String describe() {
        StringBuilder b = new StringBuilder("page=" + page + (hasNext ? " more" : ""));
        for (int k = 0; k < items.size(); k++)
            b.append(" [").append(first + k).append(':').append(items.get(k)).append('@').append((int) rects.get(k).centerX()).append(']');
        return b.toString();
    }

    private float arrowW() { return dp(44); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutPage();
    }

    private void layoutPage() {
        items.clear();
        rects.clear();
        hasPrev = page > 0;
        hasNext = false;
        if (source == null) return;
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = getHeight() > 0 ? getHeight() : (int) dp(44);
        float left = arrowW() + dp(2), right = w - arrowW() - dp(2), pad = dp(12), gap = dp(4), x = left;
        int i = first = pageStarts.get(page);
        while (source.has(i)) {
            String s = source.get(i);
            float bw = Math.max(text.measureText(s) + 2 * pad, h - dp(4));
            if (x + bw > right) {
                if (items.isEmpty()) bw = right - x;     // one very long candidate: shown cut, still pickable
                else { hasNext = true; break; }
            }
            items.add(s);
            rects.add(new RectF(x, dp(2), x + bw, h - dp(2)));
            x += bw + gap;
            i++;
        }
    }

    private int hit(float x, float y) {
        float slop = dp(12);
        if (y < -slop || y > getHeight() + slop) return NONE;
        if (x < arrowW()) return hasPrev ? PREV : NONE;
        if (x > getWidth() - arrowW()) return hasNext ? NEXT : NONE;
        for (int k = 0; k < rects.size(); k++) {
            RectF r = rects.get(k);
            if (x >= r.left - dp(2) && x <= r.right + dp(2)) return k;
        }
        return NONE;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit(e.getX(), e.getY());
                if (pressed != NONE) invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed != NONE && hit(e.getX(), e.getY()) != pressed) {
                    pressed = NONE;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                int p = pressed;
                pressed = NONE;
                if (p == NONE) return true;
                if (p != hit(e.getX(), e.getY())) { invalidate(); return true; }
                if (p == PREV) { page--; layoutPage(); invalidate(); }
                else if (p == NEXT) {
                    if (pageStarts.size() <= page + 1) pageStarts.add(first + items.size());
                    page++;
                    layoutPage();
                    invalidate();
                } else {
                    invalidate();               // the picked box goes white again in the same frame as the new list
                    if (listener != null) listener.onPick(first + p);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (pressed != NONE) { pressed = NONE; invalidate(); }
                return true;
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        int h = getHeight();
        float cy = h / 2f;
        Paint.FontMetrics fm = text.getFontMetrics();
        float base = cy - (fm.ascent + fm.descent) / 2;
        for (int k = 0; k < items.size(); k++) {
            RectF r = rects.get(k);
            boolean down = pressed == k;
            if (down) c.drawRoundRect(r, dp(6), dp(6), fill);
            c.drawRoundRect(r, dp(6), dp(6), box);
            text.setColor(down ? Color.WHITE : Color.BLACK);
            c.save();
            c.clipRect(r);
            c.drawText(items.get(k), r.centerX(), base, text);
            c.restore();
        }
        text.setColor(Color.BLACK);
        if (hasPrev || hasNext) {
            chevron(c, arrowW() / 2, cy, -1, hasPrev, pressed == PREV);
            chevron(c, getWidth() - arrowW() / 2, cy, 1, hasNext, pressed == NEXT);
        }
        drawnAt = android.os.SystemClock.uptimeMillis();
    }

    /** drawn, not a glyph: the system font has no ‹ › */
    private void chevron(Canvas c, float x, float y, int dir, boolean on, boolean down) {
        float s = dp(9);
        if (down) c.drawRoundRect(new RectF(x - arrowW() / 2 + dp(2), dp(2), x + arrowW() / 2 - dp(2), getHeight() - dp(2)), dp(6), dp(6), fill);
        arrow.setColor(down ? Color.WHITE : on ? Color.BLACK : Color.rgb(200, 200, 200));
        c.drawLine(x - dir * s / 2, y - s, x + dir * s / 2, y, arrow);
        c.drawLine(x + dir * s / 2, y, x - dir * s / 2, y + s, arrow);
    }
}
