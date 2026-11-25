package com.veradroid.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 字体商店：大家在网站上传、管理员审核过的字体（/api/fonts）。两栏：商店 / 本机字体。
 *
 * 下载的字体放进 /data/media/0/Fonts —— 就是“系统字体”和阅读器一直在读的那个目录（传书网页导入的字体也在这里，
 * 宿主把它对应到 $VD/fonts，“应用到所有应用”认的也是这个目录），所以下载完在原来的字体选择里同样能看到。
 * 文件名由服务器给（只有字母数字 . _ -，会进宿主的 SYSTEM_FONT 设置）；中文名字记在目录里的 .store.json，
 * 字体列表显示名字时用它（Books.fonts）。
 *
 * 墨水屏：列表整页翻，没有动画；一次只下载一个，进度写在状态行上；预览在详情里——没下载的看服务器画好的预览图，
 * 下载了的直接用这个字体排一段字。
 */
public class FontStoreActivity extends Activity {
    static final String DIR = "/data/media/0/Fonts";
    private static final String SAMPLE = "阅读是一座随身携带的避难所。\n天地玄黄 宇宙洪荒 日月盈昃\nThe quick brown fox 0123456789";

    static final class Item {
        int id; long size;
        String name = "", family = "", style = "", ext = "", file = "", sha = "", license = "", description = "", uploader = "", download = "", preview = "";
        boolean cjk, store;      // store = 来自商店（本机字体那一栏里，自己导入的没有这些信息）
        File local;              // 已经在本机的文件
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<Item>();
    private LinearLayout list, tabs;
    private TextView status;
    private int tab;                 // 0 商店，1 本机字体
    private String query = "";
    private int batch = 1, batches = 1, total = -1;
    private static volatile List<String> mirrors = java.util.Arrays.asList(Dl.DEF_MIRRORS);
    private static volatile String jobFile;     // 正在下载的字体文件名，null = 没有
    private static volatile String jobMsg;
    private static FontStoreActivity live;

    static void open(Activity a) {
        a.startActivity(new Intent(a, FontStoreActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        a.overridePendingTransition(0, 0);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);

        TextView title = Ui.text(this, "字体商店", 30, true);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);

        status = Ui.text(this, "正在同步…", 15, false);
        status.setPadding(0, dp(2), 0, dp(6));
        page.addView(status);

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(2), 0, dp(6));
        page.addView(tabs);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(Blocks.paged(this, list), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(6), 0, dp(2));
        actions.addView(Ui.button(this, "返回", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(Ui.button(this, "同步", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) { batch = 1; load(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(Ui.button(this, "搜索", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { searchDialog(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        Blocks.restyle(this, page, title, actions, "Fonts / shared by users");

        tabsRender();
        load();
    }

    private void tabsRender() {
        tabs.removeAllViews();
        String[] l = { "商店" + (total >= 0 ? " " + total : ""), "本机字体 " + localFiles().size() };
        for (int i = 0; i < l.length; i++) {
            final int idx = i;
            android.widget.Button b = Ui.button(this, l[i], 15, i == tab, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == tab) return;
                    if (jobFile == null) jobMsg = null;      // 上一次下载的结果看过了
                    tab = idx; batch = 1; tabsRender(); load();
                }
            });
            b.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = i == 0 ? 0 : dp(8);
            tabs.addView(b, lp);
        }
    }

    private void searchDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(query);
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setTextColor(Color.BLACK);
        input.setTypeface(Fonts.typeface(this));
        input.setBackground(Ui.frame(this, false));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new VeraDialog.Builder(this).title("搜索字体").message("字体名的一部分；留空 = 全部。").view(input)
            .negative("取消", null)
            .positive("搜索", new Runnable() { @Override public void run() {
                String t = input.getText().toString().trim();
                query = t.length() > 40 ? t.substring(0, 40) : t;
                tab = 0; batch = 1; tabsRender(); load();
            } }).show();
    }

    // ------------------------------------------------------------------ 本机的字体

    static boolean isFont(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return !n.startsWith(".") && (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"));
    }

    private static List<File> localFiles() {
        List<File> out = new ArrayList<File>();
        File[] fs = new File(DIR).listFiles();
        if (fs != null) for (File f : fs) if (f.isFile() && isFont(f.getName()) && f.length() >= 1024) out.add(f);
        java.util.Collections.sort(out);
        return out;
    }

    /** .store.json：文件名 -> {name, id, sha, uploader, license}。读不出来就是空的。 */
    static synchronized JSONObject names() {
        try { return new JSONObject(new String(Dl.read(new File(DIR, ".store.json")), "UTF-8")); } catch (Throwable t) { return new JSONObject(); }
    }

    private static synchronized void remember(Item it) {
        try {
            JSONObject all = names();
            if (it == null) return;
            JSONObject o = new JSONObject();
            o.put("name", it.name); o.put("id", it.id); o.put("sha", it.sha); o.put("uploader", it.uploader);
            o.put("license", it.license); o.put("style", it.style); o.put("cjk", it.cjk);
            all.put(it.file, o);
            Dl.write(new File(DIR, ".store.json"), all.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) { }
    }

    private static synchronized void forget(String file) {
        try {
            JSONObject all = names();
            if (all.remove(file) == null) return;
            if (all.length() == 0) new File(DIR, ".store.json").delete();
            else Dl.write(new File(DIR, ".store.json"), all.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) { }
    }

    /** 字体列表里显示的名字：商店下载的用商店里的名字，别的返回 null（调用的地方用文件名）。 */
    static String displayName(String file) {
        JSONObject o = names().optJSONObject(file);
        String n = o == null ? "" : o.optString("name");
        return n.length() > 0 ? n : null;
    }

    private static File localOf(Item it) {
        if (it.file == null || !it.file.matches("[A-Za-z0-9._-]{1,80}") || !isFont(it.file)) return null;
        File f = new File(DIR, it.file);
        return f.isFile() && (it.size <= 0 || f.length() == it.size) ? f : null;
    }

    // ------------------------------------------------------------------ 列表

    private void load() {
        if (tab == 1) { loadLocal(); return; }
        final int myBatch = batch; final String q = query;
        status.setText("正在同步…");
        list.removeAllViews();
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            final List<Item> got = new ArrayList<Item>();
            final int[] n = { 0, 1 };
            try {
                JSONObject j = StoreNet.json(StoreNet.base() + "/api/fonts?page=" + myBatch + "&per=100&q=" + StoreNet.enc(q));
                JSONArray ms = j.optJSONArray("mirrors");
                if (ms != null) {
                    List<String> m = new ArrayList<String>();
                    for (int i = 0; i < ms.length() && m.size() < 6; i++) m.add(ms.optString(i));
                    mirrors = m;
                } else mirrors = java.util.Arrays.asList(Dl.DEF_MIRRORS);
                n[0] = j.optInt("total", 0); n[1] = Math.max(1, j.optInt("pages", 1));
                JSONArray arr = j.optJSONArray("fonts");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Item it = new Item();
                    it.store = true;
                    it.id = o.optInt("id"); it.size = o.optLong("size"); it.name = o.optString("name"); it.family = o.optString("family");
                    it.style = o.optString("style"); it.ext = o.optString("ext"); it.file = o.optString("file"); it.sha = o.optString("sha256");
                    it.license = o.optString("license"); it.description = o.optString("description"); it.uploader = o.optString("uploader");
                    it.download = o.optString("download"); it.preview = o.optString("preview"); it.cjk = o.optBoolean("cjk");
                    it.local = localOf(it);
                    got.add(it);
                }
            } catch (Exception e) { err = e.getMessage() == null ? e.toString() : StoreActivity.reason(e); }
            final String fe = err;
            ui.post(new Runnable() { @Override public void run() {
                if (isFinishing() || tab != 0 || myBatch != batch || !q.equals(query)) return;
                if (fe != null) { showError(fe); return; }
                items.clear(); items.addAll(got);
                batches = n[1];
                if (q.length() == 0 && total != n[0]) { total = n[0]; tabsRender(); }
                render();
            } });
        } }, "FontSync").start();
    }

    private void loadLocal() {
        items.clear();
        JSONObject names = names();
        for (File f : localFiles()) {
            Item it = new Item();
            it.local = f; it.file = f.getName(); it.size = f.length();
            JSONObject o = names.optJSONObject(f.getName());
            if (o != null) {
                it.store = true; it.id = o.optInt("id"); it.name = o.optString("name"); it.sha = o.optString("sha");
                it.uploader = o.optString("uploader"); it.license = o.optString("license"); it.style = o.optString("style"); it.cjk = o.optBoolean("cjk");
            }
            if (it.name.length() == 0) { int dot = it.file.lastIndexOf('.'); it.name = dot > 0 ? it.file.substring(0, dot) : it.file; }
            items.add(it);
        }
        batches = 1;
        render();
    }

    private String role(Item it) {
        if (it.local == null) return "";
        SharedPreferences home = Store.prefs(this, "home");
        String p = it.local.getAbsolutePath(), r = "";
        if (p.equals(home.getString("system_font_path", null))) r = "系统字体";
        if (p.equals(Store.prefs(this, "reader").getString("font", home.getString("reader_font_path", null)))) r += (r.length() > 0 ? " / " : "") + "阅读字体";
        return r;
    }

    private void render() {
        String say;
        if (tab == 1) say = items.isEmpty() ? "本机还没有自己的字体。到“商店”下载，或在传书网页上传 .ttf / .otf。" : "本机字体 " + items.size() + " 个 · 点一个可以预览、使用、删除";
        else if (items.isEmpty()) say = query.length() > 0 ? "没有找到“" + query + "”。" : "字体商店里还没有字体。在 veradroid.wenjin.jp 登录后可以上传。";
        else say = (query.length() > 0 ? "“" + query + "” " : "") + items.size() + " 个字体" + (batches > 1 ? "（第 " + batch + " / " + batches + " 批）" : "") + " · 用户上传，都经过人工审核";
        status.setText(jobMsg != null ? jobMsg : say);
        list.removeAllViews();
        int btnW = dp(104);
        for (final Item it : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(4), dp(12), dp(4), dp(12));
            card.setBackgroundColor(Color.WHITE);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(4), 0, dp(10), 0);
            TextView n = Ui.text(this, it.name, 19, true);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(n);
            String role = role(it);
            StringBuilder m = new StringBuilder();
            if (it.style.length() > 0) m.append(it.style).append(" · ");
            m.append(StoreNet.mb(it.size));
            if (it.store) m.append(it.cjk ? " · 含中文" : " · 不含中文");
            if (tab == 0 && it.local != null) m.append(" · 已下载");
            if (role.length() > 0) m.append(" · ").append(role);
            TextView meta = Ui.text(this, m.toString(), 13, false);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(meta);
            TextView by = Ui.text(this, it.store ? "用户上传 · " + it.uploader + " · 已审核" : "自己导入的字体 · " + it.file, 12, false);
            by.setSingleLine(true);
            by.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(by);
            col.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { detail(it); } });
            Ui.fast(col);
            card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            String label = it.local != null ? "使用" : (it.file.equals(jobFile) ? "下载中" : "下载");
            View.OnClickListener act = new View.OnClickListener() { @Override public void onClick(View v) { if (it.local != null) detail(it); else download(it); } };
            View btn = Blocks.on(this) ? Blocks.button(this, label, it.local == null, act) : Ui.button(this, label, 15, it.local == null, act);
            if (btn instanceof TextView) { ((TextView) btn).setSingleLine(true); ((TextView) btn).setGravity(Gravity.CENTER); }
            card.addView(btn, new LinearLayout.LayoutParams(btnW, ViewGroup.LayoutParams.WRAP_CONTENT));
            list.addView(card);
            list.addView(Blocks.sep(this));
        }
        if (batches > 1) {
            LinearLayout more = new LinearLayout(this);
            more.setPadding(0, dp(10), 0, dp(10));
            if (batch > 1) more.addView(Ui.button(this, "上一批", 15, false, new View.OnClickListener() {
                @Override public void onClick(View v) { batch--; load(); } }));
            if (batch < batches) more.addView(Ui.button(this, "下一批", 15, false, new View.OnClickListener() {
                @Override public void onClick(View v) { batch++; load(); } }));
            list.addView(more);
        }
    }

    private void showError(String e) {
        status.setText("同步失败");
        list.removeAllViews();
        TextView t = Ui.text(this, e, 16, false);
        t.setPadding(0, dp(12), 0, dp(12));
        list.addView(t);
        list.addView(Ui.button(this, "重新同步", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ------------------------------------------------------------------ 详情：预览 + 使用 / 删除

    private void detail(final Item it) {
        if (it.local == null && it.preview.length() > 0) {
            // 没下载的：先把服务器画好的预览图取来（几十 KB），再开对话框——对话框只画一次
            status.setText("正在取预览…");
            new Thread(new Runnable() { @Override public void run() {
                final android.graphics.Bitmap bm = StoreNet.image(it.preview, "font-" + (it.sha.length() >= 16 ? it.sha.substring(0, 16) : String.valueOf(it.id)));
                ui.post(new Runnable() { @Override public void run() { if (!isFinishing()) { render(); detailShow(it, bm); } } });
            } }, "FontPreview").start();
        } else detailShow(it, null);
    }

    private void detailShow(final Item it, android.graphics.Bitmap preview) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (it.local != null) {
            Typeface tf = null;
            try { tf = Typeface.createFromFile(it.local); } catch (Throwable ignored) { }
            TextView s = new TextView(this);
            s.setText(tf == null ? "这个字体文件打不开（可能损坏了）。" : SAMPLE);
            s.setTextSize(22); s.setTextColor(Color.BLACK); s.setLineSpacing(0, 1.25f);
            if (tf != null) s.setTypeface(tf);
            s.setPadding(dp(10), dp(10), dp(10), dp(10));
            s.setBackground(Ui.frame(this, false));
            box.addView(s);
        } else if (preview != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(preview);
            iv.setAdjustViewBounds(true);
            iv.setBackground(Ui.frame(this, false));
            iv.setPadding(dp(4), dp(4), dp(4), dp(4));
            box.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            TextView s = Ui.text(this, "下载后可以预览这个字体。", 15, false);
            s.setPadding(0, dp(6), 0, dp(6));
            box.addView(s);
        }
        StringBuilder sb = new StringBuilder();
        if (it.family.length() > 0 || it.style.length() > 0) sb.append(it.family).append(it.style.length() > 0 ? " · " + it.style : "").append("\n");
        sb.append(StoreNet.mb(it.size));
        if (it.store) sb.append(it.cjk ? " · 含中文" : " · 不含中文（中文还用原来的字体）").append("\n用户上传 · ").append(it.uploader).append(" · 已审核");
        else sb.append("\n自己导入的字体（").append(it.file).append("）");
        if (it.license.length() > 0) sb.append("\n来源 / 许可：").append(it.license);
        if (it.description.length() > 0) sb.append("\n").append(it.description);
        String role = role(it);
        if (role.length() > 0) sb.append("\n现在用作：").append(role);
        if (it.store && it.local == null) sb.append("\n有问题（侵权、打不开）请到 veradroid.wenjin.jp/store/fonts 点它旁边的“举报 / 下架”。");
        TextView info = Ui.text(this, sb.toString(), 14, false);
        info.setPadding(0, dp(10), 0, dp(4));
        box.addView(info);

        VeraDialog.Builder d = new VeraDialog.Builder(this).title(it.name).view(box).negative("关闭", null);
        if (it.local == null) {
            d.positive("下载", new Runnable() { @Override public void run() { download(it); } });
        } else {
            d.items(new String[] { "设为系统字体", "设为阅读字体", "删除这个字体" }, new VeraDialog.OnPick() {
                @Override public void onPick(int which) {
                    if (which == 0) useSystem(it);
                    else if (which == 1) useReader(it);
                    else new VeraDialog.Builder(FontStoreActivity.this).title("删除字体").message("从本机删除“" + it.name + "”？" + (role(it).length() > 0 ? "它现在用作" + role(it) + "，删除后换回系统默认字体。" : ""))
                        .negative("取消", null).positive("删除", new Runnable() { @Override public void run() { delete(it); } }).show();
                }
            });
        }
        d.show();
    }

    private void useSystem(Item it) {
        Fonts.setSystem(this, it.local.getAbsolutePath(), it.name);
        VeraToast.show(this, "系统字体已设为 " + it.name);
        if (jobFile == null) jobMsg = null;
        tabsRender(); render();      // 列表用新字体重画一次（标题等下次打开再换）
    }

    private void useReader(Item it) {
        Fonts.setReader(this, it.local.getAbsolutePath(), it.name);
        VeraToast.show(this, "阅读字体已设为 " + it.name);
        if (jobFile == null) jobMsg = null;
        render();
    }

    private void delete(Item it) {
        String p = it.local.getAbsolutePath();
        SharedPreferences home = Store.prefs(this, "home");
        boolean wasSystem = p.equals(home.getString("system_font_path", null));
        if (p.equals(Store.prefs(this, "reader").getString("font", home.getString("reader_font_path", null)))) Fonts.setReader(this, null, "系统默认");
        if (wasSystem) Fonts.setSystem(this, null, "系统默认");
        boolean ok = it.local.delete();
        forget(it.file);
        VeraToast.show(this, ok ? "已删除 " + it.name : "删除失败");
        if (jobFile == null) jobMsg = null;      // 状态行不再写“已下载 …”
        it.local = null;
        tabsRender();
        if (tab == 1) loadLocal(); else render();
    }

    // ------------------------------------------------------------------ 下载

    private void download(final Item it) {
        if (!BindGate.ensureBound(this, it.name, new Runnable() { @Override public void run() { download(it); } })) return;
        if (jobFile != null) { VeraToast.show(this, "上一个字体还没下完"); return; }
        if (it.file == null || !it.file.matches("[A-Za-z0-9._-]{1,80}") || !isFont(it.file)) { VeraToast.show(this, "这个字体的文件名不对，不能下载"); return; }
        jobFile = it.file;
        say("正在下载 " + it.name + " …");
        render();
        final android.content.Context appCtx = getApplicationContext();
        final String tok = Token.get(this);
        new Thread(new Runnable() { @Override public void run() {
            Thread awake = StoreNet.keepAwake();
            String err = null;
            boolean needRebind = false;
            File part = new File(DIR, "." + it.file + ".part");     // 点开头：字体列表不认，下了一半的不会被当成字体
            try {
                File dir = new File(DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("无法创建字体目录");
                long free = new android.os.StatFs(DIR).getAvailableBytes();
                if (it.size > 0 && free < it.size * 2 + (16L << 20)) throw new Exception("存储空间不够：需要约 " + ((it.size * 2 >> 20) + 16) + " MB，现在剩 " + (free >> 20) + " MB");
                String signed = BindGate.signUrl(appCtx, "font", it.id, it.download, tok);
                String q = signed.contains("sig=") ? "" : ("device_id=" + StoreNet.enc(Update.deviceId(appCtx)) + "&token=" + StoreNet.enc(tok));
                List<String> cands = Dl.candidates(signed, q, mirrors);
                final long t0 = System.currentTimeMillis();
                Dl.Progress p = new Dl.Progress() { @Override public void on(long done, long total, boolean mirror) {
                    if (total > 0) say("正在下载 " + it.name + " … " + (done * 100 / total) + "%（" + StoreNet.mb(done) + " / " + StoreNet.mb(total) + (mirror ? "，镜像" : "") + "）");
                } };
                Exception last = null;
                boolean got = false;
                java.util.Set<String> slow = new java.util.HashSet<String>();   // 太慢的源：下一次跳过它，从断点接着下
                for (int tries = 0; tries < 6 && !got; tries++) {
                    try {
                        Dl.fetchOnce(cands, part, it.size, p, slow);
                        got = it.size <= 0 || part.length() == it.size;
                    } catch (Dl.Slow e) {
                        say("下载太慢，换一个下载源继续…"); tries--; if (slow.size() > 8) break; continue;
                    } catch (Dl.HttpError e) {
                        if (e.code == 401 || e.code == 403) { Token.clear(appCtx); throw new BindGate.BindRequiredException(BindGate.REASON_TEXT); }
                        if (e.code == 404) throw new Exception("这个字体已经从商店下架了");
                        last = e;
                    } catch (java.io.IOException e) { last = e; }
                    if (!got && tries < 5) { say("网络中断，正在从断点继续下载（第 " + (tries + 2) + " 次）…"); Thread.sleep(3000L * (tries + 1)); }
                }
                if (!got) throw new Exception("下载失败：" + (last != null ? StoreActivity.reason(last) : "网络不稳定") + "。再点一次下载会从断点继续");
                say("正在核对下载的文件…");
                if (!StoreNet.sha256(part).equalsIgnoreCase(it.sha)) {
                    Dl.clear(part);
                    Dl.fetchOnce(Dl.candidates(signed, q, null), part, it.size, p);     // 只从主站再下一次
                    if (!StoreNet.sha256(part).equalsIgnoreCase(it.sha)) { Dl.clear(part); throw new Exception("SHA-256 校验失败（下载的文件损坏，请重试）"); }
                }
                try { Typeface.createFromFile(part); } catch (Throwable t) { Dl.clear(part); throw new Exception("这个字体在本机打不开"); }
                File out = new File(DIR, it.file);
                out.delete();
                if (!part.renameTo(out)) throw new Exception("无法保存字体文件");
                out.setReadable(true, false);
                remember(it);
            } catch (BindGate.BindRequiredException be) {
                needRebind = true;
                err = "需要绑定账号后下载";
            } catch (Throwable e) {
                err = e instanceof Exception && e.getMessage() != null && (e.getMessage().startsWith("下载失败") || e.getMessage().contains("存储空间") || e.getMessage().contains("下架") || e.getMessage().contains("校验") || e.getMessage().contains("打不开") || e.getMessage().contains("无法"))
                    ? e.getMessage() : "下载失败：" + StoreActivity.reason(e);
            } finally { awake.interrupt(); jobFile = null; }
            if (needRebind) {
                ui.post(new Runnable() { @Override public void run() {
                    if (!isFinishing()) BindGate.showBindDialog(FontStoreActivity.this, it.name, new Runnable() { @Override public void run() { download(it); } });
                } });
            }
            final String msg = err != null ? err : "已下载 " + it.name + "，点“使用”可以设为系统字体或阅读字体";
            final boolean ok = err == null;
            jobMsg = msg;
            new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
                FontStoreActivity a = live;
                if (a == null || a.isFinishing()) return;
                if (ok) for (Item x : a.items) if (x.file.equals(it.file)) x.local = localOf(x);
                a.tabsRender();
                a.render();
                VeraToast.show(a, msg.length() > 60 ? msg.substring(0, 60) + "…" : msg);
            } });
        } }, "FontDownload").start();
    }

    private static void say(final String s) {
        jobMsg = s;
        new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
            FontStoreActivity a = live;
            if (a != null && !a.isFinishing()) a.status.setText(s);
        } });
    }

    private int dp(int v) { return Ui.dp(this, v); }

    @Override protected void onResume() {
        super.onResume();
        live = this;
        if (jobMsg != null && status != null) status.setText(jobMsg);
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
    }

    @Override protected void onPause() {
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (live == this) live = null;
        if (jobFile == null) jobMsg = null;      // 下次打开不再显示上一次的结果
        super.onDestroy();
    }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }
}
