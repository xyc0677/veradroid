package com.veradroid.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 内置阅读器的 EPUB 解析（纯 Java，不用 Android 类：tests-jvm 里直接跑）。
 *
 * 章节（chapters）= 书脊（spine）里的每一个正文文件，分页、续读位置、书签都按“第几个文件 + 字符位置”记。
 * 目录（toc）是另一张表：每一条 = 标题 + 层级 + 落在哪个文件的哪个字。来源按顺序：
 * EPUB3 的 nav.xhtml / EPUB2 的 toc.ncx（嵌套的 navPoint、ol 记成层级，href 里的 #id 落到文件里的那个字）；
 * 书里没有目录、目录明显不全（正文里“第X章”的标题比目录多得多）时，按正文里的标题（h1~h6、class 带 title/chapter 的段落、
 * “第X章 / 第X卷 / 序章 / 番外……”开头的短行）生成，和 KOReader 用 NCX、没有就用标题的做法一样。
 *
 * 以前：最多只读书脊里的前 120 个文件、全书最多 600 万字，目录就是这些文件本身，标题先取 &lt;title&gt;（网文 EPUB 里每个文件都是书名），
 * 一个文件里有好几章的书只有一条目录。
 */
final class Epub {
    private Epub() { }

    static class Chapter {
        String title; String text; String entry;
        /** EPUB 里的 id → 在 text 里的位置；块级元素（p / li / aside……）的 id 还记下结束位置和标签名（注释正文用）。 */
        Map<String, int[]> ids = java.util.Collections.emptyMap();   // {起, 止(-1 = 不知道), 是不是注释元素 1/0, 是不是块 1/0, 是不是 h1~h6 1/0}
        List<Link> links = java.util.Collections.emptyList();
        /** 这个文件里的标题：{在 text 里的位置, 级别}；级别 1~6 = h1~h6，3 = class 带 title / chapter 的段落，PAT_* = “第X章”一类的短行。 */
        List<int[]> heads = java.util.Collections.emptyList();
    }
    /** 正文里的一个书内链接：text[start, end) 是链接文字，target = “章节文件#id” 或 “章节文件”。 */
    static final class Link {
        int start, end; String target; boolean noteref, back;
        /** 链接里是一张图（多看的注释号图标） */
        boolean img;
        /** 注释直接写在图片的 alt 里（QQ 阅读 / 微信读书的 &lt;img class="qqreader-footnote" alt="注释文字"&gt;、多看的同类写法）：弹出来就是它 */
        String inline;
    }

    /** 目录里的一条。chapter = chapters 里的下标，offset = 那个文件文字里的字符位置。 */
    static final class TocItem {
        final String title; final int depth, chapter, offset;
        TocItem(String title, int depth, int chapter, int offset) { this.title = title; this.depth = depth; this.chapter = chapter; this.offset = offset; }
        @Override public String toString() { return depth + "|" + chapter + ":" + offset + "|" + title; }
    }

    static final class Book {
        final List<Chapter> chapters = new ArrayList<Chapter>();
        final List<TocItem> toc = new ArrayList<TocItem>();
        /** 目录从哪来：nav / ncx / headings / files（调试和测试看） */
        String tocSource = "files";
        /** 书太大（超过 MAX_CHARS 字）后面没读进来 */
        boolean truncated;
    }

    /** 全书最多读多少字（UTF-16 字符）：1600 万字 ≈ 32 MB 内存。以前 600 万字、120 个文件。 */
    static final long MAX_CHARS = 16L * 1024 * 1024;
    /** 单个文件最多读多少字节（几章合在一个文件里的书一个文件就有几 MB）。以前 2 MB。 */
    static final int MAX_ENTRY = 8 * 1024 * 1024;

    static final int PAT_VOLUME = 10, PAT_CHAPTER = 11;

    // 正则都只编译一次（ART 上 String.replaceAll 每次都重新编译：1000 多个文件的书光编译正则就要十几秒）
    private static final Map<String, Pattern[]> ATTR = new HashMap<String, Pattern[]>();
    private static final Map<String, Pattern> CLOSE = new HashMap<String, Pattern>();
    private static final Pattern P_STRIP0 = Pattern.compile("(?is)<head[^>]*>.*?</head>");
    private static final Pattern P_STRIP1 = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern P_STRIP2 = Pattern.compile("(?is)<style[^>]*>.*?</style>");
    private static final Pattern P_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern P_STRIP3 = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern P_STRIP4 = Pattern.compile("(?i)</p\\s*>");
    private static final Pattern P_STRIP5 = Pattern.compile("(?i)</div\\s*>");
    private static final Pattern P_STRIP6 = Pattern.compile("(?i)</h[1-6]\\s*>");
    private static final Pattern P_STRIP7 = Pattern.compile("(?i)<li[^>]*>");
    private static final Pattern P_STRIP8 = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern P_STRIP9 = Pattern.compile("[ \\t\\x0B\\f]+");
    private static final Pattern P_STRIP10 = Pattern.compile("\\n[ \\t]+");
    private static final Pattern P_STRIP11 = Pattern.compile("\\n{3,}");
    private static final Pattern P_ANYTAG = Pattern.compile("<[^>]+>");
    private static final Pattern P_SCHEME = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*:.*");
    private static final Pattern P_HTMLEXT = Pattern.compile("(?i).*\\.(x?html?|htm)$");
    private static final Pattern P_NUMENT = Pattern.compile("&#([xX]?)([0-9a-fA-F]{1,7});");
    private static final Pattern P_ITEM = Pattern.compile("(?is)<(?:\\w+:)?item\\b[^>]*>");
    private static final Pattern P_SPINE = Pattern.compile("(?is)<(?:\\w+:)?spine\\b[^>]*>");
    private static final Pattern P_ITEMREF = Pattern.compile("(?is)<(?:\\w+:)?itemref\\b[^>]*>");

    private static String findOpf(ZipFile z) {
        String opf = null;
        String container = readEntry(z, "META-INF/container.xml");
        if (container != null) opf = attr(container, "rootfile", "full-path");
        if (opf == null) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.toLowerCase(Locale.US).endsWith(".opf")) { opf = n; break; }
            }
        }
        return opf;
    }

    static Book load(File f) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            String opf = findOpf(z);
            if (opf == null) throw new Exception("EPUB 缺少 OPF");
            String opfXml = readEntry(z, opf);
            if (opfXml == null) throw new Exception("EPUB 的 OPF 读不到");
            String base = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";

            Map<String, String> items = new HashMap<String, String>();
            String ncxHref = null, navHref = null;
            List<String> htmlItems = new ArrayList<String>();
            Matcher im = P_ITEM.matcher(opfXml);
            while (im.find()) {
                String tag = im.group();
                String id = attr(tag, "id"), href = attr(tag, "href");
                if (href == null) continue;
                if (id != null) items.put(id, href);
                String mt = String.valueOf(attr(tag, "media-type")).toLowerCase(Locale.US);
                String props = String.valueOf(attr(tag, "properties")).toLowerCase(Locale.US);
                if (mt.contains("dtbncx") && ncxHref == null) ncxHref = href;
                if ((" " + props + " ").contains(" nav ") && navHref == null) navHref = href;
                if (P_HTMLEXT.matcher(href).matches()) htmlItems.add(href);
            }
            Matcher spineTag = P_SPINE.matcher(opfXml);
            if (spineTag.find()) { String tocId = attr(spineTag.group(), "toc"); if (tocId != null && items.containsKey(tocId)) ncxHref = items.get(tocId); }
            List<String> order = new ArrayList<String>();
            Matcher sp = P_ITEMREF.matcher(opfXml);
            while (sp.find()) {
                String idref = attr(sp.group(), "idref");
                if (idref != null && items.containsKey(idref)) order.add(items.get(idref));
            }
            if (order.isEmpty()) order.addAll(htmlItems);
            String bookTitle = null;
            Matcher dt = Pattern.compile("(?is)<dc:title[^>]*>(.*?)</dc:title>").matcher(opfXml);
            if (dt.find()) bookTitle = clean(htmlToText(dt.group(1)));

            Book book = new Book();
            // 书脊里每个文件 → 读进来的第几章（太短被跳过的文件，比如只有“第一卷”三个字的分卷页，算到它后面那一章上）
            Map<String, Integer> spineAt = new HashMap<String, Integer>();
            List<String> skipped = new ArrayList<String>();
            List<String> rawTitles = new ArrayList<String>();
            long total = 0;
            for (String href : order) {
                // 章的文件名一律存解码后的（OPF 里常写成 %E7%AC%AC1.xhtml）：书内链接的目标也是解码后的，两边对得上才能跳过去
                String name = resolve(base, pctDecode(href));
                String html = readEntry(z, name);
                if (html == null) { String raw = resolve(base, href); html = readEntry(z, raw); if (html != null) name = raw; }
                if (html == null) { html = readEntry(z, href); name = href; }
                if (html == null) continue;
                Chapter c = parseChapter(html, name);
                if (c.text.length() < 8) { skipped.add(name); continue; }
                if (total + c.text.length() > MAX_CHARS && !book.chapters.isEmpty()) { book.truncated = true; break; }
                total += c.text.length();
                int idx = book.chapters.size();
                for (String s : skipped) spineAt.put(s, idx);
                skipped.clear();
                spineAt.put(name, idx);
                rawTitles.add(titleTag(html));
                book.chapters.add(c);
            }
            nameTitles(book.chapters, rawTitles, bookTitle);

            // 书里的目录：nav 和 ncx 都有时用条目多的那个
            List<TocItem> nav = null, ncx = null;
            if (navHref != null) {
                String name = resolve(base, pctDecode(navHref));
                String xml = readEntry(z, name);
                if (xml == null) { String raw = resolve(base, navHref); xml = readEntry(z, raw); if (xml != null) name = raw; }
                if (xml != null) nav = resolveToc(parseNav(xml), dirOf(name), spineAt, book.chapters);
            }
            if (ncxHref != null) {
                String name = resolve(base, pctDecode(ncxHref));
                String xml = readEntry(z, name);
                if (xml == null) { String raw = resolve(base, ncxHref); xml = readEntry(z, raw); if (xml != null) name = raw; }
                if (xml != null) ncx = resolveToc(parseNcx(xml), dirOf(name), spineAt, book.chapters);
            }
            List<TocItem> given = null; String src = null;
            if (nav != null && !nav.isEmpty() && (ncx == null || nav.size() >= ncx.size())) { given = nav; src = "nav"; }
            else if (ncx != null && !ncx.isEmpty()) { given = ncx; src = "ncx"; }
            buildToc(book, given, src);
            return book;
        } finally {
            try { z.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 第一次打开：只读书脊里第一个够长的文件（就是 load() 里的第 0 章）先显示出来，整本在后台读。
     * 以前第一次打开要先把整本读完才出第一页（1200 章的书在 1 GHz 的 Kindle 上要半分钟）。
     */
    static Chapter firstChapter(File f) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            String opf = findOpf(z);
            String opfXml = opf != null ? readEntry(z, opf) : null;
            if (opfXml == null) return null;
            String base = opf.contains("/") ? opf.substring(0, opf.lastIndexOf('/') + 1) : "";
            Map<String, String> items = new HashMap<String, String>();
            List<String> htmlItems = new ArrayList<String>();
            Matcher im = P_ITEM.matcher(opfXml);
            while (im.find()) {
                String id = attr(im.group(), "id"), href = attr(im.group(), "href");
                if (href == null) continue;
                if (id != null) items.put(id, href);
                if (P_HTMLEXT.matcher(href).matches()) htmlItems.add(href);
            }
            List<String> order = new ArrayList<String>();
            Matcher sp = P_ITEMREF.matcher(opfXml);
            while (sp.find()) {
                String href = items.get(attr(sp.group(), "idref"));
                if (href != null) order.add(href);
            }
            if (order.isEmpty()) order.addAll(htmlItems);
            int tries = 0;
            for (String href : order) {
                if (tries++ >= 64) break;
                String name = resolve(base, pctDecode(href));
                String html = readEntry(z, name);
                if (html == null) { String raw = resolve(base, href); html = readEntry(z, raw); if (html != null) name = raw; }
                if (html == null) { html = readEntry(z, href); name = href; }
                if (html == null) continue;
                Chapter c = parseChapter(html, name);
                if (c.text.length() < 8) continue;
                c.title = "";
                return c;
            }
            return null;
        } finally {
            try { z.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ 目录缓存

    /** 目录缓存：下次打开时整本还没读完目录就能用（跳到某一章只读那一个文件）。 */
    static final class Cached { final List<String> entries = new ArrayList<String>(); final List<TocItem> toc = new ArrayList<TocItem>(); String source; }

    private static final String CACHE_VER = "vdtoc1";

    /** 缓存文件名：路径 + 大小 + 修改时间，书换了就是另一个文件。 */
    static String cacheName(File book) {
        return Integer.toHexString(book.getAbsolutePath().hashCode()) + "-" + Long.toHexString(book.length()) + "-" + Long.toHexString(book.lastModified()) + ".toc";
    }

    static void saveCache(Book b, File to) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(CACHE_VER).append('\t').append(b.chapters.size()).append('\t').append(b.toc.size()).append('\t').append(b.tocSource).append('\n');
            for (Chapter c : b.chapters) sb.append(c.entry.replace('\n', ' ')).append('\n');
            for (TocItem t : b.toc) sb.append(t.depth).append('\t').append(t.chapter).append('\t').append(t.offset).append('\t').append(t.title.replace('\n', ' ')).append('\n');
            File tmp = new File(to.getPath() + ".tmp");
            java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
            try { o.write(sb.toString().getBytes("UTF-8")); } finally { o.close(); }
            if (!tmp.renameTo(to)) tmp.delete();
        } catch (Throwable ignored) { }
    }

    static Cached readCache(File from) {
        if (from == null || !from.isFile() || from.length() > 16 * 1024 * 1024) return null;
        try {
            byte[] buf = new byte[(int) from.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(from);
            try { int n = 0; while (n < buf.length) { int r = in.read(buf, n, buf.length - n); if (r < 0) break; n += r; } } finally { in.close(); }
            String[] lines = new String(buf, "UTF-8").split("\n", -1);
            String[] h = lines[0].split("\t");
            if (h.length < 4 || !CACHE_VER.equals(h[0])) return null;
            int nc = Integer.parseInt(h[1]), nt = Integer.parseInt(h[2]);
            if (lines.length < 1 + nc + nt) return null;
            Cached c = new Cached();
            c.source = h[3];
            for (int i = 0; i < nc; i++) c.entries.add(lines[1 + i]);
            for (int i = 0; i < nt; i++) {
                String[] f = lines[1 + nc + i].split("\t", 4);
                int ch = Integer.parseInt(f[1]);
                if (ch < 0 || ch >= nc) return null;
                c.toc.add(new TocItem(f.length > 3 ? f[3] : "", Integer.parseInt(f[0]), ch, Integer.parseInt(f[2])));
            }
            return c;
        } catch (Throwable t) { return null; }
    }

    /** 续读时先只读上次那一章（不解析整本书）。 */
    static Chapter loadChapter(File f, String entry) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            String html = readEntry(z, entry);
            if (html == null) return null;
            Chapter c = parseChapter(html, entry);
            c.title = "";
            return c;
        } finally {
            try { z.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ 目录

    /** 目录文件里的一条，还没对到章节上。 */
    static final class RawToc { String title; String href; int depth; }

    /** toc.ncx：navMap 里的 navPoint 按文件里的顺序（嵌套的记层级）；navLabel/text 是标题，content/@src 是位置。pageList / navList 不要。 */
    static List<RawToc> parseNcx(String xml) {
        List<RawToc> out = new ArrayList<RawToc>();
        List<RawToc> stack = new ArrayList<RawToc>();
        Matcher m = TAG.matcher(xml);
        boolean inMap = false, inLabel = false;
        while (m.find()) {
            String name = m.group(2).toLowerCase(Locale.US);
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);    // <ncx:navPoint>
            boolean close = m.group(1).length() > 0, empty = m.group(3).trim().endsWith("/");
            if ("navmap".equals(name)) { inMap = !close; continue; }
            if (!inMap) continue;
            if ("navpoint".equals(name)) {
                if (close) { if (!stack.isEmpty()) stack.remove(stack.size() - 1); }
                else {
                    RawToc r = new RawToc(); r.depth = stack.size();
                    out.add(r);
                    if (!empty) stack.add(r);
                }
            } else if ("navlabel".equals(name)) inLabel = !close && !empty;
            else if ("text".equals(name) && !close && inLabel && !stack.isEmpty()) {
                RawToc r = stack.get(stack.size() - 1);
                int e = indexOfClose(xml, m.end(), "text");
                if (e > m.end() && r.title == null) r.title = clean(htmlToText(P_ANYTAG.matcher(xml.substring(m.end(), e)).replaceAll(" ")));
            } else if ("content".equals(name) && !close && !stack.isEmpty()) {
                RawToc r = stack.get(stack.size() - 1);
                if (r.href == null) r.href = attr(m.group(3), "src");
            }
        }
        return out;
    }

    /** EPUB3 nav.xhtml：epub:type="toc" 的 nav（没有就第一个 nav）里的 ol/li；li 里的 a 是一条，只有 span 的 li 是分组标题（落到它下面第一条）。 */
    static List<RawToc> parseNav(String xml) {
        List<RawToc> out = new ArrayList<RawToc>();
        Matcher nm = Pattern.compile("(?is)<(?:\\w+:)?nav\\b([^>]*)>").matcher(xml);
        int from = -1, firstNav = -1;
        while (nm.find()) {
            if (firstNav < 0) firstNav = nm.end();
            String a = nm.group(1).toLowerCase(Locale.US);
            if (a.matches("(?s).*(epub:type|role)\\s*=\\s*[\"'][^\"']*\\b(doc-)?toc\\b.*")) { from = nm.end(); break; }
        }
        if (from < 0) from = firstNav;
        if (from < 0) return out;
        int to = indexOfClose(xml, from, "nav");
        if (to < 0) to = xml.length();
        String s = xml.substring(from, to);
        Matcher m = TAG.matcher(s);
        int depth = -1;
        RawToc li = null;
        while (m.find()) {
            String name = m.group(2).toLowerCase(Locale.US);
            boolean close = m.group(1).length() > 0;
            if ("ol".equals(name) || "ul".equals(name)) { depth += close ? -1 : 1; li = null; }
            else if ("li".equals(name)) {
                if (close) li = null;
                else { li = new RawToc(); li.depth = Math.max(0, depth); out.add(li); }
            } else if (("a".equals(name) || "span".equals(name)) && !close && li != null && li.title == null) {
                int e = indexOfClose(s, m.end(), name);
                if (e < 0) continue;
                li.title = clean(htmlToText(P_ANYTAG.matcher(s.substring(m.end(), e)).replaceAll(" ")));
                if ("a".equals(name)) li.href = attr(m.group(3), "href");
            }
        }
        return out;
    }

    /** 目录条目对到章节上：文件 → 第几章，#id → 那一章里的字符位置。对不上文件的条目不要；没有位置的分组标题落到下一条上。 */
    static List<TocItem> resolveToc(List<RawToc> raw, String dir, Map<String, Integer> spineAt, List<Chapter> chs) {
        Map<String, Integer> lower = new HashMap<String, Integer>();
        for (Map.Entry<String, Integer> e : spineAt.entrySet()) lower.put(e.getKey().toLowerCase(Locale.US), e.getValue());
        int n = raw.size();
        int[] ch = new int[n], off = new int[n];
        for (int i = 0; i < n; i++) {
            ch[i] = -1;
            String href = raw.get(i).href;
            if (href == null || href.length() == 0 || P_SCHEME.matcher(href).matches()) continue;
            int hash = href.indexOf('#');
            String file = pctDecode(hash >= 0 ? href.substring(0, hash) : href), frag = hash >= 0 ? pctDecode(href.substring(hash + 1)) : "";
            if (file.length() == 0) continue;
            String name = resolve(dir, file);
            Integer at = spineAt.get(name);
            if (at == null) at = lower.get(name.toLowerCase(Locale.US));
            if (at == null) continue;
            ch[i] = at;
            int[] info = frag.length() > 0 ? chs.get(at).ids.get(frag) : null;
            // 跳过的短文件（分卷页）落在下一章开头；#id 只在它本来的文件里找
            off[i] = info != null && name.equals(chs.get(at).entry) ? info[0] : 0;
        }
        for (int i = n - 2; i >= 0; i--) if (ch[i] < 0 && raw.get(i).href == null && ch[i + 1] >= 0) { ch[i] = ch[i + 1]; off[i] = off[i + 1]; }
        List<TocItem> out = new ArrayList<TocItem>();
        for (int i = 0; i < n; i++) {
            if (ch[i] < 0) continue;
            String t = raw.get(i).title;
            if (t == null || t.length() == 0) t = headAt(chs.get(ch[i]), off[i]);
            if (t == null || t.length() == 0) t = chs.get(ch[i]).title;
            out.add(new TocItem(t, raw.get(i).depth, ch[i], off[i]));
        }
        return out;
    }

    /**
     * 定下目录：书里有目录就用它，除非正文里“第X章”一类的标题比它多得多（目录只列了前一部分 / 只列文件，一个文件里好几章）——那就用正文标题；
     * 目录没覆盖到、自己又有标题的文件不少于 3 个时（书脊比目录长），把这些文件的标题补进去。没有目录：正文标题，再不行就一个文件一条。
     */
    static void buildToc(Book b, List<TocItem> given, String src) {
        List<TocItem> heads = headings(b.chapters);
        int pat = 0;
        for (Chapter c : b.chapters) for (int[] h : c.heads) if (h[1] >= PAT_VOLUME) pat++;
        b.toc.clear();
        if (given != null && given.size() >= 2 && !(pat >= 3 && pat > given.size() * 3 / 2 + 2 && heads.size() > given.size())) {
            b.toc.addAll(given); b.tocSource = src;
            fillGaps(b, heads);
        } else if (heads.size() >= 2) { b.toc.addAll(heads); b.tocSource = "headings"; }
        else if (given != null && !given.isEmpty()) { b.toc.addAll(given); b.tocSource = src; }
        else {
            for (int i = 0; i < b.chapters.size(); i++) b.toc.add(new TocItem(b.chapters.get(i).title, 0, i, 0));
            b.tocSource = "files";
        }
    }

    private static void fillGaps(Book b, List<TocItem> heads) {
        boolean[] covered = new boolean[b.chapters.size()];
        for (TocItem t : b.toc) covered[t.chapter] = true;
        List<TocItem> add = new ArrayList<TocItem>();
        int last = -1;
        for (TocItem h : heads) {
            if (covered[h.chapter] || h.chapter == last) continue;   // 每个没覆盖到的文件只补它的第一个标题
            add.add(h); last = h.chapter;
        }
        if (add.size() < 3) return;
        int minDepth = Integer.MAX_VALUE;
        for (TocItem t : b.toc) minDepth = Math.min(minDepth, t.depth);
        List<TocItem> out = new ArrayList<TocItem>(b.toc.size() + add.size());
        int j = 0;
        for (TocItem t : b.toc) {
            while (j < add.size() && before(add.get(j), t)) { TocItem h = add.get(j++); out.add(new TocItem(h.title, minDepth, h.chapter, h.offset)); }
            out.add(t);
        }
        while (j < add.size()) { TocItem h = add.get(j++); out.add(new TocItem(h.title, minDepth, h.chapter, h.offset)); }
        b.toc.clear(); b.toc.addAll(out);
    }

    private static boolean before(TocItem a, TocItem b) { return a.chapter < b.chapter || a.chapter == b.chapter && a.offset < b.offset; }

    /**
     * 正文标题生成的目录。有“第X章”一类的短行（3 个以上）就只用它们（卷 → 第 0 层、章 → 第 1 层）：网文里 h 标签常常是书名、作者、“正文”；
     * 没有就用 h1~h6 / class 带 title 的段落里（出现两次以上的）最高的两级。
     */
    static List<TocItem> headings(List<Chapter> chs) {
        int pat = 0, vol = 0;
        int[] perLevel = new int[8];
        for (Chapter c : chs) for (int[] h : c.heads) {
            if (h[1] >= PAT_VOLUME) { pat++; if (h[1] == PAT_VOLUME) vol++; }
            else perLevel[h[1]]++;
        }
        // 标签标题取出现两次以上的最高两级（只出现一次的 h1 多半是书名）
        int l1 = -1, l2 = -1;
        for (int lv = 1; lv <= 6; lv++) if (perLevel[lv] >= 2) { if (l1 < 0) l1 = lv; else if (l2 < 0) l2 = lv; }
        List<TocItem> out = new ArrayList<TocItem>();
        for (int ci = 0; ci < chs.size(); ci++) {
            Chapter c = chs.get(ci);
            for (int[] h : c.heads) {
                int depth;
                if (pat >= 3) {
                    if (h[1] < PAT_VOLUME) continue;
                    depth = h[1] == PAT_VOLUME || vol == 0 ? 0 : 1;
                } else {
                    if (h[1] != l1 && h[1] != l2) continue;
                    depth = h[1] == l1 ? 0 : 1;
                }
                String t = lineAt(c.text, h[0]);
                if (t.length() > 0) out.add(new TocItem(t, depth, ci, h[0]));
            }
        }
        return out;
    }

    /** “第一章 / 第12章 / 第一百零三回 / 第三卷 / 序章 / 楔子 / 番外……”开头、40 字以内的一行。卷、部、集、篇算分卷。 */
    private static final Pattern CHAP = Pattern.compile(
        "^(?:第\\s*[0-9０-９零〇一二三四五六七八九十百千万两]{1,9}\\s*([章节回卷部集篇幕话])|(序章|序言|序幕|楔子|引子|尾声|后记|终章|番外|大结局)|(?:chapter|CHAPTER|Chapter)\\s*[0-9]+)");

    /** 一行是不是章节标题：PAT_VOLUME / PAT_CHAPTER，不是返回 0。 */
    static int patLevel(String line) {
        if (line.length() == 0 || line.length() > 40) return 0;
        char c0 = line.charAt(0);
        if (c0 != '第' && "序楔引尾后终番大Cc".indexOf(c0) < 0) return 0;
        Matcher m = CHAP.matcher(line);
        if (!m.find()) return 0;
        String k = m.group(1);
        if (k != null && "卷部集篇".contains(k)) return PAT_VOLUME;
        // 句子不算（“第一章就写到这里了，……”）：标题里一般没有句号 / 逗号连着一大段
        if (line.length() > 24 && (line.indexOf('。') >= 0 || line.indexOf('，') > 0 && line.indexOf('，') < 12)) return 0;
        return PAT_CHAPTER;
    }

    /** 从 pos 开始的那一行（去掉首尾空白），太长的不算标题。 */
    static String lineAt(String text, int pos) {
        int i = Math.max(0, Math.min(text.length(), pos));
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        int e = text.indexOf('\n', i);
        if (e < 0) e = text.length();
        String t = clean(text.substring(i, e));
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /** TXT：正文里“第X章”一类的行（3 个以上）当目录；没有返回 null（不显示目录，和以前一样）。 */
    static List<TocItem> txtToc(Chapter c) {
        c.heads = findHeads(c.text, java.util.Collections.<Object[]>emptyList());
        int pat = 0;
        for (int[] h : c.heads) if (h[1] >= PAT_VOLUME) pat++;
        if (pat < 3) return null;
        List<Chapter> one = new ArrayList<Chapter>();
        one.add(c);
        return headings(one);
    }

    /** 目录条目没写标题：用它落的那个位置后面紧跟着的标题。 */
    private static String headAt(Chapter c, int off) {
        for (int[] h : c.heads) if (h[0] >= off && h[0] - off < 200) return lineAt(c.text, h[0]);
        return null;
    }

    /** 空白（含全角空格、不换行空格、BOM）合成一个空格，去掉首尾。 */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean sp = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\uFEFF') continue;
            if (Character.isWhitespace(ch) || ch == '\u3000' || ch == '\u00a0') { sp = b.length() > 0; continue; }
            if (sp) { b.append(' '); sp = false; }
            b.append(ch);
        }
        return b.toString();
    }

    private static String dirOf(String entry) { return entry.contains("/") ? entry.substring(0, entry.lastIndexOf('/') + 1) : ""; }

    private static int indexOfClose(String s, int from, String name) {
        Pattern p;
        synchronized (ATTR) {
            p = CLOSE.get(name);
            if (p == null) { p = Pattern.compile("(?i)</(?:\\w+:)?" + Pattern.quote(name) + "\\s*>"); CLOSE.put(name, p); }
        }
        Matcher m = p.matcher(s);
        return m.find(from) ? m.start() : -1;
    }

    private static String titleTag(String html) {
        Matcher m = TITLE.matcher(html);
        return m.find() ? clean(htmlToText(m.group(1))) : "";
    }
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    /**
     * 每个文件自己的标题（书里没有目录、也找不到正文标题时目录就是这些）：先取文件里的第一个标题；
     * 再取 &lt;title&gt;，但它是书名、或者一半以上的文件都是同一个 &lt;title&gt;（网文 EPUB 常见）就不算；再取第一行短字；最后“第 N 节”。
     */
    static void nameTitles(List<Chapter> chs, List<String> rawTitles, String bookTitle) {
        Map<String, Integer> count = new HashMap<String, Integer>();
        for (String t : rawTitles) if (t.length() > 0) { Integer n = count.get(t); count.put(t, n == null ? 1 : n + 1); }
        for (int i = 0; i < chs.size(); i++) {
            Chapter c = chs.get(i);
            String t = c.heads.isEmpty() ? "" : lineAt(c.text, c.heads.get(0)[0]);
            if (t.length() == 0) {
                String r = rawTitles.get(i);
                Integer n = count.get(r);
                if (r.length() > 0 && !r.equals(bookTitle) && (chs.size() < 4 || n == null || n * 2 <= chs.size())) t = r;
            }
            if (t.length() == 0) { String l = lineAt(c.text, 0); if (l.length() > 0 && l.length() <= 30) t = l; }
            if (t.length() == 0) t = "第 " + (i + 1) + " 节";
            c.title = t;
        }
    }

    // ------------------------------------------------------------------ 读文件

    static String readEntry(ZipFile z, String name) {
        try {
            ZipEntry e = z.getEntry(name);
            if (e == null) {
                String decoded = pctDecode(name);
                if (!decoded.equals(name)) e = z.getEntry(decoded);
            }
            if (e == null) return null;
            InputStream in = z.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) { bo.write(b, 0, n); if (bo.size() > MAX_ENTRY) break; }
            } finally {
                try { in.close(); } catch (Exception ignored) { }
            }
            byte[] raw = bo.toByteArray();
            return decodeText(raw, raw.length);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 文本编码识别（EPUB 章节与 TXT 电子书共用）：
     * 1. UTF-16LE / UTF-16BE / UTF-8 BOM 精确识别并去 BOM；
     * 2. 无 BOM 时用严格 UTF-8 解码校验，遇到任何非法 UTF-8 序列即改用 GB18030（回退 GBK），
     *    彻底解决短中文 TXT 误判为 UTF-8 乱码与 Windows/网文 UTF-16 TXT 整本乱码。
     */
    static String decodeText(byte[] b, int len) {
        if (b == null || len <= 0) return "";
        int n = Math.min(len, b.length);
        String s;
        if (n >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) {
            s = new String(b, 2, n - 2, Charset.forName("UTF-16LE"));
        } else if (n >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) {
            s = new String(b, 2, n - 2, Charset.forName("UTF-16BE"));
        } else if (n >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) {
            s = new String(b, 3, n - 3, Charset.forName("UTF-8"));
        } else {
            s = null;
            String declared = sniffXmlEncoding(b, n);
            if (declared != null && !declared.equalsIgnoreCase("utf-8") && !declared.equalsIgnoreCase("utf8")) {
                try {
                    if (Charset.isSupported(declared)) s = new String(b, 0, n, Charset.forName(declared));
                } catch (Throwable ignored) { }
            }
            if (s == null) {
                try {
                    java.nio.charset.CharsetDecoder dec = Charset.forName("UTF-8").newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                    s = dec.decode(java.nio.ByteBuffer.wrap(b, 0, n)).toString();
                } catch (java.nio.charset.CharacterCodingException badUtf8) {
                    for (String cs : new String[] { "GB18030", "GBK" }) {
                        try { s = new String(b, 0, n, Charset.forName(cs)); break; } catch (Throwable ignored) { }
                    }
                }
            }
            if (s == null) s = new String(b, 0, n, Charset.forName("UTF-8"));
        }
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        return s;
    }

    private static final Pattern P_XML_ENC = Pattern.compile("(?is)<\\?xml[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']");

    private static String sniffXmlEncoding(byte[] b, int n) {
        int headLen = Math.min(n, 256);
        if (headLen < 20) return null;
        String head = new String(b, 0, headLen, Charset.forName("ISO-8859-1"));
        Matcher m = P_XML_ENC.matcher(head);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 百分号解码（UTF-8），+ 不动；解不了就原样返回。 */
    static String pctDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (int i = 0; i < s.length(); ) {
                char ch = s.charAt(i);
                if (ch == '%' && i + 3 <= s.length()) {
                    int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    b.write(v); i += 3;
                } else { b.write(String.valueOf(ch).getBytes("UTF-8")); i++; }
            }
            return new String(b.toByteArray(), "UTF-8");
        } catch (Throwable t) { return s; }
    }

    static String resolve(String base, String href) {
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

    static String attr(String tag, String name, String attrName) {
        if (tag.indexOf(attrName.charAt(0)) < 0 && tag.indexOf(Character.toUpperCase(attrName.charAt(0))) < 0) return null;
        Pattern[] ps;
        synchronized (ATTR) {
            ps = ATTR.get(attrName);
            if (ps == null) {
                ps = new Pattern[] { Pattern.compile("(?is)(?:^|[\\s\"'/])" + Pattern.quote(attrName) + "\\s*=\\s*\"([^\"]*)\""),
                    Pattern.compile("(?is)(?:^|[\\s\"'/])" + Pattern.quote(attrName) + "\\s*=\\s*'([^']*)'") };
                ATTR.put(attrName, ps);
            }
        }
        Matcher m = ps[0].matcher(tag);
        if (m.find()) return m.group(1);
        m = ps[1].matcher(tag);
        if (m.find()) return m.group(1);
        return null;
    }
    static String attr(String tag, String attrName) { return attr(tag, null, attrName); }



    /** 标记字符：私用区，书里不会有。标记夹在原来的文字里走完 stripHtml 的整套替换，最后拿掉并记下位置。 */
    private static final char MK = '\uE000', MK_END = '\uE001';
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][\\w:.-]*)([^>]*)>");
    private static final java.util.Set<String> BLOCKS = new java.util.HashSet<String>(java.util.Arrays.asList(
        "p", "li", "div", "aside", "section", "dd", "dt", "td", "blockquote", "ol", "ul", "dl", "table", "body", "h1", "h2", "h3", "h4", "h5", "h6"));

    /**
     * 一章的 HTML → 文字 + id 的位置 + 书内链接。文字和以前的 stripHtml 一模一样（续读位置、书签都按字符位置记，不能变），
     * 只是另外记下：每个带 id 的元素从哪个字开始（块级元素还有到哪里结束）、每个 &lt;a href&gt; 盖住哪几个字、指向哪里。
     */
    static Chapter parseChapter(String html, String entry) {
        Chapter c = new Chapter();
        c.entry = entry;
        String s = html;
        s = P_COMMENT.matcher(s).replaceAll(" ");
        s = P_STRIP0.matcher(s).replaceAll(" ");
        s = P_STRIP1.matcher(s).replaceAll(" ");
        s = P_STRIP2.matcher(s).replaceAll(" ");
        if (s.indexOf(MK) >= 0 || s.indexOf(MK_END) >= 0) { s = s.replace(MK, ' ').replace(MK_END, ' '); }
        List<int[]> tags = new ArrayList<int[]>();          // {起, 止, 是闭合标签 1/0}
        List<String> names = new ArrayList<String>(), attrsL = new ArrayList<String>();
        Matcher m = TAG.matcher(s);
        while (m.find()) {
            tags.add(new int[] { m.start(), m.end(), m.group(1).length() > 0 ? 1 : 0 });
            names.add(m.group(2).toLowerCase(Locale.US));
            attrsL.add(m.group(3));
        }
        if (tags.isEmpty()) { c.text = stripHtml(s); return c; }
        String dir = entry.contains("/") ? entry.substring(0, entry.lastIndexOf('/') + 1) : "";
        List<String> idNames = new ArrayList<String>();
        List<int[]> idInfo = new ArrayList<int[]>();             // {是注释 1/0, 是块 1/0, 是标题 1/0}
        List<Link> links = new ArrayList<Link>();
        List<Object[]> headRaw = new ArrayList<Object[]>();     // {级别, 标题标签里的 HTML}
        java.util.Map<Integer, List<Integer>> closeAt = new java.util.HashMap<Integer, List<Integer>>();
        StringBuilder out = new StringBuilder(s.length() + 64);
        int last = 0, open = -1, aDepth = 0;
        for (int i = 0; i < tags.size(); i++) {
            int[] t = tags.get(i);
            String name = names.get(i), at = attrsL.get(i);
            out.append(s, last, t[0]);
            List<Integer> ends = closeAt.get(i);
            if (ends != null) for (int n : ends) out.append(MK).append('X').append(n).append(MK_END);
            if (t[2] == 1) {
                if ("a".equals(name) && open >= 0) {
                    if (aDepth > 0) aDepth--;
                    else { out.append(MK).append('E').append(MK_END); open = -1; }
                }
            } else {
                int hl = headLevel(name, at);
                if (hl > 0 && !at.trim().endsWith("/")) {
                    int e = indexOfClose(s, t[1], name);
                    if (e > t[1]) headRaw.add(new Object[] { hl, s.substring(t[1], Math.min(e, t[1] + 4000)) });
                }
                String id = attr(at, "id");
                if (id == null && "a".equals(name)) id = attr(at, "name");
                if (id != null && id.length() > 0) {
                    int n = idNames.size();
                    String type = (String.valueOf(attr(at, "epub:type")) + " " + String.valueOf(attr(at, "class")) + " " + String.valueOf(attr(at, "role"))).toLowerCase(Locale.US);
                    // 注释元素：aside，或 epub:type / class / role 带 note（footnote、endnote、rearnote……）。
                    // 但注释号本身（noteref、带 href 的 <a>）和回链（backlink、footnote-back）不是：从注释“返回正文”时要跳回去，
                    // 以前 pandoc 一类的书（<a id="fnref1" class="footnote-ref">）回链也弹出一个注释框
                    boolean note = "aside".equals(name) || type.contains("note") && !type.contains("noteref") && !type.contains("back")
                        && !type.contains("-ref") && !("a".equals(name) && attr(at, "href") != null);
                    boolean block = BLOCKS.contains(name);
                    boolean head = name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6';
                    idNames.add(id); idInfo.add(new int[] { note ? 1 : 0, block ? 1 : 0, head ? 1 : 0 });
                    out.append(MK).append('I').append(n).append(MK_END);
                    if (block && !at.trim().endsWith("/")) {   // 找对应的闭合标签（同名的嵌套要数层数）
                        int depth = 0;
                        for (int j = i + 1; j < tags.size(); j++) {
                            if (!name.equals(names.get(j))) continue;
                            if (tags.get(j)[2] == 0) { if (!attrsL.get(j).trim().endsWith("/")) depth++; }
                            else if (depth == 0) {
                                List<Integer> l = closeAt.get(j);
                                if (l == null) { l = new ArrayList<Integer>(); closeAt.put(j, l); }
                                l.add(n);
                                break;
                            } else depth--;
                        }
                    }
                }
                if (("img".equals(name) || "image".equals(name)) && at.indexOf('=') >= 0) {
                    if (open >= 0) links.get(open).img = true;
                    String alt = attr(at, "alt");
                    String ty = (String.valueOf(attr(at, "class")) + " " + String.valueOf(attr(at, "epub:type"))).toLowerCase(Locale.US);
                    if (alt != null && ty.contains("note")) {
                        alt = clean(htmlToText(alt));
                        if (alt.length() >= 2) {
                            if (open >= 0) { if (links.get(open).inline == null) links.get(open).inline = alt; }
                            else {   // 不在链接里的注释图：自己就是一个注释号（点了弹 alt 里的字）
                                Link k = new Link();
                                k.target = entry; k.noteref = true; k.img = true; k.inline = alt;
                                int ln = links.size();
                                links.add(k);
                                out.append(MK).append('L').append(ln).append(MK_END).append(MK).append('E').append(MK_END);
                            }
                        }
                    }
                }
                boolean selfClose = at.trim().endsWith("/");
                String href = "a".equals(name) ? attr(at, "href") : null;
                if (href != null && href.length() > 0 && !P_SCHEME.matcher(href).matches()) {
                    if (open >= 0) { out.append(MK).append('E').append(MK_END); open = -1; aDepth = 0; }
                    Link k = new Link();
                    int hash = href.indexOf('#');
                    String file = hash >= 0 ? href.substring(0, hash) : href, frag = hash >= 0 ? href.substring(hash + 1) : "";
                    file = pctDecode(file); frag = pctDecode(frag);   // 不用 URLDecoder：它把 + 当空格（文件名里的 + 就对不上了）
                    String target = file.length() == 0 ? entry : resolve(dir, file);
                    k.target = frag.length() > 0 ? target + "#" + frag : target;
                    String type = (String.valueOf(attr(at, "epub:type")) + " " + String.valueOf(attr(at, "class")) + " " + String.valueOf(attr(at, "role"))).toLowerCase(Locale.US);
                    k.back = type.contains("back") || type.contains("return");   // backlink、footnote-back、doc-backlink、returnlink……
                    k.noteref = !k.back && (type.contains("noteref") || type.contains("footnote") || type.contains("endnote") || type.contains("-ref"));
                    k.img = type.contains("note");   // 多看的 class="duokan-footnote"：链接里没有字也是注释号
                    open = links.size();
                    links.add(k);
                    out.append(MK).append('L').append(open).append(MK_END);
                    if (selfClose) { out.append(MK).append('E').append(MK_END); open = -1; aDepth = 0; }
                } else if ("a".equals(name) && open >= 0 && !selfClose) {
                    aDepth++;
                }
            }
            out.append(s, t[0], t[1]);
            last = t[1];
        }
        out.append(s, last, s.length());
        if (open >= 0) out.append(MK).append('E').append(MK_END);
        String marked = stripHtml(out.toString());
        // 拿掉标记、记下位置。只在标记的地方补做 stripHtml 本来会做的空白合并（标记把两个空格 / 换行隔开了），
        // 别处一个字都不动：没有链接的书得到的文字和以前完全一样
        StringBuilder t = new StringBuilder(marked.length());
        int[] idStart = new int[idNames.size()], idEnd = new int[idNames.size()];
        java.util.Arrays.fill(idStart, -1); java.util.Arrays.fill(idEnd, -1);
        int[] linkStart = new int[links.size()], linkEnd = new int[links.size()];
        java.util.Arrays.fill(linkStart, -1); java.util.Arrays.fill(linkEnd, -1);
        int cur = -1;
        boolean afterMark = false;
        for (int i = 0; i < marked.length(); i++) {
            char ch = marked.charAt(i);
            if (ch == MK) {
                int e = marked.indexOf(MK_END, i);
                if (e < 0) break;
                char kind = marked.charAt(i + 1);
                int n = -1;
                try { if (e > i + 2) n = Integer.parseInt(marked.substring(i + 2, e)); } catch (NumberFormatException ignored) { }
                int pos = t.length();
                if (kind == 'I' && n >= 0 && n < idStart.length) idStart[n] = pos;
                else if (kind == 'X' && n >= 0 && n < idEnd.length) idEnd[n] = pos;
                else if (kind == 'L' && n >= 0 && n < linkStart.length) { linkStart[n] = pos; cur = n; }
                else if (kind == 'E' && cur >= 0) { linkEnd[cur] = pos; cur = -1; }
                i = e;
                afterMark = true;
                continue;
            }
            if (afterMark) {
                int len = t.length();
                char prev = len > 0 ? t.charAt(len - 1) : '\n';
                boolean ws = ch == ' ' || ch == '\t';
                if (len == 0 && (ws || ch == '\n')) continue;
                if (ws && (prev == ' ' || prev == '\n')) continue;
                if (ch == '\n' && prev == '\n' && len > 1 && t.charAt(len - 2) == '\n') continue;
                if (ch == '\n' && prev == ' ') { t.setLength(len - 1); }
                if (!ws && ch != '\n') afterMark = false;
            }
            t.append(ch);
        }
        int n = t.length();
        while (n > 0 && Character.isWhitespace(t.charAt(n - 1))) n--;
        t.setLength(n);
        c.text = t.toString();
        java.util.Map<String, int[]> ids = new java.util.HashMap<String, int[]>();
        for (int i = 0; i < idNames.size(); i++) {
            if (idStart[i] < 0 || ids.containsKey(idNames.get(i))) continue;
            int st = Math.min(idStart[i], n), en = idEnd[i] < 0 ? -1 : Math.min(idEnd[i], n);
            ids.put(idNames.get(i), new int[] { st, en, idInfo.get(i)[0], idInfo.get(i)[1], idInfo.get(i)[2] });
        }
        c.ids = ids;
        List<Link> kept = new ArrayList<Link>();
        for (int i = 0; i < links.size(); i++) {
            Link k = links.get(i);
            k.start = Math.min(linkStart[i], n); k.end = Math.min(linkEnd[i] < 0 ? linkStart[i] : linkEnd[i], n);
            if (k.start < 0) continue;
            while (k.start < k.end && Character.isWhitespace(c.text.charAt(k.start))) k.start++;
            while (k.end > k.start && Character.isWhitespace(c.text.charAt(k.end - 1))) k.end--;
            if (k.end <= k.start && !k.back && (k.img || k.inline != null) && (k.target.indexOf('#') > 0 || k.inline != null)) {
                // 没有字的链接：注释号是一张小图（多看格式 <a class="duokan-footnote"><img/></a> 很常见）。以前整个丢掉，这种注释根本点不开。
                // 只有链接里真有图（或者 class 带 note）才这样挂：多看注释框里那个空的回链 <a href="#…-back"></a> 以前也挂到了正文最后一个字上
                // 正文不能多出字（续读位置、书签都按字符位置记）：链接挂在它前面那个字上（行首就挂后面那个字），那个字画虚下划线、点了弹注释
                int p = Math.min(k.start, n);
                while (p > 0 && Character.isWhitespace(c.text.charAt(p - 1))) p--;
                if (p > 0) { k.start = p - 1; k.end = p; }
                else { p = Math.min(k.start, n); while (p < n && Character.isWhitespace(c.text.charAt(p))) p++; if (p < n) { k.start = p; k.end = p + 1; } }
                k.noteref = true;
            }
            if (k.end > k.start) kept.add(k);
        }
        c.links = kept;
        c.heads = findHeads(c.text, headRaw);
        return c;
    }

    /** h1~h6 → 1~6；class / epub:type 带 title、chapter、heading 的 p / div → 3（note、subtitle 不算）。 */
    private static int headLevel(String name, String at) {
        if (name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1' && name.charAt(1) <= '6') return name.charAt(1) - '0';
        if (!"p".equals(name) && !"div".equals(name)) return 0;
        if (at.indexOf('=') < 0) return 0;
        String type = (String.valueOf(attr(at, "class")) + " " + String.valueOf(attr(at, "epub:type"))).toLowerCase(Locale.US);
        if (type.contains("note") || type.contains("subtitle")) return 0;
        return type.contains("title") || type.contains("chapter") || type.contains("heading") ? 3 : 0;
    }

    /**
     * 这个文件里的标题在 text 里的位置：标签标题按它的第一行字在 text 里往后找（不在文字里插标记：续读位置按字符记，文字一个字都不能变）；
     * 再加上“第X章”一类的短行。按位置排好。
     */
    static List<int[]> findHeads(String text, List<Object[]> headRaw) {
        List<int[]> hs = new ArrayList<int[]>();
        int from = 0;
        for (Object[] r : headRaw) {
            String line = lineAt(stripHtml((String) r[1]), 0);
            if (line.endsWith("…")) line = line.substring(0, line.length() - 1);
            if (line.length() == 0) continue;
            int p = text.indexOf(line, from);
            if (p < 0 && line.length() > 6) p = text.indexOf(line.substring(0, 6), from);
            if (p < 0) { String first = line.split(" ")[0]; if (first.length() >= 2) p = text.indexOf(first, from); }
            if (p < 0) continue;
            hs.add(new int[] { p, (Integer) r[0] });
            from = p + 1;
        }
        int n = text.length();
        for (int i = 0; i < n; ) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = n;
            int a = i;
            while (a < e && (text.charAt(a) == ' ' || text.charAt(a) == '\u3000' || text.charAt(a) == '\t')) a++;
            if (a < e && e - a <= 60) {
                char c0 = text.charAt(a);
                if (c0 == '第' || "序楔引尾后终番大Cc".indexOf(c0) >= 0) {
                    int lv = patLevel(clean(text.substring(a, e)));
                    if (lv > 0) hs.add(new int[] { a, lv });
                }
            }
            i = e + 1;
        }
        if (hs.size() > 1) java.util.Collections.sort(hs, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] x, int[] y) { return x[0] != y[0] ? (x[0] < y[0] ? -1 : 1) : 0; }
        });
        return hs;
    }

    // ------------------------------------------------------------------ 注释

    /** 弹出来的注释：文字（最多 NOTE_MAX 字），more = 后面还有（弹框里出“全文”） */
    static final class Note {
        final String text; final boolean more;
        Note(String text, boolean more) { this.text = text; this.more = more; }
        @Override public String toString() { return (more ? "+" : "") + text; }
    }
    static final int NOTE_MAX = 1500;

    /** 像注释号的链接文字：[1]、(2)、〔3〕、注1、[注]、①、¹、*、†、a…… */
    private static final String NOTE_NUM = "(?:[0-9０-９]{1,3}|[a-zA-Z]|[*＊†‡§※]{1,3}|[①-⑳⑴-⒇㈠-㈩]|[¹²³⁴-⁹⁰]{1,3}|[ⅰ-ⅹⅠ-Ⅹ]|[一二三四五六七八九十]{1,3})";
    private static final Pattern NOTE_MARK = Pattern.compile("^[\\[(（〔【<［⁽]?\\s*(?:(?:注|註|note|Note|NOTE)\\s*" + NOTE_NUM + "?|" + NOTE_NUM + ")\\s*[\\])）〕】>］⁾]?$");
    /** 注释正文里一行开头的注释号（[5]、（5）、注5、5.）：没有元素边界时，下一条注释从这里开始 */
    private static final Pattern NOTE_LEAD = Pattern.compile("^[ \\u3000\\t·]*(?:[\\[［(（〔【⁽]\\s*(?:注|註)?\\s*[0-9０-９]{1,3}\\s*[\\]］)）〕】⁾]|(?:注|註)\\s*[0-9０-９]{1,3}|[①-⑳⑴-⒇]|[0-9]{1,3}[.、．])");
    /** 回链的字：↩ ↑ ^ 返回 / 回到正文…… */
    private static final Pattern BACK_MARK = Pattern.compile("^[\\[(（〔【［]?\\s*(?:[↩↑⏎↵⤴⬆^︿]|返回|回到正文|返回正文|回正文|back|Back|return)[\\uFE0E\\uFE0F]?\\s*[\\])）〕】］]?$");

    static boolean noteMark(String label) { return label != null && label.length() > 0 && label.length() <= 8 && NOTE_MARK.matcher(label).matches(); }

    /** 这个位置前面（同一行里）只有空白、li 的“· ”或者开括号：是一行（一条注释）的开头 */
    static boolean lineStart(String t, int pos) {
        for (int i = Math.min(pos, t.length()) - 1; i >= 0; i--) {
            char ch = t.charAt(i);
            if (ch == '\n') return true;
            if (ch != ' ' && ch != '　' && ch != '\t' && ch != '·' && "[［(（〔【⁽".indexOf(ch) < 0) return false;
        }
        return true;
    }

    /**
     * 点了正文里的链接 k（链接的字 label），它指向 target 这一章里的 frag：是注释就返回注释的文字，不是注释（目录、回链、
     * 指向正文中间的锚点）或者取不出字就返回 null——调用方直接跳过去，不弹中间那个框。
     *
     * 算注释：目标是 aside / epub:type 或 class 带 note 的元素（footnote、endnote、rearnote……），或者链接像个注释号
     * （noteref、多看的注释图、[5] 这种字）而目标是一条注释的开头（一个块：p / li / div，或者一行开头的锚点）。
     * 指向一段正文中间的锚点的是回链（注释里的 [5] 跳回正文），跳回去。注释直接写在图片 alt 里的弹 alt。
     */
    static Note note(Link k, String label, Chapter target, String frag) {
        if (k == null || k.back) return null;
        int[] info = target == null || frag == null || frag.length() == 0 ? null : target.ids.get(frag);
        Note n = null;
        if (info != null) {
            boolean heading = info.length > 4 && info[4] == 1;
            boolean entry = info[3] == 1 && !heading || lineStart(target.text, info[0]);
            boolean marker = k.noteref || k.img || noteMark(label) || info[3] == 1 && !heading && label != null && label.length() <= 6;
            if (info[2] == 1 || marker && entry) n = noteAt(target, info, label);
        }
        if ((n == null || n.text.length() == 0) && k.inline != null) n = new Note(k.inline.length() > NOTE_MAX ? k.inline.substring(0, NOTE_MAX) + "…" : k.inline, false);
        return n == null || n.text.length() == 0 ? null : n;
    }

    /**
     * 注释的文字：块级元素（aside / li / p / div）到它结束为止；目标是段落里的锚点（结束不知道）就到这一段结束（空一行）。
     * 两种都在下一条注释开始的地方截住：后面一行开头的另一个锚点 / 注释号链接 / “[6]”一类的字（一串 div、只隔一个换行的
     * 注释以前一下子带上后面几十条）。开头的回链（“[5]”、↩）和 li 的“· ”去掉，结尾的回链（↩、返回）也去掉。
     */
    static Note noteAt(Chapter c, int[] info, String label) {
        if (c == null || info == null) return null;
        String t = c.text;
        int L = t.length();
        int st = Math.max(0, Math.min(L, info[0]));
        int en = info[1] > st ? Math.min(info[1], L) : -1;
        if (en < 0) { en = t.indexOf("\n\n", st + 1); if (en < 0) en = L; }
        int b = st;
        while (b < en && (Character.isWhitespace(t.charAt(b)) || t.charAt(b) == '　' || t.charAt(b) == '·')) b++;
        // 下一条注释：这一条第一行之后、一行开头的锚点 / 链接 / 注释号（li 的开头是“\n· ”：从第一个字算起）
        int nl = t.indexOf('\n', b);
        if (nl >= 0 && nl < en) {
            int next = en;
            for (int[] v : c.ids.values()) if (v[0] > nl && v[0] < next && lineStart(t, v[0])) next = v[0];
            for (Link k : c.links) if (k.start > nl && k.start < next && lineStart(t, k.start) && noteMark(t.substring(k.start, k.end).trim())) next = k.start;
            for (int i = nl; i >= 0 && i < next; i = t.indexOf('\n', i + 1)) {
                Matcher m = NOTE_LEAD.matcher(t.substring(i + 1, Math.min(next, i + 24)));
                if (m.lookingAt()) { next = i + 1; break; }
            }
            // 往回退到那一行的开头（“· ”、括号不留在上一条后面）
            while (next > b && next < en && t.charAt(next - 1) != '\n') next--;
            if (next > b) en = Math.min(en, next);
        }
        // 开头：空白、“· ”（上面已经跳过），然后是回链 / 注释号
        st = b;
        for (Link k : c.links) {
            if (k.start > b || k.end <= b || k.end > en) continue;
            String l = t.substring(k.start, k.end).trim();
            if (k.back || noteMark(l) || BACK_MARK.matcher(l).matches() || l.equals(label)) { b = k.end; break; }
        }
        String lab = label == null ? "" : label.trim();
        if (b == st || b < en && lineStart(t, b)) {
            String rest = t.substring(b, Math.min(en, b + 24));
            if (lab.length() > 0 && rest.startsWith(lab)) b += lab.length();
            else if (noteMark(lab)) {
                String core = lab.replaceAll("[\\[\\]()（）〔〕【】<>［］⁽⁾\\s]|注|註", "");
                if (core.length() > 0) {
                    Matcher m = Pattern.compile("^[\\[［(（〔【<⁽]?\\s*(?:注|註)?\\s*" + Pattern.quote(core) + "\\s*[\\]］)）〕】>⁾]?").matcher(rest);
                    if (m.lookingAt() && m.end() > 0) b += m.end();
                }
            }
        }
        while (b < en && (Character.isWhitespace(t.charAt(b)) || "\u3000·.．、:：]］)）〕】⁾".indexOf(t.charAt(b)) >= 0)) b++;
        // 结尾的回链
        int e = en;
        while (e > b && Character.isWhitespace(t.charAt(e - 1))) e--;
        for (Link k : c.links) {
            if (k.start < b || k.end > e || k.end < e - 1) continue;
            String l = t.substring(k.start, k.end).trim();
            if (k.back || BACK_MARK.matcher(l).matches()) { e = k.start; break; }
        }
        boolean more = e - b > NOTE_MAX;
        if (more) e = b + NOTE_MAX;
        if (e <= b) return new Note("", false);
        String s = t.substring(b, e).trim();
        if (s.startsWith("· ")) s = s.substring(2).trim();
        if (more) s += "…";
        return new Note(s, more);
    }

    static String stripHtml(String html) {
        String s = html;
        s = P_COMMENT.matcher(s).replaceAll(" ");
        s = P_STRIP0.matcher(s).replaceAll(" ");
        s = P_STRIP1.matcher(s).replaceAll(" ");
        s = P_STRIP2.matcher(s).replaceAll(" ");
        s = P_STRIP3.matcher(s).replaceAll("\n");
        s = P_STRIP4.matcher(s).replaceAll("\n\n");
        s = P_STRIP5.matcher(s).replaceAll("\n");
        s = P_STRIP6.matcher(s).replaceAll("\n\n");
        s = P_STRIP7.matcher(s).replaceAll("\n· ");
        s = P_STRIP8.matcher(s).replaceAll(" ");
        s = htmlToText(s);
        s = P_STRIP9.matcher(s).replaceAll(" ");
        s = P_STRIP10.matcher(s).replaceAll("\n");
        s = P_STRIP11.matcher(s).replaceAll("\n\n");
        return s.trim();
    }

    static String htmlToText(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        s = s.replace("&nbsp;", " ").replace("&ensp;", " ").replace("&emsp;", "\u3000").replace("&thinsp;", " ")
             .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
             .replace("&mdash;", "\u2014").replace("&ndash;", "\u2013").replace("&hellip;", "\u2026")
             .replace("&ldquo;", "\u201C").replace("&rdquo;", "\u201D").replace("&lsquo;", "\u2018").replace("&rsquo;", "\u2019")
             .replace("&middot;", "\u00B7").replace("&bull;", "\u2022").replace("&copy;", "\u00A9").replace("&reg;", "\u00AE").replace("&trade;", "\u2122")
             .replace("&times;", "\u00D7").replace("&divide;", "\u00F7").replace("&plusmn;", "\u00B1").replace("&deg;", "\u00B0")
             .replace("&laquo;", "\u00AB").replace("&raquo;", "\u00BB");
        if (s.indexOf("&#") >= 0) {
            Matcher m = P_NUMENT.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                try {
                    int cp = Integer.parseInt(m.group(2), m.group(1).length() > 0 ? 16 : 10);
                    if (cp <= 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                        m.appendReplacement(sb, "");
                    } else {
                        m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
                    }
                } catch (Exception e) {
                    m.appendReplacement(sb, "");
                }
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        return s.replace("&amp;", "&");
    }
}
