// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * More of the management page (feat/web-ux): move / copy, mkdir -p for folder uploads, zip download, the small text
 * editor, thumbnails and previews, wallpaper / icon management, app pre-compilation.
 * Same rules as WebAdmin: Transfer.route() has checked the PIN session and (for POST) the CSRF token; every path goes
 * through WebAdmin.resolve() (no "..", no hidden segments, no symlinks), read-only folders stay read-only, and the
 * blocked names (scripts, dotfiles, SSH material) can never be created, changed, copied, zipped or edited.
 */
final class WebFiles {
    static final long ZIP_MAX = 1L << 30;              // 1 GiB per zip download
    static final int TEXT_MAX = 512 * 1024;             // editor: files up to 512 KB
    static final String[] TEXT_EXT = { "txt", "md", "json", "conf", "css", "opf", "ncx", "csv", "ini", "xml", "log", "yml", "yaml" };
    static final String[] IMG_EXT = { "jpg", "jpeg", "png", "webp", "gif", "bmp" };

    private final Context ctx;
    private final Transfer t;
    private final SecureRandom rnd = new SecureRandom();

    WebFiles(Transfer t, Context c) { this.t = t; this.ctx = c; }

    static String ext(String n) {
        int d = n.lastIndexOf('.');
        return d < 0 ? "" : n.substring(d + 1).toLowerCase(Locale.US);
    }
    static boolean in(String e, String[] list) { for (String x : list) if (x.equals(e)) return true; return false; }

    boolean handleGet(Transfer.Req r, OutputStream out) throws Exception {
        String p = r.path;
        if (p.equals("/api/fs/zip")) { zip(r.q.get("root"), r.q.get("path"), r.q.get("names"), out); return true; }
        if (p.equals("/api/fs/text")) { Transfer.json(out, 200, textRead(r.q.get("root"), r.q.get("path"))); return true; }
        if (p.equals("/api/fs/thumb")) { thumb(r.q.get("root"), r.q.get("path"), num(r.q.get("w"), 240), out); return true; }
        if (p.equals("/api/theme/list")) { Transfer.json(out, 200, themeList()); return true; }
        if (p.equals("/api/theme/thumb")) { themeThumb(r.q.get("kind"), r.q.get("name"), num(r.q.get("w"), 240), out); return true; }
        if (p.equals("/api/compile")) { Transfer.json(out, 200, compileStatus()); return true; }
        return false;
    }

    /** POST bodies are read here (the editor needs more than WebAdmin's 16 KB). */
    boolean handlePost(Transfer.Req r, java.io.InputStream in, OutputStream out) throws Exception {
        String p = r.path;
        if (!(p.equals("/api/fs/move") || p.equals("/api/fs/mkdirp") || p.equals("/api/fs/text") || p.startsWith("/api/theme/")
            || p.equals("/api/app/compile"))) return false;
        if (p.equals("/api/theme/icon/clear")) return false;   // WebAdmin
        JSONObject b;
        try { b = new JSONObject(new String(t.body(r, in, p.equals("/api/fs/text") ? TEXT_MAX * 6 + 4096 : 16384), "UTF-8")); }
        catch (Exception e) { Transfer.json(out, 200, Transfer.obj("ok", false, "msg", "请求太大或格式不对")); return true; }
        JSONObject res;
        if (p.equals("/api/fs/move")) res = move(b);
        else if (p.equals("/api/fs/mkdirp")) res = mkdirp(b.optString("root"), b.optString("path"), b.optString("rel"));
        else if (p.equals("/api/fs/text")) res = textWrite(b);
        else if (p.equals("/api/theme/wallpaper/set")) res = wallSet(b.optString("name"));
        else if (p.equals("/api/theme/wallpaper/clear")) { Theme.clearWallpaper(); res = Transfer.obj("ok", true); }
        else if (p.equals("/api/theme/gallery/delete")) res = galleryDelete(b.optString("name"));
        else if (p.equals("/api/theme/icon/set")) res = iconSet(b.optString("pkg"), b.optString("name"));
        else if (p.equals("/api/app/compile")) res = compile(b.optString("pkg"));
        else res = Transfer.obj("ok", false, "msg", "不支持");
        Transfer.json(out, 200, res);
        return true;
    }

    private static int num(String s, int d) { try { return Integer.parseInt(s); } catch (Exception e) { return d; } }
    private static JSONObject err(String m) { return Transfer.obj("ok", false, "msg", m); }

    // ================================================================== move / copy

    private static boolean specialRootEntry(String which, File dir, String name) {
        return !"kindle".equals(which) && dir.equals(WebAdmin.rootDir(which))
            && (name.equals("KindleLibrary") || name.equals("KindleFonts") || name.equals("Fonts"));
    }

    private JSONObject move(JSONObject b) throws Exception {
        String sr = b.optString("root"), sp = b.optString("path"), dr = b.optString("toRoot", sr), dp = b.optString("toPath");
        boolean copy = b.optBoolean("copy", false);
        JSONArray names = b.optJSONArray("names");
        File sd = WebAdmin.resolve(sr, sp), dd = WebAdmin.resolve(dr, dp);
        if (sd == null || !sd.isDirectory()) return err("原来的文件夹不存在");
        if (dd == null || !dd.isDirectory()) return err("目标文件夹不存在");
        if (!copy) { String ro = WebAdmin.readOnlyWhy(sr, sd); if (ro != null) return err(ro); }
        String ro2 = WebAdmin.readOnlyWhy(dr, dd);
        if (ro2 != null) return err("目标文件夹：" + ro2);
        if (names == null || names.length() == 0 || names.length() > 500) return err("没有选文件");
        String ddc = dd.getCanonicalPath();
        int done = 0;
        JSONArray failed = new JSONArray();
        for (int i = 0; i < names.length(); i++) {
            String n = names.optString(i);
            if (n.length() == 0 || n.indexOf('/') >= 0 || n.startsWith(".") || WebAdmin.isProtected(n) || specialRootEntry(sr, sd, n)) { failed.put(n + "（受保护）"); continue; }
            File f = WebAdmin.resolve(sr, (sp == null ? "" : sp) + "/" + n);
            if (f == null || !f.exists()) { failed.put(n + "（不存在或是链接）"); continue; }
            String fc = f.getCanonicalPath();
            if (f.isDirectory() && (ddc.equals(fc) || ddc.startsWith(fc + "/"))) { failed.put(n + "（不能放进它自己里面）"); continue; }
            if (!copy && sd.getCanonicalPath().equals(ddc)) { failed.put(n + "（已经在这个文件夹里）"); continue; }
            if (f.isDirectory() && (WebAdmin.readOnlyWhy(sr, f) != null || lockedInside(f, 0, new int[] { 200000 }))) { failed.put(n + "（里面有脚本或受保护的文件）"); continue; }
            File target = unique(dd, n);
            boolean ok = !copy && f.renameTo(target);
            if (!ok) {   // 复制；或者跨文件系统的移动（Android 存储 <-> Kindle 书库）：复制后删除
                long need = f.isDirectory() ? size(f, 0) : f.length();
                if (need > dd.getUsableSpace() - 64L * 1024 * 1024) { failed.put(n + "（空间不够）"); continue; }
                // 移动时隐藏文件也搬过去：后面会把原文件夹整个删掉，以前 .nomedia、应用的点文件就这样丢了（lockedInside 已经拦下了危险的点文件）
                int[] skipped = { 0 };
                ok = copyTree(f, target, 0, !copy, skipped);
                if (!ok && !f.isDirectory() && "kindle".equals(dr) && Transfer.isKindleRoot(dd)) {
                    String putName = Transfer.libputFile(ctx, f, target.getName());
                    if (putName != null) {
                        target = new File(dd, putName);
                        ok = true;
                    }
                }
                if (!ok) deleteTree(target, 0);
                else if (!copy && skipped[0] > 0) { failed.put(n + "（已复制过去；里面有隐藏的文件夹或脚本没有搬，原文件夹留着）"); continue; }
                else if (!copy) ok = deleteTree(f, 0);
            }
            if (ok) {
                if (!copy && !target.isDirectory() && Lib.isBook(f.getName())) BlocksFiles.migrateReaderPrefs(ctx, f.getAbsolutePath(), target.getAbsolutePath());
                done++;
            } else failed.put(n);
        }
        if (done > 0) Lib.scan(ctx, 0);
        return Transfer.obj("ok", failed.length() == 0, "done", done, "failed", failed, "msg", failed.length() == 0 ? "" : "有些没有完成");
    }

    static boolean lockedInside(File d, int depth, int[] budget) {
        if (depth > 64 || --budget[0] <= 0) return true;
        File[] fs = d.listFiles();
        if (fs == null) return true;
        for (File f : fs) {
            if (WebAdmin.isLink(f)) continue;
            String n = f.getName();
            if (".".equals(n) || "..".equals(n)) continue;
            if (f.isDirectory()) {
                if (WebAdmin.isProtected(n) || lockedInside(f, depth + 1, budget)) return true;
                continue;
            }
            // 普通的隐藏文件（.DS_Store、.nomedia…）不算：复制时本来就跳过；以点开头的 .sh / 受保护文件照样拦
            if (n.startsWith(".") && !Transfer.isScript(n.substring(1)) && !WebAdmin.isProtected(n.substring(1))) continue;
            if (WebAdmin.isProtected(n)) return true;
        }
        return false;
    }

    private static long size(File d, int depth) {
        if (depth > 64) return 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        long s = 0;
        for (File f : fs) { if (WebAdmin.isLink(f)) continue; s += f.isDirectory() ? size(f, depth + 1) : f.length(); }
        return s;
    }

    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 2; i < 1000; i++) { File g = new File(dir, base + " (" + i + ")" + ext); if (!g.exists()) return g; }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static boolean copyTree(File src, File dst, int depth, boolean dots, int[] skipped) {
        if (depth > 64 || WebAdmin.isLink(src)) return false;
        if (src.isDirectory()) {
            if (!dst.mkdir()) return false;
            dst.setReadable(true, false); dst.setWritable(true, false); dst.setExecutable(true, false);
            File[] fs = src.listFiles();
            if (fs == null) return false;
            for (File f : fs) {
                String fn = f.getName();
                // 点文件只搬普通文件（不搬隐藏文件夹：lockedInside 不检查它们里面），脚本 / 受保护的名字永远不搬
                if (WebAdmin.isLink(f) || (fn.startsWith(".") && (!dots || f.isDirectory() || Transfer.isScript(fn.substring(1)) || WebAdmin.isProtected(fn.substring(1))))) {
                    if (dots && !WebAdmin.isLink(f)) skipped[0]++;
                    continue;
                }
                if (!copyTree(f, new File(dst, f.getName()), depth + 1, dots, skipped)) return false;
            }
            return true;
        }
        File tmp = new File(dst.getParentFile(), ".vdcopy-" + Long.toHexString(System.nanoTime()));
        InputStream in = null;
        OutputStream o = null;
        try {
            in = new FileInputStream(src);
            o = new FileOutputStream(tmp);
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.close(); o = null;
            in.close(); in = null;
            if (tmp.length() != src.length()) { tmp.delete(); return false; }
            tmp.setReadable(true, false);
            tmp.setLastModified(src.lastModified());
            if (tmp.renameTo(dst)) return true;
        } catch (IOException e) { /* fall through */ }
        finally {
            if (o != null) try { o.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
        tmp.delete();
        return false;
    }

    private static boolean deleteTree(File f, int depth) {
        if (depth > 64) return false;
        if (!WebAdmin.isLink(f) && f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) if (!deleteTree(c, depth + 1)) return false;
        }
        return f.delete();
    }

    // ================================================================== mkdir -p (folder upload)

    private JSONObject mkdirp(String which, String path, String rel) throws Exception {
        File d = WebAdmin.resolve(which, path);
        if (d == null || !d.isDirectory()) return err("文件夹不存在");
        if (rel == null || rel.length() == 0 || rel.length() > 1024) return err("路径不对");
        String sub = path == null ? "" : path;
        for (String seg : rel.split("/")) {
            if (seg.length() == 0) continue;
            String n = WebAdmin.checkName(seg);
            if (n == null) return err("文件夹名字不能用：" + seg);
            File cur = WebAdmin.resolve(which, sub);
            if (cur == null) return err("路径不对");
            String ro = WebAdmin.readOnlyWhy(which, cur);
            if (ro != null) return err(ro);
            File next = new File(cur, n);
            if (!next.exists()) {
                if (!next.mkdir()) return err("建不了文件夹 " + n);
                next.setReadable(true, false); next.setWritable(true, false); next.setExecutable(true, false);
            }
            sub = sub.length() == 0 ? n : sub + "/" + n;
            File chk = WebAdmin.resolve(which, sub);
            if (chk == null || !chk.isDirectory()) return err("“" + n + "”已经是一个文件，或者是链接");
        }
        return Transfer.obj("ok", true, "path", sub);
    }

    // ================================================================== zip download

    private static final class Item { File f; String name; }

    private void collect(File d, String prefix, List<Item> out, long[] total, int depth) {
        if (depth > 64 || out.size() > 200000) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".") || WebAdmin.isLink(f) || WebAdmin.isProtected(n) || WebAdmin.isKindleBind(f)) continue;
            if (f.isDirectory()) collect(f, prefix + n + "/", out, total, depth + 1);
            else { Item it = new Item(); it.f = f; it.name = prefix + n; out.add(it); total[0] += f.length(); }
        }
    }

    private void zip(String which, String path, String namesJson, OutputStream out) throws Exception {
        File d = WebAdmin.resolve(which, path);
        if (d == null || !d.isDirectory()) { Transfer.json(out, 404, err("文件夹不存在")); return; }
        List<Item> items = new ArrayList<Item>();
        long[] total = { 0 };
        String zipName;
        if (namesJson == null || namesJson.length() == 0) {
            zipName = d.equals(WebAdmin.rootDir(which)) ? ("kindle".equals(which) ? "Kindle书库" : "Android存储") : d.getName();
            collect(d, zipName + "/", items, total, 0);
        } else {
            JSONArray names;
            try { names = new JSONArray(namesJson); } catch (Exception e) { Transfer.json(out, 400, err("参数不对")); return; }
            if (names.length() == 0 || names.length() > 500) { Transfer.json(out, 400, err("没有选文件")); return; }
            zipName = names.length() == 1 ? names.optString(0) : (d.equals(WebAdmin.rootDir(which)) ? "选中的文件" : d.getName() + "-选中");
            for (int i = 0; i < names.length(); i++) {
                String n = names.optString(i);
                if (n.length() == 0 || n.indexOf('/') >= 0 || n.startsWith(".") || WebAdmin.isProtected(n)) continue;
                File f = WebAdmin.resolve(which, (path == null ? "" : path) + "/" + n);
                if (f == null || !f.exists() || WebAdmin.isKindleBind(f)) continue;
                if (f.isDirectory()) collect(f, n + "/", items, total, 0);
                else { Item it = new Item(); it.f = f; it.name = n; items.add(it); total[0] += f.length(); }
            }
        }
        if (items.isEmpty()) { Transfer.json(out, 404, err("没有可以打包的文件（脚本、隐藏文件和链接不会打包）")); return; }
        if (total[0] > ZIP_MAX) { Transfer.json(out, 413, err("太大了：" + (total[0] >> 20) + " MB，一次最多打包 " + (ZIP_MAX >> 20) + " MB")); return; }
        String fn = zipName + ".zip";
        String ascii = fn.replaceAll("[^A-Za-z0-9._ ()-]", "_");
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"" + ascii
            + "\"; filename*=UTF-8''" + URLEncoder.encode(fn, "UTF-8").replace("+", "%20")
            + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: sandbox\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        ZipOutputStream z = new ZipOutputStream(new java.io.BufferedOutputStream(out, 65536));
        z.setLevel(Deflater.BEST_SPEED);
        byte[] buf = new byte[65536];
        for (Item it : items) {
            ZipEntry e = new ZipEntry(it.name);
            e.setTime(it.f.lastModified());
            try {
                InputStream in = new FileInputStream(it.f);
                z.putNextEntry(e);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
                } finally {
                    try { in.close(); } catch (IOException ignored) { }
                    z.closeEntry();
                }
            } catch (IOException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("EPIPE")) throw ex;
            }
        }
        z.finish();
        z.flush();
    }

    // ================================================================== text editor

    static boolean editable(String name) { return in(ext(name), TEXT_EXT) && !WebAdmin.isProtected(name) && !name.startsWith("."); }

    private JSONObject textRead(String which, String path) throws Exception {
        File f = WebAdmin.resolve(which, path);
        if (f == null || !f.isFile()) return err("没有这个文件");
        if (WebAdmin.isProtected(f.getName())) return err("这个文件不能在这里打开");
        if (f.length() > TEXT_MAX) return Transfer.obj("ok", false, "msg", "文件太大（超过 512 KB），请下载后用电脑编辑", "tooBig", true);
        byte[] raw;
        try { raw = HostReq.read(f, TEXT_MAX + 1); } catch (Exception e) { return err("读不了：" + e.getMessage()); }
        CharsetDecoder dec = java.nio.charset.Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try { CharBuffer cb = dec.decode(ByteBuffer.wrap(raw)); text = cb.toString(); }
        catch (CharacterCodingException e) { return Transfer.obj("ok", false, "msg", "不是 UTF-8 文本，不能在网页里编辑", "binary", true); }
        File dir = f.getParentFile();
        boolean canEdit = editable(f.getName()) && WebAdmin.readOnlyWhy(which, dir) == null;
        return Transfer.obj("ok", true, "text", text, "size", f.length(), "mtime", f.lastModified(), "editable", canEdit,
            "why", canEdit ? "" : !editable(f.getName()) ? "这种文件只能查看" : WebAdmin.readOnlyWhy(which, dir));
    }

    private JSONObject textWrite(JSONObject b) throws Exception {
        String which = b.optString("root"), path = b.optString("path");
        String text = b.optString("text", null);
        if (text == null) return err("没有内容");
        byte[] data = text.getBytes("UTF-8");
        if (data.length > TEXT_MAX) return err("太大了：网页里最多编辑 512 KB 的文件");
        int slash = path.lastIndexOf('/');
        String dirRel = slash < 0 ? "" : path.substring(0, slash), name = slash < 0 ? path : path.substring(slash + 1);
        File dir = WebAdmin.resolve(which, dirRel);
        if (dir == null || !dir.isDirectory()) return err("文件夹不存在");
        String ro = WebAdmin.readOnlyWhy(which, dir);
        if (ro != null) return err(ro);
        boolean create = b.optBoolean("create", false);
        String n = WebAdmin.checkName(name);
        if (n == null || !editable(n)) return err("只能编辑 " + android.text.TextUtils.join(" / ", TEXT_EXT) + " 文本文件（不能是脚本或隐藏文件）");
        File f = new File(dir, n);
        if (create) { if (f.exists()) return err("已经有同名的文件"); }
        else {
            File rf = WebAdmin.resolve(which, path);
            if (rf == null || !rf.isFile()) return err("没有这个文件");
            long mt = b.optLong("mtime", 0);
            if (mt > 0 && rf.lastModified() != mt && !b.optBoolean("force", false))
                return Transfer.obj("ok", false, "conflict", true, "msg", "这个文件在你打开之后被改过了");
        }
        if (data.length > dir.getUsableSpace() - 1024 * 1024) return err("空间不够");
        File tmp = new File(dir, ".vdedit-" + Long.toHexString(rnd.nextLong()));
        try {
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(data); o.getFD().sync(); } finally { o.close(); }
        } catch (IOException e) {
            tmp.delete();
            String m = String.valueOf(e.getMessage());
            return err(m.contains("Required key not available") ? "这台 Kindle 的书库是加密的，Android 这边写不进去" : "保存失败：" + m);
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(f)) { tmp.delete(); return err("保存失败"); }
        return Transfer.obj("ok", true, "mtime", f.lastModified(), "size", f.length());
    }

    // ================================================================== thumbnails / previews

    private static void sendImage(OutputStream out, Bitmap bm, boolean png) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, png ? 100 : 82, bo);
        Transfer.send(out, 200, png ? "image/png" : "image/jpeg", bo.toByteArray(), "Cache-Control: private, max-age=120\r\n");
    }

    private static final long MAX_THUMB_PIXELS = 2_000_000L;
    private static final int MAX_THUMB_DIM = 2048;
    private static final long MAX_PDF_PIXELS = 1_500_000L;
    private static final int MAX_PDF_DIM = 1600;

    static Bitmap decodeScaled(byte[] data, File f, int w) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        if (data != null) BitmapFactory.decodeByteArray(data, 0, data.length, o); else BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while ((o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= w / 2)
                || (long) (o.outWidth / s) * (o.outHeight / s) > MAX_THUMB_PIXELS
                || o.outWidth / s > MAX_THUMB_DIM
                || o.outHeight / s > MAX_THUMB_DIM) {
            if (s >= 1024) break;
            s *= 2;
        }
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap b = data != null ? BitmapFactory.decodeByteArray(data, 0, data.length, o2) : BitmapFactory.decodeFile(f.getPath(), o2);
        if (b == null) return null;
        int tw = b.getWidth(), th = b.getHeight();
        if (tw > w) {
            th = Math.max(1, (int) ((long) th * w / tw));
            tw = w;
        }
        if (th > MAX_THUMB_DIM) {
            tw = Math.max(1, (int) ((long) tw * MAX_THUMB_DIM / th));
            th = MAX_THUMB_DIM;
        }
        long px = (long) tw * th;
        if (px > MAX_THUMB_PIXELS) {
            double scale = Math.sqrt((double) MAX_THUMB_PIXELS / px);
            tw = Math.max(1, (int) (tw * scale));
            th = Math.max(1, (int) (th * scale));
        }
        if (tw != b.getWidth() || th != b.getHeight()) {
            Bitmap c = Bitmap.createScaledBitmap(b, tw, th, true);
            if (c != b) b.recycle();
            b = c;
        }
        return b;
    }

    private void thumb(String which, String path, int w, OutputStream out) throws Exception {
        w = Math.max(48, Math.min(1600, w));
        File f = WebAdmin.resolve(which, path);
        if (f == null || !f.isFile() || WebAdmin.isProtected(f.getName())) { Transfer.send(out, 404, "text/plain", "no".getBytes(), null); return; }
        Bitmap b = null;
        String e = ext(f.getName());
        try {
            if (in(e, IMG_EXT)) b = decodeScaled(null, f, w);
            else if (e.equals("epub")) b = epubCover(f, w);
            else if (e.equals("pdf")) b = pdfPage(f, w);
        } catch (Throwable ex) { b = null; }
        if (b == null) { Transfer.send(out, 404, "text/plain", "no preview".getBytes(), null); return; }
        try { sendImage(out, b, e.equals("png")); } finally { b.recycle(); }
    }

    private static Bitmap epubCover(File f, int w) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            ZipEntry best = null;
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName().toLowerCase(Locale.US);
                if (e.isDirectory() || !(n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif"))) continue;
                if (e.getSize() > 8 << 20) continue;
                boolean cover = n.contains("cover");
                if (best == null || (cover && !best.getName().toLowerCase(Locale.US).contains("cover"))) best = e;
            }
            if (best == null) return null;
            InputStream in = z.getInputStream(best);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) > 0 && bo.size() < (8 << 20)) bo.write(b, 0, n); } finally { in.close(); }
            return decodeScaled(bo.toByteArray(), null, w);
        } finally { z.close(); }
    }

    private static Bitmap pdfPage(File f, int w) throws Exception {
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r = null;
        try {
            r = new PdfRenderer(pfd);
            if (r.getPageCount() < 1) return null;
            PdfRenderer.Page pg = r.openPage(0);
            try {
                int pw = Math.max(1, pg.getWidth()), ph = Math.max(1, pg.getHeight());
                int rw = Math.min(w, MAX_PDF_DIM);
                int rh = Math.max(1, (int) ((long) rw * ph / pw));
                if (rh > 3 * rw) rh = 3 * rw;
                if (rh > MAX_PDF_DIM) {
                    rw = Math.max(1, (int) ((long) rw * MAX_PDF_DIM / rh));
                    rh = MAX_PDF_DIM;
                }
                long px = (long) rw * rh;
                if (px > MAX_PDF_PIXELS) {
                    double scale = Math.sqrt((double) MAX_PDF_PIXELS / px);
                    rw = Math.max(1, (int) (rw * scale));
                    rh = Math.max(1, (int) (rh * scale));
                }
                Bitmap b = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
                new Canvas(b).drawColor(Color.WHITE);
                pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            } finally { pg.close(); }
        } finally { if (r != null) r.close(); pfd.close(); }
    }

    // ================================================================== wallpaper / icons

    private static String galleryName(String n) {
        if (n == null || n.length() == 0 || n.length() > 200 || n.indexOf('/') >= 0 || n.startsWith(".") || WebAdmin.isProtected(n)) return null;
        return in(ext(n), IMG_EXT) ? n : null;
    }

    private JSONObject themeList() throws Exception {
        JSONObject o = Transfer.obj("ok", true);
        File wf = Theme.wallFile();
        o.put("wallpaper", wf == null ? JSONObject.NULL : Transfer.obj("name", wf.getName(), "time", wf.lastModified(), "size", wf.length()));
        JSONArray g = new JSONArray();
        File gd = new File(Theme.USER_WALL);
        File[] fs = gd.listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); } });
            for (File f : fs) if (f.isFile() && galleryName(f.getName()) != null && !WebAdmin.isLink(f)) g.put(Transfer.obj("name", f.getName(), "size", f.length(), "time", f.lastModified()));
        }
        o.put("gallery", g);
        PackageManager pm = ctx.getPackageManager();
        JSONArray apps = new JSONArray();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            File own = Theme.iconFile(pkg);
            apps.put(Transfer.obj("pkg", pkg, "label", String.valueOf(ri.loadLabel(pm)), "own", Theme.hasOwnIcon(pkg),
                "custom", own != null, "source", own == null ? "" : own.getPath().startsWith(Theme.DIR) ? "theme" : "folder"));
        }
        o.put("apps", apps);
        o.put("statusbar", Theme.statusBar());
        return o;
    }

    private void themeThumb(String kind, String name, int w, OutputStream out) throws Exception {
        w = Math.max(48, Math.min(1600, w));
        File f = null;
        if ("wall".equals(kind)) f = Theme.wallFile();
        else if ("gallery".equals(kind) && galleryName(name) != null) {
            File g = new File(Theme.USER_WALL, name);
            if (!WebAdmin.isLink(g)) f = g;
        } else if ("icon".equals(kind) && name != null && name.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) f = Theme.iconFile(name);
        if (f == null || !f.isFile()) { Transfer.send(out, 404, "text/plain", "no".getBytes(), null); return; }
        Bitmap b = decodeScaled(null, f, w);
        if (b == null) { Transfer.send(out, 404, "text/plain", "no".getBytes(), null); return; }
        try { sendImage(out, b, "icon".equals(kind)); } finally { b.recycle(); }
    }

    private JSONObject wallSet(String name) throws Exception {
        String n = galleryName(name);
        if (n == null) return err("图片名字不对");
        File f = new File(Theme.USER_WALL, n);
        if (!f.isFile() || WebAdmin.isLink(f)) return err("图库里没有这张图");
        final String[] res = { "超时" };
        final CountDownLatch l = new CountDownLatch(1);
        Theme.setWallpaper(ctx, f, new Theme.Done() { @Override public void done(String e) { res[0] = e; l.countDown(); } });
        l.await(30, TimeUnit.SECONDS);
        return res[0] == null ? Transfer.obj("ok", true) : err(res[0]);
    }

    private JSONObject galleryDelete(String name) {
        String n = galleryName(name);
        if (n == null) return err("图片名字不对");
        File f = new File(Theme.USER_WALL, n);
        if (WebAdmin.isLink(f)) return err("不能删除链接");
        return Transfer.obj("ok", f.isFile() && f.delete());
    }

    private JSONObject iconSet(String pkg, String name) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) return err("包名不对");
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); } catch (Exception e) { return err("没有这个应用"); }
        String n = galleryName(name);
        if (n == null) return err("图片名字不对");
        File f = new File(Theme.USER_WALL, n);
        if (!f.isFile() || WebAdmin.isLink(f)) return err("图库里没有这张图");
        String e = Theme.setIcon(pkg, f);
        return e == null ? Transfer.obj("ok", true) : err(e);
    }

    // ================================================================== pre-compilation (boot/lib/compile.sh)

    private JSONObject compileStatus() throws Exception {
        JSONObject o = Transfer.obj("ok", true);
        Map<String, String> m = HostLink.kv(HostLink.CTL + "compile.status");
        for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue());
        String pkg = m.get("pkg");
        if (pkg != null && pkg.length() > 0) {
            try {
                ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
                o.put("label", String.valueOf(ai.loadLabel(ctx.getPackageManager())));
            } catch (Exception ignored) { }
        }
        return o;
    }

    private JSONObject compile(String pkg) {
        if ("all".equals(pkg)) { HostLink.cmd("compile all"); return Transfer.obj("ok", true); }
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || pkg.length() > 200) return err("包名不对");
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); } catch (Exception e) { return err("没有这个应用"); }
        HostLink.cmd("compile " + pkg);
        return Transfer.obj("ok", true);
    }
}
