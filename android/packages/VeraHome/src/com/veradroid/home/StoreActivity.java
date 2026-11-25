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
    private String query = "";
    private int batch = 1, batches = 1;
    private LinearLayout tabs, tagBar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<App> apps = new ArrayList<App>();
    private volatile java.util.Map<String, Integer> installedVersions = java.util.Collections.emptyMap();
    private LinearLayout list;
    private TextView status;
    // 一次只下载 / 安装一个，进度和结果放在静态字段里（Scribe 用户：商店装微信读书“失败”，同一个 APK 侧载能装）：
    //  - 以前下载线程只往开着的那个界面上写字：用户离开商店（或界面被系统重建）以后，进度和失败原因谁也看不到；
    //  - 再点一次“安装”会再起一条线程，两条线程往同一个文件里写，SHA-256 必然失败；
    //  - 127 MB 要下好几分钟，Kindle 10 分钟没碰就进屏保，宿主在屏保时冻结 Android，下载就断在那里。
    private static volatile String jobPkg;      // 正在处理的包名，null = 没有
    private static volatile String jobMsg;      // 当前进度 / 最近一次的结果（重新打开商店时显示）
    private static StoreActivity live;          // 正在前台的商店界面（进度写到它上面），只在主线程读写
    // 下载镜像（服务器 /api/store/apps 的 "mirrors"；没有这一项时用 Dl.DEF_MIRRORS，[] = 不用镜像）
    private static volatile List<String> mirrors = java.util.Arrays.asList(Dl.DEF_MIRRORS);

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
        page.addView(tabs);

        tagBar = new LinearLayout(this);
        tagBar.setOrientation(LinearLayout.VERTICAL);
        tagBar.setPadding(0, dp(2), 0, dp(6));
        page.addView(tagBar);

        // 整页翻（PageScrollView + 翻页条），不跟手滚动：墨水屏上一次只换一次画面
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
        actions.addView(Ui.button(this, "应用更新", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { Update.now(StoreActivity.this); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        Blocks.restyle(this, page, title, actions, "Store / e-ink apps");

        tabsRender();
        tagBarRender();
        load();
    }

    /** 官方推荐下的 6 大功能分类（2 行紧凑排布，墨水屏无需横向滚动，点击 0ms 本地筛选）。 */
    private void tagBarRender() {
        tagBar.removeAllViews();
        if (cat != 0) { tagBar.setVisibility(View.GONE); return; }
        tagBar.setVisibility(View.VISIBLE);
        int[][] rows = { { 0, 1, 2, 3 }, { 4, 5, 6 } };
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (r > 0) row.setPadding(0, dp(4), 0, 0);
            for (int c = 0; c < rows[r].length; c++) {
                final int idx = rows[r][c];
                android.widget.Button b = Ui.button(this, TAG_L[idx], 13, idx == tagIdx, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (tagIdx == idx) return;
                        tagIdx = idx;
                        tagBarRender();
                        render();
                    }
                });
                b.setSingleLine(true);
                b.setPadding(dp(4), dp(4), dp(4), dp(4));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.leftMargin = c == 0 ? 0 : dp(6);
                row.addView(b, lp);
            }
            tagBar.addView(row);
        }
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
        final android.widget.EditText input = new android.widget.EditText(this);
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
        final int myCat = cat, myBatch = batch;
        final String q = query;
        status.setText("正在同步…");
        list.removeAllViews();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                final List<App> got = new ArrayList<App>();
                final int[] cnt = { -1, -1, 1 };
                try {
                    JSONObject j = get(StoreNet.base() + "/api/store/apps?category=" + CATS[myCat] + "&page=" + myBatch + "&per=100&q=" + enc(q)
                        + "&device_id=" + enc(Update.deviceId(StoreActivity.this)) + "&token=" + enc(t));
                    JSONArray cs = j.optJSONArray("categories");
                    if (cs != null) for (int i = 0; i < cs.length(); i++) {
                        JSONObject c = cs.getJSONObject(i);
                        for (int k = 0; k < CATS.length; k++) if (CATS[k].equals(c.optString("id"))) cnt[k] = c.optInt("count", -1);
                    }
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
                        a.tag = o.optString("tag", "");
                        a.uploader = o.optString("uploader"); a.source = o.optString("source"); a.iconUrl = o.optString("icon");
                        try { a.icon = getPackageManager().getApplicationIcon(a.pkg); } catch (Throwable ignored) { }
                        got.add(a);
                    }
                } catch (Exception e) { err = e.getMessage(); }
                final java.util.Map<String, Integer> instMap = err == null ? snapshotInstalled(getApplicationContext()) : null;
                final String fe = err;
                ui.post(new Runnable() { @Override public void run() {
                    if (isFinishing() || myCat != cat || myBatch != batch || !q.equals(query)) return;   // 用户已经换了一栏
                    if (fe != null) { showError(fe); return; }
                    if (instMap != null) installedVersions = instMap;
                    apps.clear(); apps.addAll(got);
                    batches = cnt[2];
                    if (cnt[0] != counts[0] || cnt[1] != counts[1]) { counts[0] = cnt[0]; counts[1] = cnt[1]; tabsRender(); }
                    render();
                    icons(got);
                } });
            }
        }, "StoreSync").start();
    }

    /** 没装的应用：图标从商店取（缓存在本机），全部取完后只重画一次。 */
    private void icons(final List<App> shown) {
        new Thread(new Runnable() { @Override public void run() {
            boolean any = false;
            for (App a : shown) {
                if (a.icon != null || a.iconUrl.length() == 0) continue;
                android.graphics.Bitmap bm = StoreNet.image(a.iconUrl, "icon-" + (a.sha.length() >= 16 ? a.sha.substring(0, 16) : String.valueOf(a.id)));
                if (bm != null) { a.icon = new android.graphics.drawable.BitmapDrawable(getResources(), bm); any = true; }
            }
            if (any) ui.post(new Runnable() { @Override public void run() {
                if (!isFinishing() && apps.size() == shown.size() && (apps.isEmpty() || apps.get(0) == shown.get(0))) render();
            } });
        } }, "StoreIcons").start();
    }

    /** 应用的完整说明、上传者、校验值；用户上传的还写明怎么举报。 */
    private void detail(final App a) {
        StringBuilder sb = new StringBuilder();
        sb.append(a.pkg).append("\n版本 ").append(a.versionName).append(" · ").append(StoreNet.mb(a.size));
        if (a.user) sb.append("\n用户上传 · ").append(a.uploader).append(" · 已审核");
        else sb.append("\n官方推荐");
        if (a.source.length() > 0) sb.append("\n来源：").append(a.source);
        if (a.description.length() > 0) sb.append("\n\n").append(a.description);
        if (a.sha.length() >= 16) sb.append("\n\nSHA-256 ").append(a.sha.substring(0, 16)).append("…");
        if (a.user) sb.append("\n\n用户上传的应用由上传者提供。有问题（侵权、不安全、打不开）请到 veradroid.wenjin.jp/store/apps 点它旁边的“举报 / 下架”。");
        int inst = installed(a.pkg);
        new VeraDialog.Builder(this).title(a.name).message(sb.toString()).negative("关闭", null)
            .positive(inst <= 0 ? "安装" : (inst < a.versionCode ? "更新" : "重新安装"), new Runnable() { @Override public void run() { install(a); } }).show();
    }

    private void render() {
        String activeTag = (cat == 0 && tagIdx > 0 && tagIdx < TAGS.length) ? TAGS[tagIdx] : "";
        List<App> shown = new ArrayList<App>();
        for (App a : apps) {
            if (activeTag.length() == 0 || activeTag.equals(a.tag)) shown.add(a);
        }
        String what = query.length() > 0 ? "“" + query + "”" : (activeTag.length() > 0 ? activeTag : CAT_L[cat]);
        status.setText(shown.isEmpty() ? (query.length() > 0 ? "没有找到" + what + "。" : cat == 1 ? "还没有用户上传的应用。在 veradroid.wenjin.jp 登录后可以上传。" : "当前分类下还没有应用。")
            : what + " " + shown.size() + " 个" + (batches > 1 ? "（第 " + batch + " / " + batches + " 批）" : "")
              + (cat == 1 ? " · 都经过人工审核" : " · 阅读类已置顶"));
        if (jobMsg != null) status.setText(jobMsg);   // 正在下载 / 安装，或者上一次的结果（包括失败原因）
        list.removeAllViews();
        // 每一行同一个样子：左边固定大小的图标框（装了的用应用图标，没装的写首字），中间名字 / 版本 / 说明各自一行或两行、
        // 放不下就省略号，右边按钮固定宽度。以前名字、说明多长就折几行，按钮跟着名字那一行上下跑（多看那一条名字长、说明长，看起来是歪的）。
        int box = dp(56), btnW = dp(104);
        for (final App a : shown) {
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
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(10), 0);
            TextView n = Ui.text(this, a.name, 19, true);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(n);
            int installed = installed(a.pkg);
            String label = installed <= 0 ? "安装" : (installed < a.versionCode ? "更新" : "重新安装");
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
        if (jobPkg != null) {
            String m = "正在处理另一个应用，等它下载 / 安装完再点" + (jobMsg != null ? "：" + jobMsg : "");
            status.setText(m); VeraToast.show(this, "上一个还没装完");
            return;
        }
        final String t = token();
        jobPkg = a.pkg;
        say("正在下载 " + a.name + " …");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Thread awake = StoreNet.keepAwake();
                try { job(a, t, app); }
                catch (BindGate.BindRequiredException be) {
                    jobDone("需要绑定账号后下载", false);
                    ui.post(new Runnable() { @Override public void run() {
                        if (!isFinishing()) BindGate.showBindDialog(StoreActivity.this, a.name, new Runnable() { @Override public void run() { install(a); } });
                    } });
                }
                catch (Throwable e) { jobDone("安装失败：" + reason(e), false); }   // 任何意外（OOM 之类）都要说出来，不能让线程悄悄死掉
                finally { awake.interrupt(); jobPkg = null; }
            }
        }, "StoreDownload").start();
    }

    /** The end of a job: remember the result for a store screen opened later, show it on the one in front. */
    private static void jobDone(final String msg, final boolean ok) {
        jobDone(msg, ok, null);
    }

    private static void jobDone(final String msg, final boolean ok, final java.util.Map<String, Integer> inst) {
        jobMsg = msg;
        new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
            StoreActivity s = live;
            if (s == null || s.isFinishing()) return;
            if (ok) {
                if (inst != null) s.installedVersions = inst;
                s.render();      // 按钮变成“已安装”；render 会显示 jobMsg
            }
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
            if (a.size > 0 && out.length() != a.size) throw new Exception("文件大小不对（" + out.length() + " / " + a.size + "）");
            say("正在核对下载的文件…");
            if (!StoreNet.sha256(out).equalsIgnoreCase(a.sha)) {
                // 下坏了，或镜像上的文件和主站的不一样：只从主站重下一次
                android.util.Log.w("VeraStore", "sha256 mismatch, downloading again from the main site");
                Dl.clear(out);
                download(a, t, out, null);
                say("正在核对下载的文件…");
                if (!StoreNet.sha256(out).equalsIgnoreCase(a.sha)) throw new Exception("SHA-256 校验失败（下载的文件损坏，请重试）");
            }
            out.setReadable(true, false);
            say("下载完成，正在安装 " + a.name + " …（大应用可能要好几分钟）");
        } catch (Exception e) {
            err = reason(e);
            // 下了一半的留着（再点安装从断点继续）；校验失败 / 大小不对的删掉
            if (out != null && (err == null || !err.startsWith("下载失败"))) Dl.clear(out);
        }
        if (err != null) { jobDone("安装失败：" + err, false); return; }
        // 等宿主的安装结果（ctl/inst.<文件名>），显示真正的结果和原因；以前交给宿主后就再也不说了
        android.content.pm.PackageInfo want = Transfer.apkInfo(ctx, out);
        final String r = Transfer.hostInstall(out, new Transfer.InstallProgress() {
            @Override public void minutes(int n) { say("正在安装 " + a.name + "… 已用 " + n + " 分钟"); }
        });
        final boolean ok = r != null ? r.contains("Success") : Transfer.installedNow(ctx, want);
        final String msg = ok ? "已安装 " + a.name
            : r != null ? "安装失败：" + r
            : "安装 " + a.name + " " + (Transfer.INSTALL_WAIT_MS / 60000) + " 分钟还没有结果，稍后看看应用列表";
        if (ok || r != null) out.delete();
        jobDone(msg, ok, ok ? snapshotInstalled(ctx) : null);
    }

    /** Download a.download into out (Dl: fastest of the mirrors and the main site, several Range connections for big
     *  files), resuming after a broken / stalled connection; up to 8 tries, the counter starts again after real progress. */
    private void download(final App a, String t, File out, List<String> mirrors) throws Exception {
        String signed = BindGate.signUrl(getApplicationContext(), "apk", a.id, a.download, t);
        String q = signed.contains("sig=") ? "" : ("device_id=" + enc(Update.deviceId(getApplicationContext())) + "&token=" + enc(t));
        List<String> cands = Dl.candidates(signed, q, mirrors);
        Exception last = null;
        long before = Dl.have(out); int fails = 0;
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
            try {
                Dl.fetchOnce(cands, out, a.size, p, slow);
                if (a.size <= 0 || out.length() == a.size) return;
            } catch (Dl.Slow e) {
                // 这个源太慢（国内晚上直连镜像常见）：换下一个源，从已经下好的地方接着下，不算失败、不等
                say("下载太慢（" + (e.bps >> 10) + " KB/s），换一个下载源继续…");
                before = Dl.have(out);
                continue;
            } catch (Dl.HttpError e) {
                if (e.code == 404 || e.code == 403) throw e;   // 商店里没有了 / 没权限：重试也没用
                last = e;
            } catch (java.io.IOException e) {
                last = e;
            }
            long now = Dl.have(out);
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
        new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
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
        if (jobMsg != null && status != null) status.setText(jobMsg);
        if (!apps.isEmpty()) refreshInstalled(true);
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
    }

    @Override protected void onDestroy() {
        if (live == this) live = null;
        super.onDestroy();
    }

    @Override protected void onPause() {
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }
}
