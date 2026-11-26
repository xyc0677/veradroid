// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;

/**
 * 设置 → 设备与更新 → 存储空间：Android 用的系统盘（Kindle 存储上的一个镜像文件）有多大、用了多少，可以调大调小。
 * 宿主那边是 boot/lib/storage.sh：`storage info` 把数字写到 ctl/storage（st.键=值，单位 MB），`storage resize N` 的
 * 答复在 ctl/storage.result。PW6 没有镜像（和 Kindle 共用空间），这里只显示剩余空间。
 */
public class StorageActivity extends Activity {
    private static final String INFO = HostLink.CTL + "storage", RESULT = HostLink.CTL + "storage.result";
    private final Handler handler = new Handler();
    private LinearLayout list;
    private Map<String, String> st;
    private int target = -1;
    private TextView targetView, hintView;
    private Button applyBtn;
    private long asked;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        getWindow().setWindowAnimations(0);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(16), dp(8), dp(16), dp(8));
        setContentView(page);
        TextView title = Ui.text(this, "存储空间", 30, true);
        title.setPadding(0, dp(8), 0, dp(8));
        page.addView(title);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout paged = Blocks.paged(this, list);
        page.addView(paged, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(Ui.button(this, "返回", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        actions.addView(Ui.button(this, "刷新", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) { ask(); }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(actions);
        Blocks.restyle(this, page, title, actions, "Storage / space");
        line("正在读取…", 18, false);
    }

    /** 问宿主要一次数字；答复文件出现（或变新）就重画。5 秒没有答复就说明宿主太旧。 */
    private void ask() {
        if (isFinishing()) return;
        asked = System.currentTimeMillis();
        final long before = new File(INFO).lastModified();
        HostLink.cmd("storage info");
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            int n = 0;
            @Override public void run() {
                if (isFinishing()) return;
                File f = new File(INFO);
                if (f.isFile() && f.lastModified() != before || (f.isFile() && n >= 12)) { st = HostLink.kv(INFO); render(); return; }
                if (++n > 25) { list.removeAllViews(); line("读不到存储信息。这一版的系统不支持调整，请先更新 VeraDroid。", 18, false); return; }
                handler.postDelayed(this, 200);
            }
        }, 200);
    }

    private int n(String k) { return HostLink.num(st, "st." + k, 0); }

    static String size(int mb) {
        if (mb < 1024) return mb + " MB";
        return String.format(java.util.Locale.US, "%.1f GB", mb / 1024f);
    }

    private void render() {
        list.removeAllViews();
        targetView = null; applyBtn = null; hintView = null;
        if ("shared".equals(st.get("st.mode"))) {
            line("与 Kindle 共用空间，无需调整", 22, true);
            bar("Kindle 存储", n("kindle_total") - n("kindle_free"), n("kindle_total"),
                "剩余 " + size(n("kindle_free")) + " / 共 " + size(n("kindle_total")));
            line("这台机器上 Android 直接用 Kindle 的剩余空间：书和应用占多少就用多少，删掉就还回去。", 16, false);
            return;
        }
        if (!"image".equals(st.get("st.mode"))) { line("读不到存储信息。", 18, false); return; }
        final int image = n("image"), min = n("min"), max = n("max");
        bar("Android 存储（系统、应用和书）", n("used"), Math.max(1, n("total")),
            "已用 " + size(n("used")) + "，剩余 " + size(n("free")) + " / 共 " + size(image));
        bar("Kindle 这边", Math.max(0, n("kindle_total") - n("kindle_free")), Math.max(1, n("kindle_total")),
            "剩余 " + size(n("kindle_free")) + " / 共 " + size(n("kindle_total")));
        list.addView(Ui.rule(this, 2, Color.BLACK));

        if ("1".equals(st.get("st.busy"))) {
            line("正在调整，请稍候…", 18, true);
            handler.postDelayed(new Runnable() { @Override public void run() { ask(); } }, 1500);
            return;
        }
        if (target < 0) target = image;
        target = Math.max(min, Math.min(max, target));
        list.addView(Blocks.sectionTitle(this, "调整大小", 20));
        String range = "可调范围 " + size(min) + " – " + size(max) + "。";
        if ("fat".equals(st.get("st.cap"))) range += "Kindle 存储是 FAT 格式，单个文件最大 4 GB。";
        else range += "始终给 Kindle 留至少 " + n("keep") + " MB。";
        line(range, 15, false);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(4));
        row.addView(Ui.button(this, "－256", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { pick(target - 256, min, max, image); }
        }));
        targetView = Ui.text(this, "", 24, true);
        targetView.setGravity(Gravity.CENTER);
        row.addView(targetView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Ui.button(this, "＋256", 16, false, new View.OnClickListener() {
            @Override public void onClick(View v) { pick(target + 256, min, max, image); }
        }));
        list.addView(row);

        // 几个常用的大小：只列范围内的，再加上“最小”“最大”
        ArrayList<Integer> c = new ArrayList<Integer>();
        if (min < image) c.add(min);
        for (int v : new int[] { 1536, 2048, 3072, 4096, 6144, 8192 }) if (v > min && v < max && Math.abs(v - image) >= 16 && !c.contains(v)) c.add(v);
        if (max > image && !c.contains(max)) c.add(max);
        LinearLayout presets = new LinearLayout(this);
        presets.setPadding(0, dp(4), 0, dp(4));
        for (final int v : c) {
            String label = v == min ? "最小 " + size(v) : v == max ? "最大 " + size(v) : size(v);
            Button pb = Ui.button(this, label, 14, false, new View.OnClickListener() {
                @Override public void onClick(View x) { pick(v, min, max, image); }
            });
            presets.addView(pb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (presets.getChildCount() == 4) { list.addView(presets); presets = new LinearLayout(this); presets.setPadding(0, dp(4), 0, dp(4)); }
        }
        if (presets.getChildCount() > 0) list.addView(presets);

        hintView = Ui.text(this, "", 15, false);
        hintView.setPadding(0, dp(6), 0, dp(6));
        list.addView(hintView);
        applyBtn = Ui.button(this, "调整", 18, true, new View.OnClickListener() {
            @Override public void onClick(View v) { confirmResize(image); }
        });
        list.addView(applyBtn);
        pick(target, min, max, image);
        list.addView(Ui.rule(this, 1, Color.BLACK));

        final boolean auto = !"0".equals(st.get("st.auto"));
        LinearLayout ar = new LinearLayout(this);
        ar.setGravity(Gravity.CENTER_VERTICAL);
        ar.setPadding(0, dp(10), 0, dp(10));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, "快满时自动扩大", 20, true));
        col.addView(Ui.text(this, "剩余不到 200 MB、Kindle 还有地方时，每次扩大 512 MB。", 14, false));
        ar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ar.addView(Ui.button(this, auto ? "开" : "关", 16, auto, new View.OnClickListener() {
            @Override public void onClick(View v) {
                v.setEnabled(false);
                HostLink.cmd("storage auto " + (auto ? "off" : "on"));
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(new Runnable() { @Override public void run() { if (!isFinishing()) ask(); } }, 350);
            }
        }));
        list.addView(ar);
        list.addView(Ui.rule(this, 1, Color.BLACK));
        String last = lastLine();
        if (last != null) line(last, 14, false);
        if (st.get("st.pending") != null) line("下次启动 VeraDroid 时会自动扩大到 " + size(n("pending")) + "。", 14, false);
    }

    private void pick(int v, int min, int max, int image) {
        target = Math.max(min, Math.min(max, v));
        if (targetView == null) return;
        targetView.setText(size(target) + (target == image ? "（现在）" : ""));
        boolean change = Math.abs(target - image) >= 16;
        applyBtn.setVisibility(change ? View.VISIBLE : View.INVISIBLE);   // 纯黑白：不画灰色的“不可用”按钮
        String hint;
        if (!change) hint = max <= image && min >= image ? "现在不能调整：已经是能用的最小 / 最大值。" : "选一个大小，再点“调整”。";
        else if (target > image && "1".equals(st.get("st.online"))) hint = "扩大 " + size(target - image) + "：直接完成，不用重启 Android。";
        else if (target > image) hint = "扩大 " + size(target - image) + "：Android 要关闭再重新启动，约几分钟。";
        else hint = "缩小 " + size(image - target) + "，这些空间还给 Kindle：Android 要关闭再重新启动，约几分钟。";
        hintView.setText(hint);
    }

    private void confirmResize(final int image) {
        final int to = target;
        boolean online = to > image && "1".equals(st.get("st.online"));
        String msg = online
            ? "把 Android 存储从 " + size(image) + " 扩大到 " + size(to) + "。不用重启，几秒到一分钟完成。"
            : "把 Android 存储从 " + size(image) + (to > image ? " 扩大到 " : " 缩小到 ") + size(to) + "。\n\n"
              + "Android 会关闭，屏幕上显示进度，完成后自动重新启动（约几分钟）。正在运行的应用会被关掉。\n"
              + "期间请不要关机或重启 Kindle；电量低时请先充电。书和应用数据不受影响。";
        new VeraDialog.Builder(this).title("调整存储空间？").message(msg).negative("取消", null)
            .positive("调整", new Runnable() { @Override public void run() { send(to); } }).show();
    }

    private void send(final int to) {
        final File rf = new File(RESULT);
        final boolean hadOld = rf.isFile();
        final long oldMod = hadOld ? rf.lastModified() : 0L;
        final long oldLen = hadOld ? rf.length() : -1L;
        final boolean deletedOld = !hadOld || rf.delete();
        HostLink.cmd("storage resize " + to);
        list.removeAllViews();
        line("正在调整，请稍候…", 20, true);
        line("在线扩大时这里会停留几十秒（Kindle 存储是 FAT 格式时要真的写满新增的空间）。", 14, false);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            int n = 0;
            boolean sawGone = deletedOld;
            @Override public void run() {
                if (isFinishing()) return;
                File f = new File(RESULT);
                if (!f.isFile()) {
                    sawGone = true;
                } else if (sawGone || f.lastModified() != oldMod || f.length() != oldLen) {
                    Map<String, String> r = HostLink.kv(RESULT);
                    if ("1".equals(r.get("ok")) && "offline".equals(r.get("mode"))) return;   // Android 马上被关掉
                    if ("1".equals(r.get("ok"))) VeraToast.show(StorageActivity.this, "已调整为 " + size(to));
                    else VeraToast.show(StorageActivity.this, "没有调整：" + reason(r.get("error")));
                    target = -1; ask(); return;
                }
                if (++n > 1500) { ask(); return; }
                handler.postDelayed(this, 400);
            }
        }, 400);
    }

    static String reason(String e) {
        if (e == null) return "宿主没有答复";
        if (e.equals("small")) return "比已经用掉的空间加安全余量还小";
        if (e.equals("nospace")) return "Kindle 剩余空间不够（要给 Kindle 留 256 MB）";
        if (e.equals("fatlimit")) return "Kindle 存储是 FAT 格式，单个文件最大 4 GB";
        if (e.equals("toobig")) return "超过上限";
        if (e.equals("same")) return "和现在一样大";
        if (e.equals("busy")) return "上一次调整还没结束";
        if (e.equals("battery")) return "电量太低，请先充电";
        if (e.equals("shared")) return "这台机器与 Kindle 共用空间，无需调整";
        if (e.equals("notool")) return "缺少调整工具";
        if (e.equals("extend")) return "Kindle 存储写不进去（空间不够？）";
        if (e.equals("fsck") || e.equals("fsck-after")) return "文件系统检查没通过";
        if (e.equals("interrupted")) return "上次被中断，已恢复原样";
        return e;
    }

    private String lastLine() {
        String r = st.get("st.last.result");
        if (r == null) return null;
        String op = "grow".equals(st.get("st.last.op")) ? "扩大" : "shrink".equals(st.get("st.last.op")) ? "缩小" : "调整";
        String when = st.get("st.last.time") == null ? "" : st.get("st.last.time") + " ";
        if (r.equals("ok")) return "上次：" + when + op + "到 " + size(n("last.to")) + "，成功。";
        if (r.equals("rolledback")) return "上次：" + when + op + "被中断，已恢复原样（" + size(n("last.to")) + "），数据没有变化。";
        return "上次：" + when + op + "没有完成（" + reason(st.get("st.last.reason")) + "），数据没有变化。";
    }

    private void line(String s, float sp, boolean bold) {
        TextView t = Ui.text(this, s, sp, bold);
        t.setPadding(0, dp(6), 0, dp(6));
        list.addView(t);
    }

    private void bar(String name, int used, int total, String detail) {
        TextView h = Ui.text(this, name, 18, true);
        h.setPadding(0, dp(10), 0, dp(4));
        list.addView(h);
        if (Blocks.on(this)) {   // 方块风格：20 格的方块级别条（只看不点）
            Blocks.BlockBar bb = new Blocks.BlockBar(this, 20);
            bb.setMax(Math.max(1, total));
            bb.setValue(Math.max(0, used));
            list.addView(bb, Blocks.match());
        } else {
        LevelBar lb = new LevelBar(this);
        lb.setEnabled(false);
        lb.setMax(Math.max(1, total));
        lb.setValue(Math.max(0, used));
        list.addView(lb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
        }
        TextView d = Ui.text(this, detail, 15, false);
        d.setPadding(0, dp(4), 0, dp(8));
        list.addView(d);
    }

    private int dp(int v) { return Ui.dp(this, v); }

    @Override protected void onResume() {
        super.onResume();
        StatusBarService.setSuppressed(true);
        StatusBarService.refresh(this);
        ask();
    }
    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        StatusBarService.setSuppressed(false);
        StatusBarService.refresh(this);
        super.onPause();
    }
}
