package com.veradroid.home;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置阅读器的 EPUB 目录（Epub.java）：跑 tests/reader/gen_epubs.py 生成的书，对 expect.tsv。
 * java com.veradroid.home.EpubTocTest <epub 目录>
 */
public class EpubTocTest {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir, "expect.tsv")), "UTF-8"));
        String line;
        while ((line = r.readLine()) != null) {
            String[] e = line.split("\t");
            long t0 = System.currentTimeMillis();
            Epub.Book b = Epub.load(new File(dir, e[0] + ".epub"));
            long ms = System.currentTimeMillis() - t0;
            String n = e[0];
            System.out.println(n + ": " + b.chapters.size() + " files, toc " + b.toc.size() + " (" + b.tocSource + ") " + ms + " ms"
                + (b.toc.isEmpty() ? "" : "  [" + b.toc.get(0).title + " … " + b.toc.get(b.toc.size() - 1).title + "]"));
            check(b.toc.size() == Integer.parseInt(e[1]), n + ": toc size " + b.toc.size() + " != " + e[1]);
            check(b.tocSource.equals(e[2]), n + ": source " + b.tocSource + " != " + e[2]);
            if (b.toc.isEmpty()) continue;
            check(b.toc.get(0).title.equals(e[3]), n + ": first title '" + b.toc.get(0).title + "' != '" + e[3] + "'");
            check(b.toc.get(b.toc.size() - 1).title.equals(e[4]), n + ": last title '" + b.toc.get(b.toc.size() - 1).title + "' != '" + e[4] + "'");
            check(!b.truncated, n + ": truncated");
            // 第一次打开先只读的那一章 = 整本里的第 0 章
            Epub.Chapter fc = Epub.firstChapter(new File(dir, e[0] + ".epub"));
            check(fc != null && fc.entry.equals(b.chapters.get(0).entry) && fc.text.equals(b.chapters.get(0).text), n + ": firstChapter is chapter 0");
            // 目录缓存来回一趟不变
            File cf = new File(dir, Epub.cacheName(new File(dir, e[0] + ".epub")));
            Epub.saveCache(b, cf);
            Epub.Cached cc = Epub.readCache(cf);
            boolean same = cc != null && cc.toc.size() == b.toc.size() && cc.entries.size() == b.chapters.size();
            for (int i = 0; same && i < b.toc.size(); i++) same = cc.toc.get(i).toString().equals(b.toc.get(i).toString());
            for (int i = 0; same && i < b.chapters.size(); i++) same = cc.entries.get(i).equals(b.chapters.get(i).entry);
            check(same, n + ": toc cache round trip");
            cf.delete();
            // 每一条都落在它的标题上（分卷条目落在下一章的开头）
            int bad = 0, prevCh = -1, prevOff = -1, back = 0;
            for (Epub.TocItem t : b.toc) {
                String at = Epub.lineAt(b.chapters.get(t.chapter).text, t.offset);
                if (!at.equals(t.title) && !(n.startsWith("d-") && t.depth == 0)) { if (bad++ < 3) System.out.println("  item " + t + " lands on '" + at + "'"); }
                if (t.chapter < prevCh || t.chapter == prevCh && t.offset < prevOff) back++;
                prevCh = t.chapter; prevOff = t.offset;
            }
            check(bad == 0, n + ": " + bad + " toc items do not land on their title");
            check(back == 0, n + ": toc not in reading order (" + back + ")");
            // 文件自己的标题不能是书名（每个文件 <title> 都是书名）
            for (Epub.Chapter c : b.chapters) if (c.title.startsWith("测试长篇")) { check(false, n + ": file title is the book title: " + c.entry); break; }
            if (n.startsWith("d-")) {
                check(b.toc.get(0).depth == 0 && b.toc.get(1).depth == 1, n + ": volume / chapter depths");
                check(b.toc.get(0).chapter == b.toc.get(1).chapter && b.toc.get(0).offset == 0, n + ": volume entry lands on its first chapter");
                check(b.chapters.size() == 1200, n + ": volume pages are not chapters (" + b.chapters.size() + ")");
            }
            if (n.startsWith("e-")) check(b.chapters.size() == 120 && b.toc.get(9).chapter == 0 && b.toc.get(10).chapter == 1 && b.toc.get(9).offset > 0, n + ": anchors inside files");
            if (n.startsWith("i-")) check(b.chapters.size() == 750, n + ": calibre split files " + b.chapters.size());
            if (n.startsWith("k-")) check(b.toc.get(9).title.equals("第十章 别离"), n + ": empty navLabel takes the heading: " + b.toc.get(9).title);
        }
        r.close();

        // “第X章”识别
        check(Epub.patLevel("第一章 风起") == Epub.PAT_CHAPTER, "第一章");
        check(Epub.patLevel("第1203章　归来") == Epub.PAT_CHAPTER, "第1203章");
        check(Epub.patLevel("第三卷 星河") == Epub.PAT_VOLUME, "第三卷");
        check(Epub.patLevel("第二天早上，他起得很早") == 0, "第二天 is not a chapter");
        check(Epub.patLevel("番外 后日谈") == Epub.PAT_CHAPTER, "番外");
        check(Epub.patLevel("第一章就写到这里了，我们接着说后面发生的那件很长很长的事情。") == 0, "a sentence is not a title");
        check(Epub.clean(" 第一章　　风起 ").equals("第一章 风起"), "full-width spaces collapse");

        // TXT：“第X章”行当目录
        StringBuilder t = new StringBuilder();
        for (int i = 1; i <= 50; i++) t.append("第").append(i).append("章　标题").append(i).append("\n\n正文正文正文。第").append(i).append("章里的事。\n\n");
        Epub.Chapter c = new Epub.Chapter();
        c.text = t.toString();
        List<Epub.TocItem> tt = Epub.txtToc(c);
        check(tt != null && tt.size() == 50 && tt.get(49).title.equals("第50章 标题50"), "txt toc " + (tt == null ? "null" : tt.size() + " " + tt.get(tt.size() - 1).title));
        Epub.Chapter plain = new Epub.Chapter();
        plain.text = "没有章节的一段文字。\n\n第二段。";
        check(Epub.txtToc(plain) == null, "txt without chapters has no toc");

        // Cycle 6 回归：HTML 实体（含 $ / \ 数值实体、常见中英文实体、&amp; 后解码、非法码点）、HTML 注释含 >、BOM 与 GB18030/UTF-16 编码
        String ent = Epub.htmlToText("&#36;100 &amp; &#92;path &mdash; &ldquo;hi&rdquo;&hellip; &amp;lt;tag&amp;gt; &#x4E2D;");
        check("$100 & \\path \u2014 \u201Chi\u201D\u2026 &lt;tag&gt; 中".equals(ent), "htmlToText entities: " + ent);
        String badCp = Epub.htmlToText("A&#55296;B&#0;C&#x110000;D");
        check("ABCD".equals(badCp), "htmlToText invalid/surrogate codepoints: " + badCp);
        String cmt = Epub.stripHtml("<!-- a > b --><p>正文内容</p><!-- end -->");
        check("正文内容".equals(cmt), "stripHtml comment with >: " + cmt);

        byte[] utf8Bom = "\uFEFF第一章 风起\n正文".getBytes("UTF-8");
        String d8 = Epub.decodeText(utf8Bom, utf8Bom.length);
        check(d8.startsWith("第一章") && Epub.patLevel(Epub.lineAt(d8, 0)) == Epub.PAT_CHAPTER, "UTF-8 BOM stripped for patLevel");

        byte[] u16le = new byte[] { (byte) 0xFF, (byte) 0xFE };
        byte[] u16body = "第一章 归来".getBytes("UTF-16LE");
        byte[] u16all = new byte[2 + u16body.length];
        System.arraycopy(u16le, 0, u16all, 0, 2); System.arraycopy(u16body, 0, u16all, 2, u16body.length);
        check("第一章 归来".equals(Epub.decodeText(u16all, u16all.length)), "UTF-16LE BOM decode");

        byte[] u16be = new byte[] { (byte) 0xFE, (byte) 0xFF };
        byte[] u16bBody = "第二章 远行".getBytes("UTF-16BE");
        byte[] u16bAll = new byte[2 + u16bBody.length];
        System.arraycopy(u16be, 0, u16bAll, 0, 2); System.arraycopy(u16bBody, 0, u16bAll, 2, u16bBody.length);
        check("第二章 远行".equals(Epub.decodeText(u16bAll, u16bAll.length)), "UTF-16BE BOM decode");

        byte[] shortGbk = "春眠不觉晓".getBytes("GB18030");
        check("春眠不觉晓".equals(Epub.decodeText(shortGbk, shortGbk.length)), "short GB18030 decode");

        // 命名空间前缀 OPF (<opf:item>, <opf:spine>, <opf:itemref>) 解析回归
        File nsEpub = new File(dir, "ns-opf-test.epub");
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(nsEpub));
        try {
            zos.putNextEntry(new java.util.zip.ZipEntry("META-INF/container.xml"));
            zos.write("<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>".getBytes("UTF-8"));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("OEBPS/content.opf"));
            zos.write("<opf:package xmlns:opf=\"http://www.idpf.org/2007/opf\"><opf:manifest><opf:item id=\"c1\" href=\"ch1.xhtml\" media-type=\"application/xhtml+xml\"/></opf:manifest><opf:spine><opf:itemref idref=\"c1\"/></opf:spine></opf:package>".getBytes("UTF-8"));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("OEBPS/ch1.xhtml"));
            zos.write("<html><body><h1>第一章 命名空间</h1><p>这是一段足够长的正文内容，用于验证带前缀 OPF 能够正常打开。</p></body></html>".getBytes("UTF-8"));
            zos.closeEntry();
        } finally { zos.close(); }
        Epub.Book nsBook = Epub.load(nsEpub);
        check(nsBook.chapters.size() == 1 && nsBook.chapters.get(0).text.contains("命名空间"), "namespaced OPF epub load");
        nsEpub.delete();

        if (a.length > 1) {   // 额外的书（比如 VD_BIG 生成的大书）：只打印
            for (int i = 1; i < a.length; i++) {
                long t0 = System.currentTimeMillis();
                Epub.Book b = Epub.load(new File(a[i]));
                System.out.println(a[i] + ": " + b.chapters.size() + " files, toc " + b.toc.size() + " (" + b.tocSource + ") " + (System.currentTimeMillis() - t0) + " ms");
            }
        }
        System.out.println(fails == 0 ? "EpubTocTest: ok" : "EpubTocTest: " + fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }
}
