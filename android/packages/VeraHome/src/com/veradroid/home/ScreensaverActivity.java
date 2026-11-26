package com.veradroid.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 设置 → 设备与更新 → 屏保：Kindle 自带（默认）/ 轮换全部图片 / 只用一张；还能从内部存储导入图片。
 *
 * 图片来自两个地方：Android 的 /sdcard/Screensaver/（导入、传书网页都放这里）和 Kindle 存储的 veradroid/screensaver/
 * （USB 拷进去的；Android 看不到那个文件夹，宿主的 `saver list` 临时拷到 /dev/kfb/saver/ 给这里显示缩略图）。
 * 选择写到 /sdcard/Screensaver/.vd-saver，宿主每次进屏保时读（host/&#42;/boot/lib/saver.sh）。
 * 缩略图一页 3×3，一页的图全部解码好才换上去（一次刷新），翻页用按钮或横划，没有滚动和动画。
 */
public class ScreensaverActivity extends Activity {
    static final String SD_DIR = "/data/media/0/Screensaver";
    static final String SEL = SD_DIR + "/.vd-saver";
    private static final String VD_DIR = HostLink.KFB + "saver";
    private static final int COLS = 3, ROWS = 3, PER = COLS * ROWS;

    private final Handler handler = new Handler();
    private final List<Item> items = new ArrayList<Item>();
    private LinearLayout page, grid;
    private TextView modeText, foundText, pageLabel;
    /** 三个放图的地方，和宿主写进两个文件夹的“把屏保图片放这里.txt”说的一样。 */
    static final String WHERE =
            "图片（jpg / png / webp / bmp / gif，大小写都行，竖图最好）放在这三个地方都行：\n"
          + "· USB 连电脑：Kindle 盘 → veradroid → screensaver 文件夹\n"
          + "· Android 里：内部存储 → Screensaver 文件夹（下面的“从内部存储导入…”也放到这里）\n"
          + "· 传书网页：“文件” → Screensaver 文件夹 → 上传\n"
          + "放好后回到这一页就能看到（打开这一页时会把图转成适合这块屏的灰度图）。手机的 HEIC 照片请先转成 JPG。";
    private Blocks.Pager pager;
    private int pageNo;
    private String mode, file;   // 当前选择
    private volatile int gen;    // 换页 / 重读时作废还在解码的上一批

    static final class Item {
        final String key;   // "kindle" | "sd/名字" | "vd/名字"
        final File f;       // 缩略图来源（Kindle 自带的预览可能没有）
        final String label;
        final boolean cached;   // 已命中 .vd-cache 的 USB 图，无需再 check/convert
        volatile String why;   // 用不了的原因（null = 能用 / 还没查）
        Item(String key, File f, String label) { this(key, f, label, false); }
        Item(String key, File f, String label, boolean cached) { this.key = key; this.f = f; this.label = label; this.cached = cached; }
    }
    private volatile String urgent;   // 刚选中的那张：转换线程先转它
    private int nSd, nVd;
    private String elsewhere = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);
        TextView title = Ui.text(this, "屏保", 30, true);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);
        modeText = Ui.text(this, "正在读取…", 17, false);
        modeText.setPadding(0, 0, 0, dp(4));
        page.addView(modeText);
        // 图片放在哪里：每次都写在页面上（用户问过“屏保图片放哪”），找到几张也直接说
        foundText = Ui.text(this, "正在找你的图片…", 15, true);
        foundText.setPadding(0, dp(2), 0, dp(2));
        page.addView(foundText);
        TextView where = Ui.text(this, WHERE, 14, false);
        where.setPadding(0, 0, 0, dp(8));
        page.addView(where);
        LinearLayout modes = new LinearLayout(this);
        modes.addView(Ui.button(this, "用 Kindle 自带的", 17, false, new View.OnClickListener() {
            @Override public void onClick(View v) { choose("kindle", null); }
        }), weight());
        modes.addView(Ui.button(this, "轮换全部图片", 17, false, new View.OnClickListener() {
            @Override public void onClick(View v) { choose("rotate", null); }
        }), weight());
        page.addView(modes);
        TextView hint = Ui.text(this, "点一张图：每次休眠都只用这一张。", 15, false);
        hint.setPadding(0, dp(10), 0, dp(4));
        page.addView(hint);
        grid = new PagedBox(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        ((PagedBox) grid).setListener(new PagedBox.Listener() { @Override public void onPage(int dir) { turn(dir); } });
        page.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(Ui.button(this, "上一页", 17, false, new View.OnClickListener() { @Override public void onClick(View v) { turn(-1); } }), weight());
        pageLabel = Ui.text(this, "", 17, false);
        pageLabel.setGravity(Gravity.CENTER);
        nav.addView(pageLabel, weight());
        nav.addView(Ui.button(this, "下一页", 17, false, new View.OnClickListener() { @Override public void onClick(View v) { turn(1); } }), weight());
        page.addView(nav);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(6), 0, 0);
        actions.addView(Ui.button(this, "返回", 17, false, new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }), weight());
        actions.addView(Ui.button(this, "从内部存储导入…", 17, true, new View.OnClickListener() { @Override public void onClick(View v) { importImage(); } }), weight());
        page.addView(actions);
        if (Blocks.on(this)) {   // 方块风格：方块页头；“上一页 / 下一页”换成翻页条
            Blocks.restyle(this, page, title, actions, "Sleep / screensaver");
            int at = page.indexOfChild(nav);
            page.removeView(nav);
            pager = new Blocks.Pager(this, new Blocks.Pager.Listener() { @Override public void onPage(int dir) { turn(dir); } });
            page.addView(pager, at, Blocks.gap(this, 8));
        }
        readChoice();
        // 宿主把 veradroid/screensaver 的图拷出来（最多等 4 秒；宿主太旧就只显示 Android 里的图）
        final File lf = new File(HostLink.CTL + "saver.listed");
        final boolean hadOld = lf.isFile();
        final long oldMod = hadOld ? lf.lastModified() : 0L;
        final boolean deletedOld = !hadOld || lf.delete();
        HostLink.cmd("saver list");
        handler.postDelayed(new Runnable() {
            int n = 0;
            boolean shown;
            boolean sawGone = deletedOld;
            @Override public void run() {
                if (isFinishing()) return;
                if (!lf.isFile()) {
                    sawGone = true;
                } else if (sawGone || lf.lastModified() != oldMod) {
                    load(); return;
                }
                // 4 秒还没拷完（图多、图大）：先显示 Android 里的图，拷完再整页重读一次（最多再等 40 秒）
                if (++n >= 20 && !shown) { shown = true; load(); }
                if (n < 220) handler.postDelayed(this, 200);
            }
        }, 200);
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
        handler.removeCallbacksAndMessages(null);
        HostLink.cmd("saver done");
        super.onDestroy();
    }

    private int dp(int v) { return Ui.dp(this, v); }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }

    /** 屏保图的扩展名：png / jpg / jpeg / webp / bmp / gif / heic……（SaverFormat.EXTS，不分大小写；heic 之类会标“用不了”并说原因）。 */
    private static boolean isImage(String n) { return SaverFormat.isCandidate(n); }

    private static List<File> images(String dir) {
        List<File> out = new ArrayList<File>();
        File[] l = new File(dir).listFiles();
        if (l == null) return out;
        Arrays.sort(l, new Comparator<File>() { @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); } });
        for (File f : l) if (f.isFile() && isImage(f.getName()) && !f.getName().equalsIgnoreCase("default.png")) out.add(f);
        return out;
    }

    private void load() {
        ++gen;
        items.clear();
        File kp = new File(VD_DIR, ".kindle-preview.png");
        items.add(new Item("kindle", kp.isFile() ? kp : null, "Kindle 自带"));
        nSd = 0; nVd = 0;
        for (File f : images(SD_DIR)) { items.add(new Item("sd/" + f.getName(), f, f.getName())); nSd++; }
        java.util.Set<String> seenVd = new java.util.HashSet<String>();
        for (File f : images(VD_DIR)) {
            seenVd.add(f.getName());
            items.add(new Item("vd/" + f.getName(), f, f.getName() + "（USB）")); nVd++;
        }
        // 已命中转换缓存的 USB 图：宿主不向 tmpfs 重复拷贝原图，直接复用 .vd-cache 缩略图
        for (String[] l : tsv(new File(VD_DIR, ".cached"))) {
            if (l.length < 1 || !isImage(l[0]) || seenVd.contains(l[0])) continue;
            File cf = SaverImage.cacheFile("vd/" + l[0]);
            if (cf == null || !cf.isFile()) continue;
            seenVd.add(l[0]);
            items.add(new Item("vd/" + l[0], cf, l[0] + "（USB）", true)); nVd++;
        }
        // 宿主没拷过来的：按原因区分（超张数 / 超总容量 / 单张过大）
        for (String[] l : tsv(new File(VD_DIR, ".skipped"))) {
            if (l.length < 2 || !isImage(l[0])) continue;
            Item it = new Item("vd/" + l[0], null, l[0] + "（USB）");
            String reason = l.length >= 3 ? l[2] : "size";
            if ("count".equals(reason)) {
                it.why = "超出 60 张上限：当前最多显示 60 张 USB 屏保图";
            } else if ("total".equals(reason)) {
                it.why = "超出预览总容量上限：请分批放入或先打开本页生成缓存后再加新图";
            } else {
                long mb = 0; try { mb = Long.parseLong(l[1]) >> 20; } catch (NumberFormatException e) { }
                it.why = "太大（" + mb + " MB）：请缩小到 32 MB 以内再放进 veradroid/screensaver";
            }
            items.add(it); nVd++;
        }
        elsewhere = elsewhere();
        if (mode == null) mode = items.size() > 1 ? "rotate" : "kindle";   // 没选过：宿主照旧做法，有用户图就轮换
        foundText.setText(found(nSd, nVd));
        pageNo = 0;
        showMode();
        prepare();
    }

    private static List<String[]> tsv(File f) {
        List<String[]> out = new ArrayList<String[]>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"));
            try { String l; while ((l = r.readLine()) != null && out.size() < 200) if (l.length() > 0) out.add(l.split("\t")); } finally { r.close(); }
        } catch (Exception e) { }
        return out;
    }

    /** 放错地方的图：Kindle 盘上的 screensaver、linkss/screensavers（宿主数好写在 .elsewhere），Android 里小写的 screensaver 之类。 */
    private String elsewhere() {
        StringBuilder b = new StringBuilder();
        for (String[] l : tsv(new File(VD_DIR, ".elsewhere"))) if (l.length >= 2) b.append(b.length() > 0 ? "、" : "").append("Kindle 盘的 ").append(l[0]).append("（").append(l[1]).append(" 张）");
        File[] top = new File("/data/media/0").listFiles();
        if (top != null) for (File d : top) {
            String n = d.getName();
            if (!d.isDirectory() || n.equals("Screensaver") || !n.toLowerCase(java.util.Locale.ROOT).startsWith("screensaver")) continue;
            int c = images(d.getPath()).size();
            if (c > 0) b.append(b.length() > 0 ? "、" : "").append("内部存储的 ").append(n).append("（").append(c).append(" 张）");
        }
        return b.length() == 0 ? "" : "\n另外在 " + b + " 里有图片，但屏保不读那里：请移到上面说的文件夹（注意大小写：Screensaver / screensaver）。";
    }

    /**
     * 后台：先快速查一遍每张能不能用（文件头、尺寸），再显示第一页；然后逐张转成灰度 PNG（SaverImage.convert，没变的跳过）。
     * 转换失败的标上原因；在当前页上的话重画这一页。
     */
    private volatile int prepGen;   // 重读时作废上一轮的检查 / 转换线程

    private void prepare() {
        final int myPrep = ++prepGen;
        final List<Item> its = new ArrayList<Item>(items);
        final boolean vdListed = new File(HostLink.CTL + "saver.listed").exists();
        new Thread(new Runnable() {
            @Override public void run() {
                for (Item it : its) if (it.f != null && !it.cached && it.why == null && !it.key.equals("kindle")) it.why = SaverImage.check(it.f);
                handler.post(new Runnable() { @Override public void run() { if (!isFinishing() && myPrep == prepGen) { showFound(); showMode(); renderPage(); } } });
                if (myPrep != prepGen) return;
                java.util.Set<String> keys = new java.util.HashSet<String>();
                for (Item it : its) keys.add(it.key);
                SaverImage.prune(keys, vdListed);
                int[] p = SaverImage.panel(ScreensaverActivity.this);
                for (int i = 0; i < its.size() && !isFinishing() && myPrep == prepGen; i++) {
                    String u = urgent;
                    if (u != null) { urgent = null; for (Item it : its) if (it.key.equals(u)) convertOne(it, p); }
                    convertOne(its.get(i), p);
                }
                String u = urgent;
                if (u != null) { urgent = null; for (Item it : its) if (it.key.equals(u)) convertOne(it, p); }
            }
        }, "saver-convert").start();
    }

    private void convertOne(final Item it, int[] p) {
        if (it.f == null || it.cached || it.why != null || it.key.equals("kindle")) return;
        final String why = SaverImage.convert(it.key, it.f, p[0], p[1]);
        if (why == null) return;
        it.why = why;
        handler.post(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                showFound(); showMode();
                int idx = items.indexOf(it);
                if (idx >= pageNo * PER && idx < (pageNo + 1) * PER) renderPage();
            }
        });
    }

    private void showFound() {
        int bad = 0;
        for (Item it : items) if (it.why != null) bad++;
        foundText.setText(found(nSd, nVd) + (bad > 0 ? "其中 " + bad + " 张用不了（格子上写着原因）。" : "") + elsewhere);
    }

    /** “找到 N 张你的图片”：分开说两个文件夹各几张，一张都没有时说下一步做什么。 */
    static String found(int nSd, int nVd) {
        if (nSd + nVd == 0) return "还没有找到你的图片：按下面任一种方法放进去（现在用 Kindle 自带的屏保）。";
        return "找到 " + (nSd + nVd) + " 张你的图片（Android 的 Screensaver " + nSd + " 张，USB 的 veradroid/screensaver " + nVd + " 张）。";
    }

    private void readChoice() {
        mode = null; file = null;
        java.util.Map<String, String> m = HostLink.kv(SEL);
        String md = m.get("mode");
        if ("kindle".equals(md) || "rotate".equals(md) || "one".equals(md)) { mode = md; file = m.get("file"); }
    }

    private void showMode() {
        String s;
        if ("one".equals(mode) && file != null) s = "当前：只用一张（" + file.substring(file.indexOf('/') + 1) + "）";
        else if ("rotate".equals(mode)) {
            int usable = 0;
            for (Item it : items) if (!"kindle".equals(it.key) && it.why == null) usable++;
            s = "当前：轮换全部图片（共 " + usable + " 张，没有图片时用 Kindle 自带的）";
        }
        else s = "当前：Kindle 自带的屏保";
        modeText.setText(s);
    }

    private void choose(String m, String f) {
        try {
            new File(SD_DIR).mkdirs();
            File tmp = new File(SD_DIR, ".vd-saver.tmp");
            FileWriter w = new FileWriter(tmp);
            w.write("mode=" + m + "\n" + (f != null ? "file=" + f + "\n" : ""));
            w.close();
            if (!tmp.renameTo(new File(SEL))) throw new java.io.IOException("rename");
            new File(SEL).setReadable(true, false);
            mode = m; file = f;
            if (f != null) urgent = f;   // 宿主画不了原图（webp 之类）时要靠转好的那张：先转它
        } catch (Exception e) {
            VeraToast.show(this, "没能保存选择：" + e.getMessage());
            return;
        }
        showMode();
        renderPage();
    }

    private void turn(int dir) {
        int pages = Math.max(1, (items.size() + PER - 1) / PER);
        int np = Math.max(0, Math.min(pages - 1, pageNo + dir));
        if (np == pageNo) return;
        pageNo = np;
        renderPage();
    }

    private boolean selected(Item it) {
        if (it.key.equals("kindle")) return "kindle".equals(mode);
        return "one".equals(mode) && it.key.equals(file);
    }

    /** 这一页的缩略图在后台解码，全部好了才一次换上（墨水屏只刷一次）。 */
    private void renderPage() {
        final int my = ++gen;
        final int from = pageNo * PER, to = Math.min(items.size(), from + PER);
        int pages = Math.max(1, (items.size() + PER - 1) / PER);
        pageLabel.setText((pageNo + 1) + " / " + pages);
        if (pager != null) pager.set(pageNo + 1, pages);
        final int cw = Math.max(dp(80), (getResources().getDisplayMetrics().widthPixels - dp(32)) / COLS - dp(12));
        final int ch = cw * 4 / 3;
        final File[] files = new File[to - from];
        for (int i = from; i < to; i++) files[i - from] = items.get(i).f;
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap[] bm = new Bitmap[files.length];
                for (int i = 0; i < files.length && my == gen; i++) bm[i] = thumb(files[i], cw, ch);
                handler.post(new Runnable() { @Override public void run() { if (my == gen && !isFinishing()) fill(from, to, bm, cw, ch); } });
            }
        }, "saver-thumbs").start();
    }

    private void fill(int from, int to, Bitmap[] bm, int cw, int ch) {
        grid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < PER; i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(cell, lp);
            int idx = from + i;
            if (idx >= to) continue;
            final Item it = items.get(idx);
            boolean sel = selected(it);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setStroke(sel ? dp(4) : 2, Color.BLACK);
            cell.setBackground(bg);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            if (it.why != null) {
                TextView bad = Ui.text(this, "用不了\n" + shortWhy(it.why), 15, true);
                bad.setGravity(Gravity.CENTER);
                cell.addView(bad, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            } else if (bm[i] != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bm[i]);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            } else {
                TextView none = Ui.text(this, it.key.equals("kindle") ? "Kindle\n自带" : "无法预览", 18, true);
                none.setGravity(Gravity.CENTER);
                cell.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }
            TextView name = Ui.text(this, (sel ? "[当前] " : it.why != null ? "[用不了] " : "") + it.label, 13, sel);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            name.setGravity(Gravity.CENTER);
            cell.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setClickable(true);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (it.why != null) {
                        new VeraDialog.Builder(ScreensaverActivity.this).title("这张图用不了")
                            .message(it.label + "\n\n" + it.why + "\n\n能用的格式：JPG、PNG、WEBP、BMP、GIF（扩展名大小写都行）。")
                            .positive("知道了", null).show();
                        return;
                    }
                    if (it.key.equals("kindle")) choose("kindle", null); else choose("one", it.key);
                }
            });
            Ui.fast(cell);
        }
    }

    /** 格子里放得下的短原因（冒号前那半句）。 */
    static String shortWhy(String why) {
        int c = why.indexOf('：');
        String s = c > 0 ? why.substring(0, c) : why;
        return s.length() > 18 ? s.substring(0, 18) + "…" : s;
    }

    /** 灰度、按格子大小缩小；坏图 / 太大的图返回 null。 */
    static Bitmap thumb(File f, int w, int h) {
        if (f == null || !f.isFile() || f.length() > SaverImage.MAX_BYTES) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1;
            while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
            return bm == null ? null : SaverImage.upright(bm, f);   // 照片的 EXIF 方向（和转好的屏保图一样）
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从内部存储（Pictures、Download、Kindle 的 documents……）挑一张，复制进 /sdcard/Screensaver/ 并设成“只用这一张”。 */
    private void importImage() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<File> all = Theme.findImages();
                final List<File> imgs = new ArrayList<File>();
                for (File f : all) if (!SD_DIR.equals(f.getParent()) && isImage(f.getName())) imgs.add(f);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (imgs.isEmpty()) {
                            new VeraDialog.Builder(ScreensaverActivity.this).title("导入屏保图片")
                                .message("没有找到 jpg / png / webp / bmp / gif 图片。用传书网页上传到内部存储，或用 USB 把图片放进 Kindle 的 veradroid/screensaver 文件夹。")
                                .positive("知道了", null).show();
                            return;
                        }
                        String[] names = new String[imgs.size()];
                        for (int i = 0; i < names.length; i++) names[i] = imgs.get(i).getName() + "\n" + Theme.placeLabel(imgs.get(i)) + " · " + Math.max(1, imgs.get(i).length() / 1024) + " KB";
                        new VeraDialog.Builder(ScreensaverActivity.this).title("导入屏保图片").items(names, new VeraDialog.OnPick() {
                            @Override public void onPick(int which) { copyIn(imgs.get(which)); }
                        }).negative("取消", null).show();
                    }
                });
            }
        }, "saver-find").start();
    }

    /**
     * 拷进 Android 的 Screensaver 文件夹再设成屏保。拷贝放在后台线程：图可能是几 MB 的 PNG，源可能在 Kindle 的书库（FUSE）上，
     * 以前在主线程上拷，点完要卡好几秒（触摸 5 秒没回应就是 ANR）。拷完回到主线程选中、刷新列表。
     */
    private void copyIn(final File src) {
        VeraToast.show(this, "正在导入…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null, nm = null;
                String bad = SaverImage.check(src);
                if (bad != null) err = bad;
                else try {
                    File dir = new File(SD_DIR);
                    dir.mkdirs();
                    nm = src.getName().replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]", "_");
                    File dst = new File(dir, nm);
                    File tmp = new File(dir, "." + nm + ".tmp");
                    try {
                        InputStream in = new FileInputStream(src);
                        try {
                            OutputStream out = new FileOutputStream(tmp);
                            try {
                                byte[] buf = new byte[65536];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            } finally { out.close(); }
                        } finally { in.close(); }
                        tmp.setReadable(true, false);
                        int[] pn = SaverImage.panel(ScreensaverActivity.this);
                        String why = SaverImage.convert("sd/" + nm, tmp, pn[0], pn[1]);
                        if (why != null) { err = why; }
                        else {
                            if (!tmp.renameTo(dst)) throw new java.io.IOException("rename failed");
                            dst.setReadable(true, false);
                        }
                    } finally {
                        if (tmp.exists()) tmp.delete();
                    }
                } catch (Exception e) { err = String.valueOf(e.getMessage()); }
                final String e = err, name = nm;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (e != null) { VeraToast.show(ScreensaverActivity.this, "导入失败：" + e); return; }
                        choose("one", "sd/" + name);
                        load();
                        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals("sd/" + name)) { pageNo = i / PER; renderPage(); break; }
                        VeraToast.show(ScreensaverActivity.this, "已导入并设为屏保");
                    }
                });
            }
        }, "saver-import").start();
    }
}
