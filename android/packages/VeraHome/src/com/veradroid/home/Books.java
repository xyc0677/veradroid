// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** EPUB/PDF 的封面、标题、作者，以及 Kindle 自带字体列表。 */
final class Books {
    private Books() { }

    static class Info {
        String title = "";
        String author = "";
        String coverEntry = "";
        String opf = "";
    }

    static class Font {
        String name;
        String path;
        Font(String n, String p) { name = n; path = p; }
    }

    // ------------------------------------------------------------------ metadata

    static Info info(File f) {
        Info i = new Info();
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        i.title = dot > 0 ? n.substring(0, dot) : n;
        String ext = dot > 0 ? n.substring(dot + 1).toLowerCase(Locale.US) : "";
        if (!"epub".equals(ext)) return i;
        ZipFile z = null;
        try {
            z = new ZipFile(f);
            fillEpubInfo(z, i);
        } catch (Throwable ignored) {
        } finally {
            try { if (z != null) z.close(); } catch (Throwable ignored) { }
        }
        return i;
    }

    private static void fillEpubInfo(ZipFile z, Info i) {
        String container = readEntry(z, "META-INF/container.xml");
        String opf = container == null ? null : attr(container, "rootfile", "full-path");
        if (opf == null) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String e = en.nextElement().getName();
                if (e.toLowerCase(Locale.US).endsWith(".opf")) { opf = e; break; }
            }
        }
        if (opf == null) return;
        i.opf = opf;
        String xml = readEntry(z, opf);
        if (xml == null) return;
        String t = tag(xml, "dc:title");
        String a = tag(xml, "dc:creator");
        if (t != null && t.trim().length() > 0) i.title = t.trim();
        if (a != null && a.trim().length() > 0) i.author = a.trim();
        String base = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";

        Map<String, String> items = new LinkedHashMap<String, String>();
        Matcher im = Pattern.compile("(?is)<(?:\\w+:)?item\\b[^>]*>").matcher(xml);
        while (im.find()) {
            String id = attr(im.group(), "id");
            String href = attr(im.group(), "href");
            if (id != null && href != null) items.put(id, href);
        }
        String coverId = null;
        Matcher meta = Pattern.compile("(?is)<(?:\\w+:)?meta\\b[^>]*name\\s*=\\s*[\"']cover[\"'][^>]*>").matcher(xml);
        if (meta.find()) coverId = attr(meta.group(), "content");
        String coverHref = null;
        if (coverId != null) coverHref = items.get(coverId);
        if (coverHref == null) {
            Matcher cm = Pattern.compile("(?is)<(?:\\w+:)?item\\b[^>]*properties\\s*=\\s*[\"'][^\"']*cover-image[^\"']*[\"'][^>]*>").matcher(xml);
            if (cm.find()) coverHref = attr(cm.group(), "href");
        }
        if (coverHref == null) {
            for (String href : items.values()) {
                String lo = href.toLowerCase(Locale.US);
                if (lo.contains("cover") && (lo.endsWith(".jpg") || lo.endsWith(".jpeg") || lo.endsWith(".png") || lo.endsWith(".gif") || lo.endsWith(".webp"))) {
                    coverHref = href; break;
                }
            }
        }
        if (coverHref == null) {
            for (String href : items.values()) {
                String lo = href.toLowerCase(Locale.US);
                if (lo.endsWith(".jpg") || lo.endsWith(".jpeg") || lo.endsWith(".png") || lo.endsWith(".gif") || lo.endsWith(".webp")) {
                    coverHref = href; break;
                }
            }
        }
        if (coverHref != null) {
            String decHref = Epub.pctDecode(coverHref);
            String decBase = Epub.pctDecode(base);
            String decPath = resolve(decBase, decHref);
            String rawPath = resolve(base, coverHref);
            if (z.getEntry(decPath) != null) i.coverEntry = decPath;
            else if (z.getEntry(rawPath) != null) i.coverEntry = rawPath;
            else if (z.getEntry(decHref) != null) i.coverEntry = decHref;
            else if (z.getEntry(coverHref) != null) i.coverEntry = coverHref;
        }
    }

    /** A 2:3 cover thumbnail; null for TXT / failures. Call off the UI thread. */
    static Bitmap cover(File f, int tw, int th) {
        String n = f.getName().toLowerCase(Locale.US);
        try {
            if (n.endsWith(".pdf")) return pdfCover(f, tw, th);
            if (!n.endsWith(".epub")) return null;
            ZipFile z = new ZipFile(f);
            try {
                Info info = new Info();
                fillEpubInfo(z, info);
                if (info.coverEntry == null || info.coverEntry.length() == 0) return null;
                ZipEntry e = z.getEntry(info.coverEntry);
                if (e == null) return null;
                InputStream in = z.getInputStream(e);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try {
                    byte[] b = new byte[32768];
                    int r;
                    while ((r = in.read(b)) > 0) { bo.write(b, 0, r); if (bo.size() > 8 * 1024 * 1024) break; }
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
                byte[] data = bo.toByteArray();
                // Do not decode a 4000px cover at full size just to shrink it to 180px:
                // on a 512 MB Kindle that alone can be tens of MB and OOM.
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                int sample = 1;
                while (bounds.outWidth / sample > tw * 2 || bounds.outHeight / sample > th * 2) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                return fit(src, tw, th);
            } finally {
                try { z.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap pdfCover(File f, int tw, int th) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer pdf = null;
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            pdf = new PdfRenderer(pfd);
            if (pdf.getPageCount() <= 0) return null;
            PdfRenderer.Page p = pdf.openPage(0);
            try {
                Bitmap bm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                bm.eraseColor(Color.WHITE);
                int pw = p.getWidth(), ph = p.getHeight();
                if (pw > 0 && ph > 0) {
                    float sc = Math.min(tw / (float) pw, th / (float) ph);
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postScale(sc, sc);
                    m.postTranslate((tw - pw * sc) * 0.5f, (th - ph * sc) * 0.5f);
                    p.render(bm, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } else {
                    p.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
                return bm;
            } finally {
                p.close();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { if (pdf != null) pdf.close(); } catch (Throwable ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }
        }
    }

    private static Bitmap fit(Bitmap src, int tw, int th) {
        if (src == null) return null;
        try {
            Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(Color.WHITE);
            float s = Math.max(tw / (float) src.getWidth(), th / (float) src.getHeight());
            int sw = Math.max(1, (int) (src.getWidth() * s));
            int sh = Math.max(1, (int) (src.getHeight() * s));
            Bitmap sc = Bitmap.createScaledBitmap(src, sw, sh, true);
            c.drawBitmap(sc, (tw - sw) / 2f, (th - sh) / 2f, null);
            if (sc != src) sc.recycle();
            return out;
        } finally {
            src.recycle();
        }
    }

    // ------------------------------------------------------------------ fonts

    static List<Font> fonts() {
        List<Font> out = new ArrayList<Font>();
        out.add(new Font("系统默认", null));
        addFont(out, "/system/fonts/DroidSansFallback.ttf", "系统黑体");
        addFont(out, "/system/fonts/DroidSansMono.ttf", "等宽");
        org.json.JSONObject storeNames = null;
        for (String dir : new String[] { "/data/media/0/KindleFonts", "/data/media/0/Fonts" }) {
            File[] fs = new File(dir).listFiles();
            if (fs == null) continue;
            boolean isUserFonts = dir.endsWith("/Fonts");
            if (isUserFonts && storeNames == null) storeNames = FontStoreActivity.names();
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.startsWith(".") || !(n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"))) continue;
                // Kindle 自带的字体有几十个文件（各种字重、各国文字）：只挑常用的几种。
                // 用户自己导入的字体（Fonts，传书网页上传进来的）一个不漏地列出来——以前这里也套了同一个过滤，
                // 文件名里没有 song / heiti / kai 的导入字体在“系统字体”里看不到。
                if (dir.endsWith("/KindleFonts") && !wanted(n)) continue;
                if (f.length() < 1024) continue;   // 空文件 / 没传完
                // 字体商店下载的：用商店里的名字（文件名是给宿主用的字母数字）
                String shop = null;
                if (isUserFonts && storeNames != null) {
                    org.json.JSONObject jo = storeNames.optJSONObject(f.getName());
                    String sn = jo == null ? "" : jo.optString("name");
                    if (sn.length() > 0) shop = sn;
                }
                String label = shop != null ? shop : label(f.getName());
                boolean dup = false;
                for (Font o : out) if (o.name.equals(label)) { dup = true; break; }
                if (!dup) out.add(new Font(label, f.getAbsolutePath()));
            }
        }
        return out;
    }

    private static boolean wanted(String n) {
        if (n.contains("stsong") || n.contains("stheiti")) return true;
        if (n.contains("wenkai") || n.contains("kaiti") || n.contains("kai")) return true;
        if (n.startsWith("bookerly-regular") || n.startsWith("baskerville-regular")) return true;
        return false;
    }

    private static void addFont(List<Font> out, String path, String name) {
        File f = new File(path);
        if (f.isFile()) out.add(new Font(name, path));
    }

    private static String label(String file) {
        String n = file.toLowerCase(Locale.US);
        if (n.contains("stsong")) return n.contains("tc") ? "宋体（繁）" : "宋体（Kindle）";
        if (n.contains("stheiti")) return n.contains("tc") ? "黑体（繁）" : "黑体（Kindle）";
        if (n.contains("wenkai")) return "楷书（霞鹜文楷）";
        if (n.contains("kaiti")) return "楷体";
        if (n.startsWith("bookerly")) return "Bookerly（英文）";
        if (n.startsWith("baskerville")) return "Baskerville（英文）";
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    // ------------------------------------------------------------------ epub helpers

    private static String readEntry(ZipFile z, String name) {
        try {
            ZipEntry e = z.getEntry(name);
            if (e == null) {
                String d = Epub.pctDecode(name);
                if (!d.equals(name)) e = z.getEntry(d);
            }
            if (e == null) return null;
            InputStream in = z.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) { bo.write(b, 0, n); if (bo.size() > 4 * 1024 * 1024) break; }
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            byte[] raw = bo.toByteArray();
            return Epub.decodeText(raw, raw.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("(?is)<" + Pattern.quote(name) + "[^>]*>(.*?)</" + Pattern.quote(name) + ">").matcher(xml);
        if (!m.find()) return null;
        return html(m.group(1)).trim();
    }

    private static String attr(String tag, String name, String attrName) {
        Matcher m = Pattern.compile("(?is)(?:^|[\\s\"'/])" + Pattern.quote(attrName) + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        if (m.find()) return m.group(1);
        m = Pattern.compile("(?is)(?:^|[\\s\"'/])" + Pattern.quote(attrName) + "\\s*=\\s*'([^']*)'").matcher(tag);
        if (m.find()) return m.group(1);
        return null;
    }
    private static String attr(String tag, String attrName) { return attr(tag, null, attrName); }

    private static String resolve(String base, String href) {
        String p = base + href;
        p = p.replace("%20", " ");
        String[] parts = p.split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (String part : parts) {
            if (part.length() == 0 || part.equals(".")) continue;
            if (part.equals("..")) { if (!out.isEmpty()) out.remove(out.size() - 1); }
            else out.add(part);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : out) { if (sb.length() > 0) sb.append('/'); sb.append(part); }
        return sb.toString();
    }

    private static String html(String s) {
        return Epub.htmlToText(s.replaceAll("(?s)<[^>]+>", " "));
    }
}
