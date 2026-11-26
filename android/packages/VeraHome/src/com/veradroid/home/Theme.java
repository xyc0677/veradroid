// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 桌面的自定义外观：壁纸、应用图标、状态栏。
 *
 *   /sdcard/.vdhome/theme/                 （= /data/media/0/.vdhome/theme，VeraHome 自己的目录）
 *     wallpaper.png|jpg|jpeg|webp          当前壁纸（设置里选的图会复制到这里；传书网页“主题”页上传的也放这里）
 *     icons/<包名>.png                     替换某个应用的图标（长按应用 → 更换图标；或网页上传 icon-<包名>.png）
 *     statusbar.json                       {"clock":true,"battery":true,"clock_format":"HH:mm","text":"我的 Kindle"}
 *   /sdcard/VeraDroid/wallpaper/           放图片的地方：选壁纸 / 图标时最先列出这里的图片（网页或数据线都可以往里放）
 *   /sdcard/VeraDroid/icons/<包名>.png     直接放进来就替换那个应用的图标（优先级低于上面 theme/icons）
 *   /sdcard/Icons/<包名>.png               老版本的位置，仍然认
 *
 * 壁纸只解码一次：按屏幕大小缩好、转成灰度、按“深浅”压淡，存成一张 8 位的“墨量”图（ALPHA_8，PW6 上 2 MB，
 * 原来的 RGB_565 是 4 MB），画的时候白底上用黑色按墨量画。处理好的结果另存一份在 cache/wall/，开机时直接读回来，
 * 不再每次开机解一遍几 MB 的 JPEG。原图从不留在内存里。
 */
final class Theme {
    static final String DIR = "/data/media/0/.vdhome/theme";   // owned by VeraHome (.veradroid is root 0700)
    static final String USER_DIR = "/data/media/0/VeraDroid";
    static final String USER_WALL = USER_DIR + "/wallpaper";
    static final String USER_ICONS = USER_DIR + "/icons";
    static final String LEGACY_ICONS = "/data/media/0/Icons";
    private static final long MAX_FILE = 8L * 1024 * 1024;
    private static final String[] WALL_NAMES = { "wallpaper.png", "wallpaper.jpg", "wallpaper.jpeg", "wallpaper.webp" };

    /** 壁纸怎么铺：铺满（居中裁掉多出来的）、完整显示（留白边）、原大小居中。偏好键 wallmode。 */
    static final int FIT_FILL = 0, FIT_WHOLE = 1, FIT_CENTER = 2;
    /** “壁纸深浅”（偏好键 wallpaperdim，0..4 = 20% / 30% / 50% / 70% / 原图）盖的白色有多浓。 */
    private static final int[] VEIL_ALPHA = { 180, 160, 128, 77, 0 };

    private static volatile Bitmap wall;   // ALPHA_8，墨量
    private static volatile String wallKey = "";
    private static volatile int bump;      // 应用里自己改了主题：version() 跟着变
    private static Context app;
    private static int lastW, lastH;

    private Theme() { }

    private static File dir() { File d = new File(DIR); d.mkdirs(); return d; }
    private static File icons() { File d = new File(dir(), "icons"); d.mkdirs(); return d; }

    /**
     * 主题变了没有。只看几个目录的修改时间（文件换了名字 / 加了 / 删了，目录时间都会变；我们自己和传书网页写文件
     * 都是先写临时文件再改名），不再每次把目录列一遍。
     */
    static long version() {
        long v = bump;
        for (String d : new String[] { DIR, DIR + "/icons", USER_ICONS, LEGACY_ICONS }) v = v * 31 + new File(d).lastModified();
        return v;
    }

    static void touched() { bump++; SwitcherActivity.ICONS.clear(); }

    static File wallFile() {
        for (String n : WALL_NAMES) {
            File f = new File(DIR, n);
            if (f.isFile()) return f;
        }
        return null;
    }

    static boolean hasWallpaper() { return wallFile() != null; }

    private static int dimOf(Context c) {
        int dim = Store.prefs(c, "home").getInt("wallpaperdim", 2);
        return dim < 0 || dim >= VEIL_ALPHA.length ? 2 : dim;
    }

    private static int fitOf(Context c) {
        int m = Store.prefs(c, "home").getInt("wallmode", FIT_FILL);
        return m < FIT_FILL || m > FIT_CENTER ? FIT_FILL : m;
    }

    private static String keyFor(File f, int w, int h, int fit, int dim) {
        return f.getName() + "|" + f.lastModified() + "|" + f.length() + "|" + w + "x" + h + "|" + fit + "|" + dim;
    }

    private static File cacheDir() { return Store.cache("wall"); }
    private static File cacheFile(String key) { return new File(cacheDir(), Integer.toHexString(key.hashCode()) + ".a8"); }

    /**
     * 主页背景，或 null（没有壁纸）。内存里有就直接用；没有就读处理好的缓存（几十毫秒）；都没有才解码原图（只在
     * 换了图 / 换了铺法 / 换了深浅之后的第一次，而这些在设置里一改就已经在后台先做好了）。
     */
    static synchronized Drawable wallpaper(Context c, int w, int h) {
        app = c.getApplicationContext();
        lastW = w; lastH = h;
        Bitmap b = wallBitmap(c, w, h);
        return b == null ? null : new WallDrawable(b);
    }

    /**
     * 主线程用：内存里已经处理好的那张（和现在的图、铺法、深浅、屏幕大小都对得上）就返回它，否则返回 null，绝不等。
     * wallpaper() 是 synchronized 的：后台正在处理新壁纸（传书网页换了图、设置里改了深浅 → prepareAsync / setWallpaper）时，
     * 主线程回到主页要等那张图解码 + 逐像素处理完（Kindle 上 1–5 秒），没有缓存时主线程还会自己解码原图。
     * 拿不到时调用方先用白底，再 prepareAsync，做好后再贴上去。
     */
    static Drawable wallpaperIfReady(Context c, int w, int h) {
        app = c.getApplicationContext();
        lastW = w; lastH = h;
        File f = wallFile();
        if (f == null || w <= 0 || h <= 0) return null;
        Bitmap b = wall; String k = wallKey;
        if (b == null || b.isRecycled() || !keyFor(f, w, h, fitOf(c), dimOf(c)).equals(k)) return null;
        return new WallDrawable(b);
    }

    private static synchronized Bitmap wallBitmap(Context c, int w, int h) {
        File f = wallFile();
        if (f == null || w <= 0 || h <= 0) { dropWall(); return null; }
        String key = keyFor(f, w, h, fitOf(c), dimOf(c));
        if (wall != null && key.equals(wallKey)) return wall;
        Bitmap b = readCache(key, w, h);
        if (b == null) b = build(f, w, h, fitOf(c), dimOf(c), key);
        if (b == null) return null;
        dropWall();
        wall = b; wallKey = key;
        return wall;
    }

    private static void dropWall() {
        // 不在后台线程 recycle()：旧壁纸可能正被主线程上的 WallDrawable.draw() 画着，直接丢掉引用交给 GC 回收
        wall = null; wallKey = "";
    }

    /** 在后台把当前设置下的壁纸先处理好（换了图、铺法、深浅时调用）。done 在主线程跑。 */
    static void prepareAsync(Context c, final Runnable done) {
        final Context a = c.getApplicationContext();
        final int w = lastW > 0 ? lastW : a.getResources().getDisplayMetrics().widthPixels;
        final int h = lastH > 0 ? lastH : a.getResources().getDisplayMetrics().heightPixels;
        new Thread(new Runnable() {
            @Override public void run() {
                Ui.lowPriority();
                try { wallBitmap(a, w, h); } catch (Throwable ignored) { }
                if (done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
            }
        }, "wall-prepare").start();
    }

    private static Bitmap readCache(String key, int w, int h) {
        File f = cacheFile(key);
        if (!f.isFile()) return null;
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            if (f.length() != b.getByteCount()) { b.recycle(); return null; }
            byte[] buf = new byte[b.getByteCount()];
            FileInputStream in = new FileInputStream(f);
            int n = 0;
            try { while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; } }
            finally { in.close(); }
            if (n != buf.length) { b.recycle(); return null; }
            b.copyPixelsFromBuffer(ByteBuffer.wrap(buf));
            return b;
        } catch (Throwable t) { return null; }
    }

    /** 解码原图（不超过需要的大小）→ 按铺法画到屏幕大小 → 灰度、压淡 → 8 位墨量图，并存一份缓存。 */
    private static Bitmap build(File f, int w, int h, int fit, int dim, String key) {
        return buildInk(f, w, h, fit, 255 - VEIL_ALPHA[dim], cacheFile(key), true, false);
    }

    /**
     * 任意一张图 → 屏幕大小的 8 位墨量图（白纸上按墨量画黑）：keep = 最深处的墨量（255 = 原图，越小越淡）。
     * 阅读器的“阅读背景”也用这一套（和壁纸同样只解码一次、存缓存、原图不留在内存里）。在后台线程调用。
     */
    static Bitmap inkImage(File f, int w, int h, int keep, File cache) {
        Bitmap b = readInk(cache, w, h);
        return b != null ? b : buildInk(f, w, h, FIT_FILL, keep, cache, false, true);
    }

    /** Bayer 4×4 有序抖动的门限（0..15）。 */
    static final int[] BAYER = { 0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5 };

    private static Bitmap readInk(File f, int w, int h) {
        if (f == null || !f.isFile()) return null;
        try {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            if (f.length() != b.getByteCount()) { b.recycle(); return null; }
            byte[] buf = new byte[b.getByteCount()];
            FileInputStream in = new FileInputStream(f);
            int n = 0;
            try { while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; } }
            finally { in.close(); }
            if (n != buf.length) { b.recycle(); return null; }
            b.copyPixelsFromBuffer(ByteBuffer.wrap(buf));
            return b;
        } catch (Throwable t) { return null; }
    }

    private static Bitmap buildInk(File f, int w, int h, int fit, int keep, File cache, boolean wallCache, boolean dots) {
        Bitmap src = null, rgb = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sw = o.outWidth, sh = o.outHeight;
            if (sw <= 0 || sh <= 0) return null;
            boolean png = o.outMimeType != null && o.outMimeType.contains("png");
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inPreferredConfig = png ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;   // JPEG 直接解成 565，省一半
            if (fit == FIT_CENTER && (sw > w || sh > h)) {
                // 原大小居中、图又比屏幕大：只解中间屏幕那么大的一块
                BitmapRegionDecoder rd = BitmapRegionDecoder.newInstance(f.getAbsolutePath(), false);
                int cw = Math.min(sw, w), chh = Math.min(sh, h);
                src = rd.decodeRegion(new Rect((sw - cw) / 2, (sh - chh) / 2, (sw + cw) / 2, (sh + chh) / 2), d);
                rd.recycle();
            } else {
                int s = 1;
                if (fit != FIT_CENTER) {
                    // 铺满：两边都不小于屏幕；完整显示：长的那边不小于屏幕
                    if (fit == FIT_FILL) while (sw / (s * 2) >= w && sh / (s * 2) >= h) s *= 2;
                    else while (sw / (s * 2) >= w || sh / (s * 2) >= h) s *= 2;
                }
                d.inSampleSize = s;
                src = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            }
            if (src == null) return null;
            rgb = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas cv = new Canvas(rgb);
            cv.drawColor(Color.WHITE);
            float bw = src.getWidth(), bh = src.getHeight(), k;
            if (fit == FIT_FILL) k = Math.max(w / bw, h / bh);
            else if (fit == FIT_WHOLE) k = Math.min(w / bw, h / bh);
            else k = 1f;
            float dw = bw * k, dh = bh * k;
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            cv.drawBitmap(src, null, new RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), p);
            src.recycle(); src = null;
            // 灰度 + 白色罩子 → 墨量：ink = (255 - 亮度) * (255 - 罩子) / 255
            Bitmap a8 = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            int stride = a8.getRowBytes();
            byte[] out = new byte[stride * h];
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                rgb.getPixels(row, 0, w, 0, y, w, 1);
                int base = y * stride;
                for (int x = 0; x < w; x++) {
                    int c = row[x];
                    int lum = (((c >> 16) & 0xff) * 77 + ((c >> 8) & 0xff) * 150 + (c & 0xff) * 29) >> 8;
                    int ink = (255 - lum) * keep / 255;
                    // 网点（阅读背景）：只有纯黑点和白纸。墨水屏的“对比度”会把很淡的灰直接拉成白，
                    // 纯黑点不受影响；Bayer 4×4 有序抖动，墨量多少就是点有多密
                    if (dots) ink = ink * 16 / 256 > BAYER[(y & 3) * 4 + (x & 3)] ? 255 : 0;
                    out[base + x] = (byte) ink;
                }
            }
            rgb.recycle(); rgb = null;
            a8.copyPixelsFromBuffer(ByteBuffer.wrap(out));
            if (wallCache) writeCache(cache, out);
            else if (cache != null) writeFile(cache, out);
            return a8;
        } catch (Throwable t) {
            android.util.Log.w("VeraHome", "wallpaper: " + t);
            return null;
        } finally {
            if (src != null && !src.isRecycled()) src.recycle();
            if (rgb != null && !rgb.isRecycled()) rgb.recycle();
        }
    }

    private static void writeCache(File f, byte[] data) {
        File d = cacheDir();
        File[] old = d.listFiles();
        if (old != null) for (File o : old) o.delete();   // 只留当前这一张
        writeFile(f, data);
    }

    private static void writeFile(File f, byte[] data) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(data); } finally { o.close(); }
            tmp.renameTo(f);
        } catch (Throwable t) { tmp.delete(); }
    }

    /** 白纸上按墨量画黑色：一张 8 位图，画起来和画一块纯色差不多快。 */
    static final class WallDrawable extends Drawable {
        private final Bitmap ink;
        private final Paint paint = new Paint();
        WallDrawable(Bitmap b) { ink = b; paint.setColor(Color.BLACK); }
        @Override public void draw(Canvas c) {
            // 底下的白纸是窗口背景（HomeActivity 的窗口背景是白色），这里不再先涂一遍白：一屏只画一遍
            Rect r = getBounds();
            if (ink.isRecycled()) return;
            if (r.width() == ink.getWidth() && r.height() == ink.getHeight()) c.drawBitmap(ink, r.left, r.top, paint);
            else c.drawBitmap(ink, null, r, paint);
        }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 把一张图设成壁纸：复制进主题目录（原图删了也不影响），然后在后台处理好。 */
    static void setWallpaper(Context c, final File src, final Done done) {
        final Context a = c.getApplicationContext();
        final int w = lastW > 0 ? lastW : a.getResources().getDisplayMetrics().widthPixels;
        final int h = lastH > 0 ? lastH : a.getResources().getDisplayMetrics().heightPixels;
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                Ui.lowPriority();
                String err = null;
                String n = src.getName().toLowerCase(Locale.US);
                String ext = n.endsWith(".png") ? "png" : n.endsWith(".webp") ? "webp" : "jpg";
                try {
                    place("wallpaper." + ext, new FileInputStream(src), src.length());
                    touched();
                    if (wallBitmap(a, w, h) == null) { clearWallpaper(); err = "这张图打不开"; }
                } catch (Throwable t) { err = "设置失败：" + t.getMessage(); }
                final String e = err;
                ui.post(new Runnable() { @Override public void run() { done.done(e); } });
            }
        }, "wall-set").start();
    }

    interface Done { void done(String error); }

    static void clearWallpaper() {
        for (String o : WALL_NAMES) new File(DIR, o).delete();
        synchronized (Theme.class) { dropWall(); }
        File[] old = cacheDir().listFiles();
        if (old != null) for (File o : old) o.delete();
        touched();
    }

    // ------------------------------------------------------------------ icons

    /** 这个应用的自定义图标文件（主题目录 → /sdcard/VeraDroid/icons → 老的 /sdcard/Icons），没有就 null。后台线程也能调。 */
    static File iconFile(String pkg) {
        if (pkg == null) return null;
        for (String d : new String[] { DIR + "/icons", USER_ICONS, LEGACY_ICONS }) {
            File f = new File(d, pkg + ".png");
            if (f.isFile()) return f;
        }
        return null;
    }

    /** 应用里设的（可以“恢复默认”删掉的）那一个。 */
    static boolean hasOwnIcon(String pkg) { return new File(DIR + "/icons", pkg + ".png").isFile(); }

    /** 解码到不小于 size 的最小尺寸（不解原图大小），给图标用。 */
    static Bitmap decodeAtLeast(File f, int size) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= size && o.outHeight / (s * 2) >= size) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) { return null; }
    }

    /** 选了一张图当某个应用的图标：裁成正方形、缩到 256 像素存成 PNG。返回 null 表示成功，否则是原因。 */
    static String setIcon(String pkg, File src) {
        Bitmap b = decodeAtLeast(src, 256);
        if (b == null) return "这张图打不开";
        Bitmap sq = null;
        try {
            int side = Math.min(b.getWidth(), b.getHeight()), out = Math.min(256, side);
            sq = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(sq);
            Rect from = new Rect((b.getWidth() - side) / 2, (b.getHeight() - side) / 2, (b.getWidth() + side) / 2, (b.getHeight() + side) / 2);
            cv.drawBitmap(b, from, new Rect(0, 0, out, out), new Paint(Paint.FILTER_BITMAP_FLAG));
            File dest = new File(icons(), pkg + ".png"), tmp = new File(icons(), "." + pkg + ".png.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { sq.compress(Bitmap.CompressFormat.PNG, 100, o); } finally { o.close(); }
            if (!tmp.renameTo(dest)) return "保存失败";
            dest.setReadable(true, false);
            touched();
            return null;
        } catch (Throwable t) {
            return "保存失败：" + t.getMessage();
        } finally {
            b.recycle();
            if (sq != null) sq.recycle();
        }
    }

    static void clearIcon(String pkg) {
        new File(DIR + "/icons", pkg + ".png").delete();
        touched();
    }

    // ------------------------------------------------------------------ picking images

    /** 选壁纸 / 图标时列出的图片：常放图片的几个文件夹（含 Kindle 的 documents），新的在前，最多 200 张。后台线程调用。 */
    static List<File> findImages() {
        final List<File> out = new ArrayList<File>();
        new File(USER_WALL).mkdirs();
        String[][] roots = {
            { USER_WALL, "2" }, { USER_DIR, "1" }, { "/data/media/0/Pictures", "2" }, { "/data/media/0/Download", "1" },
            { "/data/media/0/DCIM", "2" }, { Transfer.BOOKS, "1" }, { Transfer.KINDLE, "1" }, { "/data/media/0", "0" } };
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (String[] r : roots) collect(new File(r[0]), Integer.parseInt(r[1]), out, seen);
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                boolean ua = a.getPath().startsWith(USER_WALL), ub = b.getPath().startsWith(USER_WALL);
                if (ua != ub) return ua ? -1 : 1;                       // VeraDroid/wallpaper 里的排最前
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out.size() > 200 ? new ArrayList<File>(out.subList(0, 200)) : out;
    }

    private static void collect(File d, int depth, List<File> out, java.util.Set<String> seen) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) { if (depth > 0) collect(f, depth - 1, out, seen); continue; }
            String l = n.toLowerCase(Locale.US);
            if ((l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp") || l.endsWith(".bmp") || l.endsWith(".gif"))
                && f.length() > 0 && f.length() <= MAX_FILE && seen.add(f.getAbsolutePath())) out.add(f);
            if (out.size() > 600) return;
        }
    }

    /** 列表里给一张图片标的出处。 */
    static String placeLabel(File f) {
        String p = f.getParent() == null ? "" : f.getParent();
        if (p.startsWith(USER_WALL)) return "VeraDroid/wallpaper";
        if (p.startsWith(Transfer.KINDLE)) return "Kindle 文档" + p.substring(Transfer.KINDLE.length());
        if (p.startsWith("/data/media/0/")) return p.substring("/data/media/0/".length());
        if (p.equals("/data/media/0")) return "内部存储";
        return p;
    }

    // ------------------------------------------------------------------ status bar / theme upload (transfer page)

    static JSONObject statusBar() {
        try {
            File f = new File(DIR, "statusbar.json");
            if (!f.isFile() || f.length() > 64 * 1024) return new JSONObject();
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            in.close();
            return new JSONObject(new String(b, 0, n, "UTF-8"));
        } catch (Throwable t) { return new JSONObject(); }
    }

    /** Import an uploaded file. Returns a short message for the web page. */
    static String importFile(File f) { return importFile(app, f); }

    static String importFile(Context c, File f) {
        if (c != null && app == null) app = c.getApplicationContext();
        String n = f.getName();
        // uniqueFile() may have added "(1)" etc.; strip it again
        n = n.replaceAll("\\s*\\(\\d+\\)(?=\\.[A-Za-z0-9]+$)", "");
        boolean[] sbChanged = { false };
        try {
            String r;
            boolean changed = false;
            if (n.toLowerCase(Locale.US).endsWith(".zip")) {
                r = importZip(f, sbChanged);
                changed = !r.contains("没有可用");
            } else {
                r = place(n, new FileInputStream(f), f.length(), sbChanged);
                if (r == null) return "不认识这个文件：请用 wallpaper.jpg、icon-包名.png、statusbar.json 或 .zip 主题包";
                changed = true;
            }
            if (changed) touched();
            if (sbChanged[0] && app != null) StatusBarService.refresh(app);
            // 网页换了壁纸：趁这会儿在后台处理好，回到主页时只是读一张缓存
            if (app != null && hasWallpaper()) prepareAsync(app, null);
            return r;
        } catch (Throwable t) {
            return "导入失败：" + t.getMessage();
        } finally {
            f.delete();
        }
    }

    private static String importZip(File f, boolean[] sbChanged) throws Exception {
        int ok = 0;
        ZipInputStream z = new ZipInputStream(new FileInputStream(f));
        try {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (name.contains("..")) continue;                 // no path traversal
                String base = name.substring(name.lastIndexOf('/') + 1);
                String lowerPath = name.toLowerCase(Locale.US);
                if (lowerPath.startsWith("icons/") || lowerPath.contains("/icons/")) base = "icon-" + base;
                if (place(base, z, -1, sbChanged) != null) ok++;
            }
        } finally { z.close(); }
        return ok == 0 ? "主题包里没有可用的文件" : "主题已导入（" + ok + " 个文件）";
    }

    /** Store one recognised file; null if the name is not part of a theme. Does not close {@code in} for zips. */
    private static String place(String name, InputStream in, long size) throws Exception {
        return place(name, in, size, null);
    }

    private static String place(String name, InputStream in, long size, boolean[] sbChanged) throws Exception {
        String lower = name.toLowerCase(Locale.US);
        File dest; String msg;
        if (lower.matches("wallpaper\\.(png|jpg|jpeg|webp)")) {
            dest = new File(dir(), lower); msg = "壁纸已更换";
        } else if (lower.startsWith("icon-") && lower.endsWith(".png")) {
            String pkg = name.substring(5, name.length() - 4);
            if (!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) { if (size >= 0) in.close(); return null; }
            dest = new File(icons(), pkg + ".png"); msg = "已替换 " + pkg + " 的图标";
        } else if (lower.equals("statusbar.json")) {
            dest = new File(dir(), "statusbar.json"); msg = "状态栏设置已更新";
        } else { if (size >= 0) in.close(); return null; }   // size >= 0：调用方交过来的是文件流，由这里关（以前不认识的名字 / 太大时漏一个 fd）
        if (size > MAX_FILE) { in.close(); throw new Exception("文件太大（最大 8 MB）"); }
        File tmp = new File(dest.getParentFile(), "." + dest.getName() + ".tmp");
        FileOutputStream out;
        try { out = new FileOutputStream(tmp); } catch (Exception e) { if (size >= 0) in.close(); throw e; }
        byte[] buf = new byte[65536]; long done = 0; int r;
        try {
            while ((r = in.read(buf)) > 0) {
                done += r;
                if (done > MAX_FILE) throw new Exception("文件太大（最大 8 MB）");
                out.write(buf, 0, r);
            }
        } catch (Exception e) { out.close(); tmp.delete(); throw e;
        } finally { out.close(); if (size >= 0) in.close(); }
        if (lower.equals("statusbar.json")) new JSONObject(new String(readAll(tmp), "UTF-8"));   // must be valid JSON
        // 新壁纸到了才删掉别的扩展名的旧壁纸（以前先删再写，写失败就没有壁纸了）
        if (lower.startsWith("wallpaper.")) for (String o : WALL_NAMES) if (!o.equals(lower)) new File(dir(), o).delete();
        if (!tmp.renameTo(dest)) throw new Exception("保存失败");
        dest.setReadable(true, false);
        if (lower.equals("statusbar.json") && sbChanged != null) sbChanged[0] = true;
        return msg;
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int n = 0; while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
        in.close();
        return b;
    }

    /** Back to the built-in look. */
    static void reset() {
        File d = new File(DIR);
        File[] ic = new File(d, "icons").listFiles();
        if (ic != null) for (File f : ic) f.delete();
        clearWallpaper();
        new File(d, "statusbar.json").delete();
        new File(d, ".reset").delete();
        try { new FileOutputStream(new File(dir(), ".reset")).close(); } catch (Exception ignored) { }   // bumps version()
        touched();
        if (app != null) StatusBarService.refresh(app);
    }
}
