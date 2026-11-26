// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * E-ink friendly scrolling: a swipe turns one page when the finger lifts (no
 * following the finger, no fling, no animation), so the panel redraws once.
 * The up/down buttons do the same.
 */
class PageScrollView extends ScrollView {
    private final int slop;
    private float downX, downY;
    private boolean dragging;

    PageScrollView(Context c) {
        super(c);
        slop = Math.max(12, ViewConfiguration.get(c).getScaledTouchSlop());
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setSmoothScrollingEnabled(false);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            downX = e.getX();
            downY = e.getY();
            dragging = false;
            return false;
        case MotionEvent.ACTION_MOVE: {
            float dx = Math.abs(e.getX() - downX);
            float dy = Math.abs(e.getY() - downY);
            if (dy > slop && dy > dx) {
                dragging = true;
                return true;
            }
            return false;
        }
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
            dragging = false;
            return false;
        }
        return false;
    }

    private Runnable onPage;
    /** 翻了一页以后调用（更新页码之类）。 */
    void setOnPage(Runnable r) { onPage = r; }
    /** 用按钮翻页以后调用：和划动翻页一样通知（页码立刻跟上，不等下一次排版）。 */
    void firePage() { if (onPage != null) onPage.run(); }

    /**
     * 手指划动时内容不跟着动（墨水屏上跟手滚动 = 一路连着刷）：抬手时按划的方向整页翻一次，只换一次画面。
     */
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            downY = e.getY();
            dragging = false;
            return true;
        case MotionEvent.ACTION_MOVE:
            if (Math.abs(e.getY() - downY) > slop) dragging = true;
            return true;
        case MotionEvent.ACTION_UP:
            if (dragging && page(e.getY() < downY ? 1 : -1) && onPage != null) onPage.run();
            dragging = false;
            return true;
        case MotionEvent.ACTION_CANCEL:
            dragging = false;
            return true;
        }
        return true;
    }

    @Override public void fling(int velocityY) { /* no inertia */ }

    /** 从第一页开始按 page() 的规则一页页往下算，得到每一页的顶边（页数、当前页都按它算，和真翻页一致）。 */
    /**
     * 页码条每次排版完都要问页数和当前页（各算一遍 stops，每次把整棵内容树走一遍）；窗口里随便哪里排一次版，所有翻页页面的监听都会被叫到。
     * 内容没有重新排版过（onLayout 没来过、垫的空白也没变）时，用上次算好的。
     */
    private int layoutGen, stopsGen = -1, stopsPad, stopsH;
    private java.util.List<Integer> stopsCache;
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        inLayout = true;
        try { super.onLayout(changed, l, t, r, b); } finally { inLayout = false; }
        layoutGen++;
        // 内容变了（设置页“应用预编译”那一行状态字变长 / 变短）时 ScrollView 自己会挪滚动位置（夹到新的底边、或者让刚点过的按钮
        // 离开上边的渐隐区），翻好的页就错开半行，页顶露出上一条的下半截（测试机 VeraHome 88：点“立即优化”以后）。翻页翻到哪就停在哪。
        if (getChildCount() > 0) {
            android.view.View ch = getChildAt(0);
            int h = getHeight();
            int realH = Math.max(0, ch.getHeight() - tailPad);
            int realMax = Math.max(0, realH - h);
            if (realMax == 0) {
                if (tailPad > 0) {
                    ch.setPadding(ch.getPaddingLeft(), ch.getPaddingTop(), ch.getPaddingRight(), Math.max(0, ch.getPaddingBottom() - tailPad));
                    tailPad = 0;
                }
                pageY = 0;
                if (getScrollY() != 0) super.scrollTo(0, 0);
                return;
            }
            if (pageY >= realH) {
                if (tailPad > 0) {
                    ch.setPadding(ch.getPaddingLeft(), ch.getPaddingTop(), ch.getPaddingRight(), Math.max(0, ch.getPaddingBottom() - tailPad));
                    tailPad = 0;
                }
                pageY = realMax;
            } else if (tailPad > 0) {
                int needPad = Math.max(0, pageY - realMax);
                if (tailPad > needPad) {
                    int drop = tailPad - needPad;
                    ch.setPadding(ch.getPaddingLeft(), ch.getPaddingTop(), ch.getPaddingRight(), Math.max(0, ch.getPaddingBottom() - drop));
                    tailPad = needPad;
                }
            }
            int max = Math.max(0, realMax + tailPad);
            if (pageY > max) pageY = max;
            if (pageY >= 0 && getScrollY() != pageY) {
                int y = Math.min(pageY, max);
                if (y != getScrollY()) super.scrollTo(0, y);
            }
        }
    }

    /** 页面上的控件拿到焦点 / 要求显示某块区域时不自动滚：这里只按整页翻（自动滚会停在两页之间）。 */
    @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) { return 0; }

    /** 最后一次翻页（或回到顶）停的位置；排版以后照样停在这里。 */
    private int pageY = 0;
    private boolean inLayout;   // ScrollView 排版时自己调的 scrollTo（夹底边、滚到焦点）不算翻页

    private java.util.List<Integer> stops() {
        if (stopsCache != null && stopsGen == layoutGen && stopsPad == tailPad && stopsH == getHeight()) return stopsCache;
        java.util.List<Integer> out = stops0();
        stopsCache = out; stopsGen = layoutGen; stopsPad = tailPad; stopsH = getHeight();
        return out;
    }

    private java.util.List<Integer> stops0() {
        java.util.List<Integer> out = new java.util.ArrayList<Integer>();
        out.add(0);
        int content = getChildCount() > 0 ? getChildAt(0).getHeight() - tailPad : 0, h = getHeight();
        if (h <= 0) return out;
        java.util.List<int[]> items = leaves();
        int y = 0;
        for (int guard = 0; guard < 200 && y + h < content; guard++) {
            int target = y + (h - h / 10);
            for (int[] it : items) if (it[1] > y + h) { if (it[0] > y) target = it[0]; break; }
            if (target <= y) break;
            y = target;
            out.add(y);
        }
        return out;
    }

    /**
     * 页数和当前页：两种风格都按 stops()（和真翻页一样的规则）算。经典风格以前用“内容高 / 屏高”估页数，内容高里还算上了
     * 最后一页垫的空白：在最后一页再点 ▼ 还能往下翻出空白页，页数也跟着涨（用户视频：设置“显示与刷新”2 / 4 之后一直是白页）。
     */
    int pages() { return stops().size(); }

    int currentPage() {
        java.util.List<Integer> st = stops();
        int y = getScrollY(), n = 1;
        for (int i = 0; i < st.size(); i++) if (st.get(i) <= y + 2) n = i + 1;
        return n;
    }

    /**
     * 翻一页。尽量停在一行的边上：往下翻时，这一页最下面那个没显示全的条目成为下一页的第一条；
     * 往上翻时反过来。这样不会有条目被切成两半、上一页看半行下一页看半行。
     */
    boolean page(int dir) {
        int content = getChildCount() > 0 ? getChildAt(0).getHeight() : 0;
        int h = getHeight();
        int max = Math.max(0, content - h);
        int y = getScrollY();
        if (dir > 0 && y + h >= content - tailPad) return false;   // 已经是最后一页（下面只剩垫的空白）：两种风格都停住，不再翻出空白页
        int target = y + dir * (h - h / 10);
        if (getChildCount() > 0) {
            // 分组框里的每一行也算一个条目（设置页一个分组常常比一屏还高），翻页停在行的边上（经典风格的列表也一样）
            java.util.List<int[]> items = leaves();
            if (dir > 0) {
                for (int[] it : items) if (it[1] > y + h) { if (it[0] > y) target = it[0]; break; }
            } else {
                // 往回翻：回到上一个真正的页顶（和往下翻停过的位置一样；第一页是 0，不是第一个条目的顶边）
                target = 0;
                for (int st : stops()) if (st < y - 2) target = st;
            }
        }
        // 最后一页：内容不够一整屏时，原来会被夹到“底部对齐”，于是最后一页顶上露出上一页条目的下半截。
        // 改成在内容底下垫空白，让最后一页也从一个条目的顶边开始。
        if (dir > 0 && target > max && target > y && getChildCount() > 0) {
            final android.view.View ch = getChildAt(0);
            final int pad = tailPad + (target - max);
            final int to = target;
            ch.setPadding(ch.getPaddingLeft(), ch.getPaddingTop(), ch.getPaddingRight(), ch.getPaddingBottom() - tailPad + pad);
            tailPad = pad;
            ch.measure(MeasureSpec.makeMeasureSpec(ch.getWidth(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            ch.layout(ch.getLeft(), ch.getTop(), ch.getLeft() + ch.getMeasuredWidth(), ch.getTop() + ch.getMeasuredHeight());
            scrollTo(0, to);
            return true;
        }
        target = Math.max(0, Math.min(max, target));
        if (target == y) return false;
        scrollTo(0, target);
        return true;
    }

    /**
     * 条目（内容坐标的 [上, 下]），按从上到下：内容的每个子控件；比七成屏幕还高的竖排分组（LinearLayout，设置页的长分组）
     * 再拆成它里面的每一行。矮一些的卡片（首页的天气、日历）整张算一条，不会被拆开、丢了框的下边。
     */
    private java.util.List<int[]> leaves() {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        if (getChildCount() == 0 || !(getChildAt(0) instanceof android.view.ViewGroup)) return out;
        android.view.ViewGroup g = (android.view.ViewGroup) getChildAt(0);
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            if (c instanceof android.widget.LinearLayout && ((android.widget.LinearLayout) c).getOrientation() == android.widget.LinearLayout.VERTICAL
                && ((android.view.ViewGroup) c).getChildCount() > 2 && c.getHeight() > getHeight() * 2 / 5) {
                android.view.ViewGroup cg = (android.view.ViewGroup) c;
                for (int k = 0; k < cg.getChildCount(); k++) {
                    android.view.View r = cg.getChildAt(k);
                    if (r.getVisibility() == GONE || r.getHeight() <= 4) continue;   // 细线不算条目
                    out.add(new int[] { c.getTop() + r.getTop(), c.getTop() + r.getBottom() });
                }
            } else out.add(new int[] { c.getTop(), c.getBottom() });
        }
        return out;
    }

    /**
     * 方块风格：这一屏最下面没显示全的那一条用白色盖住（它会是下一页的第一条），屏幕上不出现半行。
     */
    @Override protected void dispatchDraw(android.graphics.Canvas c) {
        int cut = -1;
        if (Blocks.on(getContext()) && getChildCount() > 0) {
            int y = getScrollY(), h = getHeight();
            if (getChildAt(0).getHeight() > h) {
                for (int[] it : leaves()) {
                    if (it[1] > y + h) { if (it[0] > y && it[0] < y + h) cut = it[0]; break; }
                }
            }
        }
        if (cut < 0) { super.dispatchDraw(c); return; }
        // 不画那一条（以前是盖一块白色）：底下是什么就露出什么——方块首页有壁纸时盖白会在壁纸上留一块白条
        c.save();
        c.clipRect(0, getScrollY(), getWidth(), cut);
        super.dispatchDraw(c);
        c.restore();
    }

    /** page() 为了让最后一页从条目顶边开始而在内容底下垫的空白；回到第一页时去掉。 */
    private int tailPad;

    @Override public void scrollTo(int x, int y) {
        super.scrollTo(x, y);
        if (!inLayout) pageY = getScrollY();
        if (y == 0 && tailPad > 0 && getChildCount() > 0) {
            android.view.View ch = getChildAt(0);
            ch.setPadding(ch.getPaddingLeft(), ch.getPaddingTop(), ch.getPaddingRight(), Math.max(0, ch.getPaddingBottom() - tailPad));
            tailPad = 0;
        }
    }
}
