package com.veradroid.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * “方块”界面风格的共用控件：直角黑框卡片、黑底白字的分组标题条、中文标题上面一行可关掉的英文小字、
 * 步进器“‹ 值 ›”（代替滑条）、方块格子的级别条、“‹ 1/4 ›”翻页条、底部四个标签的导航。
 *
 * 墨水屏规则和经典风格一样：没有动画、没有按下高亮（点按在抬手时生效，见 {@link Ui#fast}）、纯黑白、
 * 改一个值只重画那一个控件。尺寸都按 dp 算，四种屏幕（1072/1236/1264/1272 宽）和横屏都用同一套比例。
 */
final class Blocks {
    private Blocks() { }

    static final String STYLE_KEY = "ui_style", CAPS_KEY = "ui_caps";

    /** 设置里选了“方块”风格（默认经典）。 */
    static boolean on(Context c) {
        try { return "blocks".equals(Store.prefs(c, "home").getString(STYLE_KEY, "blocks")); } catch (Throwable t) { return false; }
    }

    /** 英文小字（每个中文标题上面那一行）。默认开，设置里可以关。 */
    static boolean caps(Context c) {
        try { return Store.prefs(c, "home").getBoolean(CAPS_KEY, true); } catch (Throwable t) { return true; }
    }

    static int dp(Context c, int v) { return Ui.dp(c, v); }

    // ------------------------------------------------------------------ 墨水屏动画（设置 → 显示与刷新 → 动画：关 / 简洁 / 完整）

    static final String ANIM_KEY = "ui_anim";
    static final String[] ANIM_L = { "关", "简洁", "完整" };
    /** 0 关，1 简洁（默认：换页 / 设置单用两三步硬件擦除），2 完整（再加按下反色、卡片加入时闪一下边框）。 */
    static int anim(Context c) {
        try { return Math.max(0, Math.min(2, Store.prefs(c, "home").getInt(ANIM_KEY, 1))); } catch (Throwable t) { return 1; }
    }

    static final int DIR_DOWN = 0, DIR_UP = 1, DIR_LEFT = 2, DIR_RIGHT = 3;
    static final int KIND_PAGE = 0, KIND_SHEET = 1, KIND_LIST = 2;

    /**
     * 下一次画面变化用墨水屏控制器的硬件擦除（MTK swipe，显示桥的 SWIPE 一次性命令）：只有几步，不是连续动画；
     * 中间帧是控制器自己的快刷，最后一帧是 REAGL（GLR16），不闪、不留残影。i.MX 的机型没有这个功能，桥会直接照常刷新。
     */
    static void swipe(Context c, int dir, int kind) {
        int lv = anim(c);
        if (lv == 0) return;
        int steps = kind == KIND_LIST ? lv : lv + 1;   // 简洁：翻列表 1 步、换页 / 设置单 2 步；完整：2 / 3 步
        HostLink.epd("SWIPE " + dir + " " + steps);
    }

    /**
     * 和 {@link Ui#fast} 一样抬手时才生效；动画选“完整”时，按下的那一刻把这个方块反色（墨水屏上是一次很快的小块 DU 刷新），
     * 抬手时还原，像 Kindle 原生的按下反馈。其余档位不画按下状态（少一次刷新）。
     */
    static void fast(final View v) {
        final int slop = android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            private boolean armed, inverted;
            private void invert(View view, boolean on) {
                if (on == inverted) return;
                inverted = on;
                if (on) {
                    Paint p = new Paint();
                    p.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] { -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0 }));
                    view.setLayerType(View.LAYER_TYPE_SOFTWARE, p);
                } else view.setLayerType(View.LAYER_TYPE_NONE, null);
            }
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (view.isLongClickable() || !view.isEnabled()) return false;
                switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    armed = true;
                    Perf.touch(e);
                    if (anim(view.getContext()) == 2) invert(view, true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (e.getX() < -slop || e.getY() < -slop || e.getX() > view.getWidth() + slop || e.getY() > view.getHeight() + slop) { armed = false; invert(view, false); }
                    return true;
                case MotionEvent.ACTION_UP:
                    invert(view, false);
                    if (armed) { armed = false; Ui.clickAfterDispatch(view); }
                    return true;
                default:
                    armed = false;
                    invert(view, false);
                    return true;
                }
            }
        });
    }

    /** 直角黑框（墨水屏上圆角边缘会抖成灰点，方块风格全部用直角）。 */
    static GradientDrawable box(Context c, int strokePx, boolean filled) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(filled ? Color.BLACK : Color.WHITE);
        if (strokePx > 0) g.setStroke(strokePx, Color.BLACK);
        return g;
    }

    /** 外框线宽：屏幕越密越粗一点（PW6 上 3 像素）。 */
    static int stroke(Context c) { return Math.max(2, Math.round(c.getResources().getDisplayMetrics().density * 1.5f)); }
    /** 硬投影的宽度：框的右边和下边多一条实心黑边，看起来是“压”在纸上的方块。 */
    static int shade(Context c) { return Math.max(3, dp(c, 3)); }

    /**
     * 方块风格的主框：直角黑框 + 右下方实心投影（不是灰色渐变，墨水屏上是纯黑），可选一圈内框（双线）。
     * 框画在 [0, w-投影) × [0, h-投影)，投影在右边和下边；放内容的控件要在右边和下边多留出投影的宽度（见 {@link #framePad}）。
     */
    static final class Frame extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint();
        private final int st, sh, inner;
        private final boolean filled;
        Frame(Context c, boolean shadow, boolean dbl, boolean filled) {
            st = stroke(c); sh = shadow ? shade(c) : 0; inner = dbl ? dp(c, 4) : 0; this.filled = filled;
            p.setAntiAlias(false);
        }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            int l = b.left, t = b.top, r = b.right - sh, bt = b.bottom - sh;
            p.setColor(Color.BLACK);
            p.setStyle(Paint.Style.FILL);
            if (sh > 0) { c.drawRect(l + sh, bt, b.right, b.bottom, p); c.drawRect(r, t + sh, b.right, bt, p); }
            if (filled) c.drawRect(l, t, r, bt, p);
            else {
                fillWhite(c, l, t, r, bt, p); p.setColor(Color.BLACK);
                c.drawRect(l, t, r, t + st, p); c.drawRect(l, bt - st, r, bt, p);
                c.drawRect(l, t, l + st, bt, p); c.drawRect(r - st, t, r, bt, p);
                if (inner > 0) {
                    int i = inner, w1 = Math.max(1, st / 2);
                    c.drawRect(l + i, t + i, r - i, t + i + w1, p); c.drawRect(l + i, bt - i - w1, r - i, bt - i, p);
                    c.drawRect(l + i, t + i, l + i + w1, bt - i, p); c.drawRect(r - i - w1, t + i, r - i, bt - i, p);
                }
            }
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 首页有壁纸时卡片里面的白底有多“实”（255 = 实心白，壁纸只在卡片之间露出来）。只有首页画在壁纸上，
     * 别的页面底下是白纸，半透明的白和实心白画出来一样，所以用一个全局值就够了（HomeActivity.blocksWall 设）。
     */
    static volatile int veil = 255;
    static final String WALLCARD_KEY = "blk_wallcard";
    static final String[] WALLCARD_L = { "实心白卡片", "半透明", "通透" };
    /**
     * 半透明不用半透明的白（墨水屏的“对比度”会把淡灰拉成白，半透明和实心看起来一样），而是“纱窗”：
     * 4×4 里 12 个（半透明）/ 8 个（通透）像素涂白，其余露出壁纸。值 = 涂白的像素数，16 = 实心。
     */
    private static final int[] WALLCARD_A = { 16, 12, 8 };
    static int wallCardAlpha(Context c) {
        int m = 0;
        try { m = Store.prefs(c, "home").getInt(WALLCARD_KEY, 0); } catch (Throwable ignored) { }
        return WALLCARD_A[m < 0 || m >= WALLCARD_A.length ? 0 : m] >= 16 ? 255 : WALLCARD_A[m];
    }
    private static final android.util.SparseArray<android.graphics.BitmapShader> SCREENS = new android.util.SparseArray<android.graphics.BitmapShader>();
    /** 按 veil 填白：255 = 实心；否则用“纱窗”网点。 */
    static void fillWhite(Canvas c, int l, int t, int r, int b, Paint p) {
        int v = veil;
        p.setColor(Color.WHITE);
        if (v >= 16) { c.drawRect(l, t, r, b, p); return; }
        android.graphics.BitmapShader sh = SCREENS.get(v);
        if (sh == null) {
            byte[] px = new byte[16];
            for (int i = 0; i < 16; i++) px[i] = (byte) (Theme.BAYER[i] < v ? 255 : 0);
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ALPHA_8);
            bm.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
            sh = new android.graphics.BitmapShader(bm, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
            SCREENS.put(v, sh);
        }
        p.setShader(sh);
        c.drawRect(l, t, r, b, p);
        p.setShader(null);
    }

    /** 没有框的一块白底，跟着 {@link #veil}（首页标题行压在壁纸上时用）。 */
    static final class Veil extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint();
        @Override public void draw(Canvas c) { android.graphics.Rect r = getBounds(); fillWhite(c, r.left, r.top, r.right, r.bottom, p); }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static Frame frame(Context c) { return new Frame(c, true, false, false); }
    static Frame frameDouble(Context c) { return new Frame(c, true, true, false); }
    static Frame plain(Context c) { return new Frame(c, false, false, false); }
    static Frame plainFilled(Context c) { return new Frame(c, false, false, true); }

    /** 给带投影的框设内边距：四边 pad，右边和下边再加投影宽度。 */
    static void framePad(View v, int padDp) {
        Context c = v.getContext();
        int p = dp(c, padDp), s = shade(c);
        v.setPadding(p, p, p + s, p + s);
    }

    /** 小号英文大写字（关掉英文小字时返回 GONE 的控件，调用方不用判断）。 */
    static TextView cap(Context c, String en) {
        TextView t = Ui.text(c, en == null ? "" : en.toUpperCase(Locale.US), 10, true);
        t.setLetterSpacing(0.08f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (en == null || !caps(c)) t.setVisibility(View.GONE);
        return t;
    }

    /** 英文小字 + 中文标题，竖着两行。 */
    static LinearLayout titled(Context c, String en, String zh, float sp, boolean bold) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(cap(c, en));
        TextView t = Ui.text(c, zh, sp, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        l.addView(t);
        return l;
    }

    /** 黑底白字的分组标题条。 */
    static TextView header(Context c, String zh) {
        TextView t = Ui.text(c, zh, 15, true);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(Color.BLACK);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 5), 0, dp(c, 5));
        return t;
    }

    /** 竖排的卡片：2 像素直角黑框。 */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(frame(c));
        framePad(l, 12);
        return l;
    }

    /**
     * 空状态 / 读取中：居中一个带框的图标、一行粗体标题、一行说明；读取中再加一条方块进度（静止的，不做动画）。
     * 用来代替一句孤零零的提示文字。
     */
    static LinearLayout empty(Context c, Icons.Kind icon, String title, String hint, boolean loading) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(c, 20), dp(c, 28), dp(c, 20), dp(c, 28));
        FrameLayout ib = new FrameLayout(c);
        ib.setBackground(frame(c));
        ib.setPadding(0, 0, shade(c), shade(c));
        ib.addView(new Icons.IconView(c, icon), new FrameLayout.LayoutParams(dp(c, 30), dp(c, 30), Gravity.CENTER));
        l.addView(ib, lp(dp(c, 60) + shade(c), dp(c, 60) + shade(c)));
        TextView t = Ui.text(c, title, 17, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 12), 0, dp(c, 4));
        l.addView(t, match());
        if (hint != null && hint.length() > 0) {
            TextView h = Ui.text(c, hint, 13, false);
            h.setGravity(Gravity.CENTER);
            l.addView(h, match());
        }
        if (loading) {
            BlockBar b = new BlockBar(c, 8);
            b.noPercent();
            b.setValue(3);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(c, 200), ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = dp(c, 12);
            l.addView(b, bp);
        }
        return l;
    }

    /** 黑底白字的小标签（“继续阅读”）。 */
    static TextView chip(Context c, String s) {
        TextView t = Ui.text(c, s, 13, true);
        t.setTextColor(Color.WHITE);
        t.setBackgroundColor(Color.BLACK);
        t.setPadding(dp(c, 10), dp(c, 2), dp(c, 10), dp(c, 2));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 文件类型角标：细框里的 TXT / EPUB / JPG。 */
    static TextView badge(Context c, String s) {
        TextView t = Ui.text(c, s, 9, true);
        t.setBackground(plain(c));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(c, 2), dp(c, 2), dp(c, 2), dp(c, 2));
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static LinearLayout.LayoutParams weight(float wt) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, wt); }
    static LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    static LinearLayout.LayoutParams gap(Context c, int topDp) { LinearLayout.LayoutParams p = match(); p.topMargin = dp(c, topDp); return p; }

    /** 设置行：左边中文 + 英文小字，右边一个控件；下面一条细线。 */
    static LinearLayout row(Context c, String zh, String en, View control) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 12), dp(c, 8), dp(c, 8), dp(c, 8));
        r.setMinimumHeight(dp(c, 60));
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, zh, 17, true);
        left.addView(t);
        left.addView(cap(c, en));
        r.addView(left, weight(1));
        if (control != null) r.addView(control);
        return r;
    }

    /** 竖排列表里两行之间的细线。 */
    static View hair(Context c) { return Ui.rule(c, Math.max(1, stroke(c) - 1), Color.BLACK); }
    static View vrule(Context c) { View v = new View(c); v.setBackgroundColor(Color.BLACK); return v; }

    /** 统计小方块：英文小字、中文名、右下大数字。 */
    static LinearLayout tile(Context c, String en, String zh, String value) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackground(frame(c));
        t.setPadding(dp(c, 10), dp(c, 8), dp(c, 10) + shade(c), dp(c, 8) + shade(c));
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(cap(c, en));
        TextView z = Ui.text(c, zh, 13, false);
        z.setSingleLine(true);
        l.addView(z);
        t.addView(l, weight(1));
        TextView v = Ui.text(c, value, 22, true);
        v.setSingleLine(true);
        v.setTag("value");
        t.addView(v);
        return t;
    }

    /** 竖排的统计格：大数字在上，名字在下（“我的”页三格一排）。 */
    static LinearLayout stat(Context c, String value, String zh, String en) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(frame(c));
        t.setPadding(dp(c, 10), dp(c, 8), dp(c, 8) + shade(c), dp(c, 8) + shade(c));
        TextView v = Ui.text(c, value, 20, true);
        v.setSingleLine(true);
        t.addView(v);
        TextView z = Ui.text(c, zh, 13, false);
        z.setSingleLine(true);
        t.addView(z);
        t.addView(cap(c, en));
        return t;
    }

    /** 列表里一行可以点的条目：左对齐标题 + 小字说明，右边一个 ›（与普通设置行保持统一视觉层级）。 */
    static LinearLayout navRow(Context c, String zh, String hint, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 12), dp(c, 9), dp(c, 10), dp(c, 9));
        r.setMinimumHeight(dp(c, 56));
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.LEFT);
        TextView t = Ui.text(c, zh, 17, true);
        t.setGravity(Gravity.LEFT);
        mid.addView(t);
        if (hint != null) {
            TextView h = Ui.text(c, hint, 12, false);
            h.setGravity(Gravity.LEFT);
            h.setSingleLine(false);
            h.setMaxLines(2);
            h.setEllipsize(TextUtils.TruncateAt.END);
            h.setPadding(0, dp(c, 2), 0, 0);
            mid.addView(h);
        }
        r.addView(mid, weight(1));
        r.addView(new Icons.IconView(c, Icons.Kind.CHEV_R), lp(dp(c, 18), dp(c, 18)));
        r.setClickable(true);
        r.setOnClickListener(l);
        Blocks.fast(r);
        return r;
    }

    /** 方框里的图标按钮（页面右上角的“⋯”、设置页的返回）。 */
    static View squareButton(Context c, Icons.Kind k, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(frame(c));
        int s = dp(c, 44) + shade(c);
        f.setPadding(0, 0, shade(c), shade(c));
        Icons.IconView iv = new Icons.IconView(c, k);
        f.addView(iv, new FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        f.setClickable(true);
        f.setOnClickListener(l);
        Blocks.fast(f);
        return f;
    }

    /** 文字按钮：直角黑框，filled = 黑底白字。 */
    static TextView button(Context c, String s, boolean filled, View.OnClickListener l) {
        TextView t = Ui.text(c, s, 16, true);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(filled ? Color.WHITE : Color.BLACK);
        t.setBackground(filled ? plainFilled(c) : plain(c));
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setMinHeight(dp(c, 48));
        t.setClickable(true);
        t.setOnClickListener(l);
        Blocks.fast(t);
        return t;
    }

    /** 页面最上面的标题区：英文小字一行（可以带数字，如 “LIBRARY / 12 BOOKS”）、大标题、右边一个方框按钮。 */
    static LinearLayout pageHead(Context c, String en, String zh, View right) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(c, 16), dp(c, 6), dp(c, 16), dp(c, 6));
        LinearLayout l = titled(c, en, zh, 26, true);
        h.addView(l, weight(1));
        if (right != null) h.addView(right);
        return h;
    }


    /**
     * 单独一个 Activity（Wi-Fi、蓝牙、存储空间、统计……）在方块风格下的页头：英文小字 + 大标题，右边是 extra（可以没有）和一个返回方块。
     * 和“我的”下面的设置页同一个样子。
     */
    static LinearLayout activityHead(final android.app.Activity a, String en, String zh, View extra) {
        LinearLayout right = new LinearLayout(a);
        right.setGravity(Gravity.CENTER_VERTICAL);
        if (extra != null) {
            right.addView(extra);
            View sp = new View(a);
            right.addView(sp, lp(dp(a, 10), 1));
        }
        right.addView(squareButton(a, Icons.Kind.BACK, new View.OnClickListener() { @Override public void onClick(View v) { a.onBackPressed(); } }));
        LinearLayout h = pageHead(a, en, zh, right);
        h.setPadding(dp(a, 16), dp(a, 10), dp(a, 16), dp(a, 8));
        return h;
    }

    /**
     * 别的应用上面的状态栏（StatusBarService，盖在窗口上面）在这一页会不会显示：显示的话内容从它下面开始。
     * 这几页自己在 onResume 里把状态栏藏起来了（setSuppressed），不用让。
     * 状态栏的占位窗口在的时候，系统已经把顶部让出来了（窗口的 inset），这里不能再让一次。
     */
    static int statusInset(android.app.Activity a) {
        if (a instanceof StatsActivity || a instanceof StorageActivity || a instanceof BackupActivity || a instanceof StoreActivity || a instanceof FontStoreActivity || a instanceof InboxActivity
            || a instanceof TransferActivity || a instanceof HomeActivity || a instanceof LockSettingsActivity || a instanceof ScreensaverActivity
            || a instanceof WifiActivity || a instanceof BluetoothActivity || a instanceof HostSettingsActivity || a instanceof AppDisplayActivity || a instanceof FilePickerActivity) return 0;
        try { if (!Store.prefs(a, "home").getBoolean("statusbar", true)) return 0; } catch (Throwable ignored) { }
        if (StatusBarService.reserving()) return 0;
        return StatusBarService.height(a);
    }

    /**
     * 在已经有左右边距的根布局里加方块页头（根布局的边距改成 16dp，页头自己不再留左右边距）。
     * 给那些整页是一个竖排 LinearLayout 的 Activity 用：只换页头，下面的内容照旧。
     */
    static LinearLayout headInto(android.app.Activity a, LinearLayout root, String en, String zh, View extra) {
        root.setPadding(dp(a, 16), statusInset(a), dp(a, 16), dp(a, 12));
        LinearLayout h = activityHead(a, en, zh, extra);
        h.setPadding(0, dp(a, 10), 0, dp(a, 8));
        root.addView(h, 0, match());
        return h;
    }

    /**
     * 只能整页翻的内容区（PageScrollView）+ 下面的翻页条“‹ 1/3 ›”（只有一页时不占地方）。
     * 翻页停在条目的边上（见 PageScrollView.page），一次只换一次画面。
     */
    static LinearLayout paged(Context c, View content) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        final PageScrollView sv = new PageScrollView(c);
        sv.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        final Context cc = c;
        final Pager pager = new Pager(c, new Pager.Listener() {
            @Override public void onPage(int dir) { swipe(cc, dir > 0 ? DIR_UP : DIR_DOWN, KIND_LIST); if (sv.page(dir)) sv.firePage(); }
        });
        LinearLayout.LayoutParams pp = match();
        pp.topMargin = dp(c, 6);
        box.addView(pager, pp);
        final Runnable label = new Runnable() { @Override public void run() {
            int n = sv.pages();
            pager.set(sv.currentPage(), n);
            int vis = n > 1 ? View.VISIBLE : View.GONE;
            if (pager.getVisibility() != vis) pager.setVisibility(vis);
        } };
        pager.setVisibility(View.GONE);
        sv.setOnPage(label);
        final android.view.ViewTreeObserver.OnPreDrawListener pdl = new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (sv.getHeight() <= 0) return true;
                int n = sv.pages();
                pager.set(sv.currentPage(), n);
                int vis = n > 1 ? View.VISIBLE : View.GONE;
                if (pager.getVisibility() != vis) {
                    pager.setVisibility(vis);
                    return false;   // 跳过未放翻页条的半成品帧，立刻重排后再一次画齐（墨水屏少一次整屏重刷）
                }
                android.view.ViewTreeObserver vto = sv.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnPreDrawListener(this);
                return true;
            }
        };
        sv.getViewTreeObserver().addOnPreDrawListener(pdl);
        sv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                android.view.ViewTreeObserver vto = sv.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnPreDrawListener(pdl);
            }
        });
        return box;
    }

    /**
     * 老样子的简单页（大标题、中间一个列表、最下面一排“返回 / 刷新…”）在方块风格下换装：
     * 大标题换成方块页头（右边返回方块），最下面那排去掉“返回”、其余按钮等宽；能拖着滚的 ScrollView 换成整页翻 + 翻页条。
     * 经典风格什么都不做。actions 的第一个按钮必须是“返回”。
     */
    static void restyle(android.app.Activity a, LinearLayout page, TextView title, LinearLayout actions, String en) {
        if (!on(a)) return;
        String zh = String.valueOf(title.getText());
        page.removeView(title);
        headInto(a, page, en, zh, null);
        if (actions != null && actions.getChildCount() > 0) {
            actions.removeViewAt(0);
            for (int i = 0; i < actions.getChildCount(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.leftMargin = i == 0 ? 0 : dp(a, 8);
                actions.getChildAt(i).setLayoutParams(lp);
            }
            if (actions.getChildCount() == 0) page.removeView(actions);
            else { actions.setPadding(0, dp(a, 10), 0, 0); }
        }
        for (int i = 0; i < page.getChildCount(); i++) {
            View ch = page.getChildAt(i);
            if (ch instanceof android.widget.ScrollView && !(ch instanceof PageScrollView) && ((ViewGroup) ch).getChildCount() == 1) {
                ViewGroup.LayoutParams lp = ch.getLayoutParams();
                View content = ((ViewGroup) ch).getChildAt(0);
                ((ViewGroup) ch).removeView(content);
                page.removeViewAt(i);
                page.addView(paged(a, content), i, lp);
            }
        }
    }

    /**
     * 老样子的“‹ 标题”顶栏（+ 下面一条线）在方块风格下换成方块页头；extra 是原来顶栏右边的按钮（可以为 null，会从顶栏里拿出来）。
     * 经典风格什么都不做。
     */
    static void restyleTop(android.app.Activity a, LinearLayout page, View top, View rule, String en, String zh, View extra) {
        if (!on(a)) return;
        if (extra != null && extra.getParent() != null) ((ViewGroup) extra.getParent()).removeView(extra);
        page.removeView(top);
        if (rule != null) page.removeView(rule);
        headInto(a, page, en, zh, extra);
    }

    /** 把几个控件从原来的父布局里拿出来，放进一个带投影的双线框（方块风格的主卡片）。 */
    static LinearLayout boxed(Context c, View... views) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(frameDouble(c));
        int ip = dp(c, 12) + stroke(c);
        l.setPadding(ip, ip, ip + shade(c), ip + shade(c));
        for (View v : views) { if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v); l.addView(v, match()); }
        return l;
    }

    /** 列表里的小标题：方块风格是黑底白字的标题条，经典风格是粗体字。 */
    static TextView sectionTitle(Context c, String s, float classicSp) {
        if (on(c)) {
            TextView h = header(c, s);
            LinearLayout.LayoutParams lp = gap(c, 12);
            lp.bottomMargin = dp(c, 4);
            h.setLayoutParams(lp);
            return h;
        }
        TextView h = Ui.text(c, s, classicSp, true);
        h.setPadding(0, dp(c, 10), 0, dp(c, 4));
        return h;
    }

    /** 列表里两行之间的线：方块风格是黑色细线，经典风格是浅灰线。 */
    static View sep(Context c) { return on(c) ? hair(c) : Ui.rule(c, 1, Color.LTGRAY); }

    /**
     * 盖在别的页面上的单子（切换应用、每个应用的显示效果）：双线框 + 投影，左右上下留 16dp 透明的边，框整个看得见。
     * 返回给 setContentView 用的外层。
     */
    static View sheet(android.app.Activity a, LinearLayout root) { return sheet(a, root, true); }

    /** see-through = 单子以外透明（下面的应用看得见，主题是悬浮窗口时）；否则单子以外白色（不透明的 Activity 下面不画，透明会是黑的）。 */
    static View sheet(android.app.Activity a, LinearLayout root, boolean seeThrough) {
        int ip = dp(a, 14) + stroke(a);
        root.setBackground(frameDouble(a));
        root.setPadding(ip, ip, ip + shade(a), ip + shade(a));
        a.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(seeThrough ? Color.TRANSPARENT : Color.WHITE));
        FrameLayout wrap = new FrameLayout(a);
        int m = dp(a, 16);
        wrap.setPadding(m, m, m, m);
        wrap.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 分组框：黑底标题条（title 为 null 时没有）+ 带投影的外框，里面的行用 {@link #addRow} 加（行之间细线）。 */
    static LinearLayout group(Context c, String title) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(frame(c));
        int st = stroke(c), sh = shade(c);
        g.setPadding(st, st, st + sh, st + sh);
        if (title != null) { TextView t = header(c, title); t.setTag(GROUP_HEAD); g.addView(t); }
        return g;
    }
    private static final Object GROUP_HEAD = new Object();

    static void addRow(LinearLayout g, View row) {
        int n = g.getChildCount();
        if (n > 0 && !(n == 1 && g.getChildAt(0).getTag() == GROUP_HEAD)) g.addView(hair(g.getContext()));
        g.addView(row, match());
    }

    /** 页面最下面的一排按钮（返回 / 刷新……）：上面一条粗黑线，按钮等宽。 */
    static LinearLayout bottomBar(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 12));
        return b;
    }

    /**
     * 分页列表（和“文件”页一样）：双线框里一页放整数行，下面翻页条；划一下或点箭头翻页，一次只换一页的行（墨水屏一次刷新）。
     * 行高固定（rowDp），所以每页正好放整行，不会有半行。调用方给行数和每一行的控件，数据变了调 {@link #refresh()}。
     * 没有数据时显示 empty（可以为 null）。
     */
    static final class PagedList extends LinearLayout {
        interface Rows { int count(); View row(int i); }
        private final Rows rows;
        private final int rowH;
        private final LinearLayout list;
        private final Pager pager;
        private View empty;
        private int page, perPage;

        PagedList(Context c, int rowDp, Rows r) {
            super(c);
            rows = r;
            rowH = dp(c, rowDp);
            setOrientation(VERTICAL);
            list = new LinearLayout(c);
            list.setOrientation(VERTICAL);
            list.setBackground(frameDouble(c));
            int ip = dp(c, 4) + stroke(c);
            list.setPadding(ip, ip, ip + shade(c), ip + shade(c));
            PagedBox pbox = new PagedBox(c);
            pbox.setOrientation(VERTICAL);
            pbox.setListener(new PagedBox.Listener() { @Override public void onPage(int dir) { turn(dir); } });
            pbox.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            addView(pbox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            pager = new Pager(c, new Pager.Listener() { @Override public void onPage(int dir) { turn(dir); } });
            addView(pager, gap(c, 8));
            pbox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int rr, int b, int ol, int ot, int or, int ob) {
                    int np = Math.max(2, (b - t - dp(getContext(), 12) - shade(getContext())) / (rowH + 1));
                    if (np != perPage && b - t > 0) { perPage = np; v.post(new Runnable() { @Override public void run() { render(); } }); }
                }
            });
        }

        void setEmpty(View v) { empty = v; }
        void refresh() { render(); }
        void toFirst() { page = 0; render(); }

        private void turn(int dir) {
            int pages = pages();
            int np = Math.max(0, Math.min(pages - 1, page + dir));
            if (np == page) return;
            swipe(getContext(), dir > 0 ? DIR_UP : DIR_DOWN, KIND_LIST);
            page = np;
            render();
        }

        private int pages() { return perPage <= 0 ? 1 : Math.max(1, (rows.count() + perPage - 1) / perPage); }

        private void render() {
            if (perPage <= 0) return;
            int n = rows.count(), pages = pages();
            if (page >= pages) page = pages - 1;
            list.removeAllViews();
            int from = page * perPage, to = Math.min(n, from + perPage);
            for (int i = from; i < to; i++) {
                if (i > from) list.addView(hair(getContext()));
                list.addView(rows.row(i), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
            }
            if (n == 0 && empty != null) { if (empty.getParent() != null) ((ViewGroup) empty.getParent()).removeView(empty); list.addView(empty, match()); }
            pager.set(page + 1, pages);
            int vis = pages > 1 ? View.VISIBLE : View.INVISIBLE;
            if (pager.getVisibility() != vis) pager.setVisibility(vis);
        }
    }

    /** 列表里常用的一行：左边图标（可以没有）、中间粗体名字和一行说明，右边一个控件（可以没有）。 */
    static LinearLayout listRow(Context c, Icons.Kind icon, String name, String hint, View right) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(c, 12), dp(c, 4), dp(c, 10), dp(c, 4));
        if (icon != null) {
            Icons.IconView iv = new Icons.IconView(c, icon);
            LinearLayout.LayoutParams ip = lp(dp(c, 26), dp(c, 26));
            ip.rightMargin = dp(c, 12);
            r.addView(iv, ip);
        }
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, name, 17, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        mid.addView(t);
        if (hint != null && hint.length() > 0) {
            TextView h = Ui.text(c, hint, 12, false);
            h.setSingleLine(false);
            h.setMaxLines(2);
            h.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(h);
        }
        r.addView(mid, weight(1));
        if (right != null) {
            LinearLayout.LayoutParams rp = right instanceof Icons.IconView ? lp(dp(c, 18), dp(c, 18))   // 图标没有自己的大小，不给定宽会占满一行
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.leftMargin = dp(c, 8);
            r.addView(right, rp);
        }
        return r;
    }

    // ------------------------------------------------------------------ switch（方块开关）

    /** 方框开关：开 = 右半边一个实心黑方块，关 = 左半边一个空心小方块。点一下切换，只重画它自己。 */
    static final class Switch extends View {
        private boolean on;
        private final Icons.Toggle.Listener l;
        private final Paint p = new Paint();
        Switch(Context c, boolean on, Icons.Toggle.Listener l) {
            super(c);
            this.on = on; this.l = l;
            setClickable(true);
            setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { Switch.this.on = !Switch.this.on; invalidate(); if (Switch.this.l != null) Switch.this.l.onToggle(Switch.this.on); } });
            Blocks.fast(this);
        }
        boolean isOn() { return on; }
        void set(boolean v) { if (v != on) { on = v; invalidate(); } }
        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(dp(getContext(), 64), dp(getContext(), 36)); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight(), st = stroke(getContext()), g = dp(getContext(), 5);
            p.setColor(Color.BLACK); p.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, w, st, p); c.drawRect(0, h - st, w, h, p); c.drawRect(0, 0, st, h, p); c.drawRect(w - st, 0, w, h, p);
            int side = h - 2 * g;
            if (on) c.drawRect(w - g - side, g, w - g, h - g, p);
            else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(st); c.drawRect(g + st / 2f, g + st / 2f, g + side - st / 2f, h - g - st / 2f, p); }
        }
    }

    // ------------------------------------------------------------------ stepper “‹ 值 ›”

    /** 步进器：两边是方框箭头，中间是框起来的当前值。一次走一格；改了只重画中间那格字。 */
    static final class Stepper extends LinearLayout {
        interface Listener { void onStep(int index); }
        private final String[] labels;
        private int index;
        private final TextView value;
        private Listener listener;
        private boolean wrap;

        Stepper(Context c, String[] labels, int index, Listener l) {
            super(c);
            this.labels = labels;
            this.index = Math.max(0, Math.min(labels.length - 1, index));
            listener = l;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int h = dp(c, 44);
            addView(arrow(c, Icons.Kind.BACK, -1), new LinearLayout.LayoutParams(dp(c, 44), h));
            value = Ui.text(c, labels[this.index], 15, true);
            value.setGravity(Gravity.CENTER);
            value.setSingleLine(false);
            value.setMaxLines(2);
            value.setMinHeight(h);
            value.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2));
            value.setEllipsize(TextUtils.TruncateAt.END);
            value.setBackground(plainFilled(c));
            value.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(dp(c, 136), ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.leftMargin = dp(c, 4); vp.rightMargin = dp(c, 4);
            addView(value, vp);
            addView(arrow(c, Icons.Kind.CHEV_R, 1), new LinearLayout.LayoutParams(dp(c, 44), h));
        }

        /** 走到头以后从另一头接着（选项不分大小时用，比如字体、开关类）。 */
        Stepper wrap() { wrap = true; return this; }

        /** 点中间的值：给“字体”这种选项多的打开一张选择表。 */
        Stepper onValueTap(View.OnClickListener l) { value.setClickable(true); value.setOnClickListener(l); Blocks.fast(value); return this; }

        void setValueWidth(int dpw) { value.getLayoutParams().width = dp(getContext(), dpw); }

        private View arrow(Context c, Icons.Kind k, final int dir) {
            FrameLayout f = new FrameLayout(c);
            f.setBackground(plain(c));
            f.addView(new Icons.IconView(c, k), new FrameLayout.LayoutParams(dp(c, 18), dp(c, 18), Gravity.CENTER));
            f.setClickable(true);
            f.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { step(dir); } });
            Blocks.fast(f);
            return f;
        }

        void step(int dir) {
            int n = index + dir;
            if (wrap) n = (n + labels.length) % labels.length;
            if (n < 0 || n >= labels.length || n == index) return;
            index = n;
            value.setText(labels[index]);
            if (listener != null) listener.onStep(index);
        }

        void set(int i) {
            i = Math.max(0, Math.min(labels.length - 1, i));
            if (i == index) return;
            index = i;
            value.setText(labels[index]);
        }

        int index() { return index; }
    }

    // ------------------------------------------------------------------ block bar（方块级别条）

    /**
     * 一排方块表示级别（亮度之类）：点哪一格就是哪一级，抬手时生效；最左边一格之前点一下 = 0。
     * 手指拖动时不跟着画（墨水屏上会一路刷），只在抬手时画一次。右边显示百分比。
     */
    static final class BlockBar extends View {
        interface Listener { void onCommit(int level); }
        private final Paint p = new Paint();
        private int blocks = 10, level, max = 10;
        private Listener listener;
        /** 0 = 无文字，1 = 百分比（"50%"），2 = 数值档位（"12"，与 Kindle 原厂亮度/暖光一致） */
        private int labelMode = 1;

        BlockBar(Context c, int blocks) {
            super(c);
            this.blocks = Math.max(2, blocks);
            max = this.blocks;
            p.setAntiAlias(false);
            p.setTextSize(dp(c, 12));
            p.setTypeface(Fonts.typeface(c));
        }

        void setListener(Listener l) { listener = l; setClickable(l != null); }
        /** 真实值的范围（亮度 0..24 之类）；格子数不变，按比例换算。 */
        void setMax(int m) { max = Math.max(1, m); invalidate(); }
        void setValue(int v) { int nv = Math.max(0, Math.min(max, v)); if (nv != level) { level = nv; invalidate(); } }
        int value() { return level; }
        void noPercent() { labelMode = 0; }
        void showValue() { labelMode = 2; p.setTextSize(dp(getContext(), 14)); invalidate(); }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(getContext(), 34));
        }

        private int labelW() {
            if (labelMode == 0) return 0;
            return dp(getContext(), labelMode == 2 ? 28 : 40);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth() - labelW(), h = getHeight();
            int g = blocks > 16 ? Math.max(1, dp(getContext(), 2)) : dp(getContext(), 3);
            float bw = (w - g * (blocks - 1)) / (float) blocks;
            int filled = Math.round(level * blocks / (float) max);
            float sw = blocks > 16 ? 1.5f : 2f;
            for (int i = 0; i < blocks; i++) {
                float x = i * (bw + g);
                p.setColor(Color.BLACK);
                if (i < filled) { p.setStyle(Paint.Style.FILL); c.drawRect(x, 2, x + bw, h - 2, p); }
                else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw); c.drawRect(x + 1, 3, x + bw - 1, h - 3, p); }
            }
            if (labelMode != 0) {
                p.setStyle(Paint.Style.FILL);
                String s = labelMode == 2 ? String.valueOf(level) : ((level * 100 / max) + "%");
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(s, getWidth(), h / 2f + p.getTextSize() / 2.8f, p);
            }
        }

        private float downX;
        private boolean dragged;
        private int startLevel;

        private int xToCell(float x, int w) {
            if (x <= 0) return 0;
            if (x >= w) return blocks - 1;
            return Math.min(blocks - 1, (int) (x * blocks / Math.max(1, w)));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (listener == null) return false;
            int w = getWidth() - labelW();
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                Perf.touch(e);
                downX = e.getX();
                dragged = false;
                startLevel = level;
                return true;
            }
            if (act == MotionEvent.ACTION_MOVE) {
                if (Math.abs(e.getX() - downX) > dp(getContext(), 6)) dragged = true;
                if (dragged) {
                    int nb = e.getX() <= dp(getContext(), 4) ? 0 : xToCell(e.getX(), w) + 1;
                    int nv = Math.max(0, Math.min(max, Math.round(nb * max / (float) blocks)));
                    if (nv != level) { level = nv; invalidate(); }
                }
                return true;
            }
            if (act == MotionEvent.ACTION_UP || (act == MotionEvent.ACTION_CANCEL && dragged)) {
                int slop = dp(getContext(), 24);
                if (act == MotionEvent.ACTION_UP && (e.getY() < -slop || e.getY() > getHeight() + slop)) {
                    if (level != startLevel) { level = startLevel; invalidate(); }
                    return true;
                }
                int nv;
                if (dragged) {
                    int nb = e.getX() <= dp(getContext(), 4) ? 0 : xToCell(e.getX(), w) + 1;
                    nv = Math.round(nb * max / (float) blocks);
                } else {
                    int cell = xToCell(e.getX(), w);
                    // 静止点击：点在当前最后一格亮着的格子上时退一格（方便点回 0）
                    int filled = Math.round(startLevel * blocks / (float) max);
                    int nb = (cell + 1 == filled) ? cell : cell + 1;
                    nv = Math.round(nb * max / (float) blocks);
                }
                nv = Math.max(0, Math.min(max, nv));
                level = nv;
                invalidate();
                if (nv != startLevel || act == MotionEvent.ACTION_UP) listener.onCommit(level);
                return true;
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ segmented tabs

    /** 连在一起的几格，选中的那格黑底白字（文件页的“最近 / 书籍 / 图片 / 笔记”、刷新档位）。 */
    static final class SegBar extends LinearLayout {
        interface Listener { void onPick(int i); }
        private final TextView[] cells;
        private int selected = -1;

        SegBar(Context c, String[] labels, int sel, final Listener l) {
            super(c);
            setOrientation(HORIZONTAL);
            setBackground(frame(c));
            int st = stroke(c);
            setPadding(st, st, st + shade(c), st + shade(c));
            cells = new TextView[labels.length];
            float textSp = labels.length >= 4 ? 14 : 15;
            int barH = dp(c, 46);
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                TextView t = Ui.text(c, labels[i], textSp, true);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(false);
                t.setMaxLines(2);
                t.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2));
                t.setClickable(true);
                t.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (idx != selected) { select(idx); if (l != null) l.onPick(idx); } } });
                Blocks.fast(t);
                cells[i] = t;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, barH, 1);
                if (i > 0) { View sep = new View(c); sep.setBackgroundColor(Color.BLACK); addView(sep, new LinearLayout.LayoutParams(2, barH)); }
                addView(t, p);
            }
            select(sel);
        }

        void select(int i) {
            if (i == selected) return;
            selected = i;
            for (int k = 0; k < cells.length; k++) {
                boolean on = k == i;
                cells[k].setBackgroundColor(on ? Color.BLACK : Color.WHITE);
                cells[k].setTextColor(on ? Color.WHITE : Color.BLACK);
            }
        }

        int selected() { return selected; }
    }

    // ------------------------------------------------------------------ pager “‹ 1/4 ›”

    /** 翻页条：左右两个方框箭头，中间“第几页 / 共几页”（当前页码黑底）。 */
    static final class Pager extends LinearLayout {
        interface Listener { void onPage(int dir); }
        private final TextView cur, total;

        Pager(Context c, final Listener l) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(frame(c));
            setPadding(0, 0, shade(c), shade(c));
            int h = dp(c, 44);
            addView(pageBtn(c, "‹ 上一页", -1, l), new LinearLayout.LayoutParams(dp(c, 108), h));
            addView(vrule(c), new LinearLayout.LayoutParams(stroke(c), h));
            LinearLayout mid = new LinearLayout(c);
            mid.setGravity(Gravity.CENTER);
            cur = Ui.text(c, "1", 14, true);
            cur.setTextColor(Color.WHITE);
            cur.setBackgroundColor(Color.BLACK);
            cur.setPadding(dp(c, 6), 0, dp(c, 6), 0);
            mid.addView(cur);
            total = Ui.text(c, " / 1", 14, false);
            mid.addView(total);
            addView(mid, new LinearLayout.LayoutParams(0, h, 1));
            addView(vrule(c), new LinearLayout.LayoutParams(stroke(c), h));
            addView(pageBtn(c, "下一页 ›", 1, l), new LinearLayout.LayoutParams(dp(c, 108), h));
        }

        private View pageBtn(Context c, String label, final int dir, final Listener l) {
            TextView t = Ui.text(c, label, 15, true);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(Color.BLACK);
            t.setClickable(true);
            t.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (l != null) l.onPage(dir); } });
            Blocks.fast(t);
            return t;
        }

        /** 点中间的页码（目录：输入第几条）。 */
        void onMidTap(OnClickListener l) { View mid = getChildAt(2); mid.setClickable(true); mid.setOnClickListener(l); }

        void set(int page1, int pages) {
            String a = String.valueOf(page1), b = " / " + pages;
            if (!a.contentEquals(cur.getText())) cur.setText(a);
            if (!b.contentEquals(total.getText())) total.setText(b);
        }
    }

    // ------------------------------------------------------------------ bottom navigation

    /** 底部四个标签：图标 + 名字，当前的那个上面压一条粗黑线、名字加粗。 */
    static final class Nav extends LinearLayout {
        interface Listener { void onTab(int i); }
        private final View[] marks;
        private final TextView[] names;
        private int active = -1;

        Nav(Context c, String[] labels, Icons.Kind[] kinds, final Listener l) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundColor(Color.WHITE);
            addView(Ui.rule(c, stroke(c), Color.BLACK));
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            marks = new View[labels.length];
            names = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                View m = new View(c);
                marks[i] = m;
                LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(c, 64), dp(c, 7));
                mp.bottomMargin = dp(c, 4);
                cell.addView(m, mp);
                cell.addView(new Icons.IconView(c, kinds[i]), lp(dp(c, 26), dp(c, 26)));
                TextView t = Ui.text(c, labels[i], 14, false);
                t.setPadding(0, dp(c, 2), 0, dp(c, 8));
                t.setGravity(Gravity.CENTER);
                names[i] = t;
                cell.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setPadding(dp(c, 14), 0, dp(c, 14), 0);
                cell.setClickable(true);
                cell.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (l != null) l.onTab(idx); } });
                Blocks.fast(cell);
                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            addView(row);
        }

        void setActive(int i) {
            if (i == active) return;
            active = i;
            for (int k = 0; k < marks.length; k++) {
                marks[k].setBackgroundColor(k == i ? Color.BLACK : Color.WHITE);
                Fonts.apply(names[k], k == i);
            }
        }
    }
}
