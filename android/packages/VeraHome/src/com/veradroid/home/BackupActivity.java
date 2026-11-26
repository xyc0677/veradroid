package com.veradroid.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设置 → 设备与更新 → 已备份的数据：安装器每次更新前做的备份（Kindle 上 veradroid/backup 里）占多少地方，可以删掉不要的。
 * 也管“更新前备份”这个设置（{@link #choose}）：设置、阅读进度和书（默认）/ 只备份设置和阅读进度 / 不备份。
 *
 * 容器里看不到 veradroid/，列出和删除都由宿主做（boot/lib/backup.sh），走管理请求 {@link HostReq}（别的应用冒充不了）：
 *   backups                 列表（kv：i.N.where / name / kind / kb / time / light / lock，n、total_kb、free_kb、mode、busy、pending）
 *   backupdel new|root 名字  删一份；backupdel all 删全部能删的。名字是宿主列出来的，路径由宿主自己拼、只认固定格式
 *   updatebackup 模式        改设置
 * 正在安装、或者这次更新的备份还没等到第一次开机核对完书库，宿主不让删（lock=install / verify），这里显示“正在使用，暂不能删”。
 */
public class BackupActivity extends Activity {
    static final String[] MODE_V = { "full", "light", "off" };
    static final String[] MODE_L = { "设置、阅读进度和书", "只备份设置和阅读进度", "不备份" };
    static final String OFF_WARN = "不备份时，更新出问题无法恢复书和阅读进度。";

    private final Handler handler = new Handler();
    private LinearLayout list;
    private PageScrollView sv;
    private TextView pageNo;
    private Map<String, String> st;
    private volatile boolean busy;

    // ------------------------------------------------------------------ 设置：更新前备份

    /** 现在的方式（宿主发布在 /dev/kfb/settings 里）；老宿主没有这一项时按默认。 */
    static int mode() {
        String v = HostLink.kv(HostLink.KFB + "settings").get("UPDATE_BACKUP");
        for (int i = 0; i < MODE_V.length; i++) if (MODE_V[i].equals(v)) return i;
        return 0;
    }
    static boolean off() { return mode() == 2; }

    /** 设置页那一行的说明。 */
    static String desc() {
        String s = "更新系统前先在 Kindle 上备份，更新出问题时自动找回。默认备份设置、阅读进度和 300 MB 以内的书。当前：" + MODE_L[mode()] + "。";
        if (off()) s += "\n注意：" + OFF_WARN;
        return s;
    }

    /** 选方式；选“不备份”先确认一次。done 在改完以后跑（主线程）。 */
    static void choose(final Activity a, final Runnable done) {
        new VeraDialog.Builder(a).title("更新前备份").items(new String[] {
                MODE_L[0] + "（默认）", MODE_L[1] + "（书不备份，占地方少）", MODE_L[2] }, new VeraDialog.OnPick() {
            @Override public void onPick(final int which) {
                if (which != 2) { setMode(a, which, done); return; }
                new VeraDialog.Builder(a).title("关闭更新前备份？")
                    .message("以后更新系统时不再备份。\n\n" + OFF_WARN + "\n\n更新后照样核对书库，少了文件会记下来，但找不回来。已经有的备份不会删。")
                    .negative("取消", null)
                    .positive("不备份", new Runnable() { @Override public void run() { setMode(a, 2, done); } }).show();
            }
        }).selected(mode()).negative("取消", null).show();
    }

    private static void setMode(final Activity a, final int idx, final Runnable done) {
        new Thread(new Runnable() { @Override public void run() {
            byte[] r = HostReq.run(a, "updatebackup", MODE_V[idx], null, 15000);
            final Map<String, String> m = HostReq.kv(r);
            final boolean ok = "1".equals(m.get("ok")) && MODE_V[idx].equals(m.get("mode"));
            new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
                VeraToast.show(a, ok ? "更新前备份：" + MODE_L[idx] : "没能改（宿主没有回应，可能要先更新 VeraDroid）");
                if (done != null) new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { if (!a.isFinishing()) done.run(); }
                }, 300);
            } });
        } }, "backup-mode").start();
    }

    // ------------------------------------------------------------------ 页面

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);
        TextView title = Ui.text(this, "已备份的数据", 30, true);
        title.setPadding(0, dp(8), 0, dp(8));
        page.addView(title);
        sv = new PageScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(Ui.button(this, "返回", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(Ui.button(this, "上一页", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { if (sv.page(-1)) sv.firePage(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pageNo = Ui.text(this, "", 14, false);
        pageNo.setGravity(Gravity.CENTER);
        actions.addView(pageNo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(Ui.button(this, "下一页", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { if (sv.page(1)) sv.firePage(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(Ui.button(this, "刷新", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) { ask(null, null); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        sv.setOnPage(new Runnable() { @Override public void run() { pages(); } });
        Blocks.restyle(this, page, title, actions, "Backups");
        line("正在读取…", 18, false);
    }

    private void pages() {
        int n = sv.pages();
        pageNo.setText(n > 1 ? sv.currentPage() + " / " + n : "");
    }

    /** 问宿主（verb = null：列表；否则 backupdel arg），答复里都带着新的列表。 */
    private void ask(final String verb, final String arg) {
        if (busy) return;
        busy = true;
        if (verb != null) { list.removeAllViews(); line("正在删除…", 20, true); }
        new Thread(new Runnable() { @Override public void run() {
            final Map<String, String> m;
            try {
                byte[] r = verb == null ? HostReq.run(BackupActivity.this, "backups", null, null, 30000)
                                        : HostReq.run(BackupActivity.this, verb, arg, null, 120000);
                m = HostReq.kv(r);
            } finally {
                busy = false;
            }
            handler.post(new Runnable() { @Override public void run() {
                if (isFinishing()) return;
                if (verb != null) {
                    int n = HostLink.num(m, "deleted", 0);
                    if (n > 0) VeraToast.show(BackupActivity.this, "已删除 " + n + " 份，空出 " + size(HostLink.num(m, "freed_kb", 0)));
                    else if ("inuse".equals(m.get("error"))) VeraToast.show(BackupActivity.this, "正在使用，暂不能删");
                    else VeraToast.show(BackupActivity.this, "没有删除（" + (m.isEmpty() ? "宿主没有回应" : "宿主拒绝了") + "）");
                }
                st = m;
                render();
            } });
        } }, "backups").start();
    }

    private static final class Item {
        String where, name, kind, time, lock; int kb; boolean light;
        boolean locked() { return lock != null && lock.length() > 0; }
    }

    private List<Item> items() {
        List<Item> l = new ArrayList<Item>();
        int n = HostLink.num(st, "n", 0);
        for (int i = 1; i <= n && i <= 200; i++) {
            Item it = new Item();
            it.where = st.get("i." + i + ".where"); it.name = st.get("i." + i + ".name"); it.kind = st.get("i." + i + ".kind");
            it.time = st.get("i." + i + ".time"); it.lock = st.get("i." + i + ".lock");
            it.kb = HostLink.num(st, "i." + i + ".kb", 0); it.light = "1".equals(st.get("i." + i + ".light"));
            if (it.where == null || it.name == null) continue;
            l.add(it);
        }
        return l;
    }

    static String size(int kb) {
        if (kb < 1024) return kb + " KB";
        if (kb < 1024 * 1024) return String.format(Locale.US, "%.1f MB", kb / 1024f);
        return String.format(Locale.US, "%.2f GB", kb / (1024f * 1024f));
    }

    static String kindLabel(String kind, boolean light) {
        if ("user".equals(kind)) return light ? "更新前备份（设置和阅读进度）" : "更新前备份";
        if ("cc".equals(kind)) return "Kindle 书库索引";
        if ("docs".equals(kind)) return "旧版书库备份";
        if ("docsr".equals(kind)) return "旧版书库备份（已恢复过一次）";
        if ("media".equals(kind)) return "卸载时留下的内部存储";
        return "备份";
    }

    private static String what(Item it) {
        if ("user".equals(it.kind)) return it.light ? "设置、阅读进度和笔记，不含书。" : "设置、阅读进度、笔记和一部分书。";
        if ("cc".equals(it.kind)) return "Kindle 自己的书库目录数据库，书库出问题时用。";
        if ("docs".equals(it.kind)) return "老版本做的书库备份，下次更新时会用它补回缺的书一次。";
        if ("docsr".equals(it.kind)) return "老版本做的书库备份，已经用来补过书。";
        if ("media".equals(it.kind)) return "卸载 VeraDroid 时留下的 Android 内部存储（书、下载的文件），重新安装时放回去。";
        return "";
    }

    private void render() {
        list.removeAllViews();
        if (st == null || st.isEmpty() || !"1".equals(st.get("ok"))) {
            line("读不到备份信息。这一版的系统还不支持，请先更新 VeraDroid。", 18, false);
            return;
        }
        final List<Item> items = items();
        int deletable = 0, delKb = 0;
        for (Item it : items) if (!it.locked()) { deletable++; delKb += it.kb; }
        line("共 " + items.size() + " 份，占 " + size(HostLink.num(st, "total_kb", 0)) + "；Kindle 剩余 " + size(HostLink.num(st, "free_kb", 0)) + "。", 18, true);
        // 现在的设置 + 更改
        LinearLayout mr = new LinearLayout(this);
        mr.setGravity(Gravity.CENTER_VERTICAL);
        mr.setPadding(0, dp(4), 0, dp(4));
        int mi = 0;
        for (int i = 0; i < MODE_V.length; i++) if (MODE_V[i].equals(st.get("mode"))) mi = i;
        TextView mt = Ui.text(this, "更新前备份：" + MODE_L[mi] + (mi == 2 ? "\n" + OFF_WARN : ""), 15, mi == 2);
        mr.addView(mt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mr.addView(Ui.button(this, "更改", 15, false, new View.OnClickListener() {
            @Override public void onClick(View v) { choose(BackupActivity.this, new Runnable() { @Override public void run() { ask(null, null); } }); }
        }));
        list.addView(mr);
        String last = lastLine();
        if (last != null) line(last, 14, false);
        if ("1".equals(st.get("busy"))) line("正在安装更新，等装完再删。", 15, true);
        else if ("1".equals(st.get("pending"))) line("这次更新的备份要等第一次开机核对完书库才能删。", 14, false);
        list.addView(Ui.rule(this, 2, Color.BLACK));
        if (items.isEmpty()) { line("没有备份。", 18, false); pagesLater(); return; }
        for (final Item it : items) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(8), 0, dp(8));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(Ui.text(this, kindLabel(it.kind, it.light), 18, true));
            String sub = (it.time == null || it.time.length() == 0 ? "" : it.time + " · ") + size(it.kb);
            if ("root".equals(it.where)) sub += " · 在 Kindle 根目录（旧版位置）";
            col.addView(Ui.text(this, sub, 14, false));
            String w = what(it);
            if (w.length() > 0) col.addView(Ui.text(this, w, 13, false));
            r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (it.locked()) {
                TextView lk = Ui.text(this, "正在使用\n暂不能删", 14, true);
                lk.setGravity(Gravity.CENTER);
                r.addView(lk);
            } else {
                r.addView(Ui.button(this, "删除", 15, false, new View.OnClickListener() {
                    @Override public void onClick(View v) { confirmOne(it); }
                }));
            }
            list.addView(r);
            list.addView(Ui.rule(this, 1, Color.BLACK));
        }
        if (deletable > 0) {
            final int dn = deletable, dk = delKb;
            LinearLayout ar = new LinearLayout(this);
            ar.setPadding(0, dp(12), 0, dp(4));
            ar.addView(Ui.button(this, "全部删除（" + dn + " 份，" + size(dk) + "）", 16, true, new View.OnClickListener() {
                @Override public void onClick(View v) { confirmAll(items, dn, dk); }
            }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            list.addView(ar);
        }
        pagesLater();
    }

    private void pagesLater() { sv.scrollTo(0, 0); handler.postDelayed(new Runnable() { @Override public void run() { pages(); } }, 100); }

    private String lastLine() {
        String r = st.get("last.result"), s = st.get("last.stamp");
        if (r == null || r.length() == 0) return null;
        String when = s != null && s.matches("\\d{8}-\\d{6}") ? s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8) + " " + s.substring(9, 11) + ":" + s.substring(11, 13) + " " : "";
        if (r.equals("ok")) return "上次更新（" + when.trim() + "）：已备份" + ("light".equals(st.get("last.kind")) && !"light".equals(st.get("last.mode")) ? "，空间不够只备份了设置和阅读进度" : "") + "。";
        if (r.equals("off")) return "上次更新（" + when.trim() + "）：备份已关闭，没有备份。";
        if (r.equals("nospace")) return "上次更新（" + when.trim() + "）：空间不够，没有备份。";
        if (r.equals("failed")) return "上次更新（" + when.trim() + "）：备份失败，没有备份。";
        return null;
    }

    private void confirmOne(final Item it) {
        String msg = kindLabel(it.kind, it.light) + (it.time == null || it.time.length() == 0 ? "" : "，" + it.time) + "，" + size(it.kb) + "。\n"
            + what(it) + "\n\n删除后不能恢复。";
        new VeraDialog.Builder(this).title("删除这份备份？").message(msg).negative("取消", null)
            .positive("删除", new Runnable() { @Override public void run() { ask("backupdel", it.where + " " + it.name); } }).show();
    }

    private void confirmAll(List<Item> items, int n, int kb) {
        StringBuilder sb = new StringBuilder("会删除下面 " + n + " 份，共 " + size(kb) + "：\n");
        int shown = 0, kept = 0;
        for (Item it : items) {
            if (it.locked()) { kept++; continue; }
            if (shown++ < 8) sb.append("· ").append(kindLabel(it.kind, it.light)).append(it.time == null || it.time.length() == 0 ? "" : " " + it.time).append("，").append(size(it.kb)).append("\n");
        }
        if (shown > 8) sb.append("· 另外 ").append(shown - 8).append(" 份\n");
        if (kept > 0) sb.append("正在使用的 ").append(kept).append(" 份不删。\n");
        sb.append("\n删除后不能恢复。之后更新出问题时，就没有这些备份可以找回数据了。");
        new VeraDialog.Builder(this).title("全部删除？").message(sb.toString()).negative("取消", null)
            .positive("全部删除", new Runnable() { @Override public void run() { ask("backupdel", "all"); } }).show();
    }

    private void line(String s, float sp, boolean bold) {
        TextView t = Ui.text(this, s, sp, bold);
        t.setPadding(0, dp(6), 0, dp(6));
        list.addView(t);
    }

    private int dp(int v) { return Ui.dp(this, v); }

    @Override protected void onResume() {
        super.onResume();
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
        ask(null, null);
    }
    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
