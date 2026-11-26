// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Minimal file picker for reader apps (Legado and others) that call
 * ACTION_GET_CONTENT / ACTION_OPEN_DOCUMENT. AOSP has no DocumentsUI in this
 * rootfs, so those apps previously could not import a book-source JSON at all.
 */
public class FilePickerActivity extends Activity {
    private static final String ROOT = "/data/media/0";
    private File dir = new File(ROOT);
    private LinearLayout list;
    private TextView path;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        w.setWindowAnimations(0);
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        hd.addView(Icons.bar(this, Icons.Kind.BACK, 28, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ROOT.equals(dir.getAbsolutePath())) finish(); else open(dir.getParentFile());
            }
        }));
        TextView t = Ui.text(this, "选择文件", 24, true); t.setPadding(dp(10), 0, 0, 0);
        hd.addView(t);
        root.addView(hd);
        path = Ui.text(this, "", 14, false); path.setTextColor(Color.DKGRAY);
        path.setPadding(0, dp(4), 0, dp(6));
        root.addView(path);
        View pathRule = Ui.rule(this, 1, Color.LTGRAY);
        root.addView(pathRule);
        if (Blocks.on(this)) { buildBlocks(root, hd, pathRule); return; }

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        open(new File(ROOT));
    }

    /** 方块风格：页头（返回方块 = 上一级 / 退出）、路径条、分页列表（一页整数行）。 */
    private Blocks.PagedList paged;
    private final java.util.List<File> files = new java.util.ArrayList<File>();

    private void buildBlocks(LinearLayout root, View hd, View pathRule) {
        Blocks.restyleTop(this, root, hd, pathRule, "Files / pick", "选择文件", null);
        path.setTextColor(Color.BLACK);
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.START);
        root.removeView(path);
        LinearLayout pb = Blocks.boxed(this, path);
        pb.setBackground(Blocks.frame(this));
        pb.setPadding(dp(10), dp(6), dp(10) + Blocks.shade(this), dp(6) + Blocks.shade(this));
        root.addView(pb, Blocks.match());
        paged = new Blocks.PagedList(this, 60, new Blocks.PagedList.Rows() {
            @Override public int count() { return files.size(); }
            @Override public View row(int i) {
                final File f = files.get(i);
                View r = Blocks.listRow(FilePickerActivity.this, iconFor(f), f.getName(),
                    f.isDirectory() ? "文件夹" : (f.length() / 1024) + " KB", f.isDirectory() ? new Icons.IconView(FilePickerActivity.this, Icons.Kind.CHEV_R) : null);
                r.setClickable(true);
                r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pick(f); } });
                Blocks.fast(r);
                return r;
            }
        });
        paged.setEmpty(Blocks.empty(this, Icons.Kind.FOLDER, "这里没有能选的文件", null, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.topMargin = dp(8);
        root.addView(paged, lp);
        setContentView(root);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        open(new File(ROOT));
    }

    @Override public void onBackPressed() {
        if (dir != null && !ROOT.equals(dir.getAbsolutePath())) open(dir.getParentFile()); else super.onBackPressed();
    }

    private void pick(File f) {
        if (f.isDirectory()) { open(f); return; }
        Uri u = new Uri.Builder().scheme("content").authority(VeraFileProvider.AUTHORITY).path(f.getAbsolutePath()).build();
        Intent i = new Intent();
        i.setData(u);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setResult(RESULT_OK, i);
        finish();
    }

    /**
     * 目录项：isDirectory / length 在后台线程里各问一次就记下来。以前 open() 在主线程上 listFiles，排序比较时每次都
     * isDirectory()（约 2·n·log n 次 stat），每行再 length()：KindleLibrary 是 FUSE，几千本书时主线程卡几秒到几十秒（ANR）。
     */
    private static final class Entry extends File {
        final boolean dir; final long len;
        Entry(File f) { super(f.getPath()); dir = f.isDirectory(); len = dir ? 0 : f.length(); }
        @Override public boolean isDirectory() { return dir; }
        @Override public long length() { return len; }
    }
    private int openGen;

    /** 在后台读目录（每项只 stat 一次），读完回到主线程显示；期间又点了别的目录就丢掉这一次的结果。 */
    private void open(final File d) {
        if (d == null) return;
        final int gen = ++openGen;
        new Thread(new Runnable() {
            @Override public void run() {
                if (!d.isDirectory()) return;
                File[] raw = d.listFiles();
                final File[] fs;
                if (raw == null) fs = null;
                else { fs = new File[raw.length]; for (int i = 0; i < raw.length; i++) fs[i] = new Entry(raw[i]); }
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (gen != openGen || isFinishing()) return;
                    show(d, fs);
                } });
            }
        }, "filepicker-list").start();
    }

    private void show(File d, File[] listed) {
        dir = d;
        path.setText(d.getAbsolutePath());
        if (paged != null) {
            files.clear();
            File[] fs0 = listed;
            if (fs0 != null) {
                Arrays.sort(fs0, new Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File f : fs0) if (!f.getName().startsWith(".") && (f.isDirectory() || allowed(f))) files.add(f);
            }
            paged.toFirst();
            return;
        }
        list.removeAllViews();
        File[] fs = listed;
        if (fs == null) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (final File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (!f.isDirectory() && !allowed(f)) continue;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(0, dp(9), 0, dp(9));
            TextView n = Ui.text(this, (f.isDirectory() ? "📁 " : "📄 ") + f.getName(), 18, true);
            r.addView(n);
            TextView s = Ui.text(this, f.isDirectory() ? "文件夹" : (f.length() / 1024) + " KB", 12, false);
            s.setTextColor(Color.DKGRAY); r.addView(s);
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (f.isDirectory()) { open(f); return; }
                    // 用 Builder 编码路径：文件名里的 # ? % 用 Uri.parse 会被当成片段 / 查询 / 转义，对方打开就找不到文件
                    Uri u = new Uri.Builder().scheme("content").authority(VeraFileProvider.AUTHORITY).path(f.getAbsolutePath()).build();
                    Intent i = new Intent();
                    i.setData(u);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    setResult(RESULT_OK, i);
                    finish();
                }
            });
            r.addView(Ui.rule(this, 1, Color.LTGRAY));
            list.addView(r);
        }
    }

    private static Icons.Kind iconFor(File f) {
        if (f.isDirectory()) return Icons.Kind.FOLDER;
        String n = f.getName().toLowerCase(java.util.Locale.US);
        if (isImageExt(n)) return Icons.Kind.IMAGE;
        if (isBookExt(n)) return Icons.Kind.BOOK;
        if (isFontExt(n)) return Icons.Kind.FONT;
        if (n.endsWith(".apk")) return Icons.Kind.GRID;
        return Icons.Kind.NOTE;
    }

    private static boolean isImageExt(String n) {
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    private static boolean isBookExt(String n) {
        return n.endsWith(".epub") || n.endsWith(".pdf") || n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3")
            || n.endsWith(".kfx") || n.endsWith(".fb2") || n.endsWith(".cbz") || n.endsWith(".cbr") || n.endsWith(".djvu");
    }

    private static boolean isFontExt(String n) {
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
    }

    private static String mimeFor(String n) {
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".xml") || n.endsWith(".opml")) return "text/xml";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".conf") || n.endsWith(".ini") || n.endsWith(".yaml") || n.endsWith(".yml")) return "text/plain";
        if (n.endsWith(".epub")) return "application/epub+zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".mobi") || n.endsWith(".azw") || n.endsWith(".azw3")) return "application/x-mobipocket-ebook";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".otf")) return "font/otf";
        if (n.endsWith(".ttc")) return "font/collection";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        int dot = n.lastIndexOf('.');
        String m = dot < 0 ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1));
        return m != null ? m.toLowerCase(java.util.Locale.US) : "application/octet-stream";
    }

    private static boolean mimeMatch(String pattern, String fileMime, String lowerName) {
        if ("*/*".equals(pattern) || "application/octet-stream".equals(pattern)) return true;
        if (pattern.equals(fileMime)) return true;
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            if (fileMime.startsWith(prefix)) return true;
            if ("text/".equals(prefix) && ("application/json".equals(fileMime) || "application/xml".equals(fileMime))) return true;
            if ("font/".equals(prefix) && isFontExt(lowerName)) return true;
        }
        if (("application/x-font-ttf".equals(pattern) || "application/x-font-truetype".equals(pattern) || "application/font-sfnt".equals(pattern)) && isFontExt(lowerName)) return true;
        if ("application/xml".equals(pattern) && "text/xml".equals(fileMime)) return true;
        return false;
    }

    private boolean allowed(File f) {
        String name = f.getName();
        if (WebAdmin.isProtected(name)) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        Intent in = getIntent();
        String[] extras = in != null ? in.getStringArrayExtra(Intent.EXTRA_MIME_TYPES) : null;
        java.util.List<String> want = new java.util.ArrayList<String>();
        if (extras != null && extras.length > 0) {
            for (String s : extras) if (s != null && s.trim().length() > 0) want.add(s.trim().toLowerCase(java.util.Locale.US));
        } else if (in != null && in.getType() != null && in.getType().trim().length() > 0) {
            want.add(in.getType().trim().toLowerCase(java.util.Locale.US));
        }
        if (!want.isEmpty()) {
            String fm = mimeFor(n);
            for (String p : want) if (mimeMatch(p, fm, n)) return true;
            return false;
        }
        return isImageExt(n) || isBookExt(n) || isFontExt(n)
            || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".html") || n.endsWith(".htm")
            || n.endsWith(".rtf") || n.endsWith(".doc") || n.endsWith(".docx")
            || n.endsWith(".mdx") || n.endsWith(".mdd") || n.endsWith(".apkg") || n.endsWith(".dsl")
            || n.endsWith(".json") || n.endsWith(".xml") || n.endsWith(".opml") || n.endsWith(".bak")
            || n.endsWith(".csv") || n.endsWith(".db") || n.endsWith(".sqlite")
            || n.endsWith(".css") || n.endsWith(".js") || n.endsWith(".conf") || n.endsWith(".ini")
            || n.endsWith(".log") || n.endsWith(".yml") || n.endsWith(".yaml")
            || n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")
            || n.endsWith(".tar") || n.endsWith(".gz") || n.endsWith(".tgz")
            || n.endsWith(".apk");
    }

    @Override protected void onResume() {
        super.onResume();
        StatusBarService.suppress(this, true);
        StatusBarService.refresh(this);
    }

    @Override protected void onStop() {
        StatusBarService.suppress(this, false);
        StatusBarService.refresh(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        StatusBarService.suppress(this, false);
        super.onDestroy();
    }

    private int dp(int v) { return Ui.dp(this, v); }
}
