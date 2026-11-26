package com.veradroid.home;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * “方块”界面风格（设置／界面风格）：底部四个标签 首页 / 文件 / 应用 / 我的，每个标签和“我的”下面的设置页
 * 都是一棵完整的控件树，挂在同一个 FrameLayout 里只切换可见性（和经典风格的主页 / 书库一样：换页面只刷一次）。
 * 每一页记一个“签名”（它用到的数据），签名没变就直接换上去，变了才在显示之前同步重搭。
 *
 * 数据全部来自现有的地方：书库索引和封面（{@link Lib}）、阅读进度（reader 偏好）、阅读时长（{@link Stats}）、
 * 应用列表和图标（{@link HomeActivity}）、宿主的状态和设置文件（{@link HostLink}）。
 */
final class BlocksHome {
    static final int HOME = 0, FILES = 1, APPS = 2, ME = 3;
    static final int S_DISPLAY = 10, S_POWER = 11, S_STORAGE = 12, S_ABOUT = 13, S_CARDS = 14, S_LIBRARY = 15;
    /** 经典设置的各类做成的方块设置页：S_CATS = 分类列表（全部设置），S_CATS + 1 + t = 第 t 类。只在点进去时搭，回到标签页时拆掉。 */
    static final int S_CATS = 30;
    static int catKey(int t) { return S_CATS + 1 + Math.max(-1, Math.min(HomeActivity.CAT_COUNT - 1, t)); }
    static boolean isCat(int key) { return key >= S_CATS && key <= S_CATS + HomeActivity.CAT_COUNT; }
    /** 设置页之间往里点（设备与关于 → 全部设置 → 手势）时记下来路，返回一层一层退。 */
    private final java.util.ArrayList<Integer> trail = new java.util.ArrayList<Integer>();

    final HomeActivity a;
    final SharedPreferences prefs;
    private final LinearLayout root;
    private final FrameLayout body;
    private final Blocks.Nav nav;
    private final TextView stLeft, stRight;
    private final SparseArray<View> pages = new SparseArray<View>();
    private final SparseArray<String> sigs = new SparseArray<String>();
    private int cur = -1;
    /** 设置页 / 全部书页的返回去处（一般是“我的”，从首页“全部”进来的回首页）。 */
    int backTo = ME;
    final BlocksFiles files;
    final BlocksSettings settings;

    BlocksHome(HomeActivity activity) {
        a = activity;
        prefs = Store.prefs(a, "home");
        files = new BlocksFiles(this);
        settings = new BlocksSettings(this);
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        // 不涂底色：底下是 HomeActivity 的 page（白纸，首页标签有壁纸时是壁纸，见 HomeActivity.blocksWall）
        // 最上面一行小字：左边名字，右边时间和电量（只在换页面和整分钟时改）
        LinearLayout st = new LinearLayout(a);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setPadding(dp(16), dp(5), dp(16), dp(4));
        st.setBackgroundColor(Color.WHITE);   // 最上面一行小字不压在壁纸上
        Icons.IconView logo = new Icons.IconView(a, Icons.Kind.BOOK);
        st.addView(logo, Blocks.lp(dp(16), dp(16)));
        stLeft = Ui.text(a, "", 12, true);
        stLeft.setPadding(dp(6), 0, 0, 0);
        st.addView(stLeft, Blocks.weight(1));
        stRight = Ui.text(a, "", 12, false);
        st.addView(stRight);
        root.addView(st);
        root.addView(Ui.rule(a, 1, Color.BLACK));
        body = new FrameLayout(a);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        nav = new Blocks.Nav(a, new String[] { "首页", "文件", "应用", "我的" },
            new Icons.Kind[] { Icons.Kind.HOME, Icons.Kind.FOLDER, Icons.Kind.GRID, Icons.Kind.USER }, new Blocks.Nav.Listener() {
                @Override public void onTab(int i) {
                    if (i == cur) { if (i == FILES && files.toRoot()) a.changed(); return; }
                    Blocks.swipe(a, order(i) > order(cur) ? Blocks.DIR_LEFT : Blocks.DIR_RIGHT, Blocks.KIND_PAGE);
                    show(i);
                    a.changed();
                }
            });
        root.addView(nav);
        if (a.standalone) nav.setVisibility(View.GONE);   // 盖在应用上面的设置：没有 首页 / 文件 / 应用 标签
    }

    View root() { return root; }

    /** 换页动画的方向：标签从左到右，设置页在“我的”的右边。 */
    private static int order(int key) { return key < 0 ? 0 : key >= 10 ? 4 : key; }

    /** 用户点进某一页（设置分类、全部书……）：往左擦；返回：往右擦。 */
    /** 打开经典设置第 t 类对应的方块设置页（-1 = 全部设置的分类列表）；返回时回到现在这一页。 */
    void openCat(int t) {
        int key = catKey(t);
        if (cur >= 10 && cur != key) trail.add(cur); else if (cur < 10) { trail.clear(); backTo = cur < 0 ? ME : cur; }
        go(key);
    }

    void go(int key) { Blocks.swipe(a, order(key) >= order(cur) ? Blocks.DIR_LEFT : Blocks.DIR_RIGHT, Blocks.KIND_PAGE); show(key); a.changed(); }
    int current() { return cur < 0 ? HOME : cur; }
    int dp(int v) { return Ui.dp(a, v); }

    // ------------------------------------------------------------------ switching

    /** 显示一页：签名变了先同步重搭，再只让它可见。 */
    void show(int key) {
        Perf.measure(a.perfRoot(), "blocks " + key);
        if (key == HOME || key == FILES) a.ensureLib();
        if (key < 10) dropCats();
        View v = page(key);
        for (int i = 0; i < body.getChildCount(); i++) {
            View c = body.getChildAt(i);
            int vis = c == v ? View.VISIBLE : View.GONE;
            if (c.getVisibility() != vis) c.setVisibility(vis);
        }
        cur = key;
        a.blocksWall(key);
        nav.setActive(key == S_LIBRARY ? HOME : key >= 10 ? ME : key);
        if (key == HOME) { tickClock(); BlocksCards.maybeFetchWeather(a, new BlocksCards.Changed() { @Override public void changed() { if (cur == HOME) { dataChanged(); a.changed(); } } }); }
        if (key == ME) refreshMe();
        if (key == catKey(HomeActivity.CAT_XFER_)) a.updateTransferInfo();
    }

    /** 回到四个标签时把经典设置做成的那几页拆掉（不常用，留着占内存；512 MB 的机型也要跑）。 */
    private void dropCats() {
        trail.clear();
        for (int k = S_CATS; k <= S_CATS + HomeActivity.CAT_COUNT; k++) {
            View v = pages.get(k);
            if (v == null) continue;
            body.removeView(v);
            pages.remove(k);
            sigs.remove(k);
        }
    }

    /** 当前页的数据可能变了（书库扫完、封面做好、应用装卸）：签名变了就重搭当前页（一次刷新）；别的页等显示时再说。 */
    private final Runnable dataChangedLater = new Runnable() { @Override public void run() { dataChanged(); } };
    void dataChanged() {
        // 不用 isShown()：从应用回来的 onResume 里窗口还没显示，那时正要在第一帧之前把页面换好
        if (cur < 0 || root.getParent() == null || root.getVisibility() != View.VISIBLE) return;
        if (a.touching()) { a.afterTouch(dataChangedLater); return; }   // 手指还按着：抬手、点按处理完再换页（见 HomeActivity.touching）
        String s = sig(cur);
        if (!s.equals(sigs.get(cur))) show(cur);
    }

    private View page(int key) {
        String s = sig(key);
        View v = pages.get(key);
        if (v != null && s.equals(sigs.get(key))) return v;
        // 首页：卡片的排列没变时只换掉数据变了的那几张卡片（天气取回来、统计变了、封面做好……），不整页重搭
        if (v != null && key == HOME && patchHome()) { sigs.put(key, s); return v; }
        View nv = build(key);
        if (v != null) body.removeView(v);
        nv.setVisibility(View.GONE);
        body.addView(nv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pages.put(key, nv);
        sigs.put(key, s);
        return nv;
    }

    /** 主页闲下来时：把四个标签先搭好、量好（不可见），第一次点哪个都只是换一棵树。 */
    void prebuild(View warmTarget) {
        for (int k : PREBUILD) {
            View v = pages.get(k);
            if (v != null && sig(k).equals(sigs.get(k))) continue;
            v = page(k);
            a.warmIn(v, body);
            return;   // 一次一页，下一次空闲再搭下一页
        }
    }
    /** 闲下来时先搭好的页：四个标签和“我的”下面的设置页（和经典风格的设置页一样都预先搭）。 */
    private static final int[] PREBUILD = Ui.ONE_CORE ? new int[] { HOME, FILES, APPS, ME }   // 单核：只搭四个标签，设置页点进去时再搭
        : new int[] { HOME, FILES, APPS, ME, S_DISPLAY, S_POWER, S_STORAGE, S_ABOUT, S_CARDS };
    boolean prebuilt() { for (int k : PREBUILD) if (pages.get(k) == null || !sig(k).equals(sigs.get(k))) return false; return true; }

    private String sig(int key) {
        String caps = Blocks.caps(a) + "/" + Fonts.weight(a);   // 英文小字、界面字重变了，每一页都要重搭
        switch (key) {
        case HOME: {
            Calendar c = Calendar.getInstance();
            return caps + "|" + Lib.version() + "|" + prefs.getString(BlocksCards.KEY, BlocksCards.DEFAULT) + "|" + Stats.weekMs(a) / 60000 + "|" + Stats.bookmarkCount(a)
                + "|" + prefs.getLong("la_time", 0) + "|" + prefs.getLong("wx_at", 0) + prefs.getString("wx_city", "") + "|" + c.get(Calendar.DAY_OF_YEAR)
                + "|" + a.appsVersion() + "|" + prefs.getString("blk_quick", "") + "|" + a.libOn() + "|" + prefs.getInt(Blocks.WALLCARD_KEY, 0);
        }
        case FILES: return caps + "|" + files.sig();
        case APPS: return appsSig(caps, a.appsVersion());
        case ME: return caps + "|" + UiMode.isExpert(a) + "|" + Stats.monthMs(a) / 60000 + "|" + Stats.bookmarkCount(a) + "|" + Lib.version() + "|" + prefs.getString("name", "VeraDroid");
        case S_LIBRARY: return caps + "|" + Lib.version() + "|" + libSort;
        default: return caps + "|" + settings.sig(key);
        }
    }

    private String appsSig(String caps, long appsVer) {
        return caps + "|" + appsVer + "|" + prefs.getInt("cols", 3) + "|" + prefs.getInt("iconstyle", 0) + "|" + prefs.getString("blk_pins", "") + prefs.getString("blk_hidden", "") + showHidden + prefs.getInt("sort", 0);
    }

    private View build(int key) {
        switch (key) {
        case HOME: return buildHome();
        case FILES: return files.build();
        case APPS: return buildApps();
        case ME: return buildMe();
        case S_LIBRARY: return buildLibrary();
        default: return settings.build(key);
        }
    }

    /** 已经搭好的某一页作废（设置改了它的内容），下次显示时重搭。 */
    void invalidate(int key) { sigs.put(key, "x"); }

    boolean back() {
        if (cur >= 10) {
            Blocks.swipe(a, Blocks.DIR_RIGHT, Blocks.KIND_PAGE);
            show(trail.isEmpty() ? backTo : trail.remove(trail.size() - 1));
            a.changed();
            return true;
        }
        if (cur == FILES && files.up()) { a.changed(); return true; }
        if (cur != HOME) { Blocks.swipe(a, Blocks.DIR_RIGHT, Blocks.KIND_PAGE); show(HOME); a.changed(); return true; }
        return true;
    }

    void refreshStatus(String clock, String state) {
        String name = prefs.getString("name", "VeraDroid");
        if (!name.contentEquals(stLeft.getText())) stLeft.setText(name);
        String r = clock + "    " + state;
        if (!r.contentEquals(stRight.getText())) stRight.setText(r);
    }

    // ------------------------------------------------------------------ minute tick: only the clock card's text changes

    private final List<TextView> clocks = new ArrayList<TextView>();
    private int tickDay = -1;
    void tickClock() {
        String t = Ui.clock(a);
        for (TextView v : clocks) if (v.isAttachedToWindow() && !t.contentEquals(v.getText())) v.setText(t);
        int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if (tickDay >= 0 && day != tickDay && cur == HOME) dataChanged();   // 过了零点：日期、日历、诗词都换（一天一次）
        tickDay = day;
    }

    // ------------------------------------------------------------------ 首页

    /** 首页上的一张卡片：它是哪张、挂在哪、搭它时用到的数据（{@link #cardSig}）。view 为 null = 那时没搭出来。 */
    private static final class Slot { String id, sig; View view; }
    private final List<Slot> homeSlots = new ArrayList<Slot>();
    private String homeLayoutSig;

    /** 首页的排列：哪些卡片、什么顺序、横竖屏、字体、遮盖、标题里的书数。这些变了要整页重搭。 */
    private String homeLayoutSig() {
        boolean wide = a.getResources().getDisplayMetrics().widthPixels > a.getResources().getDisplayMetrics().heightPixels;
        return Blocks.caps(a) + "/" + Fonts.weight(a) + "|" + prefs.getString(BlocksCards.KEY, BlocksCards.DEFAULT) + "|" + a.libOn()
            + "|" + prefs.getInt(Blocks.WALLCARD_KEY, 0) + "|" + wide + "|" + (a.libOn() ? Lib.all().size() : 0);
    }

    /** 一张卡片用到的数据（和 sig(HOME) 里的各项对应：每一项都至少落在一张卡片上）。 */
    private String cardSig(String id) {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if ("continue".equals(id)) return Lib.version() + "|" + prefs.getString("la_pkg", "") + prefs.getLong("la_time", 0) + "|" + a.appsVersion();
        if ("stats".equals(id)) return Stats.weekMs(a) / 60000 + "|" + Stats.bookmarkCount(a);
        if ("recent".equals(id)) return String.valueOf(Lib.version());
        if ("weather".equals(id)) return prefs.getLong("wx_at", 0) + prefs.getString("wx_city", "") + "|" + day;
        if ("apps".equals(id)) return prefs.getString("blk_quick", "") + "|" + a.appsVersion();
        if (BlocksCards.isWidget(id)) return "";   // 小部件自己会更新，重搭反而要重新绑定
        return String.valueOf(day);   // 时钟（日期）、日历、诗词
    }

    /**
     * 首页的签名变了而排列没变：只重搭数据变了的卡片，换在原来的位置上（其余卡片、标题、翻页条都不动）。
     * 先把要换的都搭好再一起换，搭不出来就返回 false 让调用方整页重搭。调用方保证手指没按在屏幕上（dataChanged）。
     */
    private boolean patchHome() {
        if (homeSlots.isEmpty() || !homeLayoutSig().equals(homeLayoutSig)) return false;
        List<Slot> todo = new ArrayList<Slot>();
        List<String> newSigs = new ArrayList<String>();
        for (Slot sl : homeSlots) {
            String cs = cardSig(sl.id);
            if (cs.equals(sl.sig)) continue;
            if (sl.view == null || !(sl.view.getParent() instanceof ViewGroup)) return false;
            todo.add(sl); newSigs.add(cs);
        }
        if (todo.isEmpty()) return true;
        List<View> made = new ArrayList<View>();
        for (Slot sl : todo) {
            View c = card(sl.id);
            if (c == null) return false;
            if (c.getBackground() == null) c.setBackground(new Blocks.Veil());
            made.add(c);
        }
        for (int i = 0; i < todo.size(); i++) {
            Slot sl = todo.get(i);
            ViewGroup parent = (ViewGroup) sl.view.getParent();
            int idx = parent.indexOfChild(sl.view);
            ViewGroup.LayoutParams lp = sl.view.getLayoutParams();
            dropClocksIn(sl.view);
            parent.removeViewAt(idx);
            parent.addView(made.get(i), idx, lp);
            sl.view = made.get(i);
            sl.sig = newSigs.get(i);
        }
        return true;
    }

    /** 被换掉的卡片里的时钟不再按分钟更新。 */
    private void dropClocksIn(View old) {
        for (int i = clocks.size() - 1; i >= 0; i--) {
            View v = clocks.get(i);
            while (v != null && v != old) v = v.getParent() instanceof View ? (View) v.getParent() : null;
            if (v == old) clocks.remove(i);
        }
    }

    private View buildHome() {
        clocks.clear();
        homeSlots.clear();
        homeLayoutSig = homeLayoutSig();
        LinearLayout pg = new LinearLayout(a);
        pg.setOrientation(LinearLayout.VERTICAL);
        int n = a.libOn() ? Lib.all().size() : 0;
        LinearLayout head = Blocks.pageHead(a, "Library / " + n + " books", "首页", Blocks.squareButton(a, Icons.Kind.MORE, new View.OnClickListener() {
            @Override public void onClick(View v) { a.mainMenu(); }
        }));
        head.setBackground(new Blocks.Veil());   // 有壁纸时标题行也垫一层白（按“卡片遮盖”的浓淡）
        pg.addView(head);
        LinearLayout cards = new LinearLayout(a);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(dp(16), 0, dp(16), dp(8));
        // 横屏：两栏，卡片轮流放左右（每栏从上往下排）；竖屏一栏
        boolean wide = a.getResources().getDisplayMetrics().widthPixels > a.getResources().getDisplayMetrics().heightPixels;
        LinearLayout colL = cards, colR = null;
        if (wide) {
            LinearLayout two = new LinearLayout(a);
            two.setOrientation(LinearLayout.HORIZONTAL);
            colL = new LinearLayout(a); colL.setOrientation(LinearLayout.VERTICAL);
            colR = new LinearLayout(a); colR.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp1 = Blocks.weight(1); lp1.rightMargin = dp(8);
            LinearLayout.LayoutParams lp2 = Blocks.weight(1); lp2.leftMargin = dp(8);
            two.addView(colL, lp1); two.addView(colR, lp2);
            cards.addView(two, Blocks.match());
        }
        int n0 = 0;
        for (String id : BlocksCards.order(a)) {
            Slot sl = new Slot();
            sl.id = id;
            sl.sig = cardSig(id);
            homeSlots.add(sl);
            View c = card(id);
            sl.view = c;
            if (c == null) continue;
            if (c.getBackground() == null) c.setBackground(new Blocks.Veil());   // 没有框的卡片（最近阅读……）的字不直接压在壁纸上
            LinearLayout col = colR != null && (n0 % 2 == 1) ? colR : colL;
            col.addView(c, Blocks.gap(a, col.getChildCount() == 0 ? 2 : 10));
            n0++;
            if (id.equals(flashCard) && Blocks.anim(a) > 0) flashOnce(c);
        }
        flashCard = null;
        if (n0 == 0) {
            LinearLayout e = Blocks.empty(a, Icons.Kind.WIDGET, "首页上没有卡片", "点这里挑几张：继续阅读、时钟、日历、天气……", false);
            e.setClickable(true);
            e.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { backTo = HOME; go(S_CARDS); } });
            Blocks.fast(e);
            cards.addView(e, Blocks.match());
        }
        pg.addView(paged(cards), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return pg;
    }

    /** 内容放不下一屏时一页一页翻（划动或点下面的翻页条）。放得下时翻页条不显示。 */
    /** 刚在“首页卡片”里加上的卡片：第一次出现时整块反色一下再还原（两次小块刷新），告诉你它放在了哪里。 */
    String flashCard;
    private void flashOnce(final View v) {
        Paint p = new Paint();
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] { -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0 }));
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, p);
        v.postDelayed(new Runnable() { @Override public void run() { v.setLayerType(View.LAYER_TYPE_NONE, null); } }, 450);
    }

    View paged(LinearLayout content) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final PageScrollView sv = new PageScrollView(a);
        sv.addView(content);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        final Blocks.Pager pager = new Blocks.Pager(a, new Blocks.Pager.Listener() {
            @Override public void onPage(int dir) { Blocks.swipe(a, dir > 0 ? Blocks.DIR_UP : Blocks.DIR_DOWN, Blocks.KIND_LIST); if (sv.page(dir)) sv.firePage(); }
        });
        LinearLayout.LayoutParams pp = Blocks.match();
        pp.leftMargin = dp(16); pp.rightMargin = dp(16); pp.bottomMargin = dp(6);
        box.addView(pager, pp);
        final Runnable label = new Runnable() { @Override public void run() {
            pager.set(sv.currentPage(), sv.pages());
            int vis = sv.pages() > 1 ? View.VISIBLE : View.GONE;
            if (pager.getVisibility() != vis) pager.setVisibility(vis);
        } };
        pager.setVisibility(View.GONE);
        sv.setOnPage(new Runnable() { @Override public void run() { label.run(); a.changed(); } });
        // 每次排版完核对一次页数（翻页条出现 / 消失会再排一次版，在同一帧里完成），稳定后或 View detach 时及时移除避免泄漏
        final android.view.ViewTreeObserver.OnGlobalLayoutListener gl = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (sv.getHeight() <= 0) return;
                int before = pager.getVisibility();
                label.run();
                if (pager.getVisibility() == before) {
                    android.view.ViewTreeObserver vto = sv.getViewTreeObserver();
                    if (vto.isAlive()) vto.removeOnGlobalLayoutListener(this);
                }
            }
        };
        sv.getViewTreeObserver().addOnGlobalLayoutListener(gl);
        sv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                android.view.ViewTreeObserver vto = sv.getViewTreeObserver();
                if (vto.isAlive()) vto.removeOnGlobalLayoutListener(gl);
            }
        });
        return box;
    }

    /** 长按卡片：上移 / 下移 / 移除 / 管理卡片。卡片里能点的东西也一起接上长按（不然长按会被它们吃掉）。 */
    private View card(final String id) {
        View v = card0(id);
        if (v != null && !BlocksCards.isWidget(id)) attachLong(v, new View.OnLongClickListener() { @Override public boolean onLongClick(View x) { cardMenu(id); return true; } });
        return v;
    }

    /**
     * 只接到卡片本身和本来就能点的控件上。以前每个 ViewGroup 都接：setOnLongClickListener 会让它“可长按”，
     * 可长按的控件会吃掉落在它上面的整个触摸，点按就到不了外层那一行的 onClick——“继续阅读”卡片点了没反应
     * （0.95 用户诊断：封面框和右边那一栏盖满了整行，点哪里都被它们吃掉）。不能点的地方长按会冒泡到卡片本身。
     */
    private static void attachLong(View v, View.OnLongClickListener l) { attachLong(v, l, true); }
    private static void attachLong(View v, View.OnLongClickListener l, boolean top) {
        if (top || v.isClickable()) v.setOnLongClickListener(l);
        if (v instanceof ViewGroup) { ViewGroup g = (ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) attachLong(g.getChildAt(i), l, false); }
    }

    private void cardMenu(final String id) {
        new VeraDialog.Builder(a).title(BlocksCards.name(a, id)).items(new String[] { "上移", "下移", "从首页移除", "管理首页卡片…" }, new VeraDialog.OnPick() {
            @Override public void onPick(int w) {
                List<String> l = BlocksCards.order(a);
                int i = l.indexOf(id);
                if (w == 3) { backTo = HOME; go(S_CARDS); return; }
                if (i < 0) return;
                if (w == 2) { if (BlocksCards.isWidget(id)) BlocksCards.removeWidget(a, BlocksCards.widgetId(id)); else { l.remove(i); BlocksCards.save(a, l); } }
                else { int j = i + (w == 0 ? -1 : 1); if (j < 0 || j >= l.size()) return; l.set(i, l.get(j)); l.set(j, id); BlocksCards.save(a, l); }
                show(HOME); a.changed();
            }
        }).negative("取消", null).show();
    }

    private View card0(String id) {
        try {
            if ("continue".equals(id)) return continueCard();
            if ("stats".equals(id)) return statsCard();
            if ("recent".equals(id)) return recentCard();
            if ("clock".equals(id)) return clockCard();
            if ("calendar".equals(id)) return calendarCard();
            if ("weather".equals(id)) return weatherCard();
            if ("poem".equals(id)) return poemCard();
            if ("apps".equals(id)) return quickAppsCard();
            if (BlocksCards.isWidget(id)) return widgetCard(BlocksCards.widgetId(id));
        } catch (Throwable t) { android.util.Log.w("VeraHome", "card " + id + ": " + t); }
        return null;
    }

    private Lib.Book lastBook() {
        if (!a.libOn()) return null;
        Lib.Book best = null;
        for (Lib.Book b : Lib.all()) if (b.lastRead > 1 && b.percent < 100 && (best == null || b.lastRead > best.lastRead)) best = b;
        return best;
    }

    private View continueCard() {
        final Lib.Book book = lastBook();
        final String laPkg = prefs.getString("la_pkg", null);
        final long laTime = prefs.getLong("la_time", 0);
        final ResolveInfo app = laPkg == null || !BlocksCards.readers(a).contains(laPkg) ? null : a.findApp(laPkg);
        boolean appFirst = app != null && (book == null || laTime > book.lastRead);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Blocks.frameDouble(a));
        int ip = dp(10);
        card.setPadding(ip, ip, ip + Blocks.shade(a), ip + Blocks.shade(a));
        if (book == null && app == null) card.addView(emptyRow());
        else card.addView(appFirst ? appRow(app, laPkg, laTime) : bookRow(book));
        // 另一个来源也有：下面一行小字，点了直接去
        if (appFirst && book != null) card.addView(alsoRow("也在读：《" + book.title + "》 · " + BlocksCards.ago(book.lastRead), new View.OnClickListener() {
            @Override public void onClick(View v) { openBook(book); } }));
        else if (!appFirst && app != null) card.addView(alsoRow("最近用过：" + a.labelFor(app) + " · " + BlocksCards.ago(laTime), new View.OnClickListener() {
            @Override public void onClick(View v) { openApp(app); } }));
        return card;
    }

    /**
     * 继续阅读卡片的一行：左边一个带框的封面（或应用离开时的画面），右边顶上一条黑底白字的“继续阅读”，
     * 下面书名、读到哪、章名、格式·大小·时间，最底下“百分比 … 今天”和一条带框的进度条。
     * 这一行的高度固定成封面的高度（右边的内容在这个高度里排：上面靠上，进度条贴底）。
     */
    private LinearLayout continueRow(View coverOrThumb, String title, String l1, String l2, String l3, int pct, String when) {
        int cw = coverW(), ch = cw * 4 / 3;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout cf = new FrameLayout(a);
        cf.setBackground(Blocks.frame(a));
        cf.setPadding(Blocks.stroke(a), Blocks.stroke(a), Blocks.stroke(a) + Blocks.shade(a), Blocks.stroke(a) + Blocks.shade(a));
        cf.addView(coverOrThumb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(cf, new LinearLayout.LayoutParams(cw + Blocks.shade(a), ch + Blocks.shade(a)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView chip = Blocks.chip(a, "继续阅读");
        col.addView(chip, Blocks.match());
        TextView t = Ui.text(a, title, 19, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
        if (l1 != null) col.addView(BlocksCards.line(a, l1, 15, true));
        if (l2 != null) { TextView c2 = BlocksCards.line(a, l2, 14, false); c2.setPadding(0, dp(6), 0, 0); col.addView(c2); }
        if (l3 != null) col.addView(BlocksCards.line(a, l3, 12, false));
        col.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout foot = new LinearLayout(a);
        foot.addView(Ui.text(a, pct + "%", 12, false), Blocks.weight(1));
        foot.addView(Ui.text(a, when, 12, false));
        col.addView(foot);
        LevelBar bar = new LevelBar(a);
        bar.setEnabled(false);
        bar.setMax(100);
        bar.setValue(pct);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
        row.addView(col, new LinearLayout.LayoutParams(0, ch + Blocks.shade(a), 1));
        return row;
    }

    /** 还没有在读的书：同样的框，封面位置空着。点了去“文件”。 */
    private View emptyRow() {
        View empty = new View(a);
        empty.setBackgroundColor(Color.WHITE);
        LinearLayout row = continueRow(empty, "还没有在读的书", "去“文件”里挑一本", null, null, 0, "—");
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { files.showSeg(0); show(FILES); a.changed(); } });
        Blocks.fast(row);
        return row;
    }

    private View alsoRow(String s, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0, dp(8), 0, 0);
        r.addView(Blocks.hair(a));
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, 0);
        line.addView(BlocksCards.line(a, s, 14, false), Blocks.weight(1));
        line.addView(new Icons.IconView(a, Icons.Kind.CHEV_R), Blocks.lp(dp(16), dp(16)));
        r.addView(line);
        r.setClickable(true);
        r.setOnClickListener(l);
        Blocks.fast(r);
        return r;
    }

    private int coverW() { return Math.min(dp(130), a.getResources().getDisplayMetrics().widthPixels / 4); }

    private View bookRow(final Lib.Book b) {
        Bitmap bm = Lib.cover(a, b);
        if (bm == null && Lib.coverMissing(a, b)) { List<Lib.Book> one = new ArrayList<Lib.Book>(); one.add(b); Lib.generate(a, one); }
        String ch = Store.prefs(a, "reader").getString("ch:" + b.path, "");
        LinearLayout row = continueRow(new CoverView(a, b, bm, false), b.title, "读到 " + b.percent + "%",
            ch.length() > 0 ? "【" + ch + "】" : null, b.ext() + " · " + BlocksCards.size(b.size) + " · " + BlocksCards.ago(b.lastRead), b.percent, BlocksCards.ago(b.lastRead));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openBook(b); } });
        Blocks.fast(row);
        row.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { a.bookMenu(b); return true; } });
        return row;
    }

    private View appRow(final ResolveInfo app, String pkg, long when) {
        Bitmap th = BlocksCards.lastAppThumb(a, pkg);
        View left;
        if (th != null) {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(th);
            left = iv;
        } else {
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(a.iconFor(app));
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setBackgroundColor(Color.WHITE);
            left = iv;
        }
        LinearLayout row = continueRow(left, a.labelFor(app), "上次使用 " + BlocksCards.ago(when), "第三方应用 · 左边是离开时的画面", "点按回到这个应用", 0, BlocksCards.ago(when));
        // 第三方应用不知道读到哪：进度条不要
        LinearLayout col = (LinearLayout) row.getChildAt(1);
        col.getChildAt(col.getChildCount() - 1).setVisibility(View.GONE);
        col.getChildAt(col.getChildCount() - 2).setVisibility(View.INVISIBLE);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openApp(app); } });
        Blocks.fast(row);
        return row;
    }

    /** 继续阅读：书的文件不在了（删了 / 挪了 / 存储没挂上）就说一声并重新扫书库，不再什么都不做。 */
    private void openBook(Lib.Book b) {
        if (b.path == null || !new java.io.File(b.path).isFile()) {
            VeraToast.show(a, "找不到《" + b.title + "》的文件了，可能已被删除或移动。正在重新整理书库。");
            Lib.scan(a, 0);
            return;
        }
        try { Lib.open(a, b); } catch (Throwable t) { VeraToast.show(a, "打不开这本书：" + t.getMessage()); }
    }

    /** 继续阅读：应用被卸载 / 停用了就提示，不再静默失败。 */
    private void openApp(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try {
            android.content.pm.ApplicationInfo ai = a.getPackageManager().getApplicationInfo(pkg, 0);
            if (!ai.enabled) { VeraToast.show(a, a.labelFor(app) + " 已被停用，请到“应用”里启用后再打开。"); return; }
        } catch (Throwable t) { VeraToast.show(a, "这个应用已经卸载了。"); prefs.edit().remove("la_pkg").apply(); show(HOME); a.changed(); return; }
        a.launch(app);
    }

    private View statsCard() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        View.OnClickListener open = new View.OnClickListener() { @Override public void onClick(View v) {
            a.startActivity(new Intent(a, StatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); a.overridePendingTransition(0, 0); } };
        LinearLayout t1 = Blocks.tile(a, "This week", "本周阅读时长", Stats.shortDuration(Stats.weekMs(a)));
        LinearLayout t2 = Blocks.tile(a, "Bookmarks", "书签与摘录", String.valueOf(Stats.bookmarkCount(a)));
        for (LinearLayout t : new LinearLayout[] { t1, t2 }) { t.setClickable(true); t.setOnClickListener(open); Blocks.fast(t); }
        LinearLayout.LayoutParams p1 = Blocks.weight(1); p1.rightMargin = dp(5);
        LinearLayout.LayoutParams p2 = Blocks.weight(1); p2.leftMargin = dp(5);
        r.addView(t1, p1);
        r.addView(t2, p2);
        return r;
    }

    private View recentCard() {
        if (!a.libOn()) return null;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.BOTTOM);
        head.addView(Blocks.titled(a, "Recent", "最近阅读", 17, true), Blocks.weight(1));
        // 系统字体里没有 › 这类符号：箭头用画的
        LinearLayout all = new LinearLayout(a);
        all.setGravity(Gravity.CENTER_VERTICAL);
        all.addView(Ui.text(a, "全部", 15, false));
        all.addView(new Icons.IconView(a, Icons.Kind.CHEV_R), Blocks.lp(dp(14), dp(14)));
        all.setPadding(dp(12), dp(6), 0, dp(2));
        all.setClickable(true);
        all.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { backTo = HOME; go(S_LIBRARY); } });
        Blocks.fast(all);
        head.addView(all);
        box.addView(head);
        // 刚读过的书排第一（就算“继续阅读”卡片里也是它）：以前这里把继续阅读那本拿掉、又按“上次阅读和放进来的时间哪个新”排，
        // 读完一本回来列表看着没变，新传进来 / 被改写过的文件（比如 Kindle 书库里的状态文件）反而排在前面
        List<Lib.Book> list = Lib.recentlyRead(3);
        if (list.isEmpty()) {
            box.addView(Blocks.empty(a, Icons.Kind.BOOK, "书库里还没有书", "用传书网页传进来，或者放进 Books 文件夹", false), Blocks.match());
            return box;
        }
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, 0);
        int colW = (a.getResources().getDisplayMetrics().widthPixels - dp(32) - dp(24)) / 3;
        int ch = Math.min(colW * 4 / 3, a.getResources().getDisplayMetrics().heightPixels / 4);   // 横屏 / 小屏：封面别把一页占满
        List<Lib.Book> missing = new ArrayList<Lib.Book>();
        for (int i = 0; i < 3; i++) {
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) cp.leftMargin = dp(12);
            if (i < list.size()) {
                final Lib.Book b = list.get(i);
                Bitmap bm = Lib.cover(a, b);
                if (bm == null && Lib.coverMissing(a, b)) missing.add(b);
                FrameLayout cf = new FrameLayout(a);
                cf.setBackground(Blocks.frame(a));
                int st = Blocks.stroke(a), sh = Blocks.shade(a);
                cf.setPadding(st, st, st + sh, st + sh);
                cf.addView(new CoverView(a, b, bm, false), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                col.addView(cf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ch));
                TextView t = BlocksCards.line(a, b.title, 14, true);
                t.setPadding(0, dp(4), 0, 0);
                col.addView(t);
                LinearLayout meta = new LinearLayout(a);
                meta.addView(Ui.text(a, b.percent >= 100 ? "已读完" : b.percent + "%", 12, false), Blocks.weight(1));
                meta.addView(Ui.text(a, b.lastRead > 1 ? BlocksCards.ago(b.lastRead) : "未读", 12, false));
                col.addView(meta);
                col.setClickable(true);
                col.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Lib.open(a, b); } });
                col.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { a.bookMenu(b); return true; } });
            }
            row.addView(col, cp);
        }
        if (!missing.isEmpty()) Lib.generate(a, missing);
        box.addView(row);
        return box;
    }

    private View clockCard() {
        LinearLayout card = Blocks.card(a);
        card.addView(Blocks.cap(a, "Clock"));
        TextView t = Ui.text(a, Ui.clock(a), 52, true);
        t.setGravity(Gravity.CENTER);
        clocks.add(t);
        card.addView(t, Blocks.match());
        TextView d = Ui.text(a, BlocksCards.dateLine(), 15, false);
        d.setGravity(Gravity.CENTER);
        card.addView(d, Blocks.match());
        return card;
    }

    private View calendarCard() {
        LinearLayout card = Blocks.card(a);
        Calendar now = Calendar.getInstance();
        int y = now.get(Calendar.YEAR), m = now.get(Calendar.MONTH) + 1, today = now.get(Calendar.DAY_OF_MONTH);
        String[] lun = Lunar.lunar(y, m, today);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.BOTTOM);
        head.addView(Blocks.titled(a, "Calendar", y + "年" + m + "月", 18, true), Blocks.weight(1));
        head.addView(Ui.text(a, (lun != null ? "农历" + lun[0] + lun[1] : "") + "  星期" + BlocksCards.weekday(now), 14, false));
        card.addView(head);
        Calendar first = (Calendar) now.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int lead = first.get(Calendar.DAY_OF_WEEK) - 1;   // 周日在最左
        int days = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        String[] labels = BlocksCards.monthLabels(y, m, days);
        LinearLayout wk = new LinearLayout(a);
        wk.setPadding(0, dp(6), 0, dp(2));
        for (String s : new String[] { "日", "一", "二", "三", "四", "五", "六" }) {
            TextView t = Ui.text(a, s, 13, true);
            t.setGravity(Gravity.CENTER);
            wk.addView(t, Blocks.weight(1));
        }
        card.addView(wk);
        card.addView(Blocks.hair(a));
        int cells = ((lead + days + 6) / 7) * 7;
        LinearLayout row = null;
        for (int i = 0; i < cells; i++) {
            if (i % 7 == 0) { row = new LinearLayout(a); card.addView(row); }
            int d = i - lead + 1;
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(0, dp(2), 0, dp(2));
            if (d >= 1 && d <= days) {
                boolean on = d == today;
                TextView n = Ui.text(a, String.valueOf(d), 15, on);
                TextView l = Ui.text(a, labels[d], 9, false);
                l.setSingleLine(true);
                n.setGravity(Gravity.CENTER);   // 竖排 LinearLayout 的子控件默认占满宽度：字要自己居中
                l.setGravity(Gravity.CENTER);
                if (on) { cell.setBackgroundColor(Color.BLACK); n.setTextColor(Color.WHITE); l.setTextColor(Color.WHITE); }
                cell.addView(n);
                cell.addView(l);
            }
            row.addView(cell, new LinearLayout.LayoutParams(0, dp(42), 1));
        }
        return card;
    }

    private View weatherCard() {
        LinearLayout card = Blocks.card(a);
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String city = prefs.getString("wx_city", "");
        head.addView(Blocks.titled(a, "Weather", city.length() > 0 ? "天气 · " + city : "天气", 17, true), Blocks.weight(1));
        TextView set = Blocks.button(a, "城市", false, new View.OnClickListener() { @Override public void onClick(View v) {
            BlocksCards.cityDialog(a, new Runnable() { @Override public void run() { BlocksCards.maybeFetchWeather(a, new BlocksCards.Changed() { @Override public void changed() { show(HOME); a.changed(); } }); show(HOME); a.changed(); } });
        } });
        set.setMinHeight(dp(36));
        set.setPadding(dp(10), dp(4), dp(10), dp(4));
        head.addView(set);
        card.addView(head);
        String json = prefs.getString("wx_norm", null);
        if (city.length() == 0) { card.addView(Blocks.empty(a, Icons.Kind.CLOUD, "还没设城市", "点右上角“城市”。一小时最多取一次，只在 Wi-Fi 开着时取", false), Blocks.match()); return card; }
        if (json == null) { card.addView(Blocks.empty(a, Icons.Kind.CLOUD, "正在取天气", "Wi-Fi 开着、回到首页时去取；取不到不提示", true), Blocks.match()); return card; }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            LinearLayout now = new LinearLayout(a);
            now.setGravity(Gravity.CENTER_VERTICAL);
            now.setPadding(0, dp(4), 0, dp(4));
            now.addView(Ui.text(a, Math.round(o.getDouble("t")) + "°", 40, true));
            LinearLayout desc = new LinearLayout(a);
            desc.setOrientation(LinearLayout.VERTICAL);
            desc.setPadding(dp(14), 0, 0, 0);
            desc.addView(BlocksCards.line(a, o.optString("desc", "—"), 18, true));
            desc.addView(Ui.text(a, "湿度 " + o.optInt("hum", 0) + "%", 13, false));
            now.addView(desc, Blocks.weight(1));
            card.addView(now);
            org.json.JSONArray dd = o.getJSONArray("days");
            LinearLayout days = new LinearLayout(a);
            String[] names = { "今天", "明天", "后天" };
            for (int i = 0; i < Math.min(3, dd.length()); i++) {
                org.json.JSONObject d = dd.getJSONObject(i);
                TextView t = Ui.text(a, names[i] + "\n" + d.optString("desc", "—") + "\n" + Math.round(d.getDouble("lo")) + "° / " + Math.round(d.getDouble("hi")) + "°", 13, false);
                t.setGravity(Gravity.CENTER);
                t.setMaxLines(3);
                t.setBackground(Blocks.plain(a));
                t.setPadding(dp(2), dp(4), dp(2), dp(4));
                LinearLayout.LayoutParams lp = Blocks.weight(1);
                if (i > 0) lp.leftMargin = dp(6);
                days.addView(t, lp);
            }
            card.addView(days);
            long at = prefs.getLong("wx_at", 0);
            boolean fresh = System.currentTimeMillis() - at < 3 * 3600000L && !"0".equals(HostLink.kv(HostLink.CTL + "status").get("wifi"));
            String when = new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(new java.util.Date(at));
            TextView foot = Ui.text(a, (fresh ? "更新于 " : "离线 · 上次更新 ") + when + "  ·  " + BlocksCards.source(o.optString("src")).attribution(), 11, false);
            foot.setPadding(0, dp(4), 0, 0);
            card.addView(foot);
        } catch (Throwable t) { card.addView(Ui.text(a, "天气数据读不出来，稍后再取。", 14, false)); }
        return card;
    }

    private View poemCard() {
        String[] p = BlocksCards.poemOfDay(a);
        if (p == null) return null;
        LinearLayout card = Blocks.card(a);
        card.addView(Blocks.cap(a, "Daily poem"));
        TextView t = Ui.text(a, p[2] + "\n" + p[3], 17, false);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.3f);
        t.setPadding(0, dp(4), 0, dp(4));
        card.addView(t, Blocks.match());
        TextView w = Ui.text(a, "—— " + p[1] + "《" + p[0] + "》", 13, false);
        w.setGravity(Gravity.RIGHT);
        card.addView(w, Blocks.match());
        return card;
    }

    private View quickAppsCard() {
        LinearLayout card = Blocks.card(a);
        card.addView(Blocks.titled(a, "Quick apps", "快捷应用", 17, true));
        LinearLayout row = new LinearLayout(a);
        row.setPadding(0, dp(6), 0, 0);
        int n = 0;
        for (String pkg : prefs.getString("blk_quick", "").split(",")) {
            final ResolveInfo ri = pkg.length() == 0 ? null : a.findApp(pkg);
            if (ri == null) continue;
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(a.iconFor(ri));
            c.addView(iv, Blocks.lp(dp(52), dp(52)));
            TextView t = BlocksCards.line(a, a.labelFor(ri), 13, false);
            t.setGravity(Gravity.CENTER);
            c.addView(t, Blocks.match());
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { a.launch(ri); } });
            Blocks.fast(c);
            row.addView(c, Blocks.weight(1));
            if (++n == 5) break;
        }
        if (n == 0) { card.addView(Blocks.empty(a, Icons.Kind.GRID, "还没挑应用", "我的／首页卡片／快捷应用", false), Blocks.match()); return card; }
        for (; n < 5; n++) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        card.addView(row);
        return card;
    }

    private View widgetCard(int id) {
        LinearLayout card = Blocks.card(a);
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        View w = BlocksCards.widgetView(a, id);
        if (w == null) { card.addView(Ui.text(a, "这个小部件已经不在了（应用被卸载？）。在“我的／首页卡片”里移除。", 14, false)); return card; }
        card.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BlocksCards.widgetHeightDp(a, id))));
        return card;
    }

    // ------------------------------------------------------------------ 全部书（首页“最近阅读”的“全部”）

    private int libSort, libPage, libPer;
    private LinearLayout libGrid;
    private Blocks.Pager libPager;

    private View buildLibrary() {
        LinearLayout pg = new LinearLayout(a);
        pg.setOrientation(LinearLayout.VERTICAL);
        pg.addView(Blocks.pageHead(a, "Library / " + Lib.all().size() + " books", "全部书", Blocks.squareButton(a, Icons.Kind.BACK, new View.OnClickListener() {
            @Override public void onClick(View v) { back(); } })));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), 0, dp(16), dp(6));
        box.addView(new Blocks.SegBar(a, new String[] { "最近", "书名", "作者", "最近添加" }, libSort, new Blocks.SegBar.Listener() {
            @Override public void onPick(int i) { libSort = i; libPage = 0; renderLibrary(); a.changed(); } }), Blocks.match());
        PagedBox grid = new PagedBox(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setListener(new PagedBox.Listener() { @Override public void onPage(int dir) { turnLibrary(dir); } });
        libGrid = grid;
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        gp.topMargin = dp(10);
        box.addView(grid, gp);
        libPager = new Blocks.Pager(a, new Blocks.Pager.Listener() { @Override public void onPage(int dir) { turnLibrary(dir); } });
        box.addView(libPager, Blocks.gap(a, 8));
        pg.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        libPer = 0;
        grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int rows = Math.max(1, (b - t) / libCellH());
                int np = rows * 3;
                if (np != libPer && b - t > 0) { libPer = np; v.post(new Runnable() { @Override public void run() { renderLibrary(); } }); }
            }
        });
        return pg;
    }

    private int libCellH() {
        int w = (a.getResources().getDisplayMetrics().widthPixels - dp(32) - dp(24)) / 3;
        // 一页至少两排：封面高度不超过（屏幕高 - 顶栏、分段、翻页条）的一半
        int avail = a.getResources().getDisplayMetrics().heightPixels - dp(300);
        return Math.min(w * 4 / 3 + dp(44) + Blocks.shade(a), Math.max(dp(160), avail / 2));
    }

    private void turnLibrary(int dir) {
        int before = libPage;
        if (libPage + dir >= 0) Blocks.swipe(a, dir > 0 ? Blocks.DIR_UP : Blocks.DIR_DOWN, Blocks.KIND_LIST);
        libPage = Math.max(0, libPage + dir);
        renderLibrary();
        if (before != libPage) a.changed();
    }

    private void renderLibrary() {
        if (libGrid == null || libPer <= 0) return;
        libGrid.removeAllViews();
        List<Lib.Book> all = Lib.sorted(libSort, Lib.FILTER_ALL, null);
        int pages = Math.max(1, (all.size() + libPer - 1) / libPer);
        if (libPage >= pages) libPage = pages - 1;
        List<Lib.Book> missing = new ArrayList<Lib.Book>();
        LinearLayout row = null;
        int from = libPage * libPer, to = Math.min(all.size(), from + libPer);
        for (int i = from; i < to; i++) {
            if ((i - from) % 3 == 0) { row = new LinearLayout(a); libGrid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, libCellH())); }
            final Lib.Book b = all.get(i);
            Bitmap bm = Lib.cover(a, b);
            if (bm == null && Lib.coverMissing(a, b)) missing.add(b);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            FrameLayout cf = new FrameLayout(a);
            cf.setBackground(Blocks.frame(a));
            int st = Blocks.stroke(a), sh = Blocks.shade(a);
            cf.setPadding(st, st, st + sh, st + sh);
            cf.addView(new CoverView(a, b, bm, true), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            col.addView(cf, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            TextView t = BlocksCards.line(a, b.title, 14, true);
            t.setPadding(0, dp(4), 0, 0);
            col.addView(t);
            col.addView(BlocksCards.line(a, (b.author == null || b.author.length() == 0 ? b.ext() : b.author), 12, false));
            col.setClickable(true);
            col.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Lib.open(a, b); } });
            col.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { a.bookMenu(b); return true; } });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            cp.setMargins((i - from) % 3 == 0 ? 0 : dp(6), dp(4), (i - from) % 3 == 2 ? 0 : dp(6), dp(8));
            row.addView(col, cp);
        }
        if (row != null) for (int k = (to - from) % 3; k != 0 && k < 3; k++) row.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        if (all.isEmpty()) libGrid.addView(Blocks.empty(a, Icons.Kind.BOOK, "书库里还没有书", "用传书网页传进来，或者放进 Books 文件夹", false), Blocks.match());
        if (!missing.isEmpty()) Lib.generate(a, missing);
        libPager.set(libPage + 1, pages);
    }

    // ------------------------------------------------------------------ 应用

    private int appPage;
    private LinearLayout appGrid;
    private Blocks.Pager appPager;
    private int appPerPage;

    private View buildApps() {
        appsHeadN = a.appList().size();
        appsVerBuilt = a.appsVersion();
        LinearLayout pg = new LinearLayout(a);
        pg.setOrientation(LinearLayout.VERTICAL);
        pg.addView(Blocks.pageHead(a, "Apps / " + a.appList().size() + " installed", "应用", Blocks.squareButton(a, Icons.Kind.MORE, new View.OnClickListener() {
            @Override public void onClick(View v) { a.mainMenu(); }
        })));
        // 一行工具：排序（点一下换下一种）、显示隐藏的应用
        LinearLayout tools = new LinearLayout(a);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(16), 0, dp(16), dp(4));
        final String[] sorts = { "最近使用", "最久未用", "名称", "包名", "最近安装" };
        TextView sortBtn = Blocks.button(a, "排序：" + sorts[Math.max(0, Math.min(4, prefs.getInt("sort", 0)))], false, new View.OnClickListener() {
            @Override public void onClick(View v) { a.setAppSort((prefs.getInt("sort", 0) + 1) % 5); } });
        tools.addView(sortBtn);
        tools.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        int nh = hidden().size();
        if (nh > 0) tools.addView(Blocks.button(a, showHidden ? "藏起隐藏的应用" : "显示隐藏的（" + nh + "）", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showHidden = !showHidden; invalidate(APPS); show(APPS); a.changed(); } }));
        pg.addView(tools);
        PagedBox grid = new PagedBox(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(16), dp(2), dp(16), dp(4));
        grid.setListener(new PagedBox.Listener() { @Override public void onPage(int dir) { turnApps(dir); } });
        appGrid = grid;
        pg.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        appPager = new Blocks.Pager(a, new Blocks.Pager.Listener() { @Override public void onPage(int dir) { turnApps(dir); } });
        LinearLayout.LayoutParams pp = Blocks.match();
        pp.leftMargin = dp(16); pp.rightMargin = dp(16); pp.bottomMargin = dp(6);
        pg.addView(appPager, pp);
        appPerPage = 0;
        grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int rows = Math.max(1, (b - t - v.getPaddingTop() - v.getPaddingBottom()) / appCellH());
                int np = rows * appCols();
                if (np != appPerPage && b - t > 0) { appPerPage = np; v.post(new Runnable() { @Override public void run() { renderApps(); } }); }
            }
        });
        return pg;
    }

    private int appCols() { return Math.max(2, Math.min(5, prefs.getInt("cols", 3))); }
    private int appCellH() { return dp(150); }

    private void turnApps(int dir) {
        int before = appPage;
        if (appPage + dir >= 0) Blocks.swipe(a, dir > 0 ? Blocks.DIR_UP : Blocks.DIR_DOWN, Blocks.KIND_LIST);
        appPage = Math.max(0, appPage + dir);
        renderApps();
        if (before != appPage) a.changed();
    }

    private void renderApps() {
        if (appGrid == null || appPerPage <= 0) return;
        appGrid.removeAllViews();
        List<ResolveInfo> apps = visibleApps();
        int total = apps.size() + 1;   // 软件商店固定在第一格
        int pages = Math.max(1, (total + appPerPage - 1) / appPerPage);
        if (appPage >= pages) appPage = pages - 1;
        int c = appCols(), from = appPage * appPerPage, to = Math.min(total, from + appPerPage);
        LinearLayout row = null;
        for (int i = from; i < to; i++) {
            if ((i - from) % c == 0) {
                row = new LinearLayout(a);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, appCellH());
                appGrid.addView(row, rp);
            }
            View cell = i == 0 ? appTile(null) : appTile(apps.get(i - 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(cell, lp);
        }
        if (row != null) for (int k = (to - from) % c; k != 0 && k < c; k++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            row.addView(new View(a), lp);
        }
        appPager.set(appPage + 1, pages);
        appPager.setVisibility(pages > 1 ? View.VISIBLE : View.INVISIBLE);
    }

    private View appTile(final ResolveInfo ri) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackground(Blocks.frame(a));
        c.setPadding(dp(4), dp(8), dp(4) + Blocks.shade(a), dp(6) + Blocks.shade(a));
        if (ri == null) c.addView(new Icons.IconView(a, Icons.Kind.STORE), Blocks.lp(dp(64), dp(64)));
        else {
            ImageView iv = new ImageView(a);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.iconFor(ri));
            c.addView(iv, Blocks.lp(dp(64), dp(64)));
        }
        TextView t = Ui.text(a, ri == null ? "软件商店" : a.labelFor(ri), 15, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(6), 0, 0);
        c.addView(t, Blocks.match());
        c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (ri == null) { a.startActivity(new Intent(a, StoreActivity.class)); a.overridePendingTransition(0, 0); }
            else a.launch(ri);
        } });
        if (ri != null) c.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { appMenu(ri); return true; } });
        if (ri != null && (pins().contains(ri.activityInfo.packageName) || hidden().contains(ri.activityInfo.packageName))) {
            TextView tag = Ui.text(a, hidden().contains(ri.activityInfo.packageName) ? "已隐藏" : "置顶", 10, true);
            tag.setTextColor(Color.WHITE);
            tag.setBackgroundColor(Color.BLACK);
            tag.setPadding(dp(4), 0, dp(4), 0);
            c.addView(tag, 0);
        }
        return c;
    }

    private boolean showHidden;
    private List<String> list(String key) { List<String> l = new ArrayList<String>(); for (String x : prefs.getString(key, "").split(",")) if (x.length() > 0) l.add(x); return l; }
    List<String> pins() { return list("blk_pins"); }
    List<String> hidden() { return list("blk_hidden"); }

    /** 置顶的在前（按置顶的先后），隐藏的不显示（除非点了“显示隐藏的”），其余按应用排序设置。 */
    private List<ResolveInfo> visibleApps() {
        List<String> pin = pins(), hid = hidden();
        List<ResolveInfo> top = new ArrayList<ResolveInfo>(), rest = new ArrayList<ResolveInfo>();
        for (String p : pin) { ResolveInfo ri = a.findApp(p); if (ri != null && (showHidden || !hid.contains(p))) top.add(ri); }
        for (ResolveInfo ri : a.appList()) { String p = ri.activityInfo.packageName; if (!pin.contains(p) && (showHidden || !hid.contains(p))) rest.add(ri); }
        top.addAll(rest);
        return top;
    }

    private void toggleIn(String key, String pkg) {
        List<String> l = list(key);
        if (!l.remove(pkg)) l.add(pkg);
        prefs.edit().putString(key, TextUtils.join(",", l)).apply();
        invalidate(APPS); show(APPS); a.changed();
    }

    private void appMenu(final ResolveInfo ri) {
        final String pkg = ri.activityInfo.packageName;
        new VeraDialog.Builder(a).title(a.labelFor(ri)).items(new String[] { "打开", pins().contains(pkg) ? "取消置顶" : "置顶", hidden().contains(pkg) ? "取消隐藏" : "隐藏", "更多（图标、名字、应用优化、保持后台、卸载…）" },
            new VeraDialog.OnPick() { @Override public void onPick(int w) {
                switch (w) {
                case 0: a.launch(ri); break;
                case 1: toggleIn("blk_pins", pkg); break;
                case 2: toggleIn("blk_hidden", pkg); break;
                default: a.appMenu(ri); break;
                }
            } }).negative("取消", null).show();
    }

    /**
     * 应用列表变了（装卸、改名、换图标）。正显示着“应用”页、标题里的个数也没变：只重排格子（不整页重搭，也不排两遍）。
     * 手指按着的时候不动控件树（抬手再做，见 HomeActivity.touching）。
     */
    private final Runnable appsChangedLater = new Runnable() { @Override public void run() { appsChanged(); } };
    void appsChanged() {
        if (a.touching()) { a.afterTouch(appsChangedLater); return; }
        if (pages.get(APPS) != null && appPerPage > 0 && cur == APPS && appsHeadN == a.appList().size()) {
            String old = sigs.get(APPS), caps = Blocks.caps(a) + "/" + Fonts.weight(a);
            // 只有应用列表的版本不一样（排序、列数、置顶 / 隐藏都没变）
            if (old != null && old.equals(appsSig(caps, appsVerBuilt)) && appsVerBuilt != a.appsVersion()) {
                renderApps();
                appsVerBuilt = a.appsVersion();
                sigs.put(APPS, sig(APPS));
            }
        }
        dataChanged();
    }
    private int appsHeadN = -1;
    private long appsVerBuilt = -1;

    // ------------------------------------------------------------------ 我的

    private TextView meBattery;

    private View buildMe() {
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(8));
        LinearLayout pg = new LinearLayout(a);
        pg.setOrientation(LinearLayout.VERTICAL);
        pg.addView(Blocks.pageHead(a, a.standalone ? "Settings" : "Device / local reader", a.standalone ? "设置" : "我的", Blocks.squareButton(a, a.standalone ? Icons.Kind.CLOSE : Icons.Kind.MORE, new View.OnClickListener() {
            @Override public void onClick(View v) { if (a.standalone) a.finish(); else a.mainMenu(); }
        })));
        // 设备卡片：上面是机器，下面一排三格（电量 / 存储 / 版本），同一个双线框里，格子之间竖线隔开
        LinearLayout devBox = new LinearLayout(a);
        devBox.setOrientation(LinearLayout.VERTICAL);
        devBox.setBackground(Blocks.frameDouble(a));
        int ip = dp(3) + Blocks.stroke(a);
        devBox.setPadding(ip, ip, ip + Blocks.shade(a), ip + Blocks.shade(a));
        LinearLayout dev = new LinearLayout(a);
        dev.setGravity(Gravity.CENTER_VERTICAL);
        dev.setPadding(dp(10), dp(6), dp(10), dp(6));
        FrameLayout ib = new FrameLayout(a);
        ib.setBackground(Blocks.plain(a));
        ib.addView(new Icons.IconView(a, Icons.Kind.DEVICE), new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        dev.addView(ib, Blocks.lp(dp(52), dp(52)));
        LinearLayout dc = new LinearLayout(a);
        dc.setOrientation(LinearLayout.VERTICAL);
        dc.setPadding(dp(12), 0, 0, 0);
        dc.addView(BlocksCards.line(a, prefs.getString("name", "VeraDroid").length() > 0 ? prefs.getString("name", "VeraDroid") : "VeraDroid", 18, true));
        dc.addView(BlocksCards.line(a, model() + " · Android " + Build.VERSION.RELEASE + " · VeraHome " + version(), 13, false));
        dev.addView(dc, Blocks.weight(1));
        devBox.addView(dev);
        devBox.addView(Blocks.hair(a));
        LinearLayout r1 = new LinearLayout(a);
        Map<String, String> st = HostLink.status(a);
        String[][] cells = { { (st.get("battery") == null ? "—" : st.get("battery")) + "%", "剩余电量", "Battery" },
            { storageUsed() + "%", "存储已用", "Storage" }, { version(), "桌面版本", "Version" } };
        for (int i = 0; i < 3; i++) {
            if (i > 0) r1.addView(Blocks.vrule(a), new LinearLayout.LayoutParams(Math.max(1, Blocks.stroke(a) - 1), ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout cl = new LinearLayout(a);
            cl.setOrientation(LinearLayout.VERTICAL);
            cl.setPadding(dp(10), dp(5), dp(6), dp(5));
            TextView v = BlocksCards.line(a, cells[i][0], 17, true);
            if (i == 0) meBattery = v;
            cl.addView(v);
            cl.addView(BlocksCards.line(a, cells[i][1], 12, false));
            r1.addView(cl, Blocks.weight(1));
        }
        devBox.addView(r1);
        content.addView(devBox, Blocks.gap(a, 2));
        // 后台在做的事：系统更新 / 安装应用 / 优化应用（方块进度小卡片，没有就不显示）
        content.addView(TaskCards.strip(a), Blocks.match());
        content.addView(quickRow(st), Blocks.gap(a, 8));
        // 阅读统计
        int done = 0;
        if (a.libOn()) for (Lib.Book b : Lib.all()) if (b.percent >= 100) done++;
        LinearLayout r2 = new LinearLayout(a);
        r2.addView(Blocks.stat(a, Stats.shortDuration(Stats.monthMs(a)), "本月阅读", "This month"), cell(0));
        r2.addView(Blocks.stat(a, String.valueOf(done), "已读完", "Finished"), cell(1));
        r2.addView(Blocks.stat(a, String.valueOf(Stats.bookmarkCount(a)), "书签", "Bookmarks"), cell(2));
        for (int i = 0; i < r2.getChildCount(); i++) {
            View v = r2.getChildAt(i);
            v.setClickable(true);
            v.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { a.startActivity(new Intent(a, StatsActivity.class)); a.overridePendingTransition(0, 0); } });
            Blocks.fast(v);
        }
        content.addView(r2, Blocks.gap(a, 8));
        // 使用模式切换（紧凑单行：左说明 + 右一键切换，保证 5 个设置分类一屏全看完无需翻页）
        final boolean expert = UiMode.isExpert(a);
        LinearLayout modeBox = new LinearLayout(a);
        modeBox.setOrientation(LinearLayout.HORIZONTAL);
        modeBox.setGravity(Gravity.CENTER_VERTICAL);
        modeBox.setBackground(Blocks.frame(a));
        modeBox.setPadding(dp(12), dp(8), dp(10) + Blocks.shade(a), dp(8) + Blocks.shade(a));
        LinearLayout modeL = new LinearLayout(a);
        modeL.setOrientation(LinearLayout.VERTICAL);
        modeL.addView(Ui.text(a, "使用模式", 16, true));
        TextView mDesc = Ui.text(a, expert ? "资深模式 · 全部 DIY 开放" : "小白模式 · 省心防残影", 12, false);
        modeL.addView(mDesc);
        modeBox.addView(modeL, Blocks.weight(1));
        Blocks.SegBar modeSeg = new Blocks.SegBar(a, new String[] { "小白", "资深" }, expert ? 1 : 0, new Blocks.SegBar.Listener() {
            @Override public void onPick(int i) {
                boolean toExpert = i == 1;
                UiMode.setExpert(a, toExpert, !toExpert);
                a.onUiModeChanged();
                UiMode.guide(a, toExpert ? "已切到资深模式：全部高级自定义与单应用微调已开放" : "已切到小白模式：已套用清爽均衡画质与防残影默认设置");
            }
        });
        modeBox.addView(modeSeg, Blocks.lp(dp(176), ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(modeBox, Blocks.gap(a, 8));

        // 设置分类（按当前模式清晰分层，绝无重复项、绝不套娃，一屏全展示）
        LinearLayout sh = new LinearLayout(a);
        sh.setGravity(Gravity.BOTTOM);
        sh.addView(Ui.text(a, expert ? "系统设置（资深）" : "常用设置（小白）", 15, true), Blocks.weight(1));
        sh.addView(Blocks.cap(a, expert ? "Expert settings" : "Simple settings"));
        content.addView(sh, Blocks.gap(a, 8));
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Blocks.frameDouble(a));
        int lp4 = dp(3) + Blocks.stroke(a);
        list.setPadding(lp4, lp4, lp4 + Blocks.shade(a), lp4 + Blocks.shade(a));
        String[][] rows = expert
            ? new String[][] {
                { "显示与刷新", "亮度 · 刷新模式/自定义波形 · 旋转 · 60Hz · 单应用优化" },
                { "网络与传书", "Wi-Fi · 蓝牙 · DNS · 网页传书 · 无线调试 · 存储空间" },
                { "电源与交互", "自动休眠 · 回 Kindle · 后台数 · 输入法 · 手势 · 时间" },
                { "桌面与外观", "首页卡片 · 快捷应用 · 界面风格 · 面板 · 字重 · 壁纸" },
                { "系统与维护", "设备信息 · 性能与内存 · 更新 · 统计 · 反馈 · 恢复" } }
            : new String[][] {
                { "阅读与显示", "亮度 · 一键阅读场景（高清/均衡/极速）· 字号 · 应用优化" },
                { "网络与传书", "Wi-Fi 联网 · 蓝牙翻页器 · 手机网页传书 · 存储空间" },
                { "系统与更新", "自动休眠 · 回 Kindle · 输入法切换 · 检查更新" } };
        final int[] keys = expert
            ? new int[] { S_DISPLAY, S_STORAGE, S_POWER, S_CARDS, S_ABOUT }
            : new int[] { S_DISPLAY, S_STORAGE, S_POWER };
        for (int i = 0; i < rows.length; i++) {
            final int k = keys[i];
            if (i > 0) list.addView(Blocks.hair(a));
            list.addView(Blocks.navRow(a, rows[i][0], rows[i][1], new View.OnClickListener() { @Override public void onClick(View v) { backTo = ME; go(k); } }));
        }
        content.addView(list, Blocks.gap(a, 4));
        pg.addView(paged(content), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return pg;
    }

    /** 我的页的快捷设置：Wi-Fi 开关、亮度（下拉面板）、整屏刷新、动画档位。每格点一下只改它自己那一格的字。 */
    private View quickRow(Map<String, String> st) {
        LinearLayout r = new LinearLayout(a);
        final boolean[] wifi = { !"0".equals(st.get("wifi")) };
        final String[][] t = { { "Wi-Fi", wifi[0] ? "开" : "关" }, { "亮度", "面板" }, { "整屏刷新", "清残影" }, { "动画", Blocks.ANIM_L[Blocks.anim(a)] } };
        final Icons.Kind[] k = { Icons.Kind.WIFI, Icons.Kind.SUN_L, Icons.Kind.REFRESH, Icons.Kind.SWIPE };
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setBackground(Blocks.frame(a));
            c.setPadding(dp(4), dp(8), dp(4) + Blocks.shade(a), dp(6) + Blocks.shade(a));
            c.addView(new Icons.IconView(a, k[i]), Blocks.lp(dp(26), dp(26)));
            TextView name = Ui.text(a, t[i][0], 13, true);
            name.setGravity(Gravity.CENTER);
            c.addView(name, Blocks.match());
            final TextView val = Ui.text(a, t[i][1], 12, false);
            val.setGravity(Gravity.CENTER);
            c.addView(val, Blocks.match());
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                switch (idx) {
                case 0: wifi[0] = !wifi[0]; HostLink.cmd("wifi " + (wifi[0] ? 1 : 0)); HostLink.cmd("status"); val.setText(wifi[0] ? "开" : "关"); UiMode.guide(a, wifi[0] ? "已开启 Wi-Fi" : "已关闭 Wi-Fi（更省电）"); break;
                case 1: Panel.open(a); break;
                case 2: HostLink.cmd("clear"); UiMode.guide(a, "已执行整屏刷新，残影已清除"); break;
                default: int n = (Blocks.anim(a) + 1) % 3; prefs.edit().putInt(Blocks.ANIM_KEY, n).apply(); val.setText(Blocks.ANIM_L[n]); UiMode.guide(a, "桌面翻页动画已切到「" + Blocks.ANIM_L[n] + "」"); break;
                }
            } });
            Blocks.fast(c);
            LinearLayout.LayoutParams lp = Blocks.weight(1);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(c, lp);
        }
        return r;
    }

    private LinearLayout.LayoutParams cell(int i) {
        LinearLayout.LayoutParams p = Blocks.weight(1);
        if (i > 0) p.leftMargin = dp(8);
        return p;
    }

    /** 电量每次进“我的”都可能变：只改那一格的字。 */
    private void refreshMe() {
        if (meBattery == null) return;
        Map<String, String> st = HostLink.status(a);
        String s = (st.get("battery") == null ? "—" : st.get("battery")) + "%";
        if (!s.contentEquals(meBattery.getText())) meBattery.setText(s);
    }

    String model() {
        String m = null;
        try { m = new java.io.BufferedReader(new java.io.FileReader("/dev/kfb/model")).readLine(); } catch (Throwable ignored) { }
        return m != null && m.trim().length() > 0 ? m.trim() : Build.MODEL;
    }

    String version() {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
    }

    String buildDate() {
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date(a.getPackageManager().getPackageInfo(a.getPackageName(), 0).lastUpdateTime)); }
        catch (Throwable t) { return "?"; }
    }

    static int storageUsed() {
        try {
            android.os.StatFs s = new android.os.StatFs("/data/media/0");
            long total = s.getTotalBytes(), free = s.getAvailableBytes();
            return total <= 0 ? 0 : (int) ((total - free) * 100 / total);
        } catch (Throwable t) { return 0; }
    }
}
