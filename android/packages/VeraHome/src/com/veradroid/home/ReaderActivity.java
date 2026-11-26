// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.veradroid.home.Epub.Chapter;
import com.veradroid.home.Epub.Link;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 内置阅读器：TXT/EPUB 按页显示文字，PDF 用系统 PdfRenderer 逐页显示。
 * 左右点按或底部箭头翻页；EPUB 有目录；文字可以调字号；进度记在 App 私有 prefs 里。
 */
public class ReaderActivity extends Activity {
    private String path, ext;
    private TextView title, pageInfo;
    private JustifiedText content;
    private ImageView pdfView;
    private LinearLayout textBox;
    private TextView footLeft;
    private View topBar, bottomBar, aaBtn, bookmarkBox;
    private Icons.IconView bookmarkIcon;
    private TextView pageOverlay;
    private boolean chromeVisible = false;

    private final List<Page> pages = new ArrayList<Page>();
    private final List<Chapter> chapters = new ArrayList<Chapter>();
    private final List<Integer> chapterStart = new ArrayList<Integer>();
    /** 目录（Epub.TocItem：标题、层级、第几个文件的第几个字）和每一条所在的页（分完页才有）。 */
    private final List<Epub.TocItem> toc = new ArrayList<Epub.TocItem>();
    private int[] tocAt = new int[0];
    /** 整本读完、还没分完页时的章节（目录跳转、往后翻页先用它们）；目录缓存里的每章文件名（整本还没读完时跳章用）。 */
    private List<Chapter> preChapters;
    private List<String> tocEntries;
    private int page, textSize = 22;
    private String fontPath, fontName = "系统默认", bookTitle = "";
    private float lineSpacing = 1.35f;
    /** 对齐：0 左对齐、1 居中、2 两端对齐（两端对齐和左对齐断行一样，只是画的时候把每行拉满，见 JustifiedText）。 */
    private int marginDp = 24, align = 0;
    /** 段距（字高的倍数，0 = 没有）、首行缩进（字数）、极简全屏（不显示页脚）、点按方式（0 左边上一页 / 1 全屏下一页）。 */
    private float paraSpacing = 0f;
    private int indent = 0, tapMode = 0;
    /** 点按翻页：0 左边四分之一上一页（Kindle）、1 全屏下一页、2 三栏（左上一页 / 中间菜单 / 右下一页） */
    static final String[] TAP_L = { "左边上一页", "全屏下一页", "三栏" };
    private boolean minimal;
    /** 方块风格（主页设置里选的界面风格）：点屏幕中间出上下两条工具栏，下面六个按钮各开一张设置单。 */
    private boolean blk;
    private View blkTop, blkBottom, openCard, footView;
    private LinearLayout sheetBox;
    private TextView blkTitle, blkClock;
    private View[] toolMarks;
    private View[] toolCells;
    private int sheetOpen = -1;
    private boolean isPdf;
    private volatile PdfRenderer pdf;
    private volatile ParcelFileDescriptor pfd;
    private int pdfPages;
    private Bitmap pdfBitmap;
    private volatile int pdfGen;
    private volatile boolean gone;   // onDestroy 之后：排队的 PDF 活不再做
    private volatile boolean destroyed;
    private static int liveInstances;
    private boolean counted;
    private final java.util.concurrent.ExecutorService pdfWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler();
    private static final long MAX_PAGE_READ_MS = 300_000L;   // 单页最多计 5 分钟，防止亮屏忘关或休眠虚高
    private static final long MAX_PAUSE_TAIL_MS = 120_000L;  // 离开阅读器时末段最多计 2 分钟
    private long readAnchorUp, readAnchorWall;
    private long readActiveMs, readPendingMs;
    private int readPages, readPendingPages;
    /** 分页还没做完时先显示的那一页（从上次的位置直接排出来）：章、起止字符位置。 */
    private Chapter provChapter;
    private int provIndex = -1, provStart, provEnd;
    private boolean firstShown;
    private int availH;
    private String leftBase = "";

    private static class Page { int chapter; int start, end; }   // [start, end) = 这一页在章节文字里的字符范围（文字不另存一份：长篇网文要多占几十 MB）

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        synchronized (ReaderActivity.class) { liveInstances++; }
        counted = true;
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        // 主题是“半透明”的，只为了不要系统先画一张白色的启动窗口（墨水屏上就是先闪一页空白）；窗口本身不透明，
        // 第一页画出来以后再转成普通的全屏 Activity（见 frameShown）
        getWindow().setFormat(android.graphics.PixelFormat.OPAQUE);
        openedAt = SystemClock.uptimeMillis();
        path = getIntent().getStringExtra("path");
        if (path == null) { finish(); return; }
        // 同一个文件的几种写法（/sdcard、/storage/emulated/0）都记在 /data/media/0 下面：进度和书库里的书对得上
        for (String pre : new String[] { "/sdcard/", "/storage/emulated/0/", "/storage/self/primary/" })
            if (path.startsWith(pre)) { path = "/data/media/0/" + path.substring(pre.length()); break; }
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        loadStyle();
        blk = Blocks.on(this);

        // 整页是一层正文，工具栏盖在它上面（点屏幕顶部才出现）：出不出工具栏正文都不重排，墨水屏只刷工具栏那两条。
        FrameLayout pageView = new FrameLayout(this);
        pageRoot = pageView;
        pageView.setBackgroundColor(Color.WHITE);
        if (bgMode != 3) applyBackground();   // 图片背景在加载线程里先读好，和第一页一起出来（见 load）
        setContentView(pageView);
        Perf.measure(pageView, "reader");

        FrameLayout holder = new FrameLayout(this);
        pageView.addView(holder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content = new JustifiedText(this);
        content.setTextSize(textSize);
        content.setTextColor(Color.BLACK);
        Fonts.apply(content, false);
        content.setLineSpacing(0, lineSpacing);
        content.setGravity(Gravity.TOP | Gravity.START);
        applyStyle();
        content.setClickable(true);
        content.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { turnAt(v); }
        });
        holder.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pdfView = new ImageView(this);
        pdfView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pdfView.setVisibility(View.GONE);
        // PDF 时正文 TextView 是 GONE：图片上也要能点按翻页、叫出工具栏
        pdfView.setClickable(true);
        pdfView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { turnAt(v); }
        });
        holder.addView(pdfView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 页脚（一直在）：左边“本章还剩几页”，右边“第几页 / 共几页 · 百分比”，和 Kindle 左下角的阅读进度一个意思
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(dp(marginDp), 0, dp(marginDp), dp(6));
        footLeft = Ui.text(this, "", 12, false);
        footLeft.setSingleLine(true);
        foot.addView(footLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pageOverlay = Ui.text(this, "", 12, false);
        foot.addView(pageOverlay);
        holder.addView(foot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        footView = foot;
        if (minimal) foot.setVisibility(View.INVISIBLE);

        // 顶部工具栏：返回、书名、Aa（字体和版式）、转到（目录 / 书签 / 位置）、书签
        LinearLayout topBox = new LinearLayout(this);
        topBox.setOrientation(LinearLayout.VERTICAL);
        topBox.setBackgroundColor(Color.WHITE);
        topBox.setClickable(true);
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(22), dp(8), dp(6));   // 上面 18dp 是状态栏那一条
        top.addView(Icons.bar(this, Icons.Kind.BACK, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); overridePendingTransition(0, 0); }
        }));
        title = Ui.text(this, dot > 0 ? name.substring(0, dot) : name, 18, true);
        title.setPadding(dp(8), 0, dp(6), 0);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        aaBtn = Icons.bar(this, Icons.Kind.FONT, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { displayDialog(); }
        });
        top.addView(aaBtn);
        top.addView(Icons.bar(this, Icons.Kind.TOC, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { gotoDialog(); }
        }));
        View bm = Icons.bar(this, Icons.Kind.BOOKMARK, 30, new View.OnClickListener() {
            @Override public void onClick(View v) { toggleBookmark(); }
        });
        bookmarkIcon = (Icons.IconView) ((ViewGroup) bm).getChildAt(0);
        bookmarkIcon.setDuplicateParentStateEnabled(false);
        bookmarkBox = bm;
        top.addView(bm);
        topBox.addView(top);
        topBox.addView(Ui.rule(this, 2, Color.BLACK));
        topBar = topBox;
        topBox.setVisibility(View.GONE);
        pageView.addView(topBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 底部工具栏：上一页 / 位置 / 下一页
        LinearLayout bottomBox = new LinearLayout(this);
        bottomBox.setOrientation(LinearLayout.VERTICAL);
        bottomBox.setBackgroundColor(Color.WHITE);
        bottomBox.setClickable(true);
        bottomBox.addView(Ui.rule(this, 2, Color.BLACK));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12), dp(4), dp(12), dp(8));
        bottom.addView(Icons.bar(this, Icons.Kind.BACK, 34, new View.OnClickListener() {
            @Override public void onClick(View v) { prev(); }
        }));
        pageInfo = Ui.text(this, "", 15, false);
        pageInfo.setGravity(Gravity.CENTER);
        bottom.addView(pageInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bottom.addView(Icons.bar(this, Icons.Kind.CHEV_R, 34, new View.OnClickListener() {
            @Override public void onClick(View v) { next(); }
        }));
        bottomBox.addView(bottom);
        bottomBar = bottomBox;
        bottomBox.setVisibility(View.GONE);
        pageView.addView(bottomBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        availH = prefs().getInt(availHKey(), 0);
        content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int hh = b - t;
                if (hh <= 0 || hh == (availH > 0 ? availH : getResources().getDisplayMetrics().heightPixels)) return;
                android.util.Log.i("VeraHome", "reader: text area " + hh + " px, screen " + getResources().getDisplayMetrics().heightPixels);
                availH = hh;
                prefs().edit().putInt(availHKey(), hh).apply();
                if (!pages.isEmpty()) v.post(new Runnable() { @Override public void run() { rebuildKeepPosition(); } });
            }
        });
        if (blk) buildBlocksChrome(pageView, dot > 0 ? name.substring(0, dot) : name);
        // 方块风格：第一页慢（大书、模拟器）时不让上一屏一直停着，最多等 1.5 秒就先出一张“正在打开”的卡片
        holdFirstFrame(pageView, blk ? Math.min(1500, holdMs()) : holdMs());
        load();
    }

    /**
     * 打开书时窗口先不画（最多等 ms 毫秒），等第一页的字排好了再画：墨水屏上只刷一次，直接出字。
     * 以前先画一页空白，整本书分完页（1 秒多）再出字，是两次刷新。
     */
    private long holdUntil;
    private void holdFirstFrame(final View root, long ms) {
        holdUntil = SystemClock.uptimeMillis() + ms;
        ui.postDelayed(new Runnable() { @Override public void run() { firstShown = true; root.invalidate(); } }, ms + 50);
        Perf.whenAttached(root, new Runnable() { @Override public void run() {
            root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (!firstShown && SystemClock.uptimeMillis() < holdUntil) return false;
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    frameShown();
                    return true;
                }
            });
        } });
    }

    /** 最多等第一页多久（毫秒）。调试时可以用属性 debug.vd.readerhold 改（模拟器比真机慢几倍）。 */
    private static int holdMs() {
        try { return (Integer) Class.forName("android.os.SystemProperties").getMethod("getInt", String.class, int.class).invoke(null, "debug.vd.readerhold", 4000); }
        catch (Throwable t) { return 4000; }
    }

    /** 书库正在打开阅读器：桌面暂停时先不放出状态栏，等阅读器第一页一起画（墨水屏上少一次顶边的小刷新）。 */
    static volatile boolean opening;

    private long openedAt;
    private volatile String firstTiming = "";
    private boolean framed;

    /** 第一页要画了：状态栏这时才出来（和第一页同一次刷新），然后转回不透明的全屏 Activity，让桌面正常停下。 */
    private void frameShown() {
        if (framed) return;
        framed = true;
        opening = false;
        android.util.Log.i("VeraPerf", "reader first page ready " + (SystemClock.uptimeMillis() - openedAt) + " ms after onCreate" + (firstShown ? "" : " (timed out)") + firstTiming);
        if (resumedNow) { StatusBarService.releaseAll(); StatusBarService.refresh(this); }
        ui.post(new Runnable() { @Override public void run() {
            try { Activity.class.getMethod("convertFromTranslucent").invoke(ReaderActivity.this); } catch (Throwable ignored) { }
        } });
    }
    private boolean resumedNow;

    /** 分页用的画笔、宽、高：主线程上取好，后台线程只读。 */
    private static final class Geometry {
        android.text.TextPaint tp; int w, h; android.text.Layout.Alignment al; String sig; int indentPx, paraPx;
    }

    private String availHKey() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return "availH_" + dm.widthPixels + "x" + dm.heightPixels;
    }

    private Geometry geometry() {
        Geometry g = new Geometry();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        g.tp = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        g.tp.setTypeface(typeface());
        g.tp.setTextSize(textSize * dm.scaledDensity);
        g.w = Math.max(200, dm.widthPixels - 2 * dp(marginDp));
        // 正文区域高度：隐藏工具栏时正文占满整屏（上边距 dp(34) 给状态栏，下边距 dp(30) 给页脚）。
        // 打开书时正文还没排版，先按屏幕高度算；排版后实际高度不一样才改（见 onCreate 里的监听），前后用的是同一个数。
        int viewH = availH > 0 ? availH : dm.heightPixels;
        g.h = Math.max(240, viewH - dp(34) - dp(30));
        g.al = align == 1 ? android.text.Layout.Alignment.ALIGN_CENTER : android.text.Layout.Alignment.ALIGN_NORMAL;
        g.sig = textSize + "|" + lineSpacing + "|" + marginDp + "|" + align + "|" + fontPath + "|" + g.w + "x" + g.h + (indent > 0 || paraSpacing > 0 ? "|" + indent + "|" + paraSpacing : "");
        g.indentPx = Math.round(indent * textSize * dm.scaledDensity);
        g.paraPx = Math.round(paraSpacing * textSize * dm.scaledDensity);
        return g;
    }

    /**
     * 后台读书。续读的书先只排上次停下的那一页（TXT：读全文后从记下的字符位置排一页；EPUB：只读那一章），
     * 马上显示；整本书再在后台分页，分完后页码对上，屏幕上的字不变。
     */
    private void load() {
        final File f = new File(path);
        final Geometry g = isPdfExt() ? null : geometry();
        final SharedPreferences p = prefs();
        final boolean hasPos = p.contains("c:" + path);
        final int c0 = p.getInt("c:" + path, 0), o0 = p.getInt("o:" + path, 0);
        final String entry0 = p.getString("e:" + path, null);
        final boolean fresh = !p.contains("p:" + path);          // 没打开过：从头排第一页
        final String savedSig = p.getString("st:" + path, ""), savedLeft = p.getString("fl:" + path, "");
        final int savedPg = p.getInt("p:" + path, 0), savedTotal = p.getInt("t:" + path, 0);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (bgMode == 3 && !isPdfExt()) {
                        // 图片背景：在排第一页之前读好（缓存文件几十毫秒），和第一页同一帧出来，不多刷一次
                        loadBgInk();
                        ui.post(new Runnable() { @Override public void run() { applyBackground(); } });
                    }
                    long t0 = SystemClock.uptimeMillis();
                    // 书名书库里已经有了（Intent 带过来），不为它先解析一遍 EPUB 的 OPF
                    String given = getIntent().getStringExtra("title");
                    if (given != null && given.trim().length() > 0) bookTitle = given.trim();
                    else { String bt = Books.info(f).title; if (bt != null && bt.trim().length() > 0) bookTitle = bt; }
                    if (isPdfExt()) {
                        // 打开和第一页都放在 pdfWorker 上做：onDestroy 只等这条线程做完才关文档。
                        // 以前在这条加载线程里渲染，开着大 PDF 马上返回时，文档会在渲染途中被关掉（原生层崩溃）
                        final Bitmap first;
                        try {
                            first = pdfWorker.submit(new java.util.concurrent.Callable<Bitmap>() {
                                @Override public Bitmap call() throws Exception {
                                    if (gone) throw new Exception("已关闭");
                                    openPdf(f);
                                    return renderPdf(page);
                                }
                            }).get();
                        } catch (java.util.concurrent.ExecutionException e) { throw e.getCause(); }
                        ui.post(new Runnable() { @Override public void run() { showPdfFirst(first); } });
                        return;
                    }
                    List<Chapter> all = null;
                    List<Epub.TocItem> bookToc = null;
                    Chapter first = null;
                    int firstIdx = 0, from = 0;
                    if ("epub".equals(ext)) {
                        if (hasPos && entry0 != null) { first = Epub.loadChapter(f, entry0); firstIdx = c0; from = o0; }
                        else if (fresh) {
                            first = Epub.firstChapter(f);
                            if (first == null) { Epub.Book b = loadBook(f); all = b.chapters; bookToc = b.toc; if (!all.isEmpty()) first = all.get(0); }
                        }
                        // 目录缓存：整本读完之前目录就能用
                        final Epub.Cached cached = Epub.readCache(tocCache(f));
                        if (cached != null) ui.post(new Runnable() { @Override public void run() {
                            if (toc.isEmpty()) { toc.addAll(cached.toc); tocEntries = cached.entries; }
                        } });
                    } else {
                        Chapter c = new Chapter();
                        c.title = f.getName();
                        c.text = readText(f);
                        if (c.text.trim().length() == 0) throw new Exception("这是一个空文件");
                        all = new ArrayList<Chapter>();
                        all.add(c);
                        bookToc = Epub.txtToc(c);       // TXT 网文：“第X章”一行一行找出来当目录
                        if (hasPos || fresh) { first = c; from = hasPos ? o0 : 0; }
                    }
                    if (first != null && from >= 0 && from < first.text.length()) {
                        long t1 = SystemClock.uptimeMillis();
                        final Chapter fc = first; final int fi = firstIdx, start = skipBreaks(first.text, from);
                        final int end = pageEnd(first.text, start, g);
                        firstTiming = ": read " + (t1 - t0) + " ms, one page " + (SystemClock.uptimeMillis() - t1) + " ms";
                        // 排版条件和上次存进度时一样：页码、本章剩几页直接用存下的，分完页以后也是这几个数，页脚不会再变
                        final boolean same = g.sig.equals(savedSig) && start == o0 && fi == c0;
                        ui.post(new Runnable() { @Override public void run() {
                            showFirst(fc, fi, start, end, same ? savedPg : -1, same ? savedTotal : 0, same ? savedLeft : "");
                        } });
                    }
                    if (destroyed) return;
                    if (all == null) {
                        Epub.Book b = loadBook(f); all = b.chapters; bookToc = b.toc;
                        if (!b.chapters.isEmpty()) Epub.saveCache(b, tocCache(f));
                    }
                    if (destroyed) return;
                    if (all.isEmpty()) throw new Exception("EPUB 里没有可读的章节");
                    final List<Chapter> chs = all;
                    final List<Epub.TocItem> tc = bookToc;
                    // 分页前：目录换成整本读出来的那份，翻页 / 跳章先用读进来的章节（大书分页要几十秒）
                    ui.post(new Runnable() { @Override public void run() {
                        if (destroyed || isDestroyed()) return;
                        preChapters = chs;
                        provBookReady();
                        if (tc != null) { toc.clear(); toc.addAll(tc); tocEntries = null; }
                    } });
                    final List<Page> pg = new ArrayList<Page>();
                    final List<Integer> starts = new ArrayList<Integer>();
                    long tp0 = SystemClock.uptimeMillis();
                    paginate(chs, g, pg, starts);
                    if (destroyed) return;
                    android.util.Log.i("VeraPerf", "reader whole book: " + pg.size() + " pages in " + (SystemClock.uptimeMillis() - tp0) + " ms (background)");
                    ui.post(new Runnable() { @Override public void run() { if (destroyed || isDestroyed()) return; if (tc != null) { toc.clear(); toc.addAll(tc); } installPages(chs, pg, starts, g.sig); } });
                } catch (Throwable t) {
                    if (destroyed) return;
                    final String msg = String.valueOf(t.getMessage());
                    ui.post(new Runnable() {
                        @Override public void run() {
                            opened();
                            content.setJustify(false);
                            content.setText("打不开这本书：\n\n" + msg);
                            content.setVisibility(View.VISIBLE);
                            firstShown = true;
                        }
                    });
                }
            }
        }, "reader-load").start();
    }

    private boolean isPdfExt() { return "pdf".equals(ext); }

    private static File tocCache(File book) { return new File(Store.cache("readertoc"), Epub.cacheName(book)); }

    private static Epub.Book loadBook(File f) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        Epub.Book b = Epub.load(f);
        android.util.Log.i("VeraPerf", "reader epub: " + b.chapters.size() + " files, toc " + b.toc.size() + " (" + b.tocSource + ")"
            + (b.truncated ? ", truncated" : "") + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
        return b;
    }

    /** 分页之前先显示的那一页。 */
    private void showFirst(Chapter c, int idx, int start, int end, int savedPage, int savedTotal, String left) {
        if (isDestroyed() || !pages.isEmpty()) return;
        opened();
        provChapter = c; provIndex = idx; provStart = start; provEnd = end;
        content.setVisibility(View.VISIBLE);
        pdfView.setVisibility(View.GONE);
        setPageText(styled(c.text, start, end), c.text, start, end);
        if (bookTitle.length() > 0) title.setText(bookTitle);
        if (savedPage >= 0 && savedTotal > 0) {
            int pg = Math.min(savedPage, savedTotal - 1);
            String right = (pg + 1) + " / " + savedTotal + " · " + Math.min(100, (pg + 1) * 100 / savedTotal) + "%";
            footLeft.setText(left);
            pageOverlay.setText(right);
            pageInfo.setText(right);
            firstShown = true;
        } else {
            // 页码还不知道（第一次打开、或者改过字号）：分页多半很快就完，稍等一下让页码和字一起出来；等不到就先出字
            ui.postDelayed(new Runnable() { @Override public void run() { firstShown = true; content.invalidate(); } }, 300);
        }
    }

    /** 整本书分好页了：换上真正的页表，找到正显示的那一页。字没变就只对一下页码（通常和已经显示的一样，屏幕不动）。 */
    private void installPages(List<Chapter> chs, List<Page> pg, List<Integer> starts, String sig) {
        if (isDestroyed()) return;
        opened();
        chapters.clear(); chapters.addAll(chs);
        pages.clear(); pages.addAll(pg);
        chapterStart.clear(); chapterStart.addAll(starts);
        isPdf = false;
        mapToc();
        if (pages.isEmpty()) { firstShown = true; return; }
        if (provIndex >= 0) page = pageAt(provIndex, provStart);
        else page = savedTextPage();
        boolean sameText = provIndex >= 0 && pages.get(page).chapter == provIndex && pages.get(page).start == provStart
            && pageText(pages.get(page)).equals(String.valueOf(content.getText()));
        provChapter = null; provIndex = -1;
        if (!sig.equals(geometry().sig)) { rebuildKeepPosition(); firstShown = true; return; }   // 分页时改了字号等
        if (sameText) { paintProgress(); savePage(); }
        else showText();
        firstShown = true;
    }

    // ------------------------------------------------------------------ turning

    private void noteReadTurn(int deltaPages) {
        long nowUp = SystemClock.uptimeMillis();
        long nowWall = System.currentTimeMillis();
        boolean sleeping = new File(HostLink.CTL + "sleep").exists();
        if (!sleeping && readAnchorUp > 0) {
            long dt = Math.min(nowUp - readAnchorUp, nowWall - readAnchorWall);
            if (dt > 0) {
                if (dt > MAX_PAGE_READ_MS) dt = MAX_PAGE_READ_MS;
                readActiveMs += dt;
                readPendingMs += dt;
            }
        }
        readAnchorUp = sleeping ? 0 : nowUp;
        readAnchorWall = sleeping ? 0 : nowWall;
        if (deltaPages > 0) {
            readPages += deltaPages;
            readPendingPages += deltaPages;
        }
        if (readPendingMs >= 60_000L || readPendingPages >= 5) {
            Stats.addReading(this, readPendingMs, readPendingPages, Stats.BUILTIN_PKG);
            readPendingMs = 0;
            readPendingPages = 0;
        }
    }

    private void prev() {
        turned(-1);
        if (isPdf) {
            if (page > 0) { page--; noteReadTurn(1); showPdf(page); }
        } else if (pages.isEmpty() && provChapter != null) {
            provPrev();
        } else if (page > 0) { page--; noteReadTurn(1); showText(); }
    }

    /**
     * 整本还没分完页时往回翻（以前这里什么也不做：大书打开后十几秒到几十秒里点左边没反应）。
     * 本章里有上一页：按整本分页的切法从章首排到这一页，取前一页；在章首：整本已读进来就翻到上一章最后一页，
     * 还没读进来就记下这一下，读进来马上翻（provPending）。
     */
    private void provPrev() {
        final Geometry g = geometry();
        ReaderTurn.Cut cut = new ReaderTurn.Cut() { @Override public int end(String s, int from) { return pageEnd(s, from, g); } };
        int s = ReaderTurn.prevStart(provChapter.text, provStart, cut);
        if (s >= 0) { showProv(provChapter, provIndex, s); return; }
        if (provIndex <= 0) return;                          // 全书第一页
        if (preChapters != null && provIndex - 1 < preChapters.size()) {
            Chapter c = preChapters.get(provIndex - 1);
            showProv(c, provIndex - 1, Math.max(0, ReaderTurn.prevStart(c.text, Integer.MAX_VALUE, cut)));
            return;
        }
        waitForBook(-1);
    }

    /** 整本还没读进来、这一下要跨章：记下方向，读进来（load 里设 preChapters）时补上，并提示一句。 */
    private int provPending;
    private void waitForBook(int dir) {
        provPending = dir;
        VeraToast.show(this, "还在读这本书，读好就翻");
    }
    /** load 读完整本（preChapters 设好）后调：补上等着的那一下翻页。 */
    private void provBookReady() {
        int d = provPending;
        provPending = 0;
        if (d == 0 || !pages.isEmpty() || provChapter == null) return;
        if (d > 0) next(); else prev();
    }

    private void next() {
        turned(1);
        if (isPdf) {
            if (page < pdfPages - 1) { page++; noteReadTurn(1); showPdf(page); }
        } else if (pages.isEmpty() && provChapter != null && !ReaderTurn.chapterHasMore(provChapter.text, provEnd)) {
            // 还没分完页、这一章翻完了：整本已经读进来就接着排下一章的第一页；还没读进来就等它（以前这里什么也不做：章末点了没反应）
            if (preChapters == null) waitForBook(1);
            else if (provIndex >= 0 && provIndex + 1 < preChapters.size()) showProv(preChapters.get(provIndex + 1), provIndex + 1, 0);
        } else if (pages.isEmpty() && provChapter != null) {
            // 整本还没分完页：从这一页的末尾接着排下一页（页码等分完再显示）
            Geometry g = geometry();
            int start = skipBreaks(provChapter.text, provEnd);
            provStart = start; provEnd = pageEnd(provChapter.text, start, g);
            noteReadTurn(1);
            setPageText(styled(provChapter.text, provStart, provEnd), provChapter.text, provStart, provEnd);
            footLeft.setText(""); pageOverlay.setText(""); pageInfo.setText("");
            savePage();
        } else if (page < pages.size() - 1) { page++; noteReadTurn(1); showText(); }
    }

    /**
     * 点按区域和 Kindle 一样：屏幕最上面一条 = 叫出 / 收起工具栏；左边四分之一 = 上一页；其余 = 下一页。
     * 工具栏开着时点正文任何地方都是收起它。
     */
    private void turnAt(View v) {
        float w = v.getWidth(), h = v.getHeight();
        if (chromeVisible) { toggleChrome(); return; }
        if (v == content && lastX >= 0 && followLinkAt()) return;
        if (lastX < 0 || w <= 0) { next(); return; }
        // 方块风格：点屏幕中间一块叫出工具栏（顶上一条也算）
        if (blk && lastX > w / 3f && lastX < w * 2 / 3f && lastY > h / 4f && lastY < h * 3 / 4f) { toggleChrome(); return; }
        if (tapMode == 1 && lastY >= h * 0.14f) { next(); return; }
        // 三栏：左三分之一上一页、中间一栏叫出 / 收起工具栏、右三分之一下一页
        if (tapMode == 2) { if (lastX < w / 3f) prev(); else if (lastX > w * 2 / 3f) next(); else toggleChrome(); return; }
        if (lastY < h * 0.14f) toggleChrome();
        else if (lastX < w / 4f) prev();
        else next();
    }
    private float lastX = -1, lastY = -1;
    private boolean swiped;
    /** 左右划翻页：往左划下一页，往右划上一页；抬手时翻一次，不跟手。 */
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        Perf.touch(e);
        if (notePop != null) return super.dispatchTouchEvent(e);   // 注释框开着：点哪里都是框自己的事（收起 / 全文），不翻页、不划页
        int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop() * 4;
        switch (e.getActionMasked()) {
        case android.view.MotionEvent.ACTION_DOWN: lastX = e.getX(); lastY = e.getY(); swiped = false; break;
        case android.view.MotionEvent.ACTION_MOVE:
            if (!swiped && !chromeVisible && Math.abs(e.getX() - lastX) > slop && Math.abs(e.getX() - lastX) > 2 * Math.abs(e.getY() - lastY)) {
                swiped = true;
                android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                c.setAction(android.view.MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c);
                c.recycle();
            }
            if (swiped) return true;
            break;
        case android.view.MotionEvent.ACTION_UP:
            if (swiped) { if (e.getX() < lastX) next(); else prev(); swiped = false; return true; }
            // 拖动过的手指不算点按。底边上划“返回”（宿主手势，veradroid-input）在认出手势之前会先把按下交给 Android，
            // 认出来以后用“抬起”收回这次触摸——Android 看到的是一次往上拖的点按，以前正文就先翻一页再退出。
            // 竖着拖、斜着拖都不翻页；真正的点按抖动远小于这个距离。
            if (!chromeVisible && (Math.abs(e.getX() - lastX) > slop / 2 || Math.abs(e.getY() - lastY) > slop / 2)) {
                android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                c.setAction(android.view.MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(c);
                c.recycle();
                return true;
            }
            break;
        case android.view.MotionEvent.ACTION_CANCEL: if (swiped) { swiped = false; return true; } break;
        }
        return super.dispatchTouchEvent(e);
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        int v = chromeVisible ? View.VISIBLE : View.GONE;
        if (blk) {
            if (chromeVisible) paintBlocksTop(); else openSheet(-1);
            blkTop.setVisibility(v);
            blkBottom.setVisibility(v);
            return;
        }
        if (chromeVisible) paintBookmark();
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
    }

    /** 页脚和底栏的文字：第几页 / 共几页 · 百分比；本章还剩几页（读了几页以后按这次的速度估个时间）。 */
    private void paintProgress() {
        int total = isPdf ? pdfPages : pages.size();
        if (total <= 0) return;
        int pct = Math.min(100, (page + 1) * 100 / total);
        String right = (page + 1) + " / " + total + " · " + pct + "%";
        String left = "";
        leftBase = "";
        if (!isPdf && !pages.isEmpty() && (chapterStart.size() > 1 || tocAt.length > 1)) {
            int ch = pages.get(Math.max(0, Math.min(pages.size() - 1, page))).chapter;
            int end = ch + 1 < chapterStart.size() ? chapterStart.get(ch + 1) : pages.size();
            if (tocAt.length > 1) {
                // 按目录算“本章”：一个文件里好几章、或者一章拆成几个文件（Calibre 的 index_split_*）都对
                end = pages.size();
                for (int at : tocAt) if (at > page && at < end) end = at;
            }
            int remain = Math.max(0, end - page - 1);
            left = remain == 0 ? "本章最后一页" : "本章还剩 " + remain + " 页";
            leftBase = left;
            long spent = readActiveMs;
            if (remain > 0 && readPages >= 5 && spent > 0) {
                long mins = Math.round(remain * (spent / (double) readPages) / 60000.0);
                if (mins >= 1 && mins < 600) left += "，约 " + mins + " 分钟";
            }
        }
        if (!left.equals(String.valueOf(footLeft.getText()))) footLeft.setText(left);
        if (!right.equals(String.valueOf(pageOverlay.getText()))) pageOverlay.setText(right);
        if (!right.equals(String.valueOf(pageInfo.getText()))) pageInfo.setText(right);
        if (chromeVisible) paintBookmark();
    }

    // ------------------------------------------------------------------ bookmarks / go to

    /** 书签存在 reader 偏好的 bm:<路径> 里：文字书是“章:字符位置”，PDF 是页码，分号隔开。 */
    private List<String> bookmarks() {
        List<String> out = new ArrayList<String>();
        for (String x : prefs().getString("bm:" + path, "").split(";")) if (x.length() > 0) out.add(x);
        return out;
    }

    private String bookmarkKey() {
        if (isPdf) return String.valueOf(page);
        if (pages.isEmpty()) {
            if (provChapter != null && provIndex >= 0) return provIndex + ":" + provStart;
            return null;
        }
        Page p = pages.get(Math.max(0, Math.min(pages.size() - 1, page)));
        return p.chapter + ":" + p.start;
    }

    private int bookmarkPage(String key) {
        try {
            if (isPdf) return Math.max(0, Math.min(pdfPages - 1, Integer.parseInt(key)));
            String[] f = key.split(":");
            return pageAt(Integer.parseInt(f[0]), Integer.parseInt(f[1]));
        } catch (Throwable t) { return -1; }
    }

    private boolean isBookmarkHere(String key) {
        if (isPdf) return bookmarkPage(key) == page;
        if (pages.isEmpty()) {
            if (provChapter == null || provIndex < 0) return false;
            try {
                String[] f = key.split(":");
                int ch = Integer.parseInt(f[0]), off = Integer.parseInt(f[1]);
                return ch == provIndex && off >= provStart && off < Math.max(provStart + 1, provEnd);
            } catch (Throwable t) { return false; }
        }
        return bookmarkPage(key) == page;
    }

    private boolean bookmarkedHere() {
        for (String k : bookmarks()) if (isBookmarkHere(k)) return true;
        return false;
    }

    /** 这一页有书签时图标画成黑底白线。 */
    private void paintBookmark() {
        boolean on = bookmarkedHere();
        bookmarkIcon.setInverted(on);
        if (on) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Color.BLACK);
            g.setCornerRadius(dp(8));
            bookmarkBox.setBackground(g);
        } else bookmarkBox.setBackground(null);
    }

    private void toggleBookmark() {
        String key = bookmarkKey();
        if (key == null) return;
        List<String> l = bookmarks();
        boolean had = false;
        for (java.util.Iterator<String> it = l.iterator(); it.hasNext(); ) if (isBookmarkHere(it.next())) { it.remove(); had = true; }
        if (!had) l.add(key);
        StringBuilder sb = new StringBuilder();
        for (String k : l) { if (sb.length() > 0) sb.append(';'); sb.append(k); }
        prefs().edit().putString("bm:" + path, sb.toString()).apply();
        paintBookmark();
    }

    /**
     * 跳到第 to 页（目录、书签、百分比、页码）。和书内链接一样记下原来那一页，左下角出“‹ 返回第 N 页”；
     * 几秒内接着跳（百分比步进器一步一步点）只记最早那一页。
     */
    private void jump(int to) {
        int total = isPdf ? pdfPages : pages.size();
        if (total <= 0) return;
        int from = page;
        page = Math.max(0, Math.min(total - 1, to));
        long now = android.os.SystemClock.uptimeMillis();
        boolean chain = now - lastJumpAt < 5000 && from == lastJumpTo;
        if (page != from && !chain) pushBack(from);
        lastJumpAt = now; lastJumpTo = page;
        if (chromeVisible) toggleChrome();
        if (isPdf) showPdf(page); else showText();
        paintBackChip();
    }
    private long lastJumpAt;
    private int lastJumpTo = -1;
    /** 跳过去以后又自己翻了几页：这么多页以后“返回”收起（返回键也不再先回原处，而是照常退出）。 */
    private static final int BACK_KEEP_TURNS = 5;
    private int turnsSinceJump;

    private void pushBack(int from) {
        backStack.add(from);
        while (backStack.size() > 10) backStack.remove(0);
        turnsSinceJump = 0;
    }

    /**
     * 显示桥的“按页数全刷”（KFB_FLASH，设置里的“全刷周期”）：宿主按前台的包开关数页（appprof.sh：桌面 com.veradroid.home
     * 在前台时发 FLIPCOUNT 0，桌面换页不算翻书）。阅读器也在桌面这个包里，所以以前在自带阅读器里翻多少页都不全刷，
     * 深色 / 反色页面上残影越积越重（2026-11-21 用户反馈；测试机的 epd 日志每次翻页都是“flip not counted (FLIPCOUNT 0)”）。
     * 阅读器在前台时自己打开数页：进来时、每翻一页（宿主切回桌面时那条 FLIPCOUNT 0 可能晚到）；离开时关掉。
     */
    private void flipCount(boolean on) { HostLink.epd(on ? "FLIPCOUNT 1" : "FLIPCOUNT 0"); }

    /**
     * 翻页动画（模式里的开关 = 宿主的 KFB_ANIM_MODE，全局）。显示桥只在“变化面积超过 KFB_ANIM_MIN（45%）”时才让控制器滑动；
     * 阅读器的一页字只有字形变了，行距大的页、章尾半页、标题页常常不到这个比例，所以开关打开也看不出区别（PW5 用户反馈“没效果”）。
     * 阅读器知道这一下就是翻页：开着动画时直接请显示桥把下一次变化滑进来（SWIPE 一次性，方向按翻页方向，步数用 KFB_ANIM_STEPS）。
     * 关着、或者宿主说这台机器不用硬件滑动（KFB_SWIPE=0，Kindle 2024）时什么都不发。
     */
    private void slideTurn(int dir) {
        java.util.Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");
        int d = ReaderTurn.slideDir(HostLink.num(cfg, "KFB_ANIM_MODE", 0), "0".equals(cfg.get("KFB_SWIPE")), dir);
        if (d < 0) return;
        HostLink.epd("SWIPE " + d + " " + Math.max(1, Math.min(30, HostLink.num(cfg, "KFB_ANIM_STEPS", 12))));
    }

    /** 每翻一页调一次（dir 1 下一页 / -1 上一页）：跳转后读了 BACK_KEEP_TURNS 页，“返回”就收起。 */
    private void turned(int dir) {
        flipCount(true);
        slideTurn(dir);
        if (backStack.isEmpty()) return;
        if (++turnsSinceJump >= BACK_KEEP_TURNS) { backStack.clear(); paintBackChip(); }
    }

    /** “转到”：目录、书签、按位置跳。 */
    private void gotoDialog() {
        final int total = isPdf ? pdfPages : pages.size();
        if (total <= 0) return;
        final List<String> items = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        if (!isPdf && toc.size() > 1) { items.add("目录（" + toc.size() + " 条）"); acts.add(new Runnable() { @Override public void run() { showToc(); } }); }
        final List<String> bms = bookmarks();
        if (!bms.isEmpty()) { items.add("书签（" + bms.size() + " 个）"); acts.add(new Runnable() { @Override public void run() { bookmarkDialog(); } }); }
        items.add("开头"); acts.add(new Runnable() { @Override public void run() { jump(0); } });
        for (final int pc : new int[] { 10, 25, 50, 75, 90 }) {
            items.add("跳到 " + pc + "%"); acts.add(new Runnable() { @Override public void run() { jump(total * pc / 100); } });
        }
        new VeraDialog.Builder(this).title("转到").items(items.toArray(new String[0]), new VeraDialog.OnPick() {
            @Override public void onPick(int which) { acts.get(which).run(); }
        }).negative("取消", null).show();
    }

    private void bookmarkDialog() {
        final List<String> bms = bookmarks();
        final List<Integer> at = new ArrayList<Integer>();
        List<String> names = new ArrayList<String>();
        int total = isPdf ? pdfPages : pages.size();
        for (String k : bms) {
            int pg = bookmarkPage(k);
            if (pg < 0) continue;
            String label = "第 " + (pg + 1) + " 页 · " + ((pg + 1) * 100 / Math.max(1, total)) + "%";
            if (!isPdf && pg < pages.size()) {
                String t = pageText(pages.get(pg)).replace('\n', ' ').trim();
                label += "  " + (t.length() > 18 ? t.substring(0, 18) + "…" : t);
            }
            names.add(label);
            at.add(pg);
        }
        new VeraDialog.Builder(this).title("书签").items(names.toArray(new String[0]), new VeraDialog.OnPick() {
            @Override public void onPick(int which) { jump(at.get(which)); }
        }).negative("取消", null).show();
    }

    private void showText() {
        if (pages.isEmpty()) return;
        Page p = pages.get(Math.max(0, Math.min(pages.size() - 1, page)));
        applyStyle();
        String ch = chapters.get(p.chapter).text;
        setPageText(indent > 0 || paraSpacing > 0 ? styled(ch, p.start, p.end) : pageText(p), ch, p.start, p.end);
        paintProgress();
        if (blk && chromeVisible) paintBlocksTop();
        if (bookTitle.length() > 0 && !bookTitle.equals(String.valueOf(title.getText()))) title.setText(bookTitle);
        savePage();
    }

    // ------------------------------------------------------------------ 书内链接：注释弹出来看，目录类链接跳过去（带“返回”）

    private Chapter shownChap;
    private int shownStart;
    private final List<Link> shownLinks = new ArrayList<Link>();
    private final List<Integer> backStack = new ArrayList<Integer>();
    private TextView backChip;

    /** 这一页上的链接交给正文控件画虚下划线、做点按判断。 */
    private void markLinks(String chapter, int start, int len) {
        Chapter c = null;
        if (provChapter != null && provChapter.text == chapter) c = provChapter;
        else for (Chapter x : chapters) if (x.text == chapter) { c = x; break; }
        shownChap = c; shownStart = start;
        shownLinks.clear();
        if (c == null || c.links.isEmpty()) { content.setLinks(null); return; }
        List<Integer> r = new ArrayList<Integer>();
        for (Link k : c.links) {
            if (k.end <= start || k.start >= start + len) continue;
            shownLinks.add(k);
            r.add(Math.max(0, k.start - start)); r.add(Math.min(len, k.end - start));
        }
        int[] a = new int[r.size()];
        for (int i = 0; i < a.length; i++) a[i] = r.get(i);
        content.setLinks(a);
    }

    private boolean followLinkAt() {
        if (isPdf || shownLinks.isEmpty()) return false;
        int[] loc = new int[2];
        content.getLocationInWindow(loc);
        int i = content.linkAt(lastX - loc[0], lastY - loc[1], dp(16));
        if (i < 0 || i >= shownLinks.size()) return false;
        // 注释号那一行的上下边（窗口坐标）：注释框贴着这一行放，不切半行字
        noteLineTop = noteLineBot = -1;
        android.text.Layout l = content.getLayout();
        if (l != null) {
            int off = Math.max(0, Math.min(l.getText().length(), shownLinks.get(i).start - shownStart));
            int line = l.getLineForOffset(off), y0 = loc[1] + content.getExtendedPaddingTop();
            noteLineTop = y0 + l.getLineTop(line); noteLineBot = y0 + l.getLineBottom(line);
        }
        follow(shownLinks.get(i));
        return true;
    }

    private void follow(final Link k) {
        int hash = k.target.indexOf('#');
        final String entry = hash >= 0 ? k.target.substring(0, hash) : k.target;
        final String frag = hash >= 0 ? k.target.substring(hash + 1) : "";
        final String label = shownChap == null ? "" : shownChap.text.substring(Math.max(0, k.start), Math.min(shownChap.text.length(), k.end)).trim();
        int ci = -1;
        Chapter ch = null;
        for (int i = 0; i < chapters.size(); i++) if (entry.equals(chapters.get(i).entry)) { ci = i; ch = chapters.get(i); break; }
        if (ch == null && provChapter != null && entry.equals(provChapter.entry)) ch = provChapter;
        if (ch != null) {
            // 是注释就弹出注释的字；不是注释（目录、回链），或者取不出字，第一下就直接跳过去（不再弹一个只有“跳到注释处”的框）
            Epub.Note n = Epub.note(k, label, ch, frag);
            int[] info = frag.length() > 0 ? ch.ids.get(frag) : null;
            int off = info == null ? 0 : info[0];
            if (n != null) showNote(n, ci, off);
            else jumpTo(ci, off);
            return;
        }
        // 注释文件不在正文顺序里（或者整本还没读完）：单独读那一个文件，只弹出注释
        final File f = new File(path);
        new Thread(new Runnable() { @Override public void run() {
            Chapter c = null;
            try { c = Epub.loadChapter(f, entry); } catch (Throwable ignored) { }
            final Epub.Note n = Epub.note(k, label, c, frag);
            final boolean found = c != null;
            ui.post(new Runnable() { @Override public void run() {
                if (isDestroyed()) return;
                if (n != null) { showNote(n, -1, 0); return; }
                VeraToast.show(ReaderActivity.this, found ? "这个链接指向的内容不在正文里" : "这个链接指向的内容不在书里");
            } });
        } }, "reader-note").start();
    }

    // ------------------------------------------------------------------ 注释弹框（微信读书那样：点注释号，旁边出一个小框直接是注释的字）
    //
    // 不用对话框：对话框是另一个窗口，有暗化层、淡入淡出，墨水屏上按 DU 局部刷，框里的灰字和底下的正文叠在一起（KPW5 用户的视频）。
    // 现在是正文窗口里的一层：白底、黑框、黑字，没有阴影 / 透明 / 动画。出来前一刻和收起前一刻各给显示桥发一行
    // “H R x y w h”（display/epd.c：下一帧这一块每个像素用 REAGL 重刷），这一块整个刷干净，不留底下那页的残影。

    private FrameLayout notePop;
    private int noteLineTop = -1, noteLineBot = -1;
    private final int[] noteRect = new int[4];

    private void showNote(final Epub.Note n, final int ci, final int off) { showNote(n, ci, off, false); }

    private void showNote(final Epub.Note n, final int ci, final int off, final boolean tall) {
        closeNote(false);
        FrameLayout scrim = new FrameLayout(this);     // 整屏透明（画面不变，不刷屏）：点框外面收起，不翻页
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeNote(true); } });
        int W = pageRoot.getWidth() > 0 ? pageRoot.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int H = pageRoot.getHeight() > 0 ? pageRoot.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int side = dp(8), pad = dp(14), st = Math.max(2, dp(2)), gap = dp(30);   // 框比正文左右各宽出一点：盖住的行两头不露半个字
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke(st, Color.BLACK);
        box.setBackground(bg);
        box.setPadding(pad + st, pad + st, pad + st, pad + st);
        box.setClickable(true);   // 点框里也收起
        box.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { closeNote(true); } });
        TextView tv = new TextView(this);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Math.max(15, textSize - 3));
        Fonts.apply(tv, false);
        tv.setTextColor(Color.BLACK);
        tv.setLineSpacing(0, Math.min(lineSpacing, 1.4f));
        tv.setIncludeFontPadding(true);
        // 框放在注释号那一行的下面（注释号在下半屏就放上面），最多占半屏；放不下就截断，出“全文”
        boolean below = lastY < 0 || lastY < H / 2f;
        // 框的上边 / 下边：注释号那一行的下面 / 上面（量不到行就按点的位置留一行的距离）
        int y = below ? (noteLineBot >= 0 ? noteLineBot + dp(4) : lastY < 0 ? H / 3 : (int) lastY + gap)
                      : (noteLineTop >= 0 ? noteLineTop - dp(4) : (int) lastY - gap);
        int room = tall ? H - 2 * dp(40) : below ? H - y - dp(12) : y - dp(12);
        room = Math.max(room, H / 4);
        if (!tall) room = Math.min(room, H / 2);
        int textW = W - 2 * side - 2 * (pad + st);
        int lineH = tv.getLineHeight();
        int btnH = dp(40);
        int maxLines = Math.max(2, (room - 2 * (pad + st)) / Math.max(1, lineH));
        android.text.StaticLayout sl = new android.text.StaticLayout(n.text, tv.getPaint(), Math.max(1, textW),
            android.text.Layout.Alignment.ALIGN_NORMAL, Math.min(lineSpacing, 1.4f), 0, true);
        boolean cut = sl.getLineCount() > maxLines;
        if (cut || n.more) maxLines = Math.max(2, (room - 2 * (pad + st) - btnH) / Math.max(1, lineH));
        cut = cut || n.more;
        if (sl.getLineCount() > maxLines) {   // 截在第 maxLines 行末尾，加“…”
            int end = sl.getLineEnd(maxLines - 1);
            tv.setText(n.text.substring(0, Math.max(0, Math.min(n.text.length(), end - 1))).trim() + "…");
        } else tv.setText(n.text);
        box.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (cut && (ci >= 0 && !pages.isEmpty() || !tall)) {
            TextView all = Ui.text(this, "全文 ›", 15, true);
            all.setTextColor(Color.BLACK);
            all.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            all.setPadding(dp(12), 0, 0, 0);
            all.setClickable(true);
            all.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                // 注释在正文里：跳过去看（带“‹ 返回第 N 页”）；不在正文里（单独的注释文件）：框拉高到差不多整屏
                if (ci >= 0 && !pages.isEmpty()) { closeNote(true); jumpTo(ci, off); }
                else { closeNote(true); showNote(n, ci, off, true); }
            } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, btnH);
            lp.gravity = Gravity.END;
            box.addView(all, lp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            tall ? Gravity.CENTER_VERTICAL : below ? Gravity.TOP : Gravity.BOTTOM);
        lp.leftMargin = side; lp.rightMargin = side;
        if (!tall) { if (below) lp.topMargin = y; else lp.bottomMargin = H - y; }
        scrim.addView(box, lp);
        notePop = scrim;
        final View fbox = box;
        // 第一帧画出去之前：量出框在屏幕上的位置，告诉显示桥这一块用闪刷
        box.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                fbox.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] loc = new int[2];
                fbox.getLocationOnScreen(loc);
                int m = dp(4);
                noteRect[0] = Math.max(0, loc[0] - m); noteRect[1] = Math.max(0, loc[1] - m);
                noteRect[2] = fbox.getWidth() + 2 * m; noteRect[3] = fbox.getHeight() + 2 * m;
                epdClean();
                return true;
            }
        });
        pageRoot.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 下一帧里注释框那一块整块重刷干净（display/epd.c 的 H 提示，500 ms 内的下一帧有效；没有显示桥就什么也不发生）。
     * 用 R（REAGL，FULL：这一块每个像素都重新驱动，16 级灰，不闪）：H 的 F 是整屏闪刷（force_full），每点一次注释整屏黑一下太重；
     * 桥自己翻页清残影用的也是 REAGL。4 位波形的 i.MX 机型上桥换成 GL16 FULL。
     */
    private void epdClean() {
        if (noteRect[2] <= 0 || noteRect[3] <= 0) return;
        HostLink.epd("H R " + noteRect[0] + " " + noteRect[1] + " " + noteRect[2] + " " + noteRect[3]);
    }

    /** 收起注释框。clean：框那一块再闪刷一次（框下面的正文露出来时不留框的残影）。 */
    private boolean closeNote(boolean clean) {
        if (notePop == null) return false;
        if (clean) epdClean();
        pageRoot.removeView(notePop);
        notePop = null;
        return true;
    }

    /** 跳到书里另一处，记下现在这一页：左下角出一个“返回”，点了回来。 */
    private void jumpTo(int ci, int off) {
        if (ci < 0) return;
        if (pages.isEmpty()) { VeraToast.show(this, "还在分页，稍等一下再点"); return; }
        int to = pageAt(ci, off);
        if (to == page) return;
        pushBack(page);
        page = to;
        noteReadTurn(1);
        showText();
        paintBackChip();
    }

    /**
     * 翻页器 / 按键翻页（蓝牙翻页器经宿主 vdbtkeys 映射成 音量± / PageUp·Down / 方向键，见 BT_PT_MAP；宿主的轻点手势也可以发音量-）：
     * 以前内置阅读器不认这些键，翻页器在这里没用。下一页：音量-、PageDown、→、↓、空格；上一页：音量+、PageUp、←、↑。
     * 音量键整个吃掉（不弹系统音量条）。按住不放的自动重复不算（墨水屏上连翻几页看不清）。
     */
    private static int keyDir(int code) {
        switch (code) {
        case android.view.KeyEvent.KEYCODE_VOLUME_DOWN: case android.view.KeyEvent.KEYCODE_PAGE_DOWN: case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
        case android.view.KeyEvent.KEYCODE_DPAD_DOWN: case android.view.KeyEvent.KEYCODE_SPACE: case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
            return 1;
        case android.view.KeyEvent.KEYCODE_VOLUME_UP: case android.view.KeyEvent.KEYCODE_PAGE_UP: case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
        case android.view.KeyEvent.KEYCODE_DPAD_UP: case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            return -1;
        }
        return 0;
    }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        int d = keyDir(keyCode);
        if (d == 0) return super.onKeyDown(keyCode, event);
        if (notePop != null) { if (event.getRepeatCount() == 0) closeNote(true); return true; }   // 翻页键先收起注释框
        if (event.getRepeatCount() == 0) {
            if (chromeVisible) toggleChrome();
            if (d > 0) next(); else prev();
        }
        return true;
    }

    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        return keyDir(keyCode) != 0 || super.onKeyUp(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (closeNote(true)) return;
        if (!backStack.isEmpty() && !chromeVisible) { goBack(); return; }
        super.onBackPressed();
    }

    private void goBack() {
        if (backStack.isEmpty()) return;
        int total = isPdf ? pdfPages : pages.size();
        page = Math.max(0, Math.min(Math.max(0, total - 1), backStack.remove(backStack.size() - 1)));
        turnsSinceJump = 0;
        lastJumpTo = -1;
        if (chromeVisible) toggleChrome();
        if (isPdf) showPdf(page); else showText();
        paintBackChip();
    }

    private void paintBackChip() {
        if (backChip == null) {
            // 放在页脚正中（左右是“本章还剩”和页码，中间空着），不盖正文的最后一行
            backChip = Ui.text(this, "‹ 返回", 14, true);
            backChip.setTextColor(Color.BLACK);
            backChip.setBackground(Blocks.plain(this));
            int st = Blocks.stroke(this);
            backChip.setPadding(dp(14) + st, dp(3) + st, dp(14) + st, dp(3) + st);
            backChip.setClickable(true);
            backChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { goBack(); } });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = dp(2);
            ((FrameLayout) content.getParent()).addView(backChip, lp);
        }
        int vis = backStack.isEmpty() ? View.GONE : View.VISIBLE;
        if (!backStack.isEmpty()) {
            String t = "‹ 返回第 " + (backStack.get(backStack.size() - 1) + 1) + " 页";
            if (!t.contentEquals(backChip.getText())) backChip.setText(t);
        }
        if (backChip.getVisibility() != vis) backChip.setVisibility(vis);
    }

    /** 正文换成这一页；两端对齐时要知道这一页最后一行后面这一段是不是还没完（没完的话最后一行也拉满）。 */
    private void setPageText(CharSequence t, String chapter, int start, int end) {
        int i = start + trimEnd(chapter.substring(start, Math.min(chapter.length(), Math.max(start, end)))).length();
        while (i < chapter.length() && (chapter.charAt(i) == ' ' || chapter.charAt(i) == '\t')) i++;
        content.setContinues(i < chapter.length() && chapter.charAt(i) != '\n' && chapter.charAt(i) != '\r');
        markLinks(chapter, start, t.length());
        content.setText(t);
    }

    private void setTextSize(int v) {
        int n = Math.max(16, Math.min(34, v));
        if (n == textSize) return;
        // PDF、打不开的书、还在加载的书都没有分页：只记下字号。以前这里会 chapterStart.get(-1)，
        // 整个桌面进程（状态栏、导航栏、传书）跟着一起崩。
        if (isPdf || pages.isEmpty() || chapterStart.isEmpty()) { textSize = n; saveStyle(); return; }
        textSize = n;
        saveStyle();
        rebuildKeepPosition();
    }

    /** 章节 + 字符位置所在的那一页（重新分页后也是同一段文字）。 */
    private int pageAt(int chapter, int offset) {
        if (pages.isEmpty() || chapterStart.isEmpty()) return 0;
        chapter = Math.max(0, Math.min(chapterStart.size() - 1, chapter));
        int from = chapterStart.get(chapter);
        int to = chapter + 1 < chapterStart.size() ? chapterStart.get(chapter + 1) : pages.size();
        int best = Math.min(from, pages.size() - 1);
        for (int i = from; i < to; i++) { if (pages.get(i).start <= offset) best = i; else break; }
        return Math.max(0, Math.min(pages.size() - 1, best));
    }

    /** 在主线程上把整本重新分页（改了字号 / 行距 / 边距 / 字体时）。 */
    private void buildPages() {
        List<Page> pg = new ArrayList<Page>();
        List<Integer> starts = new ArrayList<Integer>();
        paginate(chapters, geometry(), pg, starts);
        pages.clear(); pages.addAll(pg);
        chapterStart.clear(); chapterStart.addAll(starts);
        mapToc();
    }

    /** 跳过页首的空行（每页都不以空行开头）。 */
    private static int skipBreaks(String s, int i) { return ReaderTurn.skipBreaks(s, i); }

    /**
     * 从 i 开始一页放得下的文字到哪里为止：用和正文 TextView 相同的画笔、宽度、行距排一遍，取放得下的整行。
     * 以前按“每页多少字”估算，不算换行：短段落 / 对话多的书每页末尾几行被裁掉。
     */
    private int pageEnd(String s, int i, Geometry g) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float px = textSize * dm.scaledDensity;
        int perLine = Math.max(6, (int) (g.w / (px * fontWidth())));
        int lines = Math.max(3, (int) (g.h / (px * lineSpacing)));
        int cpp = Math.max(220, Math.min(1400, perLine * lines));      // 只用来决定每次取多长一段去排版
        int win = cpp * 2;
        int end;
        while (true) {
            int stop = Math.min(s.length(), i + win);
            CharSequence src = s; int from = i, to = stop;
            if (g.indentPx > 0 || g.paraPx > 0) { src = spans(s, i, stop, g.indentPx, g.paraPx); from = 0; to = src.length(); }
            android.text.StaticLayout sl = android.text.StaticLayout.Builder.obtain(src, from, to, g.tp, g.w)
                .setAlignment(g.al).setLineSpacing(0f, lineSpacing).setIncludePad(true)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE).build();
            int fit = 0;                                         // 完整放得下的行数
            int lc = sl.getLineCount();
            while (fit < lc && sl.getLineBottom(fit) <= g.h) fit++;
            if (fit >= lc && stop < s.length() && win < 20000) { win *= 2; continue; }   // 这一段全放下了还有余：取长一点再排
            if (fit <= 0) fit = 1;                               // 一行都放不下（字号极大）也要前进
            int le = sl.getLineEnd(fit - 1);
            if (sl.getLineStart(0) != i) le += i;                  // 行偏移是相对这一段的还是相对全文的，按第一行的起点判断
            end = fit >= lc ? stop : le;
            break;
        }
        if (end <= i) end = Math.min(s.length(), i + 1);
        return end;
    }

    /** 整本分页（后台线程也能调：只读 chs 和 g）。 */
    private void paginate(List<Chapter> chs, Geometry g, List<Page> out, List<Integer> starts) {
        for (int ci = 0; ci < chs.size(); ci++) {
            if (destroyed) return;
            String s = chs.get(ci).text;
            int i = 0;
            starts.add(out.size());
            while (i < s.length()) {
                if (destroyed) return;
                i = skipBreaks(s, i);
                if (i >= s.length()) break;
                int end = pageEnd(s, i, g);
                Page p = new Page();
                p.chapter = ci;
                p.start = i;
                int e = end;
                while (e > i && (s.charAt(e - 1) == '\n' || s.charAt(e - 1) == '\r' || s.charAt(e - 1) == ' ')) e--;   // 和以前的 trimEnd 一样
                p.end = e;
                if (e > i) out.add(p);
                i = end;
            }
        }
    }

    private static String trimEnd(String t) {
        int e = t.length();
        while (e > 0 && (t.charAt(e - 1) == '\n' || t.charAt(e - 1) == '\r' || t.charAt(e - 1) == ' ')) e--;
        return t.substring(0, e);
    }

    private String pageText(Page p) { return chapters.get(p.chapter).text.substring(p.start, p.end); }

    /**
     * 目录每一条在第几页（分完页 / 重新分页以后算一次）。目录是空的（老书、TXT 没找到“第X章”）就按文件一个一条，和以前一样。
     */
    private void mapToc() {
        if (toc.isEmpty() && chapters.size() > 1)
            for (int i = 0; i < chapters.size(); i++) toc.add(new Epub.TocItem(String.valueOf(chapters.get(i).title), 0, i, 0));
        int[] at = new int[toc.size()];
        for (int i = 0; i < at.length; i++) at[i] = pageAt(toc.get(i).chapter, toc.get(i).offset);
        tocAt = at.length > 1 ? at : new int[0];
    }

    /** 现在这一页属于目录的哪一条（最后一个不在这一页后面的条目）；没有目录 -1。 */
    private int curToc() {
        int ch, end;
        if (!pages.isEmpty()) { Page p = pages.get(Math.max(0, Math.min(pages.size() - 1, page))); ch = p.chapter; end = p.end; }
        else if (provIndex >= 0) { ch = provIndex; end = provEnd; }     // 还没分完页：按先显示的那一页
        else return -1;
        int best = -1;
        for (int i = 0; i < toc.size(); i++) {
            Epub.TocItem t = toc.get(i);
            if (t.chapter < ch || t.chapter == ch && t.offset < end) {
                if (best < 0) best = i;
                else { Epub.TocItem b = toc.get(best); if (t.chapter > b.chapter || t.chapter == b.chapter && t.offset >= b.offset) best = i; }
            }
        }
        return best;
    }

    /**
     * 跳到目录第 i 条。分完页了按页跳；还没分完：整本已经读进来就直接排那一章的那一页，
     * 没读完就按目录缓存里的文件名单独读那一个文件（大书第一次打开以外，目录一打开就能用）。
     */
    private void gotoToc(int i) {
        if (i < 0 || i >= toc.size()) return;
        final Epub.TocItem t = toc.get(i);
        if (!pages.isEmpty()) { jump(pageAt(t.chapter, t.offset)); return; }
        if (chromeVisible) toggleChrome();
        if (preChapters != null && t.chapter < preChapters.size()) { showProv(preChapters.get(t.chapter), t.chapter, t.offset); return; }
        if (tocEntries == null || t.chapter >= tocEntries.size() || !"epub".equals(ext)) { VeraToast.show(this, "还在读这本书，稍等一下再点"); return; }
        final String entry = tocEntries.get(t.chapter);
        final File f = new File(path);
        new Thread(new Runnable() { @Override public void run() {
            Chapter c = null;
            try { c = Epub.loadChapter(f, entry); } catch (Throwable ignored) { }
            final Chapter fc = c;
            ui.post(new Runnable() { @Override public void run() {
                if (isDestroyed()) return;
                if (fc == null) { VeraToast.show(ReaderActivity.this, "这一章读不出来"); return; }
                showProv(fc, t.chapter, t.offset);
            } });
        } }, "reader-tocjump").start();
    }

    /** 还没分完页时显示某一章的某个位置（分完页后 installPages 按 provIndex / provStart 找回这一页）。 */
    private void showProv(Chapter c, int idx, int off) {
        if (!pages.isEmpty()) { jump(pageAt(idx, off)); return; }
        int start = skipBreaks(c.text, Math.max(0, Math.min(off, c.text.length())));
        if (start >= c.text.length()) start = skipBreaks(c.text, 0);
        if (start >= c.text.length()) return;
        int end = pageEnd(c.text, start, geometry());
        provChapter = c; provIndex = idx; provStart = start; provEnd = end;
        noteReadTurn(1);
        applyStyle();
        content.setVisibility(View.VISIBLE);
        setPageText(styled(c.text, start, end), c.text, start, end);
        footLeft.setText(""); pageOverlay.setText(""); pageInfo.setText("");
        savePage();
    }

    /** 目录条目显示的字：分卷下面的章缩进两格。 */
    private String tocLabel(int i) {
        Epub.TocItem t = toc.get(i);
        if (t.depth <= 0) return t.title;
        StringBuilder b = new StringBuilder();
        for (int d = 0; d < Math.min(4, t.depth); d++) b.append("\u3000");
        return b.append(t.title).toString();
    }

    /**
     * 经典风格的目录：一页几条（按屏幕高算），‹ › 或左右 / 上下划翻页，点中间的页码输入第几条；打开时翻到现在读的这一章。
     * 以前是一个对话框里一条一个按钮全部列出来：一千多章的网文要建一千多个控件、在墨水屏上一屏屏往下翻。
     */
    private int tocDlgPage;
    private void showToc() {
        if (toc.size() <= 1) return;
        final int cur = curToc();
        final int per = Math.max(5, Math.min(14, (int) ((getResources().getDisplayMetrics().heightPixels * 0.62f) / dp(50))));
        final int pagesN = (toc.size() + per - 1) / per;
        tocDlgPage = cur >= 0 ? cur / per : 0;
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        final PagedBox rows = new PagedBox(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, per * dp(50)));
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        final TextView pageNo = Ui.text(this, "", 16, true);
        pageNo.setGravity(Gravity.CENTER);
        final Dialog[] dlg = new Dialog[1];
        final Runnable[] fill = new Runnable[1];
        fill[0] = new Runnable() { @Override public void run() {
            tocDlgPage = Math.max(0, Math.min(pagesN - 1, tocDlgPage));
            rows.removeAllViews();
            for (int n = tocDlgPage * per; n < Math.min(toc.size(), tocDlgPage * per + per); n++) {
                final int to = n;
                TextView t = Ui.text(ReaderActivity.this, tocLabel(n), 17, n == cur);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setPadding(dp(12), 0, dp(12), 0);
                if (n == cur) { t.setBackgroundColor(Color.BLACK); t.setTextColor(Color.WHITE); }
                t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (dlg[0] != null) dlg[0].dismiss(); gotoToc(to); } });
                rows.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            }
            pageNo.setText((tocDlgPage + 1) + " / " + pagesN);
        } };
        rows.setListener(new PagedBox.Listener() { @Override public void onPage(int dir) { tocDlgPage += dir; fill[0].run(); } });
        nav.addView(Ui.button(this, "‹", 18, false, new View.OnClickListener() { @Override public void onClick(View v) { tocDlgPage--; fill[0].run(); } }));
        nav.addView(pageNo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(Ui.button(this, "›", 18, false, new View.OnClickListener() { @Override public void onClick(View v) { tocDlgPage++; fill[0].run(); } }));
        pageNo.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            tocNumberInput(new OnTocNumber() { @Override public void go(int n) { tocDlgPage = n / per; fill[0].run(); } });
        } });
        body.addView(nav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fill[0].run();
        dlg[0] = new VeraDialog.Builder(this).title("目录（" + toc.size() + " 条）").view(body).negative("关闭", null).show();
    }

    private interface OnTocNumber { void go(int index); }

    /** 输入第几条目录（1 起），翻到那一条所在的目录页。 */
    private void tocNumberInput(final OnTocNumber cb) {
        final android.widget.EditText in = Ui.editText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setSingleLine(true);
        in.setTextSize(22);
        in.setPadding(dp(12), dp(10), dp(12), dp(10));
        new VeraDialog.Builder(this).title("翻到第几条").message("1 – " + toc.size()).view(in).negative("取消", null)
            .positive("翻过去", new Runnable() { @Override public void run() {
                try { cb.go(Math.max(0, Math.min(toc.size() - 1, Integer.parseInt(in.getText().toString().trim()) - 1))); } catch (Throwable ignored) { }
            } }).show();
    }

    // ------------------------------------------------------------------ pdf

    private void openPdf(File f) throws Exception {
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        pdf = new PdfRenderer(pfd);
        pdfPages = pdf.getPageCount();
        page = Math.max(0, Math.min(pdfPages - 1, savedPage()));
    }

    /** 后台线程渲染一页 PDF（按页面比例缩到屏幕大小）。 */
    private Bitmap renderPdf(int index) {
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        PdfRenderer.Page p = pdf.openPage(index);
        try {
            float sc = Math.min(w / (float) p.getWidth(), h / (float) p.getHeight());
            Bitmap bm = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * sc)), Math.max(1, Math.round(p.getHeight() * sc)), Bitmap.Config.ARGB_8888);
            bm.eraseColor(Color.WHITE);
            p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bm;
        } finally { p.close(); }
    }

    /** PDF 第一页：图和页码一起出来。 */
    private void showPdfFirst(Bitmap bm) {
        if (isDestroyed()) { bm.recycle(); return; }
        opened();
        isPdf = true;
        content.setVisibility(View.GONE);
        pdfView.setVisibility(View.VISIBLE);
        aaBtn.setVisibility(View.GONE);
        if (toolCells != null && toolCells.length > 2) {
            toolCells[1].setVisibility(View.GONE);
            toolCells[2].setVisibility(View.GONE);
        }
        if (bookTitle.length() > 0) title.setText(bookTitle);
        pdfBitmap = bm;
        pdfView.setImageBitmap(bm);
        paintProgress();
        savePage();
        firstShown = true;
    }

    private void showPdf(final int index) {
        if (pdf == null) return;
        isPdf = true;
        paintProgress();
        final int gen = ++pdfGen;
        // 一个线程按顺序渲染：PdfRenderer 同时只能开一页，以前连点两下两个线程同时 openPage，第二个报“渲染失败”。
        pdfWorker.execute(new Runnable() {
            @Override public void run() {
                if (gen != pdfGen) return;          // 已经又翻走了：这一页不用渲染
                try {
                    // 按页面原来的宽高比缩放（以前直接画满屏幕，不是 3:4 的页面被拉伸）
                    final Bitmap bm = renderPdf(index);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (gen != pdfGen || isDestroyed()) { bm.recycle(); return; }
                            Bitmap old = pdfBitmap;
                            pdfBitmap = bm;
                            pdfView.setImageBitmap(bm);
                            if (old != null && old != bm && !old.isRecycled()) old.recycle();
                        }
                    });
                } catch (Throwable t) {
                    final String msg = String.valueOf(t.getMessage());
                    ui.post(new Runnable() { @Override public void run() { VeraToast.show(ReaderActivity.this, "PDF 渲染失败：" + msg); } });
                }
            }
        });
        savePage();
    }

    // ------------------------------------------------------------------ text extraction

    private static String readText(File f) throws Exception {
        long len = f.length();
        if (len > 8 * 1024 * 1024) throw new Exception("文件太大（超过 8 MB）");
        byte[] b = new byte[(int) len];
        FileInputStream in = new FileInputStream(f);
        int n = 0;
        try {
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
        return Epub.decodeText(b, n).replace("\r\n", "\n").replace('\r', '\n');
    }


    // ------------------------------------------------------------------ progress

    private SharedPreferences prefs() { return Store.prefs(this, "reader"); }

    private int savedPage() {
        try { return prefs().getInt("p:" + path, 0); } catch (Throwable t) { return 0; }
    }

    /**
     * 文字书的续读位置：按“章节 + 字符位置”存（c: / o:），不按页码。字号是所有书共用的，以前在一本书里改了字号，
     * 别的书按旧页码续读就偏到别处去了。没有新记录的老进度才退回用页码。
     */
    private int savedTextPage() {
        try {
            SharedPreferences p = prefs();
            if (p.contains("c:" + path)) return pageAt(p.getInt("c:" + path, 0), p.getInt("o:" + path, 0));
        } catch (Throwable ignored) { }
        return Math.max(0, Math.min(pages.size() - 1, savedPage()));
    }

    private void savePage() {
        try {
            int total = isPdf ? pdfPages : pages.size();
            if (total <= 0) {
                // 整本还没分完页（先显示的那一页）：只记位置，不动页码和总页数
                if (!isPdf && provIndex >= 0) {
                    SharedPreferences.Editor e = prefs().edit().putInt("c:" + path, provIndex).putInt("o:" + path, provStart)
                        .putLong("r:" + path, System.currentTimeMillis());
                    // 从目录跳到了别的章：续读时先读的那个文件也要跟着改（以前这里只在同一章里往后翻，文件名不会变）
                    if (provChapter != null && provChapter.entry != null) e.putString("e:" + path, provChapter.entry);
                    e.apply();
                }
                return;
            }
            SharedPreferences.Editor e = prefs().edit().putInt("p:" + path, page).putInt("t:" + path, total)
                 .putInt("s:" + path, textSize);       // p: / t: 还给图书馆算百分比
            e.putLong("r:" + path, System.currentTimeMillis());   // 上次阅读时间：书库“最近”排序用
            if (!isPdf && !pages.isEmpty()) {
                Page cur = pages.get(Math.max(0, Math.min(pages.size() - 1, page)));
                e.putInt("c:" + path, cur.chapter).putInt("o:" + path, cur.start);
                // 下次打开时先只排这一页用：EPUB 这一章的文件名、排版条件、页脚左边那句
                if (cur.chapter < chapters.size() && chapters.get(cur.chapter).entry != null) e.putString("e:" + path, chapters.get(cur.chapter).entry);
                int ti = curToc();
                if (ti >= 0) e.putString("ch:" + path, toc.get(ti).title);   // 方块主页“继续阅读”显示的章名
                else if (chapters.size() > 1 && cur.chapter < chapters.size()) e.putString("ch:" + path, String.valueOf(chapters.get(cur.chapter).title));
                e.putString("st:" + path, geometry().sig).putString("fl:" + path, leftBase);
            }
            e.apply();   // 翻页时不在主线程同步写盘
        } catch (Throwable ignored) { }
    }

    @Override protected void onResume() {
        super.onResume();
        flipCount(true);
        readAnchorUp = SystemClock.uptimeMillis();
        readAnchorWall = System.currentTimeMillis();
        readActiveMs = 0;
        readPendingMs = 0;
        readPages = 0;
        readPendingPages = 0;
        resumedNow = true;
        Stats.sampleBattery(this);
        if (framed) { StatusBarService.setSuppressed(false); StatusBarService.refresh(this); }
    }

    @Override protected void onPause() {
        resumedNow = false;
        opening = false;
        flipCount(false);
        boolean sleeping = new File(HostLink.CTL + "sleep").exists();
        if (!sleeping && readAnchorUp > 0) {
            long dt = Math.min(SystemClock.uptimeMillis() - readAnchorUp, System.currentTimeMillis() - readAnchorWall);
            if (dt > 0) {
                if (dt > MAX_PAUSE_TAIL_MS) dt = MAX_PAUSE_TAIL_MS;
                readPendingMs += dt;
            }
        }
        if (readPendingMs > 0 || readPendingPages > 0) {
            Stats.addReading(this, readPendingMs, readPendingPages, Stats.BUILTIN_PKG);
        }
        readAnchorUp = 0;
        readAnchorWall = 0;
        readPendingMs = 0;
        readPendingPages = 0;
        savePage();
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        opening = false;
        pdfGen++;
        gone = true;
        ui.removeCallbacksAndMessages(null);
        // 关文档也排在 PDF 线程上：等正在渲染的那一页画完再关。以前这里最多等 2 秒就关，大 PDF 一页要画 4 秒多，
        // close() 因为页面还开着失败，文档留给 finalize 去关；finalize 又要等全局的 PDF 锁，超过 10 秒整个桌面进程被杀（实测连开 4 次就崩）。
        try {
            pdfWorker.execute(new Runnable() {
                @Override public void run() {
                    try { if (pdf != null) pdf.close(); } catch (Throwable ignored) { }
                    try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
        pdfWorker.shutdown();
        if (pdfView != null) pdfView.setImageDrawable(null);
        if (pdfBitmap != null && !pdfBitmap.isRecycled()) { pdfBitmap.recycle(); pdfBitmap = null; }
        if (pageRoot != null) pageRoot.setBackground(null);
        if (counted) {
            counted = false;
            synchronized (ReaderActivity.class) {
                if (liveInstances > 0) liveInstances--;
                if (liveInstances == 0) releaseBgCaches();
            }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ display / fonts

    private void loadStyle() {
        try {
            SharedPreferences p = prefs();
            SharedPreferences home = Store.prefs(this, "home");
            textSize = p.getInt("size", 22);
            fontPath = p.getString("font", home.getString("reader_font_path", null));
            fontName = p.getString("fontName", home.getString("reader_font_name", "系统默认"));
            lineSpacing = p.getFloat("line", 1.35f);
            marginDp = p.getInt("margin", 24);
            align = Math.max(0, Math.min(2, p.getInt("align", 0)));
            paraSpacing = p.getFloat("para", 0f);
            indent = p.getInt("indent", 0);
            minimal = p.getBoolean("minimal", false);
            tapMode = p.getInt("tapmode", 0);
            bgMode = Math.max(0, Math.min(BG_L.length - 1, p.getInt("bg", 0)));
            bgImg = p.getString("bgimg", null);
        } catch (Throwable ignored) { }
    }

    private void saveStyle() {
        try {
            prefs().edit().putInt("size", textSize).putString("font", fontPath).putString("fontName", fontName)
                .putFloat("line", lineSpacing).putInt("margin", marginDp).putInt("align", align)
                .putFloat("para", paraSpacing).putInt("indent", indent).putBoolean("minimal", minimal).putInt("tapmode", tapMode)
                .putInt("bg", bgMode).putString("bgimg", bgImg).commit();
            Store.prefs(this, "home").edit().putString("reader_font_path", fontPath)
                .putString("reader_font_name", fontName).commit();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ 阅读背景：纯白 / 浅灰 / 纸张纹理 / 自己的图片

    static final String[] BG_L = { "纯白", "浅灰", "纸张", "图片" };
    /** 图片背景最深处的墨量（255 = 原图）：压到 22% 以内（网点最密 4 / 16），黑字的对比度不受影响，墨水屏上只是一层淡淡的底纹。 */
    private static final int BG_KEEP = 56;
    private int bgMode;
    private String bgImg;
    private FrameLayout pageRoot;
    /** 处理好的背景图（屏幕大小的 8 位墨量图），几个阅读器窗口共用；换了图 / 屏幕大小才重做。 */
    private static Bitmap bgInk;
    private static String bgInkKey = "";

    private String bgKey(File f, int w, int h) { return f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length() + "|" + w + "x" + h + "|" + BG_KEEP + "|dots"; }

    /** 后台线程：图片背景的墨量图（内存里有就用，没有读缓存文件，再没有才解码原图）。 */
    private Bitmap loadBgInk() {
        if (bgImg == null) return null;
        File f = new File(bgImg);
        if (!f.isFile()) return null;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        String key = bgKey(f, w, h);
        synchronized (ReaderActivity.class) {
            if (bgInk != null && !bgInk.isRecycled() && key.equals(bgInkKey)) return bgInk;
            File dir = Store.cache("readerbg");
            File cf = new File(dir, Integer.toHexString(key.hashCode()) + ".a8");
            if (!cf.isFile()) { File[] old = dir.listFiles(); if (old != null) for (File o : old) o.delete(); }
            Bitmap b = Theme.inkImage(f, w, h, BG_KEEP, cf);
            if (b == null) return null;
            bgInk = b; bgInkKey = key;   // 旧的那张可能还画在别的阅读器窗口上：不 recycle，交给 GC
            return b;
        }
    }

    /**
     * 纹理都用纯黑的小点（网点）画：墨水屏的“对比度”设置会把很淡的灰拉成白（真机上对比度 2.2 时 #EAEAEA 整个变白），纯黑点不受影响。
     * 浅灰 = 4×4 里 2 个点（12%）；纸张 = 随机的稀疏颗粒（约 3%）。都是平铺的小块，翻页时画起来和纯色一样快。
     */
    private static Bitmap greyTile, paperTile;
    private static synchronized Bitmap greyTile() {
        if (greyTile != null) return greyTile;
        byte[] px = new byte[16];
        for (int i = 0; i < 16; i++) px[i] = (byte) (Theme.BAYER[i] < 2 ? 255 : 0);
        Bitmap b = Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8);
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
        return greyTile = b;
    }
    private static synchronized Bitmap paperTile() {
        if (paperTile != null && !paperTile.isRecycled()) return paperTile;
        int n = 256;
        byte[] px = new byte[n * n];
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < px.length; i++) if (r.nextInt(1000) < 22) px[i] = (byte) 255;
        // 几处两三个点连在一起的小颗粒（纸浆里的杂质），不画横竖的直线：平铺以后直线会排成看得出的格子
        for (int k = 0; k < 90; k++) {
            int x = r.nextInt(n), y = r.nextInt(n);
            px[y * n + x] = (byte) 255;
            px[((y + r.nextInt(2)) % n) * n + (x + 1) % n] = (byte) 255;
        }
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8);
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px));
        return paperTile = b;
    }

    /**
     * 阅读背景：平铺的网点第一次画的时候铺成一张屏幕大小的 8 位墨量图（软件渲染下平铺着画每次要 30 ms 左右），
     * 之后和图片背景一样：白底上按墨量贴一张 8 位图（测试机实测 10–15 ms；合成成 RGB_565 再贴反而要 20 ms，要做格式转换）。
     * 铺好的图几个阅读器窗口共用，换了背景 / 屏幕大小才重做。
     */
    private static Bitmap spread, spreadFrom;
    private static int spreadW, spreadH;

    private static synchronized Bitmap spread(Bitmap tile, int w, int h) {
        if (spread != null && !spread.isRecycled() && spreadFrom == tile && spreadW == w && spreadH == h) return spread;
        int tw = tile.getWidth(), th = tile.getHeight();
        byte[] t = new byte[tile.getRowBytes() * th];
        tile.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(t));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        int stride = b.getRowBytes(), trb = tile.getRowBytes();
        byte[] out = new byte[stride * h];
        for (int y = 0; y < h; y++) {
            int src = (y % th) * trb, dst = y * stride;
            for (int x = 0; x < w; x += tw) System.arraycopy(t, src, out, dst + x, Math.min(tw, w - x));
        }
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(out));
        spread = b; spreadFrom = tile; spreadW = w; spreadH = h;
        return b;
    }

    /** 最后一个阅读器实例退出时释放整屏背景位图缓存，避免在 512MB 设备上常驻占用内存。 */
    private static synchronized void releaseBgCaches() {
        if (bgInk != null) {
            if (!bgInk.isRecycled()) bgInk.recycle();
            bgInk = null;
            bgInkKey = "";
        }
        if (spread != null) {
            if (!spread.isRecycled()) spread.recycle();
            spread = null;
            spreadFrom = null;
            spreadW = 0;
            spreadH = 0;
        }
        if (paperTile != null) {
            if (!paperTile.isRecycled()) paperTile.recycle();
            paperTile = null;
        }
    }

    private static final class BgDrawable extends android.graphics.drawable.Drawable {
        private final Bitmap ink; private final boolean tile;
        private final android.graphics.Paint p = new android.graphics.Paint();
        private int logged;
        BgDrawable(Bitmap ink, boolean tile) { this.ink = ink; this.tile = tile; p.setColor(Color.BLACK); }
        @Override public void draw(android.graphics.Canvas c) {
            long t0 = logged < 3 ? System.nanoTime() : 0;
            android.graphics.Rect r = getBounds();
            c.drawColor(Color.WHITE);
            if (ink.isRecycled() || r.width() <= 0 || r.height() <= 0) return;
            Bitmap b = tile ? spread(ink, r.width(), r.height()) : ink;
            if (b == null || b.isRecycled()) return;
            if (r.width() == b.getWidth() && r.height() == b.getHeight()) c.drawBitmap(b, r.left, r.top, p);
            else c.drawBitmap(b, null, r, p);
            // 前几次记一下画背景用了多久（翻页时多出来的就是这一点）
            if (logged < 3) { logged++; android.util.Log.i("VeraPerf", "reader bg " + (tile ? "tile" : "image") + " draw " + (System.nanoTime() - t0) / 1000 + " us"); }
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /** 按 bgMode 换背景。图片背景还没处理好时先白底，后台做好再贴（只有第一次选图时会这样）。 */
    private void applyBackground() {
        if (pageRoot == null) return;
        // 有背景图时整页都由 pageRoot 画（不透明），窗口自己的白底不用再涂一遍（每翻一页省一次整屏填充）
        getWindow().setBackgroundDrawable(bgMode == 0 ? new android.graphics.drawable.ColorDrawable(Color.WHITE) : null);
        if (bgMode == 1) pageRoot.setBackground(new BgDrawable(greyTile(), true));
        else if (bgMode == 2) pageRoot.setBackground(new BgDrawable(paperTile(), true));
        else if (bgMode == 3) {
            Bitmap b = bgInk;
            File f = bgImg == null ? null : new File(bgImg);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            if (b != null && !b.isRecycled() && f != null && bgKey(f, dm.widthPixels, dm.heightPixels).equals(bgInkKey)) { pageRoot.setBackground(new BgDrawable(b, false)); return; }
            pageRoot.setBackgroundColor(Color.WHITE);
            new Thread(new Runnable() { @Override public void run() {
                final Bitmap nb = loadBgInk();
                ui.post(new Runnable() { @Override public void run() {
                    if (isDestroyed() || destroyed || bgMode != 3) return;
                    if (nb == null) { VeraToast.show(ReaderActivity.this, "背景图片打不开，先用白底"); return; }
                    if (nb.isRecycled()) return;
                    pageRoot.setBackground(new BgDrawable(nb, false));
                } });
            } }, "reader-bg").start();
        } else pageRoot.setBackgroundColor(Color.WHITE);
    }

    private void setBg(int mode) {
        if (mode == 3 && (bgImg == null || !new File(bgImg).isFile())) { pickBgImage(); return; }
        bgMode = mode; saveStyle(); applyBackground();
    }

    /** 选背景图：和桌面壁纸同一批文件夹（VeraDroid/wallpaper、Pictures、Download、Kindle 的 documents……）。 */
    private void pickBgImage() {
        new Thread(new Runnable() { @Override public void run() {
            final List<File> imgs = Theme.findImages();
            ui.post(new Runnable() { @Override public void run() {
                if (isDestroyed()) return;
                if (imgs.isEmpty()) {
                    new VeraDialog.Builder(ReaderActivity.this).title("阅读背景")
                        .message("没有找到图片。\n\n用传书网页上传，或者把 jpg / png 放进内部存储的 VeraDroid/wallpaper 文件夹、Kindle 的 documents 文件夹，再来选。")
                        .positive("知道了", null).show();
                    return;
                }
                String[] names = new String[imgs.size()];
                for (int i = 0; i < names.length; i++) names[i] = imgs.get(i).getName() + "\n" + Theme.placeLabel(imgs.get(i)) + " · " + Math.max(1, imgs.get(i).length() / 1024) + " KB";
                new VeraDialog.Builder(ReaderActivity.this).title("选择背景图片").items(names, new VeraDialog.OnPick() {
                    @Override public void onPick(int which) {
                        bgImg = imgs.get(which).getAbsolutePath();
                        bgMode = 3; saveStyle();
                        VeraToast.show(ReaderActivity.this, "正在处理背景图片…");
                        applyBackground();
                        if (sheetOpen == 3) openSheet(3);
                    }
                }).negative("取消", new Runnable() { @Override public void run() { if (sheetOpen == 3) openSheet(3); } }).show();
            } });
        } }, "reader-bg-pick").start();
    }

    private void applyStyle() {
        if (content == null) return;
        content.setTypeface(typeface());
        content.setTextSize(textSize);
        content.setLineSpacing(0, lineSpacing);
        content.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);          // 和 buildPages 的排版保持一致
        content.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        content.setIncludeFontPadding(true);
        int m = dp(marginDp);
        content.setPadding(m, dp(34), m, dp(30));   // 下面 30dp 留给页脚
        content.setGravity(align == 1 ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : (Gravity.TOP | Gravity.START));
        content.setJustify(align == 2);
    }

    // 每翻一页都会走到这里：字体文件（18 MB 的霞鹜文楷）只解析一次
    private String cachedFontPath;
    private Typeface cachedTypeface;
    private Typeface typeface() {
        if (fontPath != null && fontPath.length() > 0) {
            if (fontPath.equals(cachedFontPath) && cachedTypeface != null) return cachedTypeface;
            try {
                cachedTypeface = Typeface.createFromFile(fontPath);
                cachedFontPath = fontPath;
                return cachedTypeface;
            } catch (Throwable ignored) { }
        }
        return Typeface.DEFAULT;
    }

    private float fontWidth() {
        String n = fontName == null ? "" : fontName.toLowerCase(Locale.US);
        if (n.contains("bookerly") || n.contains("baskerville") || n.contains("英文")) return 0.58f;
        if (n.contains("等宽") || n.contains("mono")) return 0.62f;
        return 0.96f;
    }

    /**
     * 改了字号 / 行距 / 边距 / 字体、正文区域高度变了：整本重新分页，停在同一段文字。
     * 以前在主线程上整本重排（buildPages）：大 TXT 在 512 MB 的 PW5 上要十几秒，触摸 5 秒没回应就是 ANR
     * （用户诊断：5 次“Input dispatching timed out”，主线程都在 rebuildKeepPosition → paginate → StaticLayout）。
     * 现在和打开书时一样：先用新排版只排当前这一页马上显示（showFirst 的临时页，可以往后翻），整本在后台线程分完再换上
     * 页表（installPages 按字符位置找回这一页；期间又改了排版，它发现 sig 不对会再来一次）。rebuildGen 丢掉过时的结果。
     */
    private int rebuildGen;
    private void rebuildKeepPosition() {
        if (pages.isEmpty()) {
            applyStyle();
            if (provChapter != null && provStart >= 0 && provStart < provChapter.text.length()) {
                Geometry g = geometry();
                provEnd = pageEnd(provChapter.text, provStart, g);
                setPageText(styled(provChapter.text, provStart, provEnd), provChapter.text, provStart, provEnd);
            }
            return;
        }
        Page cur = pages.get(Math.max(0, Math.min(pages.size() - 1, page)));
        final int chapter = cur.chapter, offset = cur.start;       // 按字符位置：换了字号 / 行距 / 边距还停在同一段文字
        final List<Chapter> chs = new ArrayList<Chapter>(chapters);
        final Geometry g = geometry();
        if (chapter < 0 || chapter >= chs.size()) { buildPages(); page = pageAt(chapter, offset); showText(); return; }
        Chapter c = chs.get(chapter);
        int start = skipBreaks(c.text, Math.min(offset, c.text.length()));
        int end = start < c.text.length() ? pageEnd(c.text, start, g) : c.text.length();
        pages.clear(); chapterStart.clear();
        applyStyle();
        showFirst(c, chapter, start, end, -1, 0, "");
        footLeft.setText(""); pageOverlay.setText(""); pageInfo.setText("");
        final int gen = ++rebuildGen;
        new Thread(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                final List<Page> pg = new ArrayList<Page>();
                final List<Integer> starts = new ArrayList<Integer>();
                long t0 = SystemClock.uptimeMillis();
                try { paginate(chs, g, pg, starts); } catch (Throwable t) { android.util.Log.w("VeraHome", "reader: repaginate failed", t); }
                if (destroyed) return;
                android.util.Log.i("VeraPerf", "reader repaginate: " + pg.size() + " pages in " + (SystemClock.uptimeMillis() - t0) + " ms (background)");
                ui.post(new Runnable() { @Override public void run() {
                    if (destroyed || gen != rebuildGen || isDestroyed() || !pages.isEmpty()) return;
                    installPages(chs, pg, starts, g.sig);
                } });
            }
        }, "reader-repaginate").start();
    }

    private void displayDialog() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        PageScrollView sv = new PageScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(20));
        box.setBackgroundColor(Color.WHITE);
        sv.addView(box);

        TextView h = Ui.text(this, "排版与字体", 24, true);
        box.addView(h);
        box.addView(Ui.rule(this, 1, Color.BLACK));

        // 字体单独一层（点了出列表）：字体多的时候，整张单子不用再上下翻
        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.setPadding(0, dp(6), 0, dp(2));
        fontRow.addView(Ui.text(this, "字体", 17, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        fontRow.addView(Ui.button(this, fontName + "  ›", 15, false, new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); fontList(); }
        }));
        box.addView(fontRow);

        box.addView(Ui.text(this, "字号", 17, true));
        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView sizeVal = Ui.text(this, String.valueOf(textSize), 18, true);
        sizeVal.setGravity(Gravity.CENTER);
        sizeRow.addView(Ui.button(this, "A-", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { setTextSize(textSize - 2); sizeVal.setText(String.valueOf(textSize)); }
        }));
        sizeRow.addView(sizeVal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sizeRow.addView(Ui.button(this, "A+", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { setTextSize(textSize + 2); sizeVal.setText(String.valueOf(textSize)); }
        }));
        box.addView(sizeRow);

        box.addView(Ui.text(this, "行距", 17, true));
        LinearLayout lineRow = new LinearLayout(this);
        lineRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] lineNames = { "紧凑", "标准", "宽松", "很松" };
        float[] lineVals = { 1.2f, 1.4f, 1.6f, 1.8f };
        for (int i = 0; i < lineNames.length; i++) {
            final float lv = lineVals[i];
            View b = Ui.button(this, lineNames[i], 14, Math.abs(lineSpacing - lv) < 0.01f, new View.OnClickListener() {
                @Override public void onClick(View v) { lineSpacing = lv; saveStyle(); rebuildKeepPosition(); d.dismiss(); }
            });
            lineRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(lineRow);

        box.addView(Ui.text(this, "边距", 17, true));
        LinearLayout marginRow = new LinearLayout(this);
        marginRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] marginNames = { "小", "中", "大" };
        int[] marginVals = { 12, 24, 36 };
        for (int i = 0; i < marginNames.length; i++) {
            final int mv = marginVals[i];
            View b = Ui.button(this, marginNames[i], 14, marginDp == mv, new View.OnClickListener() {
                @Override public void onClick(View v) { marginDp = mv; saveStyle(); rebuildKeepPosition(); d.dismiss(); }
            });
            marginRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(marginRow);

        box.addView(Ui.text(this, "对齐", 17, true));
        LinearLayout alignRow = new LinearLayout(this);
        alignRow.setOrientation(LinearLayout.HORIZONTAL);
        View left = Ui.button(this, "左对齐", 14, align == 0, new View.OnClickListener() {
            @Override public void onClick(View v) { align = 0; saveStyle(); rebuildKeepPosition(); d.dismiss(); }
        });
        View center = Ui.button(this, "居中", 14, align == 1, new View.OnClickListener() {
            @Override public void onClick(View v) { align = 1; saveStyle(); rebuildKeepPosition(); d.dismiss(); }
        });
        View both = Ui.button(this, "两端对齐", 14, align == 2, new View.OnClickListener() {
            @Override public void onClick(View v) { align = 2; saveStyle(); rebuildKeepPosition(); d.dismiss(); }
        });
        alignRow.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        alignRow.addView(center, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        alignRow.addView(both, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(alignRow);

        box.addView(Ui.text(this, "点按翻页", 17, true));
        LinearLayout tapRow = new LinearLayout(this);
        tapRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TAP_L.length; i++) {
            final int m = i;
            tapRow.addView(Ui.button(this, TAP_L[i], 14, tapMode == i, new View.OnClickListener() {
                @Override public void onClick(View v) { tapMode = m; saveStyle(); d.dismiss(); }
            }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(tapRow);

        box.addView(Ui.text(this, "阅读背景", 17, true));
        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < BG_L.length; i++) {
            final int m = i;
            bgRow.addView(Ui.button(this, i == 3 && bgMode == 3 ? "换图片" : BG_L[i], 14, bgMode == i, new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); if (m == 3 && bgMode == 3) pickBgImage(); else setBg(m); }
            }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(bgRow);

        View close = Ui.button(this, "关闭", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        box.addView(close, clp);

        d.setContentView(sv);
        d.show();
        w.setGravity(Gravity.BOTTOM);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** “排版与字体”的第二层：字体列表（当前的选中），选了就换、回到正文。 */
    private void fontList() {
        final List<Books.Font> fonts = Books.fonts();
        String[] names = new String[fonts.size()];
        int cur = 0;
        for (int i = 0; i < names.length; i++) {
            Books.Font f = fonts.get(i);
            names[i] = f.name;
            if ((f.path == null && fontPath == null) || (f.path != null && f.path.equals(fontPath))) cur = i;
        }
        new VeraDialog.Builder(this).title("字体").items(names, new VeraDialog.OnPick() {
            @Override public void onPick(int which) {
                fontPath = fonts.get(which).path; fontName = fonts.get(which).name; saveStyle(); rebuildKeepPosition();
            }
        }).selected(cur).negative("返回", new Runnable() { @Override public void run() { displayDialog(); } }).show();
    }


    // ------------------------------------------------------------------ 段距 / 首行缩进（不改文字，只加排版标记，书签和续读的字符位置不变）

    /** 段落最后一行下面多留 px 像素。 */
    private static final class ParaGap implements android.text.style.LineHeightSpan {
        final int px;
        ParaGap(int px) { this.px = px; }
        @Override public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v, android.graphics.Paint.FontMetricsInt fm) {
            if (end > 0 && end <= text.length() && text.charAt(end - 1) == '\n' && text instanceof android.text.Spanned && end == ((android.text.Spanned) text).getSpanEnd(this)) {
                fm.descent += px; fm.bottom += px;
            }
        }
    }

    /** s[i, stop) 复制一份，每段加上缩进 / 段距。firstIsPara：第一段是不是从段首开始（翻到段落中间的那一页不缩进）。 */
    private static CharSequence spans(String s, int i, int stop, int indentPx, int paraPx) {
        return spans(s.substring(i, stop), i == 0 || s.charAt(i - 1) == '\n', indentPx, paraPx);
    }

    private static CharSequence spans(String t, boolean firstIsPara, int indentPx, int paraPx) {
        android.text.SpannableString sp = new android.text.SpannableString(t);
        int ps = 0, n = t.length();
        while (ps < n) {
            int nl = t.indexOf('\n', ps);
            int pe = nl < 0 ? n : nl + 1;
            if (pe > ps) {
                char c0 = t.charAt(ps);
                boolean blank = c0 == '\n' || c0 == '　' || c0 == ' ' || c0 == '\t';
                if (indentPx > 0 && !blank && (ps > 0 || firstIsPara))
                    sp.setSpan(new android.text.style.LeadingMarginSpan.Standard(indentPx, 0), ps, pe, android.text.Spanned.SPAN_PARAGRAPH);
                if (paraPx > 0 && nl >= 0) sp.setSpan(new ParaGap(paraPx), ps, pe, android.text.Spanned.SPAN_PARAGRAPH);
            }
            ps = pe;
        }
        return sp;
    }

    /** 正文里显示的那一页文字：chapter[start, end) 去掉页尾空行，按需要加缩进 / 段距。 */
    private CharSequence styled(String chapter, int start, int end) {
        String t = trimEnd(chapter.substring(start, end));
        if (indent <= 0 && paraSpacing <= 0) return t;
        float sd = getResources().getDisplayMetrics().scaledDensity;
        return spans(t, start == 0 || chapter.charAt(start - 1) == '\n', Math.round(indent * textSize * sd), Math.round(paraSpacing * textSize * sd));
    }

    // ------------------------------------------------------------------ 方块风格：顶栏、底部六个按钮、设置单

    private static final String[] TOOLS = { "目录", "字体", "排版", "显示", "模式", "系统" };
    private static final Icons.Kind[] TOOL_ICONS = { Icons.Kind.TOC, Icons.Kind.FONT, Icons.Kind.LAYOUT, Icons.Kind.EYE, Icons.Kind.INVERT, Icons.Kind.GEAR };

    private void buildBlocksChrome(FrameLayout pageView, String name) {
        // 顶栏：返回、章名、时间、电量
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(Color.WHITE);
        top.setClickable(true);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(22), dp(14), dp(8));
        row.addView(Blocks.squareButton(this, Icons.Kind.BACK, new View.OnClickListener() { @Override public void onClick(View v) { finish(); overridePendingTransition(0, 0); } }));
        blkTitle = Ui.text(this, name, 16, true);
        blkTitle.setSingleLine(true);
        blkTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        blkTitle.setGravity(Gravity.CENTER);
        blkTitle.setPadding(dp(10), 0, dp(10), 0);
        row.addView(blkTitle, Blocks.weight(1));
        blkClock = Ui.text(this, "", 14, false);
        row.addView(blkClock);
        top.addView(row);
        top.addView(Ui.rule(this, 2, Color.BLACK));
        top.setVisibility(View.GONE);
        blkTop = top;
        pageView.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 底部：设置单（盖在正文上）+ 六个按钮
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClickable(true);
        bottom.setBackgroundColor(Color.WHITE);   // 设置单和按钮条之间、两边的空隙不透出正文
        sheetBox = new LinearLayout(this);
        sheetBox.setOrientation(LinearLayout.VERTICAL);
        sheetBox.setBackground(Blocks.frame(this));
        sheetBox.setPadding(dp(12), dp(8), dp(12) + Blocks.shade(this), dp(10) + Blocks.shade(this));
        sheetBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams sp = Blocks.match();
        sp.leftMargin = dp(12); sp.rightMargin = dp(12); sp.bottomMargin = dp(8);
        bottom.addView(sheetBox, sp);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        bar.addView(Ui.rule(this, 2, Color.BLACK));
        LinearLayout tools = new LinearLayout(this);
        toolMarks = new View[TOOLS.length];
        toolCells = new View[TOOLS.length];
        for (int i = 0; i < TOOLS.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            toolCells[i] = cell;
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            View m = new View(this);
            toolMarks[i] = m;
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(48), dp(6));
            mp.bottomMargin = dp(4);
            cell.addView(m, mp);
            cell.addView(new Icons.IconView(this, TOOL_ICONS[i]), Blocks.lp(dp(24), dp(24)));
            TextView t = Ui.text(this, TOOLS[i], 13, false);
            t.setPadding(0, dp(2), 0, dp(10));
            t.setGravity(Gravity.CENTER);
            cell.addView(t, Blocks.match());
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setClickable(true);
            cell.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openSheet(sheetOpen == idx ? -1 : idx); } });
            Blocks.fast(cell);
            tools.addView(cell, Blocks.weight(1));
        }
        bar.addView(tools);
        bottom.addView(bar, Blocks.match());
        bottom.setVisibility(View.GONE);
        blkBottom = bottom;
        pageView.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // “正在打开”卡片：第一页准备好以前一直在；第一页在 1.5 秒内好了的话它根本不会被画出来
        LinearLayout card = Blocks.card(this);
        card.setBackground(Blocks.frameDouble(this));
        card.setPadding(dp(18), dp(14), dp(18) + Blocks.shade(this), dp(16) + Blocks.shade(this));
        LinearLayout head = new LinearLayout(this);
        head.addView(Blocks.cap(this, "Opening / " + ext.toUpperCase(Locale.US)), Blocks.weight(1));
        head.addView(Blocks.cap(this, "Please wait"));
        card.addView(head);
        TextView tt = Ui.text(this, name, 20, true);
        tt.setSingleLine(true);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(tt);
        TextView st = Ui.text(this, "正在打开这本书…", 14, false);
        st.setPadding(0, dp(4), 0, dp(8));
        card.addView(st);
        Blocks.BlockBar bb = new Blocks.BlockBar(this, 10);
        bb.noPercent();
        bb.setValue(3);
        card.addView(bb, Blocks.match());
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * 0.72), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        openCard = card;
        pageView.addView(card, cp);
    }

    /** 第一页（或出错信息）已经放上去了：收起“正在打开”的卡片。 */
    private void opened() {
        if (openCard != null && openCard.getVisibility() != View.GONE) openCard.setVisibility(View.GONE);
    }

    private void paintBlocksTop() {
        if (blkTitle == null) return;
        String t = bookTitle.length() > 0 ? bookTitle : String.valueOf(blkTitle.getText());
        if (!isPdf && !pages.isEmpty() && chapters.size() > 1) {
            int ch = pages.get(Math.max(0, Math.min(pages.size() - 1, page))).chapter;
            if (ch < chapters.size() && chapters.get(ch).title != null) t = chapters.get(ch).title;
        }
        if (!t.contentEquals(blkTitle.getText())) blkTitle.setText(t);
        java.util.Map<String, String> st = HostLink.status(this);
        String c = Ui.clock(this) + (st.get("battery") != null ? "   " + st.get("battery") + "%" : "");
        if (!c.contentEquals(blkClock.getText())) blkClock.setText(c);
    }

    /** 打开第 i 张设置单（-1 = 收起）。同一时间只有一张；换内容只重画底部这一块。 */
    private void openSheet(int i) {
        if (isPdf && (i == 1 || i == 2)) return;
        if (i != sheetOpen && (i < 0) != (sheetOpen < 0)) Blocks.swipe(this, i < 0 ? Blocks.DIR_DOWN : Blocks.DIR_UP, Blocks.KIND_SHEET);
        sheetOpen = i;
        for (int k = 0; k < toolMarks.length; k++) toolMarks[k].setBackgroundColor(k == i ? Color.BLACK : Color.WHITE);
        sheetBox.removeAllViews();
        if (i < 0) { sheetBox.setVisibility(View.GONE); return; }
        switch (i) {
        case 0: sheetToc(-1); break;
        case 1: sheetFont(); break;
        case 2: sheetLayout(); break;
        case 3: sheetDisplay(); break;
        case 4: sheetMode(); break;
        default: sheetSystem(); break;
        }
        sheetBox.setVisibility(View.VISIBLE);
    }

    private void sheetHead(String en, String zh) {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(Blocks.titled(this, en, zh, 16, true), Blocks.weight(1));
        h.setPadding(0, 0, 0, dp(6));
        sheetBox.addView(h);
    }

    private void sheetRow(String zh, String en, View control) {
        LinearLayout r = Blocks.row(this, zh, en, control);
        r.setPadding(0, dp(4), 0, dp(4));
        r.setMinimumHeight(dp(52));
        sheetBox.addView(r, Blocks.match());
    }

    /** 目录 / 书签：一页 6 条，翻页条翻，点翻页条中间的页码输入第几条。pg = -1：翻到现在读的这一章。 */
    private int tocPage, tocSeg;
    private void sheetToc(int pg) {
        sheetBox.removeAllViews();
        tocPage = pg;
        final List<String> bms = bookmarks();
        boolean hasToc = !isPdf && toc.size() > 1;
        if (!hasToc) tocSeg = 1;
        else if (bms.isEmpty()) tocSeg = 0;
        sheetHead("Contents", hasToc ? "目录" : "书签");
        if (hasToc && !bms.isEmpty()) sheetBox.addView(new Blocks.SegBar(this, new String[] { "目录（" + toc.size() + "）", "书签（" + bms.size() + "）" }, tocSeg,
            new Blocks.SegBar.Listener() { @Override public void onPick(int k) { tocSeg = k; sheetToc(-1); } }), Blocks.match());
        final List<String> names = new ArrayList<String>();
        final List<Integer> targets = new ArrayList<Integer>();
        int highlight = -1;
        if (tocSeg == 0 && hasToc) {
            highlight = curToc();
            for (int c = 0; c < toc.size(); c++) { names.add(tocLabel(c)); targets.add(-1 - c); }   // 负数 = 目录第几条（还没分完页也能跳）
        } else {
            int total = isPdf ? pdfPages : pages.size();
            for (String k : bms) { int p = bookmarkPage(k); if (p < 0) continue; names.add("第 " + (p + 1) + " 页 · " + ((p + 1) * 100 / Math.max(1, total)) + "%"); targets.add(p); }
        }
        final int per = 6, pages0 = Math.max(1, (names.size() + per - 1) / per);
        if (pg < 0) tocPage = highlight >= 0 ? highlight / per : 0;   // 打开时翻到现在读的这一章（以前翻回第 1 页也会被拉回这里）
        tocPage = Math.max(0, Math.min(pages0 - 1, tocPage));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Blocks.plain(this));
        for (int n = tocPage * per; n < Math.min(names.size(), tocPage * per + per); n++) {
            final int to = targets.get(n);
            TextView t = Ui.text(this, names.get(n), 15, n == highlight);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(10), 0, dp(10), 0);
            t.setGravity(Gravity.CENTER_VERTICAL);
            if (n == highlight) { t.setBackgroundColor(Color.BLACK); t.setTextColor(Color.WHITE); }
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (to < 0) gotoToc(-1 - to); else jump(to); } });
            Blocks.fast(t);
            if (list.getChildCount() > 0) list.addView(Blocks.hair(this));
            list.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        }
        if (names.isEmpty()) { TextView t = Ui.text(this, "这本书没有目录，也还没有书签。", 15, false); t.setPadding(dp(10), dp(14), dp(10), dp(14)); list.addView(t); }
        sheetBox.addView(list, Blocks.gap(this, 6));
        if (isPdf || tocSeg == 1 || !hasToc) {
            final boolean marked = bookmarkedHere();
            sheetRow(marked ? "当前页已加书签" : "当前页书签", marked ? "Tap to remove" : "Add bookmark here",
                Blocks.button(this, marked ? "移除书签" : "添加书签", !marked, new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleBookmark(); sheetToc(tocPage); }
                }));
        }
        if (pages0 > 1) {
            Blocks.Pager pager = new Blocks.Pager(this, new Blocks.Pager.Listener() { @Override public void onPage(int dir) { sheetToc(Math.max(0, tocPage + dir)); } });
            pager.set(tocPage + 1, pages0);
            if (tocSeg == 0 && hasToc) pager.onMidTap(new View.OnClickListener() { @Override public void onClick(View v) {
                tocNumberInput(new OnTocNumber() { @Override public void go(int n) { sheetToc(n / per); } });
            } });
            sheetBox.addView(pager, Blocks.gap(this, 6));
        }
        // 跳到：百分比步进器（一步 5%），点中间的值输入页码
        final int total = isPdf ? pdfPages : pages.size();
        if (total > 1) {
            final String[] pct = new String[21];
            for (int i = 0; i <= 20; i++) pct[i] = (i * 5) + "%";
            final int curPct = Math.min(100, page * 100 / Math.max(1, total - 1));
            Blocks.Stepper js = new Blocks.Stepper(this, pct, Math.round(curPct / 5f), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) { jump(Math.min(total - 1, (total - 1) * i * 5 / 100)); } });
            js.onValueTap(new View.OnClickListener() { @Override public void onClick(View v) { pageInput(total); } });
            sheetRow("跳到 · 第 " + (page + 1) + " / " + total + " 页", "Go to (tap value: page)", js);
        }
    }

    /** 输入页码跳过去。 */
    private void pageInput(final int total) {
        final android.widget.EditText in = Ui.editText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setSingleLine(true);
        in.setTextSize(22);
        in.setBackground(Blocks.plain(this));
        in.setPadding(dp(12), dp(10), dp(12), dp(10));
        in.setText(String.valueOf(page + 1));
        new VeraDialog.Builder(this).title("跳到第几页").message("1 – " + total).view(in).negative("取消", null)
            .positive("跳过去", new Runnable() { @Override public void run() {
                try { jump(Integer.parseInt(in.getText().toString().trim()) - 1); } catch (Throwable ignored) { }
            } }).show();
    }

    /** 字体：字体（点中间的名字出一张一页 6 个的选择表）、字号。 */
    private void sheetFont() {
        sheetHead("Font", "字体");
        final List<Books.Font> fonts = Books.fonts();
        String[] names = new String[fonts.size()];
        int cur = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = fonts.get(i).name;
            Books.Font f = fonts.get(i);
            if ((f.path == null && fontPath == null) || (f.path != null && f.path.equals(fontPath))) cur = i;
        }
        Blocks.Stepper fs = new Blocks.Stepper(this, names, cur, new Blocks.Stepper.Listener() { @Override public void onStep(int i) {
            fontPath = fonts.get(i).path; fontName = fonts.get(i).name; saveStyle(); rebuildKeepPosition(); } }).wrap();
        fs.onValueTap(new View.OnClickListener() { @Override public void onClick(View v) { fontPicker(0); } });
        sheetRow("字体", "Font family", fs);
        final String[] sizes = new String[14];
        for (int i = 0; i < sizes.length; i++) sizes[i] = String.valueOf(14 + i * 2);
        sheetRow("字号", "Font size", new Blocks.Stepper(this, sizes, Math.max(0, Math.min(13, (textSize - 14) / 2)), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { setTextSize(14 + i * 2); } }));
        TextView imp = Ui.text(this, "导入字体：把 .ttf / .otf 放进内部存储的 Fonts 文件夹（或用传书网页上传），这里就会多出来。", 12, false);
        imp.setPadding(0, dp(6), 0, 0);
        sheetBox.addView(imp, Blocks.match());
    }

    private void fontPicker(final int pg) {
        sheetBox.removeAllViews();
        sheetHead("Quick select", "选择字体");
        final List<Books.Font> fonts = Books.fonts();
        final int per = 6, pages0 = Math.max(1, (fonts.size() + per - 1) / per);
        final int p = Math.max(0, Math.min(pages0 - 1, pg));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = p * per; i < Math.min(fonts.size(), p * per + per); i++) {
            final Books.Font f = fonts.get(i);
            boolean on = (f.path == null && fontPath == null) || (f.path != null && f.path.equals(fontPath));
            TextView t = Blocks.button(this, f.name, on, new View.OnClickListener() { @Override public void onClick(View v) {
                fontPath = f.path; fontName = f.name; saveStyle(); rebuildKeepPosition(); openSheet(1); } });
            LinearLayout.LayoutParams lp = Blocks.match(); lp.topMargin = dp(4);
            list.addView(t, lp);
        }
        sheetBox.addView(list);
        Blocks.Pager pager = new Blocks.Pager(this, new Blocks.Pager.Listener() { @Override public void onPage(int dir) { fontPicker(p + dir); } });
        pager.set(p + 1, pages0);
        sheetBox.addView(pager, Blocks.gap(this, 8));
    }

    private static final float[] LINE_V = { 1.0f, 1.1f, 1.2f, 1.3f, 1.35f, 1.4f, 1.5f, 1.6f, 1.7f, 1.8f, 2.0f };
    private static final float[] PARA_V = { 0f, 0.25f, 0.5f, 0.75f, 1.0f, 1.5f };
    private static final int[] MARGIN_V = { 8, 12, 16, 20, 24, 30, 36, 44, 56 };

    private static String[] labels(float[] v, String unit) { String[] o = new String[v.length]; for (int i = 0; i < v.length; i++) o[i] = (v[i] == 0 ? "无" : String.format(Locale.US, "%.2f", v[i]).replaceAll("0+$", "").replaceAll("\\.$", "") + unit); return o; }
    private static int nearest(float[] v, float x) { int b = 0; for (int i = 0; i < v.length; i++) if (Math.abs(v[i] - x) < Math.abs(v[b] - x)) b = i; return b; }

    /** 排版：行距、段距、左右边距、首行缩进、对齐。每改一项整本按字符位置重新分页，停在同一段字上。 */
    private void sheetLayout() {
        sheetHead("Layout", "排版");
        // 常用的三档：一点就换（行距 + 段距 + 边距一起）
        final float[][] preset = { { 1.2f, 0f, 16 }, { 1.4f, 0.25f, 24 }, { 1.7f, 0.5f, 30 } };
        int ps = -1;
        for (int i = 0; i < 3; i++) if (Math.abs(lineSpacing - preset[i][0]) < 0.01f && Math.abs(paraSpacing - preset[i][1]) < 0.01f && marginDp == (int) preset[i][2]) ps = i;
        sheetBox.addView(new Blocks.SegBar(this, new String[] { "紧凑", "标准", "宽松" }, ps, new Blocks.SegBar.Listener() { @Override public void onPick(int i) {
            lineSpacing = preset[i][0]; paraSpacing = preset[i][1]; marginDp = (int) preset[i][2];
            saveStyle(); if (footView != null) footView.setPadding(dp(marginDp), 0, dp(marginDp), dp(6)); rebuildKeepPosition(); openSheet(2);
        } }), Blocks.gap(this, 2));
        String[] lineL = new String[LINE_V.length];
        for (int i = 0; i < lineL.length; i++) lineL[i] = String.format(Locale.US, "%.2f", LINE_V[i]).replaceAll("0$", "") + " 倍";
        sheetRow("行距", "Line height", new Blocks.Stepper(this, lineL, nearest(LINE_V, lineSpacing), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { lineSpacing = LINE_V[i]; saveStyle(); rebuildKeepPosition(); } }));
        sheetRow("段距", "Paragraph spacing", new Blocks.Stepper(this, labels(PARA_V, " 行"), nearest(PARA_V, paraSpacing), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { paraSpacing = PARA_V[i]; saveStyle(); rebuildKeepPosition(); } }));
        String[] mL = new String[MARGIN_V.length];
        int mi = 0;
        for (int i = 0; i < mL.length; i++) { mL[i] = MARGIN_V[i] + " dp"; if (Math.abs(MARGIN_V[i] - marginDp) < Math.abs(MARGIN_V[mi] - marginDp)) mi = i; }
        sheetRow("左右边距", "Margin", new Blocks.Stepper(this, mL, mi, new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { marginDp = MARGIN_V[i]; saveStyle(); if (footView != null) footView.setPadding(dp(marginDp), 0, dp(marginDp), dp(6)); rebuildKeepPosition(); } }));
        sheetRow("首行缩进", "First-line indent", new Blocks.Stepper(this, new String[] { "无", "1 字", "2 字" }, Math.max(0, Math.min(2, indent)), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { indent = i; saveStyle(); rebuildKeepPosition(); } }));
        sheetRow("对齐", "Alignment", new Blocks.Stepper(this, new String[] { "左对齐", "居中", "两端对齐" }, Math.max(0, Math.min(2, align)), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { align = i; saveStyle(); rebuildKeepPosition(); } }).wrap());
    }

    /** 显示：刷新档位（宿主的 KFB_SMOOTH，整个系统一起）、全刷周期（KFB_FLASH）、极简全屏（不要页脚）。 */
    private void sheetDisplay() {
        sheetHead("Display", "显示");
        java.util.Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");
        LinearLayout sm = new LinearLayout(this);
        sm.setOrientation(LinearLayout.VERTICAL);
        sm.setPadding(0, dp(4), 0, dp(6));
        sm.addView(Blocks.titled(this, "Refresh mode", "刷新档位（全局）", 16, true));
        sm.addView(new Blocks.SegBar(this, BlocksSettings.SMOOTH_L, Math.max(0, Math.min(BlocksSettings.SMOOTH_L.length - 1, HostLink.num(cfg, "KFB_SMOOTH", 2))), new Blocks.SegBar.Listener() {
            @Override public void onPick(int i) { HostLink.settings("KFB_SMOOTH=" + i + "\n"); } }), Blocks.gap(this, 4));
        sheetBox.addView(sm, Blocks.match());
        int fi = 4;
        for (int i = 0; i < BlocksSettings.FLASH_V.length; i++) if (BlocksSettings.FLASH_V[i].equals(cfg.get("KFB_FLASH"))) fi = i;
        sheetRow("全刷周期", "Full refresh", new Blocks.Stepper(this, BlocksSettings.FLASH_L, fi, new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { HostLink.settings("KFB_FLASH=" + BlocksSettings.FLASH_V[i] + "\n"); } }));
        sheetRow("极简全屏", "Minimal mode", new Blocks.Switch(this, minimal, new Icons.Toggle.Listener() { @Override public void onToggle(boolean on) {
            minimal = on; saveStyle(); if (footView != null) footView.setVisibility(on ? View.INVISIBLE : View.VISIBLE); } }));
        sheetRow("阅读背景", "Background", new Blocks.Stepper(this, BG_L, bgMode, new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { setBg(i); } }));
        if (bgMode == 3) sheetRow("背景图片", "Image", Blocks.button(this, "换一张", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pickBgImage(); } }));
    }

    /** 模式：反色、翻页动画、点按翻页的方式。 */
    private void sheetMode() {
        sheetHead("Mode", "模式");
        java.util.Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");
        sheetRow("反色（夜间）", "Invert", new Blocks.Switch(this, "1".equals(cfg.get("KFB_INVERT")), new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) { HostLink.settings("KFB_INVERT=" + (on ? 1 : 0) + "\n"); } }));
        final String anim = cfg.get("KFB_ANIM_MODE");
        sheetRow("翻页动画", "Page slide · all apps", new Blocks.Switch(this, anim == null || !"0".equals(anim), new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) { HostLink.settings("KFB_ANIM_MODE=" + (on ? 1 : 0) + "\n"); } }));
        sheetRow("点按翻页", "Tap zones", new Blocks.Stepper(this, TAP_L, tapMode, new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { tapMode = i; saveStyle(); } }).wrap());
    }

    /** 系统：书签、跳转、清残影、面板、回桌面、回 Kindle。 */
    private void sheetSystem() {
        sheetHead("System", "系统");
        String[][] b = { { bookmarkedHere() ? "去掉书签" : "加书签", "跳转…", "整屏刷新" }, { "下拉面板", "回到桌面", "回 Kindle" } };
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int k = 0; k < 3; k++) {
                final int id = r * 3 + k;
                TextView t = Blocks.button(this, b[r][k], false, new View.OnClickListener() { @Override public void onClick(View v) {
                    switch (id) {
                    case 0: toggleBookmark(); openSheet(5); break;
                    case 1: gotoDialog(); break;
                    case 2: HostLink.cmd("clear"); break;
                    case 3: Panel.open(ReaderActivity.this); break;
                    case 4: finish(); startActivity(new Intent(ReaderActivity.this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); overridePendingTransition(0, 0); break;
                    default: HostLink.cmd("exit"); break;
                    }
                } });
                LinearLayout.LayoutParams lp = Blocks.weight(1);
                if (k > 0) lp.leftMargin = dp(8);
                row.addView(t, lp);
            }
            sheetBox.addView(row, Blocks.gap(this, r == 0 ? 2 : 8));
        }
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
