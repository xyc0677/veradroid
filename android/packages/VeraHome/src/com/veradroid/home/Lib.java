// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 书库的数据：进程里只有一份，书库页用它。主页上不再有书，所以第一次打开书库（或主页闲下来预先搭书库）时才读索引。
 *
 * 为什么不放在页面里：页面切换必须一帧画完，不能先画空页、几百毫秒后再把书和封面补上（墨水屏上就是刷两次）。
 * 所以书单、书名、作者都存一份索引（偏好文件 libidx），进程一起来就同步读进来；扫目录（/mnt/us 是 FUSE，会卡）
 * 永远在后台做，扫完和手里的一样就什么都不动。封面只在内存里留最近用到的几十张，磁盘上有缩好的 PNG，
 * 翻到新一页时同步解码（每张几毫秒），没生成过的才后台生成、生成完合并成一次重画。
 *
 * 除了标明“后台”的方法，其余都只在主线程调用。
 */
final class Lib {
    private Lib() { }

    static final int SORT_RECENT = 0, SORT_TITLE = 1, SORT_AUTHOR = 2, SORT_ADDED = 3;
    static final int FILTER_ALL = 0, FILTER_READING = 1, FILTER_UNREAD = 2, FILTER_DONE = 3;

    static final class Book {
        final String path;
        final File file;
        String title, author = "";
        long mtime, size;
        int percent;          // 0..100
        boolean done;         // 用户标为已读
        long lastRead;        // 上次打开的时间；0 = 没打开过
        boolean noCover;      // 生成过，确实没有封面（TXT 等）
        Book(String p) { path = p; file = new File(p); title = base(file.getName()); }
        String ext() { return Lib.ext(file.getName()); }
        /** 排“最近”用：读过的按上次打开，没读过的按放进来的时间。 */
        long recent() { return Math.max(lastRead, mtime); }
        boolean builtin() {
            String n = path.toLowerCase(Locale.US);
            return n.endsWith(".txt") || n.endsWith(".epub") || n.endsWith(".pdf");
        }
    }

    interface Listener { void onLibraryChanged(boolean listChanged); }

    private static final List<Book> books = new ArrayList<Book>();
    private static final Map<String, Book> known = new HashMap<String, Book>();
    private static final List<Listener> listeners = new ArrayList<Listener>();
    private static final ExecutorService worker = Ui.lowExecutor("lib");
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static boolean loaded, scanning, scanAgain;
    private static long scannedAt;
    private static int version;

    static void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }
    static int version() { return version; }
    static List<Book> all() { return books; }
    static boolean isEmpty() { return books.isEmpty(); }

    private static void changed(boolean list) {
        version++;
        for (Listener l : new ArrayList<Listener>(listeners)) l.onLibraryChanged(list);
    }

    // ------------------------------------------------------------------ index + progress

    /** 进程起来后第一次用：从索引里同步读出书单（不碰 FUSE），再读一遍进度。 */
    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences idx = Store.prefs(c, "libidx");
            for (Map.Entry<String, ?> e : idx.getAll().entrySet()) {
                String k = e.getKey();
                if (!k.startsWith("/") || !(e.getValue() instanceof String)) continue;
                String[] f = ((String) e.getValue()).split("\n", -1);
                if (f.length < 4) continue;
                Book b = new Book(k);
                try { b.mtime = Long.parseLong(f[0]); b.size = Long.parseLong(f[1]); } catch (Exception ex) { continue; }
                if (f[2].length() > 0) b.title = f[2];
                b.author = f[3];
                b.noCover = f.length > 4 && "1".equals(f[4]);
                books.add(b);
                known.put(k, b);
            }
        } catch (Throwable ignored) { }
        refreshProgress(c);
    }

    private static void saveIndex(Context c) {
        try {
            SharedPreferences.Editor e = Store.prefs(c, "libidx").edit().clear().putBoolean("__migrated", true);
            for (Book b : books) e.putString(b.path, b.mtime + "\n" + b.size + "\n" + b.title.replace('\n', ' ') + "\n"
                + (b.author == null ? "" : b.author.replace('\n', ' ')) + "\n" + (b.noCover ? "1" : "0"));
            e.apply();
        } catch (Throwable ignored) { }
    }

    /** 重新读阅读进度（阅读器写在 reader 偏好里）。返回有没有变。 */
    static boolean refreshProgress(Context c) {
        boolean ch = false;
        try {
            SharedPreferences p = Store.prefs(c, "reader");
            for (Book b : books) {
                int at = p.getInt("p:" + b.path, 0), total = p.getInt("t:" + b.path, 0);
                boolean done = p.getBoolean("d:" + b.path, false);
                int pct = done ? 100 : total > 0 ? Math.min(100, (at + 1) * 100 / total) : 0;
                long r = p.getLong("r:" + b.path, total > 0 ? 1 : 0);   // 旧版本读过的书没有时间，只知道读过
                if (pct != b.percent || done != b.done || r != b.lastRead) ch = true;
                b.percent = pct; b.done = done; b.lastRead = r;
            }
        } catch (Throwable ignored) { }
        if (ch) version++;
        return ch;
    }

    static void markDone(Context c, Book b, boolean done) {
        Store.prefs(c, "reader").edit().putBoolean("d:" + b.path, done).apply();
        b.done = done;
        if (done) b.percent = 100; else refreshProgress(c);
        changed(true);
    }

    static void touch(Context c, Book b) {
        b.lastRead = System.currentTimeMillis();
        Store.prefs(c, "reader").edit().putLong("r:" + b.path, b.lastRead).apply();
        version++;
    }

    // ------------------------------------------------------------------ scan (background)

    /** 后台重新扫一遍书的目录；和手里的一样就不通知任何人。minAgeMs：离上次扫描不到这么久就不扫。 */
    static void scan(Context c, long minAgeMs) {
        final Context app = c.getApplicationContext();
        load(app);
        if (scanning) { scanAgain = minAgeMs == 0; return; }
        if (minAgeMs > 0 && System.currentTimeMillis() - scannedAt < minAgeMs) return;
        scanning = true;
        final Map<String, Book> snapshot = new HashMap<String, Book>(known);
        worker.execute(new Runnable() {
            @Override public void run() {
                final List<Book> found = new ArrayList<Book>();
                final List<Object[]> updates = new ArrayList<Object[]>();   // 旧书文件变了：{book, mtime, size, info}
                final boolean[] differs = { false };
                final java.util.HashSet<String> seen = new java.util.HashSet<String>();
                try {
                    File[] dirs = { new File(Transfer.BOOKS), new File("/data/media/0/Download"),
                                    new File(Transfer.KINDLE), new File(Transfer.KINDLE + "/Downloads") };
                    List<File[]> lists = new ArrayList<File[]>();
                    for (File dir : dirs) lists.add(dir.listFiles());
                    lists.add(history(app));   // 在“文件”里从别的文件夹打开读过的书也算书库里的书（不然“最近阅读”永远没有它）
                    for (File[] fs : lists) {
                        if (fs == null) continue;
                        for (File f : fs) {
                            if (!isBook(f.getName()) || !f.isFile()) continue;
                            String key = f.getAbsolutePath();
                            if (!seen.add(key)) continue;
                            long mt = f.lastModified(), sz = f.length();
                            Book b = snapshot.get(key);
                            if (b == null) {
                                b = new Book(key);
                                b.mtime = mt; b.size = sz;
                                Books.Info info = Books.info(f);
                                if (info.title != null && info.title.length() > 0) b.title = info.title;
                                b.author = info.author == null ? "" : info.author;
                                differs[0] = true;
                            } else if (b.mtime != mt || b.size != sz) {
                                updates.add(new Object[] { b, mt, sz, Books.info(f) });
                                differs[0] = true;
                            }
                            found.add(b);
                        }
                    }
                } catch (Throwable t) { android.util.Log.w("VeraHome", "lib scan: " + t); }
                if (found.size() != snapshot.size()) differs[0] = true;
                ui.post(new Runnable() {
                    @Override public void run() {
                        scanning = false;
                        scannedAt = System.currentTimeMillis();
                        if (differs[0]) {
                            for (Object[] u : updates) {
                                Book b = (Book) u[0];
                                b.mtime = (Long) u[1]; b.size = (Long) u[2];
                                Books.Info info = (Books.Info) u[3];
                                if (info.title != null && info.title.length() > 0) b.title = info.title;
                                b.author = info.author == null ? "" : info.author;
                                b.noCover = false;
                                covers.remove(b.path);
                            }
                            books.clear(); books.addAll(found);
                            known.clear();
                            for (Book b : found) known.put(b.path, b);
                            for (java.util.Iterator<String> it = covers.keySet().iterator(); it.hasNext(); ) if (!known.containsKey(it.next())) it.remove();
                            refreshProgress(app);
                            saveIndex(app);
                            changed(true);
                        }
                        if (scanAgain) { scanAgain = false; scan(app, 0); }
                    }
                });
            }
        });
    }

    /** 阅读器读过（有上次阅读时间）、还在的书，不管在哪个文件夹。后台线程调。 */
    private static File[] history(Context c) {
        List<File> out = new ArrayList<File>();
        try {
            for (String k : Store.prefs(c, "reader").getAll().keySet()) {
                if (!k.startsWith("r:/")) continue;
                File f = new File(k.substring(2));
                if (isBook(f.getName()) && f.isFile()) out.add(f);
            }
        } catch (Throwable t) { android.util.Log.w("VeraHome", "lib history: " + t); }
        return out.toArray(new File[0]);
    }

    /** 打开了一本书库里还没有的书（“文件”里别的文件夹）：马上加进书库，回到主页“最近阅读”里就有它（不等下一次扫描）。 */
    static void adopt(Context c, Book b) {
        if (!loaded || known.containsKey(b.path) || !isBook(b.file.getName())) return;
        try {
            b.mtime = b.file.lastModified(); b.size = b.file.length();
            Books.Info info = Books.info(b.file);
            if (info.title != null && info.title.length() > 0) b.title = info.title;
            b.author = info.author == null ? "" : info.author;
        } catch (Throwable ignored) { }
        books.add(b);
        known.put(b.path, b);
        saveIndex(c);
        version++;   // 不通知页面（正要打开阅读器，主页这时别重画）；回到主页时按签名重搭
    }

    /** “最近阅读”：读过的书按上次阅读时间排（最新的在前）；不够 n 本再用最近放进来的没读过的书补上。 */
    static List<Book> recentlyRead(int n) {
        List<Book> read = new ArrayList<Book>(), unread = new ArrayList<Book>();
        for (Book b : books) (b.lastRead > 1 ? read : unread).add(b);
        Collections.sort(read, new Comparator<Book>() { @Override public int compare(Book a, Book b) {
            int r = Long.compare(b.lastRead, a.lastRead); return r != 0 ? r : a.path.compareTo(b.path); } });
        Collections.sort(unread, new Comparator<Book>() { @Override public int compare(Book a, Book b) {
            int r = Long.compare(b.mtime, a.mtime); return r != 0 ? r : a.path.compareTo(b.path); } });
        List<Book> out = new ArrayList<Book>();
        for (Book b : read) { if (out.size() >= n) break; out.add(b); }
        for (Book b : unread) { if (out.size() >= n) break; out.add(b); }
        return out;
    }

    // ------------------------------------------------------------------ views of the list

    static List<Book> sorted(final int sort, int filter, String query) {
        List<Book> out = new ArrayList<Book>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        for (Book b : books) {
            if (filter == FILTER_READING && !(b.lastRead > 0 && b.percent < 100)) continue;
            if (filter == FILTER_UNREAD && (b.lastRead > 0 || b.percent > 0)) continue;
            if (filter == FILTER_DONE && b.percent < 100) continue;
            if (q.length() > 0 && !b.title.toLowerCase(Locale.US).contains(q)
                && !(b.author != null && b.author.toLowerCase(Locale.US).contains(q))) continue;
            out.add(b);
        }
        final Collator zh = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<Book>() {
            @Override public int compare(Book a, Book b) {
                int r = 0;
                switch (sort) {
                case SORT_TITLE: r = zh.compare(a.title, b.title); break;
                case SORT_AUTHOR: r = zh.compare(a.author == null ? "" : a.author, b.author == null ? "" : b.author);
                    if (r == 0) r = zh.compare(a.title, b.title); break;
                case SORT_ADDED: r = Long.compare(b.mtime, a.mtime); break;
                default: r = Long.compare(b.recent(), a.recent()); break;
                }
                return r != 0 ? r : a.path.compareTo(b.path);
            }
        });
        return out;
    }

    // ------------------------------------------------------------------ covers

    private static boolean lowRam(Context c) {
        try { return ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).isLowRamDevice(); } catch (Throwable t) { return false; }
    }

    /** 封面只有一种尺寸（书库三列时一格的宽度），列表视图的小封面由它缩放。 */
    static int coverW(Context c) {
        int screen = c.getResources().getDisplayMetrics().widthPixels;
        int pad = Ui.dp(c, 14) * 2 + Ui.dp(c, 10) * 2;
        int natural = Math.max(120, (screen - pad - Ui.dp(c, 8) * 2) / 3);
        // 以前小内存机型封顶 180（那时每本书常驻一张）；现在内存里最多二十来张，用格子的实际宽度，不再放大发虚
        return Math.min(natural, 400);
    }
    static int coverH(Context c) { return coverW(c) * 3 / 2; }

    private static int capCovers = 18;
    /** 内存里最多留这么多张（按最近用到的顺序）：PW6 上一张约 470 KB，6 寸机型约 320 KB；小内存 18 张，其余 24 张。 */
    private static final LinkedHashMap<String, Bitmap> covers = new LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > capCovers; }
        // 挤出去的不 recycle：可能还挂在某个不可见的格子上，等它被换掉后由 GC 回收
    };

    private static File coverFile(Context c, Book b) {
        File dir = Store.cache("covers");
        String key = Integer.toHexString((b.path + ":" + b.mtime + ":" + b.size + ":" + coverW(c) + "x" + coverH(c)).hashCode());
        return new File(dir, key + ".png");
    }

    /** 同步拿封面：内存里有就用，否则解码磁盘上缩好的那张。都没有返回 null（需要的话调 generate）。 */
    static Bitmap cover(Context c, Book b) {
        Bitmap bm = covers.get(b.path);
        if (bm != null && !bm.isRecycled()) return bm;
        if (b.noCover) return null;
        try {
            File f = coverFile(c, b);
            if (!f.isFile()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;   // 每像素 2 字节；墨水屏只有灰度，看不出差别
            bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bm != null) covers.put(b.path, bm);
            return bm;
        } catch (Throwable t) { return null; }
    }

    /** 这本书的封面还没生成过（既不在磁盘上，也没标记为“没有封面”）。 */
    static boolean coverMissing(Context c, Book b) {
        if (b.noCover) return false;
        Bitmap bm = covers.get(b.path);
        return (bm == null || bm.isRecycled()) && !coverFile(c, b).isFile();
    }

    private static final java.util.HashSet<String> generating = new java.util.HashSet<String>();

    /** 后台给这些书生成封面缩略图；全部做完后只通知一次（一次重画）。 */
    static void generate(Context c, List<Book> want) {
        final Context app = c.getApplicationContext();
        capCovers = lowRam(app) ? 18 : 24;
        final List<Book> todo = new ArrayList<Book>();
        for (Book b : want) if (coverMissing(app, b) && generating.add(b.path)) todo.add(b);
        if (todo.isEmpty()) return;
        final int cw = coverW(app), ch = coverH(app);
        worker.execute(new Runnable() {
            @Override public void run() {
                final Map<Book, Bitmap> made = new HashMap<Book, Bitmap>();
                for (Book b : todo) {
                    Bitmap bm = null;
                    try {
                        bm = Books.cover(b.file, cw, ch);
                        if (bm != null) {
                            File out = coverFile(app, b);
                            out.getParentFile().mkdirs();
                            File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
                            FileOutputStream fo = null;
                            try {
                                fo = new FileOutputStream(tmp);
                                bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
                                fo.flush();
                                fo.close();
                                fo = null;
                                if (!tmp.renameTo(out)) tmp.delete();
                            } finally {
                                if (fo != null) try { fo.close(); } catch (Throwable ignored) { }
                                if (tmp.exists()) tmp.delete();
                            }
                            if (bm.getConfig() != Bitmap.Config.RGB_565) {
                                Bitmap small = bm.copy(Bitmap.Config.RGB_565, false);
                                if (small != null) { bm.recycle(); bm = small; }
                            }
                        }
                    } catch (Throwable ignored) { }
                    made.put(b, bm);
                }
                prune(app);
                ui.post(new Runnable() {
                    @Override public void run() {
                        boolean any = false;
                        for (Map.Entry<Book, Bitmap> e : made.entrySet()) {
                            Book b = e.getKey();
                            generating.remove(b.path);
                            if (e.getValue() == null) b.noCover = true;
                            else { covers.put(b.path, e.getValue()); any = true; }
                        }
                        saveIndex(app);
                        if (any) changed(false);
                    }
                });
            }
        });
    }

    private static void prune(Context c) {
        try {
            File[] fs = Store.cache("covers").listFiles();
            int keep = lowRam(c) ? 120 : 300;
            if (fs == null || fs.length <= keep) return;
            java.util.Arrays.sort(fs, new Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            for (int i = 0; i < fs.length - keep; i++) fs[i].delete();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ open / delete

    /** 点一本书：直接进阅读器，回到上次的位置。只有用户自己在菜单里选了“每次询问”才问。 */
    static void open(final Activity a, final Book b) {
        String def = Store.prefs(a, "home").getString("reader_default", "builtin");
        if (!b.builtin() || ("koreader".equals(def) && koreaderInstalled(a))) { openKoreader(a, b); return; }
        if ("ask".equals(def)) {
            new VeraDialog.Builder(a).title(b.title)
                .items(new String[] { "用 VeraDroid 阅读器", koreaderInstalled(a) ? "用 KOReader 打开" : "安装 KOReader" }, new VeraDialog.OnPick() {
                    @Override public void onPick(int which) {
                        if (which == 0) openBuiltin(a, b);
                        else if (koreaderInstalled(a)) openKoreader(a, b);
                        else a.startActivity(new Intent(a, StoreActivity.class));
                    }
                }).negative("取消", null).show();
            return;
        }
        openBuiltin(a, b);
    }

    static void openBuiltin(Activity a, Book b) {
        adopt(a, b);
        touch(a, b);
        Intent i = new Intent(a, ReaderActivity.class);
        i.putExtra("path", b.path);
        i.putExtra("title", b.title);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ReaderActivity.opening = true;   // 状态栏等阅读器第一页一起出来（见 HomeActivity.onPause）
        try { a.startActivity(i); } catch (RuntimeException e) { ReaderActivity.opening = false; throw e; }
        a.overridePendingTransition(0, 0);
    }

    static boolean koreaderInstalled(Context c) {
        try { c.getPackageManager().getPackageInfo("org.koreader.launcher", 0); return true; }
        catch (Exception e) { return false; }
    }

    static void openKoreader(Activity a, Book b) {
        String n = b.path.toLowerCase(Locale.US);
        String mime = n.endsWith(".epub") ? "application/epub+zip"
            : n.endsWith(".pdf") ? "application/pdf"
            : n.endsWith(".cbz") ? "application/x-cbz"
            : n.endsWith(".mobi") || n.endsWith(".azw3") || n.endsWith(".azw") ? "application/x-mobipocket-ebook"
            : "*/*";
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(b.file), mime);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            android.content.pm.ResolveInfo ri = a.getPackageManager().resolveActivity(i, 0);
            if (ri != null && ri.activityInfo != null) {
                String targetPkg = ri.activityInfo.packageName;
                if (targetPkg != null && !targetPkg.equals("android") && !targetPkg.equals(a.getPackageName())) {
                    AppProf.prepareLaunch(a, targetPkg);
                }
            }
        } catch (Throwable ignored) { }
        // targetSdk 25：带 file:// 的 Intent 会被 StrictMode 拦下（FileUriExposedException），书一本也打不开。
        // KOReader / 静读天下都按文件路径记进度，所以仍给 file://，只在这一次 startActivity 时放开这条检查（/sdcard 对所有应用可读）。
        android.os.StrictMode.VmPolicy vp = android.os.StrictMode.getVmPolicy();
        android.os.StrictMode.setVmPolicy(new android.os.StrictMode.VmPolicy.Builder().build());
        try { a.startActivity(i); adopt(a, b); touch(a, b); }
        catch (ActivityNotFoundException e) { VeraToast.show(a, b.builtin() ? "还没装 KOReader，可在软件商店安装" : "这种格式要用 KOReader 打开，可在软件商店安装"); }
        catch (Exception e) { VeraToast.show(a, "打不开 KOReader：" + e.getMessage()); }
        finally { android.os.StrictMode.setVmPolicy(vp); }
    }

    private static final String[] READER_PFX = { "p:", "t:", "s:", "c:", "o:", "e:", "ch:", "st:", "fl:", "r:", "bm:", "d:" };

    /** 删书后同步清理该路径在 reader 偏好里的书签与全部进度键。 */
    static void purgeReaderState(Context c, String path) {
        if (c == null || path == null || path.length() == 0) return;
        try {
            SharedPreferences p = Store.prefs(c, "reader");
            SharedPreferences.Editor e = null;
            for (String pfx : READER_PFX) {
                String k = pfx + path;
                if (p.contains(k)) {
                    if (e == null) e = p.edit();
                    e.remove(k);
                }
            }
            if (e != null) e.apply();
        } catch (Throwable ignored) { }
    }

    /** 文件已在后台删掉后，在主线程同步移除书库内存索引。 */
    static void removeFromIndex(Context c, String path) {
        if (path == null) return;
        Book b = known.remove(path);
        if (b == null) return;
        books.remove(b);
        covers.remove(path);
        saveIndex(c);
        changed(true);
    }

    static boolean delete(Context c, Book b) {
        if (!b.file.delete()) return false;
        purgeReaderState(c, b.path);
        books.remove(b);
        known.remove(b.path);
        covers.remove(b.path);
        saveIndex(c);
        changed(true);
        return true;
    }

    // ------------------------------------------------------------------ names

    static boolean isBook(String n) {
        String s = n.toLowerCase(Locale.US);
        // 越狱工具放在 Kindle 书库里的标记文件，不是书
        if (s.startsWith("jailbroken") || s.startsWith(".")) return false;
        // Kindle 自己往 documents 里丢的崩溃报告（KPPMainApp…_crash_….txt）也不是书
        if (s.contains("_crash_") || s.startsWith("kppmainapp")) return false;
        return s.endsWith(".epub") || s.endsWith(".pdf") || s.endsWith(".txt")
            || s.endsWith(".mobi") || s.endsWith(".azw3") || s.endsWith(".azw")
            || s.endsWith(".kfx") || s.endsWith(".prc");
    }

    static String base(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static String ext(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1).toUpperCase(Locale.US) : "BOOK";
    }
}
