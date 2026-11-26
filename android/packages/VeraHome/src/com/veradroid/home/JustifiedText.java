package com.veradroid.home;

import android.content.Context;
import android.graphics.Canvas;
import android.text.Layout;
import android.text.TextPaint;
import android.widget.TextView;

/**
 * 阅读器正文：两端对齐（API 25 的 StaticLayout 还没有 JUSTIFICATION_MODE，那是 API 26 才有的）。
 *
 * 断行完全交给 TextView 自己的 StaticLayout（左对齐，和分页用的是同一套参数），这里只改“画”：
 * 每一行算出离右边距还差多少，平均分到这一行里能拉开的地方——
 * 中日韩文字（含全角标点；—— …… 和弯引号不算，它们只在挨着汉字的一边拉开）两两之间、中日韩文字和西文之间，西文只在空格处（单词里面不拆开）。
 * 段落最后一行（行尾是换行）、整页最后一行而这一段到这里就结束了，都照常左对齐。
 * 行尾的空格不算宽度（不拉开，右边贴齐的是最后一个可见字符，句号逗号就在行尾贴着右边距）；
 * 一行里没地方拉开（一个很长的单词 / 网址）或每处要拉开得太多（超过两个字宽），也照常左对齐，免得字被拉得七零八落。
 * 所以分页、续读位置、书签都不受影响：每页放哪些字和左对齐时一模一样。
 */
final class JustifiedText extends TextView {
    private boolean justify;
    /** 这一页最后一行之后这一段还没完（翻到下一页接着）：最后一行也要两端对齐。 */
    private boolean continues;

    JustifiedText(Context c) { super(c); }

    void setJustify(boolean on) { if (on != justify) { justify = on; invalidate(); } }
    void setContinues(boolean on) { if (on != continues) { continues = on; invalidate(); } }
    boolean justify() { return justify; }

    /** 这一页上能点的链接（EPUB 的注释号、目录链接）：[起, 止) 两两一组，按这一页文字里的位置。画成虚下划线。 */
    private int[] links = new int[0];
    void setLinks(int[] l) { links = l == null ? new int[0] : l; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        Layout l = getLayout();
        if (!justify || l == null) { super.onDraw(canvas); if (l != null && links.length > 0) drawLinks(canvas, l); return; }
        CharSequence text = l.getText();
        TextPaint p = getPaint();
        p.setColor(getCurrentTextColor());
        p.drawableState = getDrawableState();
        canvas.save();
        canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
        int top = getScrollY(), bottom = top + getHeight();
        int n = l.getLineCount();
        for (int line = 0; line < n; line++) {
            if (l.getLineBottom(line) < top) continue;
            if (l.getLineTop(line) > bottom) break;
            int s = l.getLineStart(line), e = l.getLineEnd(line);
            int ve = e;                                            // 去掉行尾的换行和空白
            while (ve > s && isTrailingSpace(text.charAt(ve - 1))) ve--;
            boolean paraEnd = e > s && text.charAt(e - 1) == '\n' || line == n - 1 && !continues;
            float left = l.getParagraphLeft(line);                 // 首行缩进（LeadingMarginSpan）已经算在里面
            float avail = l.getParagraphRight(line) - left;
            int base = l.getLineBaseline(line);
            if (paraEnd || ve <= s || !drawJustified(canvas, text, s, ve, left, avail, base, p)) {
                canvas.drawText(text, s, ve, l.getLineLeft(line), base, p);
            }
        }
        canvas.restore();
        if (links.length > 0) drawLinks(canvas, l);
    }

    /** 链接下面画一条短横线组成的虚线（墨水屏上清楚，又和正文的下划线区分开）。 */
    private void drawLinks(Canvas canvas, Layout l) {
        CharSequence text = l.getText();
        android.graphics.Paint lp = new android.graphics.Paint();
        lp.setColor(getCurrentTextColor());
        float th = Math.max(2f, getTextSize() / 14f);
        float dash = getTextSize() / 4f;
        canvas.save();
        canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
        for (int k = 0; k + 1 < links.length; k += 2) {
            int a = Math.max(0, links[k]), b = Math.min(text.length(), links[k + 1]);
            if (b <= a) continue;
            for (int line = l.getLineForOffset(a); line < l.getLineCount() && l.getLineStart(line) < b; line++) {
                float[] r = span(l, text, line, a, b);
                if (r == null) continue;
                float y = l.getLineBaseline(line) + th * 2;
                for (float x = r[0]; x < r[1]; x += dash * 1.6f) canvas.drawRect(x, y, Math.min(r[1], x + dash), y + th, lp);
            }
        }
        canvas.restore();
    }

    /** 第 line 行里 [a, b) 这几个字在屏幕上的左右边（两端对齐时按拉开后的位置），不在这一行返回 null。 */
    private float[] span(Layout l, CharSequence text, int line, int a, int b) {
        int s = l.getLineStart(line), e = l.getLineEnd(line);
        int ve = e;
        while (ve > s && isTrailingSpace(text.charAt(ve - 1))) ve--;
        int x0 = Math.max(a, s), x1 = Math.min(b, ve);
        if (x1 <= x0) return null;
        float per = justify ? perOf(l, text, line, s, e, ve) : -1;
        return new float[] { xAt(l, text, line, s, ve, x0, per, false), xAt(l, text, line, s, ve, x1, per, true) };
    }

    /** 这一行两端对齐时每处拉开多少；这一行照常左对齐返回 -1（和 onDraw 的判断一样）。 */
    private float perOf(Layout l, CharSequence text, int line, int s, int e, int ve) {
        boolean paraEnd = e > s && text.charAt(e - 1) == '\n' || line == l.getLineCount() - 1 && !continues;
        if (paraEnd || ve <= s) return -1;
        TextPaint p = getPaint();
        int lead = s;
        while (lead < ve && text.charAt(lead) == '\u3000') lead++;
        int gaps = 0;
        float natural = 0;
        int runStart = s;
        for (int i = s + 1; i <= ve; i++) {
            if (i == ve || i > lead && breakBetween(text.charAt(i - 1), text.charAt(i))) {
                natural += p.measureText(text, runStart, i);
                if (i < ve) gaps++;
                runStart = i;
            }
        }
        float extra = l.getParagraphRight(line) - l.getParagraphLeft(line) - natural;
        if (gaps == 0 || extra <= 0) return -1;
        float per = extra / gaps;
        return per > 2 * p.getTextSize() ? -1 : per;
    }

    private float xAt(Layout l, CharSequence text, int line, int s, int ve, int off, float per, boolean isEnd) {
        if (per < 0) return l.getPrimaryHorizontal(off);
        TextPaint p = getPaint();
        int lead = s;
        while (lead < ve && text.charAt(lead) == '\u3000') lead++;
        float x = l.getParagraphLeft(line);
        int runStart = s;
        for (int i = s + 1; i <= off; i++) {
            boolean brk = i < ve && i > lead && breakBetween(text.charAt(i - 1), text.charAt(i));
            if (i == off || brk) {
                x += p.measureText(text, runStart, i);
                if (brk && (i < off || !isEnd)) x += per;
                runStart = i;
            }
        }
        return x;
    }

    /**
     * 点在 (x, y)（这个控件里的坐标）附近的链接是第几组（links 里的下标 / 2），没有返回 -1。
     * 注释号常常只有一个字，手指点不准：上下差一行之内、左右差 slop 像素之内都算，取最近的。
     */
    int linkAt(float x, float y, float slop) {
        Layout l = getLayout();
        if (l == null || links.length == 0) return -1;
        CharSequence text = l.getText();
        x -= getCompoundPaddingLeft(); y -= getExtendedPaddingTop();
        int best = -1; float bestD = Float.MAX_VALUE;
        for (int k = 0; k + 1 < links.length; k += 2) {
            int a = Math.max(0, links[k]), b = Math.min(text.length(), links[k + 1]);
            if (b <= a) continue;
            for (int line = l.getLineForOffset(a); line < l.getLineCount() && l.getLineStart(line) < b; line++) {
                float[] r = span(l, text, line, a, b);
                if (r == null) continue;
                float top = l.getLineTop(line), bot = l.getLineBottom(line);
                float dx = x < r[0] ? r[0] - x : x > r[1] ? x - r[1] : 0;
                float dy = y < top ? top - y : y > bot ? y - bot : 0;
                if (dx > slop || dy > slop * 0.6f) continue;
                float d = dx + dy;
                if (d < bestD) { bestD = d; best = k / 2; }
            }
        }
        return best;
    }

    /** 画一行两端对齐的字；不适合拉开就返回 false（调用的人照常画）。 */
    private static boolean drawJustified(Canvas c, CharSequence t, int s, int e, float x, float avail, int y, TextPaint p) {
        // 先数能拉开的地方，同时量出自然宽度（按“段”量：两个拉开点之间的字一起量，西文单词的字距不变）
        // 段首用全角空格缩进的（中文 TXT 常见“　　”）：缩进那几个空格不拉开，各段首行缩进一样宽
        int lead = s;
        while (lead < e && t.charAt(lead) == '\u3000') lead++;
        int gaps = 0;
        float natural = 0;
        int runStart = s;
        for (int i = s + 1; i <= e; i++) {
            if (i == e || i > lead && breakBetween(t.charAt(i - 1), t.charAt(i))) {
                natural += p.measureText(t, runStart, i);
                if (i < e) gaps++;
                runStart = i;
            }
        }
        float extra = avail - natural;
        if (gaps == 0 || extra <= 0) return false;
        float per = extra / gaps;
        if (per > 2 * p.getTextSize()) return false;              // 每处要拉开两个字宽以上：这一行左对齐更好看
        runStart = s;
        for (int i = s + 1; i <= e; i++) {
            if (i == e || i > lead && breakBetween(t.charAt(i - 1), t.charAt(i))) {
                c.drawText(t, runStart, i, x, y, p);
                x += p.measureText(t, runStart, i) + per;
                runStart = i;
            }
        }
        return true;
    }

    /** a 和 b 之间能不能拉开：空格后面、中日韩文字的前后（代理对中间、组合附加符号前面不行）。 */
    static boolean breakBetween(char a, char b) {
        if (Character.isHighSurrogate(a) || Character.isLowSurrogate(b)) return false;
        if (Character.getType(b) == Character.NON_SPACING_MARK || Character.getType(b) == Character.ENCLOSING_MARK) return false;
        if (a == '\u00A0' || b == '\u00A0') return false;             // 不断行空格：两边粘在一起
        if (b == ' ') return false;                                // 空格本身算前一个单词的一部分，拉开点在空格后面
        if (a == ' ') return true;
        return cjk(a) || cjk(b);
    }

    private static boolean isTrailingSpace(char c) { return c == '\n' || c == '\r' || c == ' ' || c == '\t'; }

    /** 中日韩文字、假名、注音、全角标点和全角字母（这些字之间都可以均匀拉开）。 */
    static boolean cjk(char c) {
        return c >= 0x2E80 && c <= 0x9FFF      // 部首、CJK 符号与标点（含全角空格、「」《》。、）、假名、注音、统一汉字
            || c >= 0xAC00 && c <= 0xD7AF      // 韩文
            || c >= 0xF900 && c <= 0xFAFF      // 兼容汉字
            || c >= 0xFE30 && c <= 0xFE4F      // 竖排兼容标点
            || c >= 0xFF00 && c <= 0xFFEF;     // 全角 ASCII 与全角标点（，！？：；（））
        // —— …… 和弯引号不算：中英文里都用，算进来英文的 ago—never 会被拉成 ago — never；挨着汉字时汉字那一边照样拉开，两个破折号之间不拉开（—— 不会断开）
    }
}
