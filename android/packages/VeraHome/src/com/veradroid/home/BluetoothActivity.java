// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 蓝牙翻页器：扫描、配对、连接、取消配对，选翻页键发成什么键。
 * 蓝牙芯片和协议栈归 Kindle（com.lab126.btfd），Android 里没有蓝牙服务（BluetoothAdapter 扫不到任何东西），
 * 所以这里只是界面：命令经 FIFO 发给宿主（"bt scan" / "bt bond MAC" …，见 host/boot/lib/bt.sh），
 *   ctl/bt.list    名字\tMAC\t已配对\t已连接，一行一个（宿主扫描时每 3 秒重写一次）
 *   ctl/bt.result  这次操作：state=running|done，op，mac，ok，error，secs
 *   ctl/status     btsupported、bton、bt（按键桥在跑）、btdev、btmap、btswap、btbridge
 * 不轮询：inotify 等文件被替换。列表只在内容变了时整块重搭（一帧）。
 */
public class BluetoothActivity extends Activity {
    private static final String[] MAP_V = { "volume", "page", "dpad", "raw" };
    private static final String[] MAP_L = { "音量键", "翻页键", "方向键", "原样" };
    private static final java.util.regex.Pattern MAC = java.util.regex.Pattern.compile("^([0-9A-F]{2}:){5}[0-9A-F]{2}$");

    private final Handler handler = new Handler();
    private LinearLayout list;
    private TextView status, power, bridge;
    private View powerBtn, bridgeBtn;
    private Seg map, swap;
    private CtlWatch.Listener watch;
    private String lastList = null, lastStatus = null, lastResult = null;
    private boolean busy;              // 宿主正在做一个蓝牙操作
    private long busySince;
    private int busySecs;
    private String busyOp = "", busyName = "";
    private final List<String[]> devices = new ArrayList<String[]>();

    static void open(Context c) {
        Intent i = new Intent(c, BluetoothActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        c.startActivity(i);
    }

    /** 宿主报告这台机器有 Kindle 的蓝牙栈（旧宿主没有这个键：当没有）。 */
    static boolean supported() {
        return "1".equals(HostLink.kv(HostLink.CTL + "status").get("btsupported"));
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        w.setWindowAnimations(0);
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        blk = Blocks.on(this);
        if (blk) Blocks.headInto(this, root, "Bluetooth / page turner", "蓝牙翻页器", Blocks.button(this, "扫描", true, new View.OnClickListener() {
            @Override public void onClick(View v) { scan(); }
        }));
        else {
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL); hd.setGravity(Gravity.CENTER_VERTICAL);
        hd.addView(Icons.bar(this, Icons.Kind.BACK, 28, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }));
        TextView t = Ui.text(this, "蓝牙翻页器", 24, true); t.setPadding(dp(10), 0, 0, 0);
        hd.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hd.addView(Ui.button(this, "扫描", 16, true, new View.OnClickListener() {
            @Override public void onClick(View v) { scan(); }
        }));
        root.addView(hd);
        root.addView(Ui.rule(this, 2, Color.BLACK));
        }

        // 蓝牙开关
        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL); pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(0, dp(8), 0, dp(4));
        power = Ui.text(this, "", 16, true);
        pr.addView(power, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        powerBtn = Ui.button(this, "打开蓝牙", 15, false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = "1".equals(HostLink.kv(HostLink.CTL + "status").get("bton"));
                send(on ? "disable" : "enable", null, on ? "正在关闭蓝牙…" : "正在打开蓝牙…（最多 15 秒）");
            }
        });
        pr.addView(powerBtn);
        root.addView(pr);

        status = Ui.text(this, "正在读取…", 15, false); status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);
        root.addView(Ui.rule(this, 1, Color.BLACK));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        PageScrollView sv = new PageScrollView(this);
        sv.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 按键映射（宿主 bt map / bt swap，写进 settings.conf，按键桥立即换，不用重启）
        root.addView(Ui.rule(this, 1, Color.BLACK));
        TextView ml = Ui.text(this, "翻页器的“下一页 / 上一页”发给应用的是", 15, true); ml.setPadding(0, dp(10), 0, dp(6));
        root.addView(ml);
        map = new Seg(this, MAP_L, 15, new Seg.Listener() {
            @Override public void onPick(int i) { HostLink.cmd("bt map " + MAP_V[i]); }
        });
        root.addView(map);
        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL); sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPadding(0, dp(8), 0, 0);
        sr.addView(Ui.text(this, "方向", 15, true), new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        swap = new Seg(this, new String[] { "正常", "反过来" }, 15, new Seg.Listener() {
            @Override public void onPick(int i) { HostLink.cmd("bt swap " + i); }
        });
        sr.addView(swap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(sr);
        TextView mh = Ui.text(this, "微信读书等阅读应用：在阅读设置里打开“音量键翻页”。应用不认音量键时改成“翻页键”或“方向键”。", 13, false);
        mh.setPadding(0, dp(6), 0, dp(2));
        root.addView(mh);
        LinearLayout br = new LinearLayout(this);
        br.setOrientation(LinearLayout.HORIZONTAL); br.setGravity(Gravity.CENTER_VERTICAL);
        br.setPadding(0, dp(4), 0, 0);
        bridge = Ui.text(this, "", 14, false);
        br.addView(bridge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bridgeBtn = Ui.button(this, "打开", 14, false, new View.OnClickListener() {
            @Override public void onClick(View v) { HostLink.cmd("bt bridge auto"); }
        });
        br.addView(bridgeBtn);
        root.addView(br);

        setContentView(root);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        renderStatus(true);
        renderList(read("bt.list"));
        watch = new CtlWatch.Listener() {
            @Override public void onCtl(final String path) {
                if (!"bt.list".equals(path) && !"bt.result".equals(path) && !"status".equals(path)) return;
                handler.post(new Runnable() { @Override public void run() { changed(path); } });
            }
        };
        CtlWatch.add(watch);
        // 上次没做完的操作（比如扫描中退出又进来）：接着显示进度
        File rf = new File(HostLink.CTL + "bt.result");
        Map<String, String> r = HostLink.kv(rf.getPath());
        if ("running".equals(r.get("state")) && System.currentTimeMillis() - rf.lastModified() < (HostLink.num(r, "secs", 30) + 5) * 1000L) result();
        else status.setText("");
        HostLink.cmd("status");
        HostLink.cmd("bt list");
        // 宿主没回 status（旧版宿主）：几秒后按现有文件判断
        handler.postDelayed(new Runnable() { @Override public void run() { renderStatus(true); } }, 5000);
    }

    @Override protected void onDestroy() {
        if (watch != null) CtlWatch.remove(watch);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void changed(String file) {
        if (isFinishing()) return;
        if ("bt.list".equals(file)) renderList(read("bt.list"));
        else if ("bt.result".equals(file)) result();
        else renderStatus(false);
    }

    // ---- 宿主状态：蓝牙开关、按键桥、映射 ----
    private void renderStatus(boolean initial) {
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        String key = new java.util.TreeMap<String, String>(st).toString();
        if (key.equals(lastStatus) && !initial) return;
        lastStatus = key;
        String sup = st.get("btsupported");
        if (sup == null) {
            power.setText("宿主程序太旧，不支持在这里配对");
            powerBtn.setVisibility(View.GONE);
            status.setText("请先更新 VeraDroid；或回到 Kindle，在“设置 → 蓝牙设备”里配对。");
            return;
        }
        if (!"1".equals(sup)) {
            power.setText("这台 Kindle 没有蓝牙");
            powerBtn.setVisibility(View.GONE);
            return;
        }
        boolean on = "1".equals(st.get("bton"));
        set(power, on ? "蓝牙：已打开" : "蓝牙：已关闭");
        ((android.widget.Button) powerBtn).setText(on ? "关闭" : "打开蓝牙");
        powerBtn.setVisibility(View.VISIBLE);
        String m = st.get("btmap");
        int mi = 0;
        for (int i = 0; i < MAP_V.length; i++) if (MAP_V[i].equals(m)) mi = i;
        map.select(mi);
        swap.select("1".equals(st.get("btswap")) ? 1 : 0);
        String dev = st.get("btdev"), names = "";
        if (dev != null && dev.length() > 0) {
            for (String d : dev.split(",")) {
                int c = d.indexOf(':');
                String n = c >= 0 ? d.substring(c + 1) : d;
                if (n.equals("kindle-input-pt")) n = "Kindle 翻页器";
                names += (names.length() > 0 ? "、" : "") + n;
            }
        }
        String bb = st.get("btbridge");
        if ("1".equals(st.get("bt"))) {
            set(bridge, names.length() > 0 ? "按键转发：运行中，正在接收 " + names : "按键转发：运行中，等翻页器连上（按一下它的键）");
            bridgeBtn.setVisibility(View.GONE);
        } else if ("0".equals(bb)) {
            set(bridge, "按键转发：已关闭");
            bridgeBtn.setVisibility(View.VISIBLE);
        } else {
            set(bridge, "按键转发：未启动（配对或连上翻页器后自动启动）");
            bridgeBtn.setVisibility(View.GONE);
        }
    }

    // ---- 设备列表 ----
    private void renderList(String raw) {
        if (raw.equals(lastList)) return;
        lastList = raw;
        devices.clear();
        for (String l : raw.split("\n")) {
            String[] f = l.split("\t", -1);
            if (f.length < 4 || !MAC.matcher(f[1]).matches()) continue;
            devices.add(f);
        }
        list.removeAllViews();
        int np = 0, nn = 0;
        for (String[] d : devices) if ("1".equals(d[2])) np++; else nn++;
        header("已配对");
        if (np == 0) hint("还没有配对过的设备。");
        for (String[] d : devices) if ("1".equals(d[2])) list.addView(row(d));
        header("附近的设备");
        if (nn == 0) hint(busy && "scan".equals(busyOp) ? "正在找…" : "让翻页器进入配对模式（多数是长按开关键或配对键几秒，指示灯快闪），再点右上角“扫描”。");
        for (String[] d : devices) if (!"1".equals(d[2])) list.addView(row(d));
        hint("点设备：配对 / 连接；点已连接的设备：断开；长按已配对的设备：取消配对。");
    }

    private boolean blk;

    private void header(String s) {
        if (blk) {
            TextView h = Blocks.header(this, s);
            LinearLayout.LayoutParams lp = Blocks.gap(this, 12);
            lp.bottomMargin = dp(2);
            list.addView(h, lp);
            return;
        }
        TextView h = Ui.text(this, s, 14, true);
        h.setPadding(0, dp(14), 0, dp(4));
        list.addView(h);
    }

    private void hint(String s) {
        TextView t = Ui.text(this, s, 14, false);
        t.setPadding(0, dp(8), 0, dp(8));
        list.addView(t);
    }

    private View row(final String[] d) {
        final String name = d[0], mac = d[1];
        final boolean paired = "1".equals(d[2]), conn = "1".equals(d[3]);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(4), dp(12), dp(4), dp(12));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tn = Ui.text(this, name, 19, conn);
        tn.setSingleLine(true);
        tn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tn);
        col.addView(Ui.text(this, mac, 13, false));
        line.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(Ui.text(this, conn ? "已连接" : paired ? "未连接 · 点击连接" : "点击配对", 16, conn));
        r.addView(line);
        r.addView(blk ? Blocks.hair(this) : Ui.rule(this, 1, Color.BLACK));
        if (blk) Blocks.fast(r);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!paired) send("bond " + mac, name, "正在配对 " + name + "…（最多 45 秒；有的翻页器要先按一下键）");
                else if (!conn) send("connect " + mac, name, "正在连接 " + name + "…（没反应就按一下翻页器的键）");
                else new VeraDialog.Builder(BluetoothActivity.this).title(name).message("已连接。断开后按翻页器的键通常会自己再连上。")
                        .negative("取消", null)
                        .positive("断开", new Runnable() { @Override public void run() { send("disconnect " + mac, name, "正在断开 " + name + "…"); } })
                        .show();
            }
        });
        if (paired) r.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                new VeraDialog.Builder(BluetoothActivity.this).title("取消配对").message("取消和“" + name + "”的配对？以后要用得重新配对（翻页器要再进入配对模式）。")
                        .negative("不了", null)
                        .positive("取消配对", new Runnable() { @Override public void run() { send("unbond " + mac, name, "正在取消配对 " + name + "…"); } })
                        .show();
                return true;
            }
        });
        return r;
    }

    // ---- 操作 ----
    private void scan() {
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        if (st.get("btsupported") == null) { status.setText("宿主程序太旧，不支持扫描。请先更新 VeraDroid。"); return; }
        if (!"1".equals(st.get("bton"))) { status.setText("蓝牙没有打开：先点上面的“打开蓝牙”。"); return; }
        send("scan", null, "正在扫描附近的设备…");
    }

    private void send(String cmd, String name, String msg) {
        if (busy && System.currentTimeMillis() - busySince < (busySecs + 10) * 1000L) {
            status.setText("请等当前操作做完（" + opName(busyOp) + "）");
            return;
        }
        String[] p = cmd.split(" ");
        if (p.length > 1 && !MAC.matcher(p[1]).matches()) { status.setText("设备地址不对"); return; }
        busy = true; busySince = System.currentTimeMillis(); busySecs = "scan".equals(p[0]) ? 18 : 45;
        busyOp = p[0]; busyName = name == null ? "" : name;
        status.setText(msg);
        HostLink.cmd("bt " + cmd);
        tick();
    }

    /** 进度：每 3 秒改一次状态行的秒数（墨水屏只刷这一行）；宿主写回结果就停。 */
    private final Runnable ticker = new Runnable() { @Override public void run() { tick(); } };
    private void tick() {
        handler.removeCallbacks(ticker);
        if (!busy) return;
        int s = (int) ((System.currentTimeMillis() - busySince) / 1000);
        if (s > busySecs + 15) {   // 宿主一直没回：放弃等
            busy = false;
            status.setText(opName(busyOp) + "没有回应。宿主程序可能太旧，或 Kindle 的蓝牙服务没响应。");
            return;
        }
        if (s >= 3) {
            String base = status.getText().toString().replaceAll("（已等 \\d+ 秒）$", "");
            status.setText(base + "（已等 " + (s / 3 * 3) + " 秒）");
        }
        handler.postDelayed(ticker, 3000);
    }

    private void result() {
        Map<String, String> r = HostLink.kv(HostLink.CTL + "bt.result");
        String key = new java.util.TreeMap<String, String>(r).toString();
        if (key.equals(lastResult)) return;
        lastResult = key;
        String op = r.get("op") == null ? "" : r.get("op");
        String name = nameOf(r.get("mac"));
        if ("running".equals(r.get("state"))) {
            // 宿主开始做了（也可能是进页面前别人发起的）：按宿主给的预计时长等
            if (!busy) { busy = true; busySince = System.currentTimeMillis(); busyOp = op; status.setText(opName(op) + "中…"); }
            busySecs = HostLink.num(r, "secs", busySecs);
            tick();
            return;
        }
        boolean wasBusy = busy;
        busy = false;
        handler.removeCallbacks(ticker);
        boolean ok = "1".equals(r.get("ok"));
        String e = r.get("error") == null ? "" : r.get("error");
        String msg;
        if ("busy".equals(e)) { msg = "宿主正在做另一项蓝牙操作，请稍等再试。"; busy = wasBusy; }
        else if ("off".equals(e)) msg = "蓝牙没有打开：先点上面的“打开蓝牙”。";
        else if ("unsupported".equals(e)) msg = "这台 Kindle 没有蓝牙。";
        else if ("lipc".equals(e)) msg = "Kindle 的蓝牙服务没有响应，请稍后再试；一直不行就回 Kindle 的设置里关开一次蓝牙。";
        else if ("badmac".equals(e)) msg = "设备地址不对。";
        else if ("scan".equals(op)) msg = ok ? "扫描完成：找到 " + devices.size() + " 个设备。没看到翻页器就让它重新进入配对模式再扫一次。" : "扫描失败。";
        else if ("bond".equals(op)) {
            if (ok) msg = "已配对并连接：" + name + "。打开阅读应用按翻页器试试。";
            else if ("notconnected".equals(e)) msg = "已配对：" + name + "。按一下翻页器的键让它连上。";
            else if ("failed".equals(e)) msg = "配对失败：" + name + " 拒绝了配对。让它重新进入配对模式再点一次。";
            else msg = "配对超时：确认 " + name + " 在配对模式（指示灯快闪），再点一次。";
        }
        else if ("connect".equals(op)) msg = ok ? "已连接：" + name : "notpaired".equals(e) ? "还没配对，先点它配对。" : "没连上 " + name + "：按一下翻页器的键唤醒它，再点一次。";
        else if ("disconnect".equals(op)) msg = ok ? "已断开：" + name : "断开超时。";
        else if ("unbond".equals(op)) msg = ok ? "已取消配对：" + name : "取消配对超时，请再试一次。";
        else if ("power".equals(op)) msg = ok ? ("1".equals(r.get("on")) ? "蓝牙已打开。" : "蓝牙已关闭。") : "蓝牙开关没有响应，请回 Kindle 的设置里试试。";
        else msg = "";
        status.setText(msg);
        HostLink.cmd("status");
    }

    private String nameOf(String mac) {
        if (mac == null) return "";
        for (String[] d : devices) if (d[1].equals(mac)) return d[0];
        return busyName.length() > 0 ? busyName : mac;
    }

    private static String opName(String op) {
        if ("scan".equals(op)) return "扫描";
        if ("bond".equals(op)) return "配对";
        if ("connect".equals(op)) return "连接";
        if ("disconnect".equals(op)) return "断开";
        if ("unbond".equals(op)) return "取消配对";
        if ("enable".equals(op) || "disable".equals(op) || "power".equals(op)) return "开关蓝牙";
        return "蓝牙操作";
    }

    private static void set(TextView t, String s) { if (!s.contentEquals(t.getText())) t.setText(s); }

    private String read(String name) {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(HostLink.CTL + name), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
        } catch (Exception ignored) { }
        return sb.toString();
    }

    private int dp(int v) { return Ui.dp(this, v); }

    @Override public void onBackPressed() { finish(); }

    @Override protected void onResume() { super.onResume(); Panel.closeOnDraw(getWindow().getDecorView()); }
    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }
}
