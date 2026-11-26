package com.veradroid.home;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 方块风格的“文件”标签：最近 / 书籍 / 图片 / 笔记，一页一页翻。只浏览和打开，不删不改（删书在长按菜单里，和经典书库同一套）。
 *  - 最近：书库索引里的书，最近读过 / 放进来的在前（{@link Lib}）。
 *  - 书籍：按文件夹浏览 Books、下载、Kindle 书库。内部存储的文件夹当场列；Kindle 书库走 FUSE 会卡，在后台列，列完再画。
 *  - 图片：{@link Theme#findImages()}（后台找，有缓存）。点一张全屏看，可以设成壁纸。
 *  - 笔记：所有书的书签（阅读器还没有摘录 / 批注）。点一条打开那本书。
 */
final class BlocksFiles {
    private static final String[] SEGS = { "最近", "书籍", "图片", "笔记" };
    private static final String ROOT = "";

    private final BlocksHome h;
    private final HomeActivity a;
    private int seg, page, perPage, totalPages = 1;
    /** 排序：0 名称，1 时间（新的在前），2 大小（大的在前）。搜索：只留名字里有这段字的。多选：选中的文件路径。 */
    private int sortBy;
    private String query = "";
    private boolean selecting;
    private final java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<String>();
    private LinearLayout actionBar;
    private TextView actionCount;
    private String path = ROOT;
    private LinearLayout list;
    private TextView pathText, countText, capText;
    private Blocks.Pager pager;
    private Blocks.SegBar segBar;
    private final Map<String, File[]> dirCache = new HashMap<String, File[]>();
    private static List<File> images;
    private static boolean findingImages;
    private int rev;
    private final Handler ui = new Handler(Looper.getMainLooper());

    BlocksFiles(BlocksHome home) { h = home; a = home.a; }

    private int dp(int v) { return Ui.dp(a, v); }

    String sig() {
        return BlocksHome.storageUsed() + "|" + Lib.version() + "|" + Stats.bookmarkCount(a) + "|" + a.libOn() + "|" + rev;
    }

    void showSeg(int s) { if (s != seg) endSelectQuiet(); seg = s; page = 0; if (segBar != null) segBar.select(s); render(); }

    /** 点“文件”标签时已经在文件页：回到书籍的最上层。 */
    boolean toRoot() {
        if (seg == 1 && !ROOT.equals(path)) { path = ROOT; page = 0; render(); return true; }
        return false;
    }

    /** 返回键：书籍里往上一级。 */
    boolean up() {
        if (seg != 1 || ROOT.equals(path)) return false;
        String p = path;
        for (String[] r : roots()) if (r[1].equals(p)) { path = ROOT; page = 0; render(); return true; }
        path = new File(p).getParent();
        if (path == null) path = ROOT;
        page = 0;
        render();
        return true;
    }

    private static String[][] roots() {
        return new String[][] { { "Books（内部存储）", Transfer.BOOKS }, { "下载", "/data/media/0/Download" }, { "Kindle 书库", Transfer.KINDLE } };
    }

    View build() {
        LinearLayout pg = new LinearLayout(a);
        pg.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = Blocks.pageHead(a, "Local storage / " + BlocksHome.storageUsed() + "% used", "文件", Blocks.squareButton(a, Icons.Kind.MORE, new View.OnClickListener() {
            @Override public void onClick(View v) { menu(); }
        }));
        pg.addView(head);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), 0, dp(16), dp(6));
        segBar = new Blocks.SegBar(a, SEGS, seg, new Blocks.SegBar.Listener() {
            @Override public void onPick(int i) { if (i != seg) endSelectQuiet(); seg = i; page = 0; render(); a.changed(); }
        });
        box.addView(segBar, Blocks.match());
        // 路径条：左边路径，右边条数
        LinearLayout pb = new LinearLayout(a);
        pb.setGravity(Gravity.CENTER_VERTICAL);
        pb.setBackground(Blocks.frame(a));
        pb.setPadding(dp(10), dp(6), dp(10) + Blocks.shade(a), dp(6) + Blocks.shade(a));
        pathText = Ui.text(a, "", 14, true);
        pathText.setSingleLine(true);
        pathText.setEllipsize(TextUtils.TruncateAt.START);
        pb.addView(pathText, Blocks.weight(1));
        countText = Ui.text(a, "", 13, false);
        pb.addView(countText);
        box.addView(pb, Blocks.gap(a, 8));
        capText = Blocks.cap(a, "");
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Blocks.frameDouble(a));
        int ip = dp(4) + Blocks.stroke(a);
        list.setPadding(ip, ip, ip + Blocks.shade(a), ip + Blocks.shade(a));
        PagedBox pbox = new PagedBox(a);
        pbox.setOrientation(LinearLayout.VERTICAL);
        pbox.setListener(new PagedBox.Listener() { @Override public void onPage(int dir) { turn(dir); } });
        pbox.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = dp(8);
        box.addView(pbox, lp);
        pager = new Blocks.Pager(a, new Blocks.Pager.Listener() { @Override public void onPage(int dir) { turn(dir); } });
        box.addView(pager, Blocks.gap(a, 8));
        // 多选时翻页条下面一排：已选几个、移动、删除、完成
        actionBar = new LinearLayout(a);
        actionBar.setGravity(Gravity.CENTER_VERTICAL);
        actionCount = Ui.text(a, "", 15, true);
        actionBar.addView(actionCount, Blocks.weight(1));
        actionBar.addView(Blocks.button(a, "移动…", false, new View.OnClickListener() { @Override public void onClick(View v) { moveDialog(new ArrayList<String>(picked)); } }));
        actionBar.addView(new View(a), Blocks.lp(dp(8), 1));
        actionBar.addView(Blocks.button(a, "删除…", false, new View.OnClickListener() { @Override public void onClick(View v) { deleteDialog(new ArrayList<String>(picked)); } }));
        actionBar.addView(new View(a), Blocks.lp(dp(8), 1));
        actionBar.addView(Blocks.button(a, "完成", true, new View.OnClickListener() { @Override public void onClick(View v) { endSelect(); } }));
        actionBar.setVisibility(View.GONE);
        box.addView(actionBar, Blocks.gap(a, 8));
        pg.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        perPage = 0;
        pbox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int np = Math.max(3, (b - t - dp(12) - Blocks.shade(a)) / rowH());
                if (np != perPage && b - t > 0) { perPage = np; v.post(new Runnable() { @Override public void run() { render(); } }); }
            }
        });
        return pg;
    }

    private int rowH() { return dp(58); }

    private void turn(int dir) {
        int np = Math.max(0, Math.min(totalPages - 1, page + dir));
        if (np == page) return;
        Blocks.swipe(a, dir > 0 ? Blocks.DIR_UP : Blocks.DIR_DOWN, Blocks.KIND_LIST);
        page = np;
        render();
        a.changed();
    }

    /** 一行：左边类型（文件夹图标 / 格式角标），中间名字，右边说明；整行可点。 */
    private interface Item { String name(); String meta(); String type(); void open(); boolean folder(); File file(); }

    private void render() {
        if (list == null || perPage <= 0) return;
        List<Item> items = filtered(items());
        if (actionCount != null) {
            String ac = "已选 " + picked.size() + " 个";
            if (!ac.contentEquals(actionCount.getText())) actionCount.setText(ac);
            int av = selecting ? View.VISIBLE : View.GONE;
            if (actionBar.getVisibility() != av) actionBar.setVisibility(av);
        }
        int pages = Math.max(1, (items.size() + perPage - 1) / perPage);
        totalPages = pages;
        if (page >= pages) page = pages - 1;
        list.removeAllViews();
        int from = page * perPage, to = Math.min(items.size(), from + perPage);
        for (int i = from; i < to; i++) {
            if (i > from) list.addView(Blocks.hair(a));
            list.addView(row(items.get(i)), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH() - 1));
        }
        if (items.isEmpty()) list.addView(Blocks.empty(a, seg == 2 ? Icons.Kind.IMAGE : seg == 3 ? Icons.Kind.BOOKMARK : seg == 1 ? Icons.Kind.FOLDER : Icons.Kind.BOOK,
            query.length() > 0 ? "没有找到“" + query + "”" : empty(), query.length() > 0 ? "点右上角，清除搜索" : emptyHint(), loading()), Blocks.match());
        String p = query.length() > 0 ? "搜索：" + query : seg == 1 ? (ROOT.equals(path) ? "/" : shortPath(path)) : seg == 0 ? "/书库/最近" : seg == 2 ? "/图片" : "/笔记/书签";
        if (!p.contentEquals(pathText.getText())) pathText.setText(p);
        String c = items.size() + " 项";
        if (!c.contentEquals(countText.getText())) countText.setText(c);
        pager.set(page + 1, pages);
    }

    private boolean loading() { return (seg == 2 && findingImages) || (seg == 1 && !ROOT.equals(path) && path.startsWith(Transfer.KINDLE) && !dirCache.containsKey(path)); }

    private boolean klibSkippedHere() {
        return seg == 1 && path != null && path.startsWith(Transfer.KINDLE)
            && "1".equals(HostLink.kv(HostLink.KFB + "ctl/status").get("klibskip"));
    }

    private String empty() {
        if (loading()) return seg == 2 ? "正在查找图片" : "正在读取文件夹";
        if (klibSkippedHere()) return "实验机型默认未挂载 Kindle 原机书库";
        switch (seg) {
        case 0: return a.libOn() ? "书库里还没有书" : "书库已关闭";
        case 2: return "没有找到图片";
        case 3: return "还没有书签";
        default: return "这个文件夹是空的";
        }
    }

    private String emptyHint() {
        if (loading()) return "Kindle 书库在另一个文件系统上，第一次要读一会儿";
        if (klibSkippedHere()) return "可在 设置／系统与性能 中选择「只读挂载 Kindle 书库」并重启 Android";
        switch (seg) {
        case 0: return a.libOn() ? ("1".equals(HostLink.kv(HostLink.KFB + "ctl/status").get("klibskip"))
            ? "实验机型默认未挂载 Kindle 书库，可用传书导入或在「设置／系统与性能」开启只读挂载"
            : "用传书网页传进来，或者放进 Books 文件夹") : "设置／应用与桌面／书库";
        case 2: return "放进 Pictures、Download 或 VeraDroid/wallpaper";
        case 3: return "阅读时点工具栏“系统／加书签”";
        default: return "";
        }
    }

    /** 搜索 + 排序（“上一级”永远在最前，文件夹在文件前面）。 */
    private List<Item> filtered(List<Item> in) {
        List<Item> keep = new ArrayList<Item>(), head = new ArrayList<Item>();
        String q = query.toLowerCase(Locale.CHINA);
        for (Item it : in) {
            if ("上一级".equals(it.name()) && it.file() == null) { head.add(it); continue; }
            if (q.length() > 0 && !it.name().toLowerCase(Locale.CHINA).contains(q)) continue;
            keep.add(it);
        }
        if (seg != 0 && seg != 3 && sortBy != 0) {
            java.util.Collections.sort(keep, new Comparator<Item>() { @Override public int compare(Item x, Item y) {
                if (x.folder() != y.folder()) return x.folder() ? -1 : 1;
                File fx = x.file(), fy = y.file();
                if (fx == null || fy == null) return 0;
                return sortBy == 1 ? Long.compare(fy.lastModified(), fx.lastModified()) : Long.compare(fy.length(), fx.length());
            } });
        }
        head.addAll(keep);
        return head;
    }

    private static String shortPath(String p) {
        if (p.startsWith(Transfer.KINDLE)) return "/Kindle 书库" + p.substring(Transfer.KINDLE.length());
        if (p.startsWith("/data/media/0")) return p.substring("/data/media/0".length());
        return p;
    }

    private View row(final Item it) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), 0, dp(10), 0);
        FrameLayout ic = new FrameLayout(a);
        if (it.folder()) ic.addView(new Icons.IconView(a, Icons.Kind.FOLDER), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        else ic.addView(Blocks.badge(a, it.type()), new FrameLayout.LayoutParams(dp(38), dp(26), Gravity.CENTER));
        r.addView(ic, Blocks.lp(dp(42), dp(42)));
        TextView n = BlocksCards.line(a, it.name(), 16, it.folder());
        n.setPadding(dp(10), 0, dp(8), 0);
        r.addView(n, Blocks.weight(1));
        String m = it.meta();
        if (m != null && m.length() > 0) r.addView(Ui.text(a, m, 13, false));
        final File f = it.file();
        if (selecting && f != null && !it.folder()) {
            boolean on = picked.contains(f.getAbsolutePath());
            View box = new View(a);
            box.setBackground(on ? Blocks.plainFilled(a) : Blocks.plain(a));
            LinearLayout.LayoutParams bp = Blocks.lp(dp(24), dp(24));
            bp.leftMargin = dp(8);
            r.addView(box, bp);
        }
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            if (selecting && f != null && !it.folder()) { String k = f.getAbsolutePath(); if (!picked.remove(k)) picked.add(k); render(); return; }
            it.open();
        } });
        if (f != null && !it.folder() && seg != 3) r.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { fileMenu(it); return true; } });
        else Blocks.fast(r);
        return r;
    }

    // ------------------------------------------------------------------ 菜单、多选、重命名、移动、删除

    private void menu() {
        final String[] items = { "搜索…", query.length() > 0 ? "清除搜索" : null, "排序：" + new String[] { "名称", "时间", "大小" }[sortBy] + "（点一下换）",
            seg == 3 ? null : (selecting ? "结束多选" : "多选（批量移动 / 删除）"), "更多…" };
        final List<String> shown = new ArrayList<String>();
        for (String x : items) if (x != null) shown.add(x);
        new VeraDialog.Builder(a).items(shown.toArray(new String[0]), new VeraDialog.OnPick() { @Override public void onPick(int w) {
            String x = shown.get(w);
            if (x.startsWith("搜索")) searchDialog();
            else if (x.startsWith("清除")) { query = ""; page = 0; render(); a.changed(); }
            else if (x.startsWith("排序")) { sortBy = (sortBy + 1) % 3; page = 0; render(); a.changed(); }
            else if (x.startsWith("结束")) endSelect();
            else if (x.startsWith("多选")) { selecting = true; picked.clear(); render(); a.changed(); }
            else a.mainMenu();
        } }).show();
    }

    private void endSelect() { selecting = false; picked.clear(); render(); a.changed(); }

    private void searchDialog() {
        final android.widget.EditText in = Ui.editText(a);
        in.setSingleLine(true);
        in.setTextSize(20);
        in.setTypeface(Fonts.typeface(a));
        in.setBackground(Blocks.plain(a));
        in.setPadding(dp(12), dp(10), dp(12), dp(10));
        in.setText(query);
        new VeraDialog.Builder(a).title("搜索文件名").message("只在当前这一栏（" + SEGS[seg] + "）里找。").view(in).negative("取消", null)
            .positive("搜索", new Runnable() { @Override public void run() { query = in.getText().toString().trim(); page = 0; render(); a.changed(); } }).show();
    }

    private void fileMenu(final Item it) {
        final File f = it.file();
        new VeraDialog.Builder(a).title(f.getName()).items(new String[] { "打开", "重命名…", "移动到…", "删除…", "多选" }, new VeraDialog.OnPick() {
            @Override public void onPick(int w) {
                List<String> one = new ArrayList<String>(); one.add(f.getAbsolutePath());
                switch (w) {
                case 0: it.open(); break;
                case 1: renameDialog(f); break;
                case 2: moveDialog(one); break;
                case 3: deleteDialog(one); break;
                default: selecting = true; picked.clear(); picked.add(f.getAbsolutePath()); render(); a.changed(); break;
                }
            }
        }).negative("取消", null).show();
    }

    private static boolean inKindle(File f) {
        if (f == null) return false;
        String p = f.getAbsolutePath();
        return p.equals(Transfer.KINDLE) || p.startsWith(Transfer.KINDLE + "/");
    }

    /** 统一拦截受保护路径、符号链接与只读目录（宿主脚本/SSH/KindleFonts 等）。 */
    private static String blockedWhy(File f) {
        if (f == null) return "文件不存在";
        if (WebAdmin.isLink(f)) return "不能操作符号链接";
        if (WebAdmin.isProtected(f.getName())) return "受保护的系统文件，不能修改或删除";
        File parent = f.getParentFile();
        if (parent != null) {
            if (inKindle(f)) {
                String ro = WebAdmin.readOnlyWhy("kindle", parent);
                if (ro != null) return ro;
            } else if (parent.getPath().equals("/data/media/0") || parent.getPath().startsWith("/data/media/0/")) {
                String ro = WebAdmin.readOnlyWhy("sdcard", parent);
                if (ro != null) return ro;
            }
        }
        return null;
    }

    private void renameDialog(final File f) {
        String why = blockedWhy(f);
        if (why != null) { VeraToast.show(a, why); return; }
        final android.widget.EditText in = Ui.editText(a);
        in.setSingleLine(true);
        in.setTextSize(18);
        in.setTypeface(Fonts.typeface(a));
        in.setBackground(Blocks.plain(a));
        in.setPadding(dp(12), dp(10), dp(12), dp(10));
        in.setText(f.getName());
        new VeraDialog.Builder(a).title("重命名").view(in).negative("取消", null).positive("改名", new Runnable() { @Override public void run() {
            String raw = in.getText().toString().trim();
            File parent = f.getParentFile();
            if (parent == null || raw.length() == 0 || raw.equals(f.getName())) return;
            String n = WebAdmin.checkName(raw);
            if (n == null) { VeraToast.show(a, "新名字不能用（不能以点开头、含非法字符或为脚本/受保护文件名）"); return; }
            File to = new File(parent, n);
            if (to.exists()) { VeraToast.show(a, "已经有同名的文件"); return; }
            String oldPath = f.getAbsolutePath();
            if (!f.renameTo(to)) { VeraToast.show(a, "改不了名（权限或只读）"); return; }
            if (Lib.isBook(f.getName()) || Lib.isBook(n)) migrateReaderPrefs(a, oldPath, to.getAbsolutePath());
            changedOnDisk(parent);
        } }).show();
    }

    /** 移动到：Books、下载、Kindle 书库三个地方之一。 */
    private void moveDialog(final List<String> paths) {
        if (paths.isEmpty()) { VeraToast.show(a, "还没选文件"); return; }
        final String[][] r = roots();
        String[] names = new String[r.length];
        for (int i = 0; i < r.length; i++) names[i] = r[i][0];
        new VeraDialog.Builder(a).title("把 " + paths.size() + " 个文件移动到").items(names, new VeraDialog.OnPick() { @Override public void onPick(final int w) {
            final File dir = new File(r[w][1]);
            if (Transfer.KINDLE.equals(r[w][1]) && ("1".equals(HostLink.kv(HostLink.CTL + "status").get("klibskip")) || WebAdmin.readOnlyWhy("kindle", dir) != null)) {
                VeraToast.show(a, "Kindle 书库为只读保护（或未挂载），不能直接移入，请用 USB 或 Kindle 同步");
                return;
            }
            new VeraDialog.Builder(a).title("确认移动").message(paths.size() + " 个文件移动到“" + r[w][0] + "”。").negative("取消", null)
                .positive("移动", new Runnable() { @Override public void run() {
                    final List<String> copyPaths = new ArrayList<String>(paths);
                    VeraToast.show(a, "正在移动 " + copyPaths.size() + " 个文件…");
                    endSelectQuiet();
                    new Thread(new Runnable() { @Override public void run() {
                        int ok = 0, skipped = 0, blocked = 0;
                        dir.mkdirs();
                        boolean toKindle = inKindle(dir);
                        for (String p : copyPaths) {
                            File f = new File(p);
                            if (!f.isFile() || blockedWhy(f) != null || (toKindle && (WebAdmin.checkName(f.getName()) == null || Transfer.isScript(f.getName())))) {
                                blocked++;
                                continue;
                            }
                            File to = new File(dir, f.getName());
                            if (to.exists()) { skipped++; continue; }
                            boolean movedOk = false;
                            File finalTo = to;
                            if (f.renameTo(to)) {
                                movedOk = true;
                            } else if (copy(f, to)) {
                                if (f.delete()) movedOk = true;
                                else to.delete();
                            } else if (Transfer.isKindleRoot(dir)) {
                                String putName = Transfer.libputFile(a, f, f.getName());
                                if (putName != null) {
                                    finalTo = new File(dir, putName);
                                    if (f.delete()) movedOk = true;
                                }
                            }
                            if (movedOk) {
                                if (Lib.isBook(f.getName())) migrateReaderPrefs(a, f.getAbsolutePath(), finalTo.getAbsolutePath());
                                ok++;
                            }
                        }
                        final int moved = ok, skipCnt = skipped, blkCnt = blocked;
                        a.runOnUiThread(new Runnable() { @Override public void run() {
                            if (a.isFinishing()) return;
                            String msg = "移动了 " + moved + " / " + copyPaths.size() + " 个";
                            if (skipCnt > 0) msg += "（" + skipCnt + " 个已存在跳过）";
                            if (blkCnt > 0) msg += "（" + blkCnt + " 个受保护跳过）";
                            VeraToast.show(a, msg);
                            changedOnDisk(dir);
                        } });
                    } }, "vd-file-move").start();
                } }).show();
        } }).negative("取消", null).show();
    }

    private void deleteDialog(final List<String> paths) {
        if (paths.isEmpty()) { VeraToast.show(a, "还没选文件"); return; }
        if (paths.size() == 1) {
            String why = blockedWhy(new File(paths.get(0)));
            if (why != null) { VeraToast.show(a, why); return; }
        }
        String first = new File(paths.get(0)).getName();
        new VeraDialog.Builder(a).title("删除 " + paths.size() + " 个文件？")
            .message((paths.size() == 1 ? "《" + first + "》" : "包括《" + first + "》") + "会从设备上删掉，不能恢复。").negative("取消", null)
            .positive("删除", new Runnable() { @Override public void run() {
                final List<String> delPaths = new ArrayList<String>(paths);
                endSelectQuiet();
                if (delPaths.size() > 1) VeraToast.show(a, "正在删除 " + delPaths.size() + " 个文件…");
                new Thread(new Runnable() { @Override public void run() {
                    int ok = 0, blocked = 0;
                    final List<String> deletedPaths = new ArrayList<String>();
                    for (String p : delPaths) {
                        File f = new File(p);
                        if (blockedWhy(f) != null) { blocked++; continue; }
                        if (f.delete()) {
                            ok++;
                            deletedPaths.add(p);
                            Lib.purgeReaderState(a, p);
                        }
                    }
                    final int deleted = ok, blkCnt = blocked;
                    a.runOnUiThread(new Runnable() { @Override public void run() {
                        for (String p : deletedPaths) Lib.removeFromIndex(a, p);
                        if (a.isFinishing()) return;
                        String msg = "删除了 " + deleted + " / " + delPaths.size() + " 个";
                        if (blkCnt > 0) msg += "（" + blkCnt + " 个受保护跳过）";
                        VeraToast.show(a, msg);
                        changedOnDisk(null);
                    } });
                } }, "vd-file-delete").start();
            } }).show();
    }

    private void endSelectQuiet() { selecting = false; picked.clear(); }

    /** 文件变了：丢掉文件夹缓存，后台重扫书库，重画这一页。 */
    private void changedOnDisk(File dir) {
        dirCache.clear();
        images = null;
        rev++;
        Lib.scan(a, 0);
        render();
        a.changed();
    }

    /** 书籍改名或移动后，把 reader 偏好里以旧路径为后缀的进度与书签迁到新路径。 */
    static void migrateReaderPrefs(android.content.Context c, String oldPath, String newPath) {
        if (oldPath == null || newPath == null || oldPath.equals(newPath)) return;
        try {
            android.content.SharedPreferences p = Store.prefs(c, "reader");
            Map<String, ?> all = p.getAll();
            android.content.SharedPreferences.Editor e = null;
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String k = entry.getKey();
                int colon = k.indexOf(':');
                if (colon <= 0 || !k.substring(colon + 1).equals(oldPath)) continue;
                String nk = k.substring(0, colon + 1) + newPath;
                Object v = entry.getValue();
                if (e == null) e = p.edit();
                if (v instanceof String) e.putString(nk, (String) v);
                else if (v instanceof Integer) e.putInt(nk, (Integer) v);
                else if (v instanceof Long) e.putLong(nk, (Long) v);
                else if (v instanceof Boolean) e.putBoolean(nk, (Boolean) v);
                else if (v instanceof Float) e.putFloat(nk, (Float) v);
                e.remove(k);
            }
            if (e != null) e.apply();
        } catch (Throwable ignored) { }
    }

    private static boolean copy(File from, File to) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(from);
            out = new java.io.FileOutputStream(to);
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.flush();
            out.close(); out = null;
            if (to.length() == from.length()) return true;
            to.delete();
            return false;
        } catch (Throwable t) { to.delete(); return false; }
        finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
    }

    private List<Item> items() {
        List<Item> out = new ArrayList<Item>();
        switch (seg) {
        case 0:
            if (!a.libOn()) break;
            for (final Lib.Book b : Lib.sorted(Lib.SORT_RECENT, Lib.FILTER_ALL, null)) out.add(bookItem(b));
            break;
        case 1:
            if (ROOT.equals(path)) {
                for (final String[] r : roots()) out.add(folder(r[0], r[1]));
                break;
            }
            if (!ROOT.equals(path)) out.add(new Item() {
                public String name() { return "上一级"; } public String meta() { return ""; } public String type() { return ""; }
                public boolean folder() { return true; } public void open() { up(); a.changed(); } public File file() { return null; } });
            File[] fs = list(path);
            if (fs == null) break;
            for (File f : fs) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) out.add(folder(f.getName(), f.getAbsolutePath()));
            }
            for (File f : fs) {
                if (f.getName().startsWith(".") || f.isDirectory()) continue;
                out.add(fileItem(f));
            }
            break;
        case 2:
            if (images == null) findImages();
            if (images != null) for (File f : images) out.add(fileItem(f));
            break;
        case 3:
            for (Map.Entry<String, ?> e : new java.util.TreeMap<String, Object>(Store.prefs(a, "reader").getAll()).entrySet()) {
                if (!e.getKey().startsWith("bm:") || !(e.getValue() instanceof String)) continue;
                final String bp = e.getKey().substring(3);
                final Lib.Book b = book(bp);
                if (!b.file.isFile()) continue;
                int k = 0;
                for (final String mark : ((String) e.getValue()).split(";")) {
                    if (mark.length() == 0) continue;
                    final int no = ++k;
                    out.add(new Item() {
                        public String name() { return b.title; }
                        public String meta() { return markLabel(bp, mark, no); }
                        public String type() { return "书签"; }
                        public boolean folder() { return false; }
                        public File file() { return null; }
                        public void open() { if (b.file.isFile()) openAt(b, mark); else VeraToast.show(a, "这本书已经不在了"); }
                    });
                }
            }
            break;
        }
        return out;
    }

    private Item folder(final String name, final String p) {
        return new Item() {
            public String name() { return name; } public String meta() { return ""; } public String type() { return ""; }
            public boolean folder() { return true; }
            public void open() { path = p; page = 0; render(); a.changed(); }
            public File file() { return null; }
        };
    }

    private Item bookItem(final Lib.Book b) {
        return new Item() {
            public String name() { return b.title; }
            public String meta() { return b.percent >= 100 ? "已读完" : b.lastRead > 1 ? b.percent + "%" : BlocksCards.size(b.size); }
            public String type() { return b.ext(); }
            public boolean folder() { return false; }
            public void open() { Lib.open(a, b); }
            public File file() { return b.file; }
        };
    }

    private Item fileItem(final File f) {
        final String ext = Lib.ext(f.getName());
        final boolean book = Lib.isBook(f.getName());
        final boolean img = isImage(f.getName());
        return new Item() {
            public String name() { return f.getName(); }
            public String meta() { Lib.Book b = book ? known(f.getAbsolutePath()) : null; return b != null && b.lastRead > 1 ? b.percent + "%" : BlocksCards.size(f.length()); }
            public String type() { return ext.length() > 4 ? ext.substring(0, 4) : ext; }
            public boolean folder() { return false; }
            public File file() { return f; }
            public void open() {
                if (book) Lib.open(a, book(f.getAbsolutePath()));
                else if (img) viewImage(f);
                else VeraToast.show(a, "这种文件在这里只能看，不能打开");
            }
        };
    }

    /** 书签的说明：PDF 是“第 n 页”，文字书是“第 n 章”（章从 1 数）。 */
    private static String markLabel(String path, String mark, int no) {
        try {
            if (path.toLowerCase(Locale.US).endsWith(".pdf")) return "第 " + (Integer.parseInt(mark) + 1) + " 页";
            String[] f = mark.split(":");
            return "书签 " + no + " · 第 " + (Integer.parseInt(f[0]) + 1) + " 章";
        } catch (Throwable t) { return "书签 " + no; }
    }

    /** 打开书并停在这个书签：把续读位置改成书签的位置（文字书按章 + 字符，PDF 按页），阅读器照常从续读位置打开（一次刷新）。 */
    private void openAt(Lib.Book b, String mark) {
        android.content.SharedPreferences.Editor e = Store.prefs(a, "reader").edit();
        try {
            if (b.path.toLowerCase(Locale.US).endsWith(".pdf")) e.putInt("p:" + b.path, Integer.parseInt(mark));
            else {
                String[] f = mark.split(":");
                e.putInt("c:" + b.path, Integer.parseInt(f[0])).putInt("o:" + b.path, Integer.parseInt(f[1]))
                    .remove("e:" + b.path).remove("fl:" + b.path);   // 记下的 EPUB 章文件名是旧位置的：不要它，先读整本
            }
        } catch (Throwable ignored) { }
        e.commit();
        Lib.open(a, b);
    }

    private static boolean isImage(String n) {
        String s = n.toLowerCase(Locale.US);
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp") || s.endsWith(".bmp") || s.endsWith(".gif");
    }

    private static Lib.Book known(String p) { for (Lib.Book b : Lib.all()) if (b.path.equals(p)) return b; return null; }
    private static Lib.Book book(String p) { Lib.Book b = known(p); return b != null ? b : new Lib.Book(p); }

    /** 列一个文件夹：内部存储当场列；Kindle 书库（FUSE）后台列，列完再画一次。 */
    private File[] list(final String p) {
        File[] c = dirCache.get(p);
        if (c != null) return c;
        if (!p.startsWith(Transfer.KINDLE)) { c = sorted(new File(p).listFiles()); dirCache.put(p, c); return c; }
        new Thread(new Runnable() { @Override public void run() {
            Ui.lowPriority();
            final File[] fs = sorted(new File(p).listFiles());
            ui.post(new Runnable() { @Override public void run() {
                dirCache.put(p, fs);
                if (!a.isFinishing() && p.equals(path) && seg == 1) { render(); a.changed(); }
            } });
        } }, "files-list").start();
        return null;
    }

    private static File[] sorted(File[] fs) {
        if (fs == null) return new File[0];
        final java.text.Collator zh = java.text.Collator.getInstance(Locale.CHINA);
        Arrays.sort(fs, new Comparator<File>() { @Override public int compare(File x, File y) { return zh.compare(x.getName(), y.getName()); } });
        return fs;
    }

    private void findImages() {
        if (findingImages) return;
        findingImages = true;
        new Thread(new Runnable() { @Override public void run() {
            Ui.lowPriority();
            final List<File> l = Theme.findImages();
            ui.post(new Runnable() { @Override public void run() {
                images = l; findingImages = false;
                if (!a.isFinishing() && seg == 2) { render(); a.changed(); }
            } });
        } }, "files-images").start();
    }

    /** 新的书 / 图片进来以后，下次进文件页重新列。 */
    void forget() { dirCache.clear(); images = null; rev++; }

    private void viewImage(final File f) {
        final Bitmap bm = Theme.decodeAtLeast(f, a.getResources().getDisplayMetrics().widthPixels);
        if (bm == null) { VeraToast.show(a, "无法打开这张图片"); return; }
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bm);
        box.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout bar = new LinearLayout(a);
        bar.setPadding(dp(16), dp(8), dp(16), dp(12));
        TextView name = BlocksCards.line(a, f.getName(), 14, false);
        bar.addView(name, Blocks.weight(1));
        bar.addView(Blocks.button(a, "设为壁纸", false, new View.OnClickListener() { @Override public void onClick(View v) {
            d.dismiss();
            Theme.setWallpaper(a, f, new Theme.Done() { @Override public void done(String err) {
                if (err == null) a.onWallpaperChanged(h.current());
                VeraToast.show(a, err == null ? "已设为壁纸（主页 / 首页标签显示）" : err);
            } });
        } }));
        View sp = new View(a);
        bar.addView(sp, Blocks.lp(dp(8), 1));
        bar.addView(Blocks.button(a, "关闭", true, new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } }));
        box.addView(bar);
        d.setContentView(box);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() { @Override public void onDismiss(android.content.DialogInterface di) { if (bm != null) bm.recycle(); } });
        Window w = d.getWindow();
        if (w != null) { w.setBackgroundDrawable(new ColorDrawable(Color.WHITE)); w.setWindowAnimations(0); }
        d.show();
    }
}
