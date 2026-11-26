// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
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
 * App store. Two sections: 官方推荐 (APKs the admin picked) and 用户上传 (APKs users uploaded on the website, shown only
 * after an admin reviewed them; each says who uploaded it), plus a door to the font store. Open to every device (no
 * account binding or authorization since 2026-11-07); a bound device still sends its token so the server can update
 * last_seen. Installation uses the host's existing pm install path, so no package-installer UI is needed.
 * The list turns whole pages (no scrolling); the server sends up to 100 entries at a time, "下一批" asks for more.
 */
public class StoreActivity extends Activity {
    private static final String[] CATS = { "official", "user" };
    private static final String[] CAT_L = { "官方推荐", "用户上传" };
    private static final String[] TAGS = { "", "阅读", "漫画与资讯", "笔记与词典", "浏览器与传输", "输入法与效率", "棋牌与益智" };
    private static final String[] TAG_L = { "全部", "阅读", "资讯", "笔记词典", "浏览传输", "效率输入", "棋牌益智" };
    private static int cat;                     // 上次看的那一栏（重新打开商店还在那一栏）
    private static int tagIdx = 0;              // 官方推荐下的功能分类筛选（0 = 全部，阅读置顶）
    private final int[] counts = { -1, -1 };
    private final int[] tagCounts = { -1, -1, -1, -1, -1, -1, -1 };
    private String query = "";
    private int batch = 1, batches = 1;
    private int loadGen = 0;
    private LinearLayout tabs, tagBar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<App>();
    private final java.util.Map<Integer, android.widget.FrameLayout> iconBoxes = new java.util.HashMap<Integer, android.widget.FrameLayout>();
    private volatile java.util.Map<String, Integer> installedVersions = java.util.Collections.emptyMap();
    private LinearLayout list;
    private PageScrollView scroll;
    private TextView status;
    // 一次只下载 / 安装一个，进度和结果放在静态字段里（Scribe 用户：商店装微信读书“失败”，同一个 APK 侧载能装）：
    //  - 以前下载线程只往开着的那个界面上写字：用户离开商店（或界面被系统重建）以后，进度和失败原因谁也看不到；
    //  - 再点一次“安装”会再起一条线程，两条线程往同一个文件里写，SHA-256 必然失败；
    //  - 127 MB 要下好几分钟，Kindle 10 分钟没碰就进屏保，宿主在屏保时冻结 Android，下载就断在那里。
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.atomic.AtomicInteger JOB_ID = new java.util.concurrent.atomic.AtomicInteger(-1);
    private static volatile boolean jobInstalling;
    private static volatile String jobMsg;      // 当前进度 / 最近一次的结果（重新打开商店时显示）
    private static StoreActivity live;          // 正在前台的商店界面（进度写到它上面），只在主线程读写
    // 下载镜像（服务器 /api/store/apps 的 "mirrors"；没有这一项时用 Dl.DEF_MIRRORS，[] = 不用镜像）
    private static volatile List<String> mirrors = java.util.Arrays.asList(Dl.DEF_MIRRORS);

    /** Non-resumable download error (404 / size mismatch / SHA-256 mismatch): clear partial file. */
    private static final class CorruptDownloadException extends Exception {
        CorruptDownloadException(String msg) { super(msg); }
    }

    static final class App {
        int id, versionCode;
        long size;
        String name, pkg, versionName, description, sha, download;
        String uploader = "", source = "", iconUrl = "", tag = "";
        boolean user;                               // 用户上传的（已审核）
        android.graphics.drawable.Drawable icon;   // 已经装了的应用的图标（后台取好）；没装的画首字
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

        // Deliberately no toolbar: this is a plain list, like an APK install sheet.
        TextView title = Ui.text(this, "软件商店", 30, true);
        title.setPadding(0, dp(8), 0, dp(4));
        page.addView(title);

        status = Ui.text(this, "正在同步…", 15, false);
        status.setPadding(0, dp(2), 0, dp(6));
        page.addView(status);

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, dp(2), 0, dp(4));
        page.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tagBar = new LinearLayout(this);
        tagBar.setOrientation(LinearLayout.VERTICAL);
        tagBar.setPadding(0, dp(2), 0, dp(6));
        page.addView(tagBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 整页翻（PageScrollView + 翻页条），不跟手滚动：墨水屏上一次只换一次画面
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout paged = Blocks.paged(this, list);
        scroll = (PageScrollView) paged.getChildAt(0);
        page.addView(paged, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

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
        actions.addView(Ui.button(this, "应用更新", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { Update.now(StoreActivity.this); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        Blocks.restyle(this, page, title, actions, "Store / e-ink apps");

        tabsRender();
        tagBarRender();
        load();
    }

    /** 官方推荐下的 6 大功能分类（2 行紧凑排布，墨水屏无需横向滚动）。 */
    private void tagBarRender() {
        tagBar.removeAllViews();
        if (cat != 0) { tagBar.setVisibility(View.GONE); return; }
        tagBar.setVisibility(View.VISIBLE);
        int[][] rows = { { 0, 1, 2, 3 }, { 4, 5, 6 } };
        int h = dp(38);
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (r > 0) row.setPadding(0, dp(4), 0, 0);
            for (int c = 0; c < rows[r].length; c++) {
                final int idx = rows[r][c];
                String lbl = TAG_L[idx] + (idx == 0 ? (counts[0] > 0 ? " " + counts[0] : "") : (tagCounts[idx] > 0 ? " " + tagCounts[idx] : ""));
                android.widget.Button b = Ui.button(this, lbl, 13, idx == tagIdx, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (tagIdx == idx) return;
                        tagIdx = idx;
                        batch = 1;
                        tagBarRender();
                        load();
                    }
                });
                b.setSingleLine(true);
                b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                b.setMinHeight(h);
                b.setMinimumHeight(h);
                b.setPadding(dp(4), dp(4), dp(4), dp(4));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.leftMargin = c == 0 ? 0 : dp(6);
                row.addView(b, lp);
            }
            tagBar.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static final java.util.regex.Pattern RE_GAME = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:chess|sudoku|2048|minesweeper|solitaire|puzzles?|crossword|battleship|checkers|dame|ludo|dicer|boardgame)(?![a-z0-9])"
            + "|goban|sudoku|minesweeper|solitaire|crossword|battleship|boardgame|sgtpuzzles|privacyfriendly(?:2048|dame|ludo|dicer|memory)"
            + "|围棋|象棋|数独|扫雷|益智|纸牌|填字|跳棋|飞行棋|海战棋|骰子|棋钟|翻牌记忆",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_COMIC_NEWS_ID = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:comics?|manga|rss|feeder?|news|wiki(?:pedia)?|podcasts?|xkcd|reddit|mastodon)(?![a-z0-9])"
            + "|antennapod|escapepod|redreader|tusky|wallabag|andstatus|aggregator|readrops"
            + "|漫画|资讯|播客|稍后阅读|名言",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_NOTE_DICT_ID = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:dict(?:ionary)?|translat(?:e|or)|notes?|notepad|diary|editor|todo|tasks?|memo|anki)(?![a-z0-9])"
            + "|jmdict|quickdic|todolist|opentasks|anymemo|markor|quillpad|orgzly|markdownr|textpad|privacyfriendlynotes"
            + "|词典|查词|翻译|笔记|日记|待办|任务清单|记忆卡片|大纲|文本编辑",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_NET_FILE_ID = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:browser|files?|ssh|vpn|terminal|wifi)(?![a-z0-9])"
            + "|einkbro|filemanager|connectbot|openvpn|kdeconnect|aria2|wifianalyzer|urlcheck|termoneplus"
            + "|浏览器|文件管理|传书|终端|链接净化|下载管理",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_READ_ID = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:read(?:er)?|ereader|ebooks?|books?|epub|pdf|djvu|legado|fiction|mupdf|reeden)(?![a-z0-9])"
            + "|koreader|weread|einkreader|ireader|moonreader|markreader|cpfiction|cpwxw|duku"
            + "|小说|阅读|读书|看书",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_COMIC_NEWS_DESC = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:comics?|manga|podcasts?|wikipedia)(?![a-z0-9])|漫画|播客|资讯",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_READ_DESC = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:epub|pdf|djvu|mobi|azw3|ebooks?|ereader)(?![a-z0-9])|小说|阅读器|电子书",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_NOTE_DICT_DESC = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:dict(?:ionary)?|translat(?:e|or)|diary|todolist)(?![a-z0-9])|词典|翻译|日记本|待办|记笔记|笔记",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern RE_NET_FILE_DESC = java.util.regex.Pattern.compile(
            "(?<![a-z0-9])(?:browser|filemanager|ssh|vpn)(?![a-z0-9])|浏览器|文件管理|传书",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 兼容旧后端或未打标条目：按包名/名称主身份优先、简介兜底推断分组（词边界匹配，避免子串误伤）。 */
    private static String inferTag(String pkg, String name, String desc) {
        String ident = (pkg == null ? "" : pkg) + " " + (name == null ? "" : name);
        if (RE_GAME.matcher(ident).find()) return TAGS[6];
        if (RE_COMIC_NEWS_ID.matcher(ident).find()) return TAGS[2];
        if (RE_NOTE_DICT_ID.matcher(ident).find()) return TAGS[3];
        if (RE_NET_FILE_ID.matcher(ident).find()) return TAGS[4];
        if (RE_READ_ID.matcher(ident).find()) return TAGS[1];
        String d = desc == null ? "" : desc;
        if (RE_GAME.matcher(d).find()) return TAGS[6];
        if (RE_COMIC_NEWS_DESC.matcher(d).find()) return TAGS[2];
        if (RE_READ_DESC.matcher(d).find()) return TAGS[1];
        if (RE_NOTE_DICT_DESC.matcher(d).find()) return TAGS[3];
        if (RE_NET_FILE_DESC.matcher(d).find()) return TAGS[4];
        return TAGS[5];
    }

    private static int tagIndexOf(String tag) {
        if (tag == null || tag.length() == 0) return -1;
        for (int k = 1; k < TAGS.length; k++) if (TAGS[k].equals(tag)) return k;
        return -1;
    }

    /** 官方推荐 | 用户上传 | 字体商店：选中的那一栏黑底。 */
    private void tabsRender() {
        tabs.removeAllViews();
        for (int i = 0; i <= CATS.length; i++) {
            final int idx = i;
            String label = i == CATS.length ? "字体商店" : CAT_L[i] + (counts[i] >= 0 ? " " + counts[i] : "");
            android.widget.Button b = Ui.button(this, label, 15, i == cat, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx == CATS.length) { FontStoreActivity.open(StoreActivity.this); return; }
                    if (idx == cat) return;
                    cat = idx; batch = 1; tabsRender(); tagBarRender(); load();
                }
            });
            b.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = i == 0 ? 0 : dp(8);
            tabs.addView(b, lp);
        }
    }

    private void searchDialog() {
        final android.widget.EditText input = Ui.editText(this);
        input.setText(query);
        input.setSingleLine(true);
        input.setTextSize(20);
        input.setTextColor(Color.BLACK);
        input.setTypeface(Fonts.typeface(this));
        input.setBackground(Ui.frame(this, false));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        new VeraDialog.Builder(this).title("搜索应用").message("应用名或包名的一部分；留空 = 全部。").view(input)
            .negative("取消", null)
            .positive("搜索", new Runnable() { @Override public void run() {
                String t = input.getText().toString().trim();
                query = t.length() > 40 ? t.substring(0, 40) : t;
                batch = 1; load();
            } }).show();
    }

    private String token() {
        return Token.get(this);
    }

    private void load() {
        final String t = token();
        final int myCat = cat, myBatch = batch, myTag = tagIdx;
        final int myGen = ++loadGen;
        final String q = query;
        if (JOB_ID.get() < 0) jobMsg = null;   // 切分类 / 搜索 / 同步时清掉早已结束的历史安装提示，避免遮盖结果计数
        status.setText("正在同步…");
        if (scroll != null) scroll.scrollTo(0, 0);
        list.removeAllViews();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                final List<App> got = new ArrayList<App>();
                final int[] cnt = { -1, -1, 1 };
                final int[] tcnt = { -1, -1, -1, -1, -1, -1, -1 };
                try {
                    String tagParam = (myCat == 0 && myTag > 0 && myTag < TAGS.length) ? "&tag=" + enc(TAGS[myTag]) : "";
                    JSONObject j = get(StoreNet.base() + "/api/store/apps?category=" + CATS[myCat] + "&page=" + myBatch + "&per=50&q=" + enc(q)
                        + tagParam + "&device_id=" + enc(Update.deviceId(StoreActivity.this)) + "&token=" + enc(t));
                    JSONArray cs = j.optJSONArray("categories");
                    if (cs != null) for (int i = 0; i < cs.length(); i++) {
                        JSONObject c = cs.getJSONObject(i);
                        for (int k = 0; k < CATS.length; k++) if (CATS[k].equals(c.optString("id"))) cnt[k] = c.optInt("count", -1);
                    }
                    JSONObject tc = j.optJSONObject("tag_counts");
                    if (tc != null) for (int k = 1; k < TAGS.length; k++) tcnt[k] = tc.optInt(TAGS[k], -1);
                    cnt[2] = Math.max(1, j.optInt("pages", 1));
                    JSONArray ms = j.optJSONArray("mirrors");
                    if (ms != null) {
                        List<String> m = new ArrayList<String>();
                        for (int i = 0; i < ms.length() && m.size() < 6; i++) m.add(ms.optString(i));
                        mirrors = m;
                    } else if (j.has("apps")) mirrors = java.util.Arrays.asList(Dl.DEF_MIRRORS);
                    JSONArray arr = j.optJSONArray("apps");
                    // 老服务器不认 category：给的是整份官方列表，“用户上传”那一栏就是空的
                    if (cs == null && myCat != 0) arr = null;
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        App a = new App();
                        a.id = o.optInt("id"); a.name = o.optString("name");
                        a.pkg = o.optString("package"); a.versionCode = o.optInt("version_code");
                        a.versionName = o.optString("version_name"); a.description = o.optString("description");
                        a.size = o.optLong("size"); a.sha = o.optString("sha256");
                        a.download = o.optString("download");
                        a.user = "user".equals(o.optString("category"));
                        String rawTag = o.optString("tag", "");
                        if (rawTag.length() == 0) rawTag = o.optString("group", "");
                        if (rawTag.length() == 0) rawTag = o.optString("category_tag", "");
                        if (!a.user && rawTag.length() == 0) rawTag = inferTag(a.pkg, a.name, a.description);
                        a.tag = rawTag;
                        a.uploader = o.optString("uploader"); a.source = o.optString("source"); a.iconUrl = o.optString("icon");
                        try { a.icon = getPackageManager().getApplicationIcon(a.pkg); } catch (Throwable ignored) { }
                        got.add(a);
                    }
                    // 兼容旧后端（未返回 tag_counts）：在官方全量无搜索时按本地分组汇总
                    if (tc == null && myCat == 0 && myTag == 0 && q.length() == 0 && cnt[2] <= 1) {
                        for (int k = 1; k < TAGS.length; k++) tcnt[k] = 0;
                        for (App a : got) {
                            int ti = tagIndexOf(a.tag);
                            if (ti > 0) tcnt[ti]++;
                        }
                    }
                } catch (Exception e) { err = e.getMessage(); }
                final java.util.Map<String, Integer> instMap = err == null ? snapshotInstalled(getApplicationContext()) : null;
                final String fe = err;
                ui.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || myGen != loadGen) return;   // 用户已经换了一栏
                    if (fe != null) { showError(fe); return; }
                    if (instMap != null) installedVersions = instMap;
                    apps.clear(); apps.addAll(got);
                    batches = cnt[2];
                    boolean tcChanged = false;
                    if (cnt[0] != counts[0] || cnt[1] != counts[1]) {
                        if (cnt[0] != counts[0]) tcChanged = true;
                        counts[0] = cnt[0]; counts[1] = cnt[1]; tabsRender();
                    }
                    for (int k = 1; k < TAGS.length; k++) if (tcnt[k] >= 0 && tcnt[k] != tagCounts[k]) { tagCounts[k] = tcnt[k]; tcChanged = true; }
                    if (tcChanged) tagBarRender();
                    render();
                    icons(got, myGen);
                } });
            }
        }, "StoreSync").start();
    }

    /** 没装的应用：图标从商店取（缓存在本机），原地替换图标槽，不再重建整页视图；先刷完当前首屏 6 个图标再补其余，首屏图标秒出。 */
    private void icons(final List<App> shown, final int myGen) {
        new Thread(new Runnable() { @Override public void run() {
            final List<App> batch = new ArrayList<App>();
            int checked = 0;
            for (App a : shown) {
                if (isFinishing() || myGen != loadGen) return;
                checked++;
                if (a.icon == null && a.iconUrl.length() > 0) {
                    android.graphics.Bitmap bm = StoreNet.image(a.iconUrl, "icon-" + (a.sha.length() >= 16 ? a.sha.substring(0, 16) : String.valueOf(a.id)), 192);
                    if (bm != null) { a.icon = new android.graphics.drawable.BitmapDrawable(getResources(), bm); batch.add(a); }
                }
                if (checked == 6 && !batch.isEmpty()) {
                    applyIcons(new ArrayList<App>(batch), myGen);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) applyIcons(batch, myGen);
        } }, "StoreIcons").start();
    }

    private void applyIcons(final List<App> loaded, final int myGen) {
        if (isFinishing() || myGen != loadGen) return;
        ui.post(new Runnable() { @Override public void run() {
            if (isFinishing() || myGen != loadGen) return;
            for (App a : loaded) {
                android.widget.FrameLayout ib = iconBoxes.get(a.id);
                if (ib == null || a.icon == null) continue;
                ib.removeAllViews();
                android.widget.ImageView iv = new android.widget.ImageView(StoreActivity.this);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setImageDrawable(a.icon);
                ib.addView(iv, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } });
    }

    /** 应用的完整说明、上传者、校验值；用户上传的还写明怎么举报。 */
    private void detail(final App a) {
        StringBuilder sb = new StringBuilder();
        sb.append(a.pkg).append("\n版本 ").append(a.versionName).append(" · ").append(StoreNet.mb(a.size));
        if (a.user) sb.append("\n用户上传 · ").append(a.uploader).append(" · 已审核");
        else sb.append("\n官方推荐").append(a.tag.length() > 0 ? " · " + a.tag : "");
        if (a.source.length() > 0) sb.append("\n来源：").append(a.source);
        if (a.description.length() > 0) sb.append("\n\n").append(a.description);
        if (a.sha.length() >= 16) sb.append("\n\nSHA-256 ").append(a.sha.substring(0, 16)).append("…");
        if (a.user) sb.append("\n\n用户上传的应用由上传者提供。有问题（侵权、不安全、打不开）请到 veradroid.wenjin.jp/store/apps 点它旁边的“举报 / 下架”。");
        int inst = installed(a.pkg);
        new VeraDialog.Builder(this).title(a.name).message(sb.toString()).negative("关闭", null)
            .positive(inst <= 0 ? "安装" : (inst < a.versionCode ? "更新" : "重新安装"), new Runnable() { @Override public void run() { install(a); } }).show();
    }

    private void render() {
        final int keepY = scroll != null ? scroll.getScrollY() : 0;
        String activeTag = (cat == 0 && tagIdx > 0 && tagIdx < TAGS.length) ? TAGS[tagIdx] : "";
        List<App> shown = new ArrayList<App>();
        for (App a : apps) {
            if (activeTag.length() == 0 || activeTag.equals(a.tag)) shown.add(a);
        }
        String what = query.length() > 0 ? "“" + query + "”" : (activeTag.length() > 0 ? activeTag : CAT_L[cat]);
        status.setText(shown.isEmpty() ? (query.length() > 0 ? "没有找到" + what + "。" : cat == 1 ? "还没有用户上传的应用。在 veradroid.wenjin.jp 登录后可以上传。" : "当前分类下还没有应用。")
            : what + " " + shown.size() + " 个" + (batches > 1 ? "（第 " + batch + " / " + batches + " 批）" : "")
              + (cat == 1 ? " · 都经过人工审核" : " · 阅读类已置顶"));
        if (JOB_ID.get() >= 0 && jobMsg != null) status.setText(jobMsg);   // 仅在下载 / 安装进行中覆盖顶部状态栏
        list.removeAllViews();
        iconBoxes.clear();
        // 每一行同一个样子：左边固定大小的图标框（装了的用应用图标，没装的写首字），中间名字 / 版本 / 说明各自一行或两行、
        // 放不下就省略号，右边按钮固定宽度。以前名字、说明多长就折几行，按钮跟着名字那一行上下跑（多看那一条名字长、说明长，看起来是歪的）。
        int box = dp(56), btnW = dp(104);
        boolean showGroupHeaders = (cat == 0 && activeTag.length() == 0);
        String lastGroup = null;
        for (final App a : shown) {
            if (showGroupHeaders && !a.user && a.tag.length() > 0 && !a.tag.equals(lastGroup)) {
                int ti = tagIndexOf(a.tag);
                int gc = (ti > 0 && query.length() == 0) ? tagCounts[ti] : -1;
                String gTitle = a.tag + (gc > 0 ? " · " + gc : "");
                TextView gh = Blocks.sectionTitle(this, gTitle, 16);
                if (lastGroup == null && gh.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) gh.getLayoutParams()).topMargin = dp(4);
                }
                list.addView(gh);
                lastGroup = a.tag;
            }
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(4), dp(12), dp(4), dp(12));
            card.setBackgroundColor(Color.WHITE);
            android.widget.FrameLayout ib = new android.widget.FrameLayout(this);
            ib.setBackground(Blocks.plain(this));
            int st = Blocks.stroke(this);
            ib.setPadding(st + dp(4), st + dp(4), st + dp(4), st + dp(4));
            if (a.icon != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setImageDrawable(a.icon);
                ib.addView(iv, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                String n0 = a.name.trim();
                TextView l = Ui.text(this, n0.length() == 0 ? "?" : n0.substring(0, Character.charCount(n0.codePointAt(0))).toUpperCase(java.util.Locale.ROOT), 24, true);
                l.setGravity(Gravity.CENTER);
                ib.addView(l, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            card.addView(ib, new LinearLayout.LayoutParams(box, box));
            iconBoxes.put(a.id, ib);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(10), 0);
            TextView n = Ui.text(this, a.name, 19, true);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(n);
            int installed = installed(a.pkg);
            int activeJob = JOB_ID.get();
            boolean isActive = a.id > 0 && a.id == activeJob;
            String label = isActive ? (jobInstalling ? "安装中" : "下载中")
                : (installed <= 0 ? "安装" : (installed < a.versionCode ? "更新" : "重新安装"));
            String tagPrefix = (!a.user && a.tag.length() > 0) ? "[" + a.tag + "] · " : "";
            TextView meta = Ui.text(this, tagPrefix + a.versionName + " · " + StoreNet.mb(a.size) + (installed > 0 ? " · 已装" : "")
                + (a.user ? " · 用户上传 · " + a.uploader + " · 已审核" : " · " + a.pkg), 12, false);
            meta.setTextColor(Blocks.on(this) ? Color.BLACK : Color.DKGRAY);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(meta);
            if (a.description.length() > 0) {
                TextView d = Ui.text(this, a.description, 14, false);
                d.setPadding(0, dp(3), 0, 0);
                d.setMaxLines(2);
                d.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(d);
            }
            card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            col.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { detail(a); } });
            Ui.fast(col);
            View btn = Blocks.on(this) ? Blocks.button(this, label, true, new View.OnClickListener() {
                @Override public void onClick(View v) { install(a); }
            }) : Ui.button(this, label, 15, true, new View.OnClickListener() {
                @Override public void onClick(View v) { install(a); }
            });
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
        if (scroll != null && keepY > 0) {
            scroll.post(new Runnable() { @Override public void run() { if (scroll != null) scroll.scrollTo(0, keepY); } });
        }
    }

    private void showError(String e) {
        status.setText("同步失败");
        list.removeAllViews();
        TextView t = Ui.text(this, e, 16, false);
        t.setPadding(0, dp(12), 0, dp(12));
        list.addView(t);
        android.widget.Button again = Ui.button(this, "重新同步", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        list.addView(again, alp);
    }

    private static java.util.Map<String, Integer> snapshotInstalled(Context c) {
        java.util.Map<String, Integer> m = new java.util.HashMap<String, Integer>();
        try {
            List<android.content.pm.PackageInfo> pkgs = c.getPackageManager().getInstalledPackages(0);
            if (pkgs != null) {
                for (android.content.pm.PackageInfo pi : pkgs) {
                    if (pi != null && pi.packageName != null) m.put(pi.packageName, pi.versionCode);
                }
            }
        } catch (Throwable ignored) { }
        return m;
    }

    private void refreshInstalled(final boolean rerender) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.Map<String, Integer> m = snapshotInstalled(app);
                ui.post(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    boolean changed = !m.equals(installedVersions);
                    installedVersions = m;
                    if (rerender && changed && !apps.isEmpty()) render();
                } });
            }
        }, "StoreInstalled").start();
    }

    private int installed(String pkg) {
        if (pkg == null) return 0;
        Integer v = installedVersions.get(pkg);
        return v != null ? v.intValue() : 0;
    }

    private void install(final App a) {
        if (!BindGate.ensureBound(this, a.name, new Runnable() { @Override public void run() { install(a); } })) return;
        if (!JOB_ID.compareAndSet(-1, a.id)) {
            String m = "正在处理另一个应用，等它下载 / 安装完再点" + (jobMsg != null ? "：" + jobMsg : "");
            status.setText(m); VeraToast.show(this, "上一个还没装完");
            return;
        }
        jobInstalling = false;
        final String t = token();
        say("正在下载 " + a.name + " …");
        render();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Thread awake = StoreNet.keepAwake();
                try { job(a, t, app); }
                catch (BindGate.BindRequiredException be) {
                    clearJob();
                    jobDone("需要绑定账号后下载", false);
                    ui.post(new Runnable() { @Override public void run() {
                        if (!isFinishing()) BindGate.showBindDialog(StoreActivity.this, a.name, new Runnable() { @Override public void run() { install(a); } });
                    } });
                }
                catch (Throwable e) {
                    clearJob();
                    jobDone("安装失败：" + reason(e), false);
                }   // 任何意外（OOM 之类）都要说出来，不能让线程悄悄死掉
                finally { awake.interrupt(); clearJob(); }
            }
        }, "StoreDownload").start();
    }

    private static void clearJob() {
        jobInstalling = false;
        JOB_ID.set(-1);
    }

    private static void rerenderLive() {
        MAIN.post(new Runnable() { @Override public void run() {
            StoreActivity s = live;
            if (s != null && !s.isFinishing()) s.render();
        } });
    }

    /** The end of a job: remember the result for a store screen opened later, show it on the one in front. */
    private static void jobDone(final String msg, final boolean ok) {
        jobDone(msg, ok, null);
    }

    private static void jobDone(final String msg, final boolean ok, final java.util.Map<String, Integer> inst) {
        jobMsg = msg;
        MAIN.post(new Runnable() { @Override public void run() {
            StoreActivity s = live;
            if (s == null || s.isFinishing()) return;
            if (ok && inst != null) s.installedVersions = inst;
            s.render();      // 无论成功或失败都刷新按钮状态（下载中 / 安装中 → 安装 / 重新安装），并保留当前页位置
            s.status.setText(msg);
            VeraToast.show(s, msg.length() > 60 ? msg.substring(0, 60) + "…" : msg);
        }});
    }

    /** A readable reason for a failed download (the exception's own message is often empty or English jargon). */
    static String reason(Throwable e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "域名解析失败（DNS）：连不上 " + Dl.host(StoreNet.base()) + "，检查 Wi-Fi 或换个网络";
        if (e instanceof java.net.SocketTimeoutException) return "网络太慢，" + (m != null && m.contains("connect") ? "连接服务器超时" : "1 分钟没有收到数据") + "。再点一次安装会从断点继续";
        if (e instanceof java.net.ConnectException) return "连不上服务器（连接被拒绝 / 被拦截）：换个网络再试";
        if (e instanceof javax.net.ssl.SSLException) return "安全连接（HTTPS）失败：检查 Kindle 的日期时间，或换个网络（" + m + "）";
        if (e instanceof OutOfMemoryError) return "内存不够：重启 VeraDroid 后再试";
        if (m != null && (m.contains("ENOSPC") || m.contains("No space left"))) return "存储空间不够：设置 → 存储空间 里扩大系统盘，或删掉一些应用 / 书";
        if (m != null && (m.contains("reset") || m.contains("unexpected end of stream") || m.contains("closed"))) return "网络连接中断（" + m + "）。再点一次安装会从断点继续";
        if (m == null || m.length() == 0) return e.getClass().getSimpleName();
        return m;
    }

    private void job(final App a, final String t, final Context ctx) throws Exception {
        File out = null; String err = null;
        try {
            if (a.pkg == null || !a.pkg.matches("[A-Za-z0-9._]{1,128}")) throw new Exception("包名不对");   // 包名会进文件名和宿主的安装命令
            File dir = Transfer.tmpDir();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Exception("无法创建下载目录");
            out = new File(dir, "store." + a.pkg + "." + a.versionCode + ".apk");
            // 同名文件：上次没下完的留着续传（自己写的、比完整的小）；别的（旧安装留下的、别的 uid 的）先删掉
            if (out.isFile() && !(out.canWrite() && a.size > 0 && out.length() < a.size)) out.delete();
            if (a.size <= 0) Dl.clear(out);   // 分段下载（out.seg/）只在知道大小时用
            // 空间：下载的 APK 和安装时 PackageManager 的副本同时在系统盘上（约 2 倍 + 编译结果）。镜像机型（PW5 / KT5 / KT6 /
            // Scribe）的系统盘不够时宿主的看门狗会自动扩（每 20 秒看一次），等它一会儿再判断。
            waitSpace(dir, a.size, Dl.have(out));
            // 下载：断了 / 卡住就从断点接着下（Range），最多试 8 次。以前一次读超时或连接被重置，127 MB 的微信读书就整个失败
            // （Scribe 用户：商店装微信读书失败，同一个 APK 侧载能装）。
            download(a, t, out, mirrors);
            if (a.size > 0 && out.length() != a.size) throw new CorruptDownloadException("文件大小不对（" + out.length() + " / " + a.size + "）");
            say("正在核对下载的文件…");
            if (!StoreNet.sha256(out).equalsIgnoreCase(a.sha)) {
                // 下坏了，或镜像上的文件和主站的不一样：只从主站重下一次
                android.util.Log.w("VeraStore", "sha256 mismatch, downloading again from the main site");
                Dl.clear(out);
                download(a, t, out, null);
                say("正在核对下载的文件…");
                if (!StoreNet.sha256(out).equalsIgnoreCase(a.sha)) throw new CorruptDownloadException("SHA-256 校验失败（下载的文件损坏，请重试）");
            }
            out.setReadable(true, false);
            jobInstalling = true;
            say("下载完成，正在安装 " + a.name + " …（大应用可能要好几分钟）");
            rerenderLive();
        } catch (BindGate.BindRequiredException be) {
            // 保留已下载的部分文件，让用户完成绑定后从断点续传，并把异常交给 install() 弹出绑定引导框
            throw be;
        } catch (CorruptDownloadException ce) {
            err = ce.getMessage();
            if (out != null) Dl.clear(out);
        } catch (Exception e) {
            err = reason(e);
            // 网络超时、断流、空间不足、限流等可续传错误一律保留部分文件（再点安装从断点继续），不再误删
        }
        if (err != null) { clearJob(); jobDone("安装失败：" + err, false); return; }
        // 等宿主的安装结果（ctl/inst.<文件名>），显示真正的结果和原因；以前交给宿主后就再也不说了
        android.content.pm.PackageInfo want = Transfer.apkInfo(ctx, out);
        final String r = Transfer.hostInstall(out, new Transfer.InstallProgress() {
            @Override public void minutes(int n) { say("正在安装 " + a.name + "… 已用 " + n + " 分钟"); }
        });
        final boolean ok = r != null ? r.contains("Success") : Transfer.installedNow(ctx, want);
        final String msg = ok ? "已安装 " + a.name
            : r != null ? "安装失败：" + r
            : "安装 " + a.name + " " + (Transfer.INSTALL_WAIT_MS / 60000) + " 分钟还没有结果，稍后看看应用列表";
        if (ok || r != null) Dl.clear(out);
        java.util.Map<String, Integer> inst = ok ? snapshotInstalled(ctx) : null;
        clearJob();
        jobDone(msg, ok, inst);
    }

    /** Download a.download into out (Dl: fastest of the mirrors and the main site, several Range connections for big
     *  files), resuming after a broken / stalled connection; up to 8 tries, the counter starts again after real progress. */
    private void download(final App a, String t, File out, List<String> mirrors) throws Exception {
        Context appCtx = getApplicationContext();
        List<String> activeMirrors = mirrors;
        String signed = null, q = "";
        List<String> cands = null;
        long signedAt = 0;
        Exception last = null;
        long before = Dl.have(out); int fails = 0;
        int consecutiveResigns = 0;
        int slowSwitches = 0;
        final java.util.Set<String> slow = new java.util.HashSet<String>();   // sources that crawled: the next try skips them
        // 速度看最近十来秒（以前从开始算平均：换了快的源以后还一直显示很慢）；大小用 StoreNet.mb（以前 4 MB 的应用一直显示 0 / 4 MB）
        Dl.Progress p = new Dl.Progress() {
            long wt = System.currentTimeMillis(), wd = -1, kbs = -1;
            @Override public void on(long done, long total, boolean mirror) {
                long now = System.currentTimeMillis();
                if (wd < 0 || done < wd) { wd = done; wt = now; }
                else if (now - wt >= 3000) { kbs = (done - wd) * 1000 / (now - wt) / 1024; if (now - wt >= 10000) { wd = done; wt = now; } }
                long pct = total > 0 ? done * 100 / total : -1;
                if (pct >= 0) say("正在下载 " + a.name + " … " + pct + "%（" + StoreNet.mb(done) + " / " + StoreNet.mb(total)
                    + (kbs < 0 ? "" : "，" + (kbs >= 1024 ? String.format("%.1f MB/s", kbs / 1024.0) : kbs + " KB/s")) + (mirror ? "，镜像" : "") + "）");
            }};
        while (fails < 8) {
            if (a.size > 0 && out.isFile() && out.length() == a.size && !new File(out.getPath() + ".seg").exists()) return;
            if (cands == null || System.currentTimeMillis() - signedAt >= 5 * 60 * 1000L) {
                signed = BindGate.signUrl(appCtx, "apk", a.id, a.download, t);
                signedAt = System.currentTimeMillis();
                q = signed.contains("sig=") ? "" : ("device_id=" + enc(Update.deviceId(appCtx)) + "&token=" + enc(t));
                cands = Dl.candidates(signed, q, activeMirrors);
                slow.clear();
            }
            try {
                Dl.fetchOnce(cands, out, a.size, p, slow);
                if (a.size <= 0 || out.length() == a.size) return;
            } catch (Dl.Slow e) {
                // 这个源太慢（国内晚上直连镜像常见）：换下一个源，从已经下好的地方接着下，上限防弱网死循环
                say("下载太慢（" + (e.bps >> 10) + " KB/s），换一个下载源继续…");
                long cur = Dl.have(out);
                if (cur > before) consecutiveResigns = 0;
                before = cur;
                last = e;
                if (++slowSwitches <= Math.max(4, cands.size() * 2)) continue;
            } catch (Dl.HttpError e) {
                if (e.code == 404) {
                    if (e.fromMirror && cands.size() > 1) {
                        say("镜像缺少该安装包，改从主站继续下载…");
                        activeMirrors = null;
                        cands = Dl.candidates(signed, q, null);
                        slow.clear();
                        before = Dl.have(out);
                        continue;
                    }
                    throw new CorruptDownloadException("这个应用已经从商店下架了");
                }
                if (e.code == 429) throw e;
                if (e.code == 401 || e.code == 403) {
                    if ("ip_blocked".equals(e.errCode)) throw e;
                    if ("bind_required".equals(e.errCode) || "token_invalid".equals(e.errCode)) {
                        Token.clear(appCtx);
                        throw new BindGate.BindRequiredException(e.getMessage() != null ? e.getMessage() : BindGate.REASON_TEXT);
                    }
                    if (consecutiveResigns < 2) {
                        // 签名过期或刚切网：静默重签续传，不误清 Token；若设备已解绑或 token 失效，signUrl 会清 token 并抛 BindRequiredException
                        consecutiveResigns++;
                        say("下载链接已刷新，正在从断点继续…");
                        cands = null;
                        before = Dl.have(out);
                        continue;
                    }
                    throw e;
                }
                last = e;
            } catch (java.io.IOException e) {
                last = e;
            }
            long now = Dl.have(out);
            if (now > before) consecutiveResigns = 0;
            if (now - before > (512 << 10)) fails = 0; else fails++;
            before = now;
            if (fails >= 8) break;
            say("网络中断，正在从断点继续下载（第 " + (fails + 1) + " 次）…");
            try { Thread.sleep(Math.min(30000, 3000L * Math.max(1, fails))); } catch (InterruptedException ie) { throw ie; }
        }
        throw new Exception("下载失败：" + (last != null ? reason(last) : "网络不稳定") + "。再点一次安装会从断点继续");
    }

    /** Free space in the staging folder for a download of size bytes plus the installer's copy; waits up to 90 s for the
     * host's automatic system-disk growth (image models), then fails with a readable reason. */
    private void waitSpace(File dir, long size, long partial) throws Exception {
        if (size <= 0) return;
        long need = size * 2 + (64L << 20) - partial;   // a partial download from an earlier try is already on the disk
        for (int i = 0; ; i++) {
            long have = new android.os.StatFs(dir.getAbsolutePath()).getAvailableBytes();
            if (have >= need) return;
            if (i >= 30) throw new Exception("存储空间不够：需要约 " + (need >> 20) + " MB，现在剩 " + (have >> 20) + " MB（设置 → 存储空间 可以扩大系统盘）");
            if (i == 0) say("存储空间不够，等系统盘自动扩大…");
            Thread.sleep(3000);
        }
    }

    private static void say(final String s) {
        jobMsg = s;
        MAIN.post(new Runnable() { @Override public void run() {
            StoreActivity a = live;
            if (a != null && !a.isFinishing()) a.status.setText(s);
        }});
    }

    private static JSONObject get(String url) throws Exception { return StoreNet.json(url); }

    private static String enc(String s) { return StoreNet.enc(s); }
    private int dp(int v) { return Ui.dp(this, v); }

    @Override protected void onResume() {
        super.onResume();
        live = this;
        if (jobMsg != null && status != null) {
            status.setText(jobMsg);
            if (JOB_ID.get() < 0) jobMsg = null;
        }
        if (!apps.isEmpty()) refreshInstalled(true);
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
    }

    @Override protected void onDestroy() {
        if (live == this) live = null;
        iconBoxes.clear();
        apps.clear();
        if (list != null) list.removeAllViews();
        super.onDestroy();
    }

    @Override protected void onPause() {
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }
}
