// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * “书库”页（主页底栏的第二个标签），控件树挂在 HomeActivity 下面，和主页、设置一样只切换可见性。
 * 布局照 Kindle 的书库：顶栏（和主页同一条）→ 一行“筛选 / 排序 / 封面·列表” → 一页封面（或列表）→ 页码 → 底栏。
 * 一页一页翻（划动或点箭头），不滚动。数据全部来自 {@link Lib}，搭页面时不读文件系统。
 */
final class LibraryPage {
    private static final String[] FILTERS = { "全部", "在读", "未读", "已读完" };
    private static final String[] SORTS = { "最近", "书名", "作者", "最近添加" };

    private final HomeActivity a;
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private View chrome;
    private LinearLayout searchRow;
    private EditText search;
    private TextView filterBtn, sortBtn, countLabel, pageLabel;
    private Icons.IconView viewIcon;
    private PagedBox content;
    private LinearLayout pager;
    private int page, sort, filter, view;   // view: 0 = 封面，1 = 列表
    private boolean searching;
    private String query = "";
    private int renderedVersion = -1;
    private String renderedSig = "";
    private final List<CoverView> shownCovers = new ArrayList<CoverView>();
    private final Runnable searchNow = new Runnable() { @Override public void run() { page = 0; render(); } };

    LibraryPage(HomeActivity activity) {
        a = activity;
        prefs = Store.prefs(a, "home");
        // 老版本只有“最近 / 书名”一个开关（lib_recent）
        sort = prefs.getInt("lib_sort", prefs.getBoolean("lib_recent", true) ? Lib.SORT_RECENT : Lib.SORT_TITLE);
        filter = prefs.getInt("lib_filter", Lib.FILTER_ALL);
        view = prefs.getInt("lib_view", 0);
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        build();
    }

    View root() { return root; }
    boolean searching() { return searching; }

    private int dp(int v) { return Ui.dp(a, v); }

    private void build() {
        chrome = a.topChrome(HomeActivity.TAB_LIB);
        root.addView(chrome);

        // 搜索状态下顶栏换成这一行：输入框 + 关闭。平时是 GONE，输入框拿不到焦点，也就没有闪烁的光标。
        searchRow = new LinearLayout(a);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(16), dp(10), dp(8), dp(8));
        search = Ui.editText(a);
        search.setHint("搜索书名、作者、应用");
        search.setHintTextColor(Color.BLACK);
        search.setTextColor(Color.BLACK);
        search.setSingleLine(true);
        search.setTextSize(18);
        search.setTypeface(Fonts.typeface(a));
        search.setCursorVisible(false);   // 闪烁的光标 = 墨水屏每 0.5 秒刷一小块
        search.setPadding(dp(18), dp(9), dp(18), dp(9));
        search.setBackground(pill());
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void afterTextChanged(Editable e) {
                String q = e.toString().trim();
                if (q.equals(query)) return;
                query = q;
                // 连着打字时合并成一次重画
                root.removeCallbacks(searchNow);
                root.postDelayed(searchNow, 350);
            }
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        searchRow.addView(Icons.bar(a, Icons.Kind.CLOSE, 28, new View.OnClickListener() {
            @Override public void onClick(View v) { endSearch(); }
        }));
        searchRow.setVisibility(View.GONE);
        root.addView(searchRow);
        root.addView(Ui.rule(a, 2, Color.BLACK));

        LinearLayout tools = new LinearLayout(a);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(8), dp(2), dp(4), dp(2));
        filterBtn = tool("", new View.OnClickListener() { @Override public void onClick(View v) { filterMenu(); } });
        tools.addView(filterBtn);
        countLabel = Ui.text(a, "", 14, false);
        countLabel.setGravity(Gravity.CENTER);
        tools.addView(countLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sortBtn = tool("", new View.OnClickListener() { @Override public void onClick(View v) { sortMenu(); } });
        tools.addView(sortBtn);
        View vb = Icons.bar(a, view == 0 ? Icons.Kind.LIST : Icons.Kind.GRID, 26, new View.OnClickListener() {
            @Override public void onClick(View v) {
                view = view == 0 ? 1 : 0;
                prefs.edit().putInt("lib_view", view).apply();
                page = 0;
                render();
                a.changed();
            }
        });
        viewIcon = (Icons.IconView) ((ViewGroup) vb).getChildAt(0);
        tools.addView(vb);
        root.addView(tools);
        root.addView(Ui.rule(a, 1, Color.BLACK));

        content = new PagedBox(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setListener(new PagedBox.Listener() { @Override public void onPage(int dir) { turn(dir); } });
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                // 高度变了（第一次排版、输入法顶上来）：每页能放的数量可能变，重排一次
                if (b - t != ob - ot && b - t > 0) v.post(new Runnable() { @Override public void run() { if (!sig().equals(renderedSig)) render(); } });
            }
        });

        pager = new LinearLayout(a);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER);
        pager.setPadding(0, dp(2), 0, dp(2));
        pager.addView(Icons.bar(a, Icons.Kind.BACK, 26, new View.OnClickListener() { @Override public void onClick(View v) { turn(-1); } }));
        pageLabel = Ui.text(a, "", 16, false);
        pageLabel.setPadding(dp(20), 0, dp(20), 0);
        pager.addView(pageLabel);
        pager.addView(Icons.bar(a, Icons.Kind.CHEV_R, 26, new View.OnClickListener() { @Override public void onClick(View v) { turn(1); } }));
        root.addView(pager);
        root.addView(a.bottomNav(HomeActivity.TAB_LIB));
        render();
    }

    private GradientDrawable pill() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(2, Color.BLACK);
        bg.setCornerRadius(dp(26));
        return bg;
    }

    private TextView tool(String s, View.OnClickListener l) {
        TextView t = Ui.text(a, s, 16, false);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        t.setOnClickListener(l);
        Ui.fast(t);
        return t;
    }

    // ------------------------------------------------------------------ paging

    private int cols() { return 3; }
    private int gap() { return dp(10); }
    private int side() { return dp(14); }
    private int cellW() { return (a.getResources().getDisplayMetrics().widthPixels - 2 * side() - (cols() - 1) * gap()) / cols(); }
    /** 一页放几行封面：封面按 2:3，放不下整行时允许压扁到 1:1.35（居中裁切），这样 6 寸屏上正好两行六本，和 Kindle 一样。 */
    private int gridRows() { return Math.max(1, (contentHeight() - gap()) / (cellW() * 135 / 100 + gap())); }
    private int cellH() { return Math.min(cellW() * 3 / 2, (contentHeight() - gap()) / gridRows() - gap()); }
    private int listRowH() { return dp(84); }

    private int contentHeight() {
        int h = content.getHeight();
        if (h > 0) return h;
        // 还没排过版：按屏幕高度估（顶栏、工具行、页码、底栏大约占这么多），排版后不对会重排
        return a.getResources().getDisplayMetrics().heightPixels - dp(200);
    }

    private int perPage(boolean list) {
        int h = contentHeight();
        if (list) return Math.max(1, h / listRowH());
        return gridRows() * cols();
    }

    private String sig() { return Lib.version() + "|" + sort + "|" + filter + "|" + view + "|" + query + "|" + searching + "|" + page + "|" + contentHeight(); }

    private void turn(int dir) {
        Perf.measure(root, "library page");
        int before = page;
        page += dir;
        render();
        if (page != before) a.changed();
    }

    /** 回到书库页时调用：数据没变就不动（同一棵树直接显示）。 */
    void refresh() {
        if (!sig().equals(renderedSig)) render();
    }

    void render() {
        boolean list = view == 1 || searching;
        List<Lib.Book> shown = Lib.sorted(sort, searching ? Lib.FILTER_ALL : filter, searching ? query : null);
        List<ResolveInfo> apps = searching && query.length() > 0 ? a.appsMatching(query) : new ArrayList<ResolveInfo>();
        int total = shown.size() + apps.size();
        int per = perPage(list);
        int pages = Math.max(1, (total + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        renderedSig = sig();
        renderedVersion = Lib.version();

        filterBtn.setText("筛选：" + FILTERS[filter]);
        sortBtn.setText("排序：" + SORTS[sort]);
        viewIcon.setKind(view == 0 ? Icons.Kind.LIST : Icons.Kind.GRID);
        String count = searching ? (query.length() == 0 ? "输入书名、作者或应用名" : "找到 " + total + " 项") : "共 " + shown.size() + " 本";
        if (!count.equals(String.valueOf(countLabel.getText()))) countLabel.setText(count);
        String pl = (page + 1) + " / " + pages;
        if (!pl.equals(String.valueOf(pageLabel.getText()))) pageLabel.setText(pl);
        pager.setVisibility(pages > 1 ? View.VISIBLE : View.INVISIBLE);

        content.removeAllViews();
        shownCovers.clear();
        int from = page * per, to = Math.min(total, from + per);
        if (total == 0) {
            boolean klibSkip = "1".equals(HostLink.kv(HostLink.KFB + "ctl/status").get("klibskip"));
            String msg = searching ? (query.length() == 0 ? "" : "没有找到。")
                : Lib.isEmpty() ? ("还没有书。\n\n点右上角菜单里的“传书”，用手机或电脑把书传进来；\n也可以把书放进 Kindle 的 documents 文件夹。"
                    + (klibSkip ? "\n\n（实验机型默认未挂载 Kindle 原机书库以保护原机书籍；如需查看，可在「设置 → 系统与性能 → 挂载 Kindle 原机书库」选择「只读挂载」并重启 Android）" : ""))
                : "没有符合筛选条件的书。";
            TextView empty = Ui.text(a, msg, 18, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(32), dp(48), dp(32), 0);
            content.addView(empty);
            return;
        }
        List<Lib.Book> missing = new ArrayList<Lib.Book>();
        if (list) {
            for (int i = from; i < to; i++) {
                if (i < apps.size()) content.addView(appRow(apps.get(i)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listRowH()));
                else content.addView(listRow(shown.get(i - apps.size()), missing), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listRowH()));
            }
        } else {
            int c = cols(), cw = cellW(), ch = cellH();
            content.setPadding(side(), gap(), side(), 0);
            LinearLayout row = null;
            for (int i = from; i < to; i++) {
                if ((i - from) % c == 0) {
                    row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ch);
                    rp.bottomMargin = gap();
                    content.addView(row, rp);
                }
                Lib.Book b = shown.get(i);
                CoverView cv = cover(b, true, missing);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, ch);
                if ((i - from) % c != 0) lp.leftMargin = gap();
                row.addView(cv, lp);
            }
        }
        if (list) content.setPadding(0, 0, 0, 0);
        if (!missing.isEmpty()) Lib.generate(a, missing);
    }

    private CoverView cover(final Lib.Book b, boolean badge, List<Lib.Book> missing) {
        CoverView cv = new CoverView(a, b, Lib.cover(a, b), badge);
        if (!cv.hasBitmap() && Lib.coverMissing(a, b)) missing.add(b);
        shownCovers.add(cv);
        cv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Lib.open(a, b); } });
        cv.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { a.bookMenu(b); return true; } });
        return cv;
    }

    private View listRow(final Lib.Book b, List<Lib.Book> missing) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), 0, dp(16), 0);
        int th = listRowH() - dp(14);
        CoverView cv = cover(b, false, missing);
        cv.setClickable(false);
        cv.setLongClickable(false);
        line.addView(cv, new LinearLayout.LayoutParams(th * 2 / 3, th));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(10), 0);
        TextView t = Ui.text(a, b.title, 18, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        String sub = (b.author != null && b.author.length() > 0 ? b.author + " · " : "") + b.ext();
        TextView s = Ui.text(a, sub, 14, false);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(s);
        line.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(Ui.text(a, b.percent >= 100 ? "已读" : b.percent > 0 || b.lastRead > 1 ? b.percent + "%" : "", 16, false));
        r.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        r.addView(Ui.rule(a, 1, Color.BLACK));
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Lib.open(a, b); } });
        r.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { a.bookMenu(b); return true; } });
        return r;
    }

    private View appRow(final ResolveInfo ri) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(16), 0, dp(16), 0);
        int th = listRowH() - dp(14);
        android.widget.ImageView icon = new android.widget.ImageView(a);
        icon.setImageDrawable(a.iconFor(ri));
        line.addView(icon, new LinearLayout.LayoutParams(th * 2 / 3, th * 2 / 3));
        TextView t = Ui.text(a, a.labelFor(ri), 18, true);
        t.setPadding(dp(14), 0, dp(10), 0);
        t.setSingleLine(true);
        line.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(Ui.text(a, "应用", 14, false));
        r.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        r.addView(Ui.rule(a, 1, Color.BLACK));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { endSearch(); a.launch(ri); } });
        Ui.fast(r);
        return r;
    }

    /** 书库没显示时数据变了：闲下来（半秒后）在看不见的树上重排好，切到书库时只是换一棵树，不在点下去那一刻才重排、解封面。 */
    private final Runnable refreshHidden = new Runnable() { @Override public void run() { if (root.getVisibility() != View.VISIBLE) refresh(); } };
    void refreshSoon() { root.removeCallbacks(refreshHidden); root.postDelayed(refreshHidden, 500); }

    /** 书库数据变了（后台扫描有新书、状态变更、封面生成好了）。书库正显示着就立刻重画，否则闲下来时在后台重排。 */
    void onLibraryChanged(boolean listChanged, boolean visible) {
        if (!visible) { refreshSoon(); return; }
        render();
    }

    // ------------------------------------------------------------------ menus

    private void filterMenu() {
        new VeraDialog.Builder(a).title("筛选").items(FILTERS, new VeraDialog.OnPick() {
            @Override public void onPick(int which) {
                if (which == filter) return;
                filter = which; page = 0;
                prefs.edit().putInt("lib_filter", filter).apply();
                render();
            }
        }).selected(filter).show();
    }

    private void sortMenu() {
        new VeraDialog.Builder(a).title("排序").items(SORTS, new VeraDialog.OnPick() {
            @Override public void onPick(int which) {
                if (which == sort) return;
                sort = which; page = 0;
                prefs.edit().putInt("lib_sort", sort).putBoolean("lib_recent", sort != Lib.SORT_TITLE).apply();
                render();
            }
        }).selected(sort).show();
    }

    // ------------------------------------------------------------------ search

    /** 点顶栏的搜索框：顶栏换成输入框，下面变成结果列表，同一帧换完；然后才弹输入法。 */
    void beginSearch() {
        if (searching) return;
        searching = true;
        query = "";
        page = 0;
        chrome.setVisibility(View.GONE);
        searchRow.setVisibility(View.VISIBLE);
        if (search.length() > 0) search.setText("");
        render();
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(search, 0);
    }

    void endSearch() {
        if (!searching) return;
        searching = false;
        root.removeCallbacks(searchNow);
        InputMethodManager imm = (InputMethodManager) a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        searchRow.setVisibility(View.GONE);
        chrome.setVisibility(View.VISIBLE);
        query = "";
        page = 0;
        render();
    }
}
