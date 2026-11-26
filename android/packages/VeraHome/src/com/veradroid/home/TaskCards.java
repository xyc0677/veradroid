package com.veradroid.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * 宿主写的进度状态文件（/dev/kfb/ctl/*.status，KEY=value，宿主 host/pw6/boot/lib/progress.sh 和 cmd.sh 写）做成的“方块”进度卡片：
 *   update.status   系统更新（安装器 bootstrap.sh、离线更新 localupdate.sh、桌面“检查并更新”）
 *   apkinst.status  安装 APK（传书网页 / 商店 / OTA 走宿主 install 命令，veradroid/apps 文件夹走 after_boot）
 *   compile.status  后台优化应用（compile.sh）
 *   netdiag.status  网络诊断（宿主 netdiag 命令）
 * 只靠 {@link CtlWatch}（ctl 目录唯一的 FileObserver）知道文件变了，不另开监听。墨水屏：一张卡片最多 2 秒重画一次，
 * 只给内容变了的字 setText；进行中的卡片每 5 秒走一次“已用时间”（只改那一行字）。
 */
final class TaskCards {
    static final String UPDATE = "update.status", APK = "apkinst.status", COMPILE = "compile.status", NET = "netdiag.status";

    private TaskCards() { }

    static Map<String, String> read(String name) {
        Map<String, String> m = HostLink.kv(HostLink.CTL + name);
        Map<String, String> r = new HashMap<String, String>();
        for (Map.Entry<String, String> e : m.entrySet()) r.put(e.getKey(), e.getValue().replace("\\n", "\n"));
        return r;
    }

    static long num(Map<String, String> m, String k, long def) {
        try { return Long.parseLong(m.get(k).trim()); } catch (Exception e) { return def; }
    }

    static String mmss(long s) {
        if (s < 0) s = 0;
        return s >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                         : String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    static long now() { return System.currentTimeMillis() / 1000; }

    static boolean active(String state) {
        return state != null && !"done".equals(state) && !"error".equals(state) && !"paused".equals(state) && state.length() > 0;
    }

    static String stepShort(String id) {
        if ("net".equals(id)) return "网络";
        if ("dl".equals(id)) return "下载";
        if ("copy".equals(id)) return "读取";
        if ("verify".equals(id)) return "校验";
        if ("unpack".equals(id)) return "解压";
        if ("backup".equals(id)) return "备份";
        if ("disk".equals(id)) return "建盘";
        if ("install".equals(id)) return "安装";
        if ("boot".equals(id)) return "开机";
        return id;
    }

    static String stepCaps(String id) {
        if ("net".equals(id)) return "Network check";
        if ("dl".equals(id)) return "Download";
        if ("copy".equals(id)) return "Offline package";
        if ("verify".equals(id)) return "Verify";
        if ("unpack".equals(id)) return "Unpack";
        if ("backup".equals(id)) return "Backup";
        if ("disk".equals(id)) return "System disk";
        if ("install".equals(id)) return "Install";
        if ("boot".equals(id)) return "First boot";
        return "System update";
    }

    /** 步骤列表一行：完成的“■ 下载”，当前的黑底白字，没到的“□ 校验”，中间“→”。 */
    static CharSequence stepLine(String steps, String cur, boolean finished) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (steps == null || steps.length() == 0) return b;
        String[] s = steps.split(",");
        int curIdx = -1;
        if (!finished && cur != null && cur.length() > 0) {
            for (int i = 0; i < s.length; i++) if (s[i].equals(cur)) { curIdx = i; break; }
        }
        for (int i = 0; i < s.length; i++) {
            if (i > 0) b.append("  →  ");
            boolean isCur = !finished && i == curIdx;
            int st = b.length();
            if (isCur) {
                b.append(" ").append(stepShort(s[i])).append(" ");
                b.setSpan(new BackgroundColorSpan(Color.BLACK), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new ForegroundColorSpan(Color.WHITE), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new StyleSpan(Typeface.BOLD), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                boolean done = finished || (curIdx > 0 && i < curIdx);
                b.append(done ? "■ " : "□ ").append(stepShort(s[i]));
            }
        }
        return b;
    }

    static void set(TextView t, CharSequence s) {
        CharSequence o = t.getText();
        if (o == null || !o.toString().equals(s == null ? "" : s.toString())) t.setText(s);
        int v = s == null || s.length() == 0 ? View.GONE : View.VISIBLE;
        if (t.getVisibility() != v) t.setVisibility(v);
    }

    /** 英文小字：用户关了“英文小字”就一直不显示。 */
    static void setCaps(TextView t, String en) {
        if (!Blocks.caps(t.getContext())) { if (t.getVisibility() != View.GONE) t.setVisibility(View.GONE); return; }
        set(t, en == null ? "" : en.toUpperCase(java.util.Locale.US));
    }

    /** 共同的骨架：带投影的框、黑底标题条、英文小字 + 已用时间、粗体标题 + 百分比、方块条、两行说明、步骤行。 */
    abstract static class Base extends LinearLayout implements CtlWatch.Listener {
        final String file;
        final Handler h = new Handler(Looper.getMainLooper());
        final TextView head, caps, elapsed, title, pct, l1, l2, steps;
        final Blocks.BlockBar bar;
        final LinearLayout extra;
        long lastDraw;
        boolean pending, attached;
        Map<String, String> m = new HashMap<String, String>();

        Base(Context c, String file, int blocks, boolean compact) {
            super(c);
            this.file = file;
            setOrientation(VERTICAL);
            setBackground(Blocks.frame(c));
            int st = Blocks.stroke(c), sh = Blocks.shade(c);
            setPadding(st, st, st + sh, st + sh);
            head = Blocks.header(c, "");
            addView(head, Blocks.match());
            LinearLayout in = new LinearLayout(c);
            in.setOrientation(VERTICAL);
            int p = Ui.dp(c, compact ? 10 : 14);
            in.setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 10));
            LinearLayout r0 = new LinearLayout(c);
            r0.setGravity(Gravity.CENTER_VERTICAL);
            caps = Blocks.cap(c, "");
            r0.addView(caps, Blocks.weight(1));
            elapsed = Ui.text(c, "", 12, true);
            r0.addView(elapsed);
            in.addView(r0, Blocks.match());
            LinearLayout r1 = new LinearLayout(c);
            r1.setGravity(Gravity.CENTER_VERTICAL);
            title = Ui.text(c, "", compact ? 17 : 22, true);
            r1.addView(title, Blocks.weight(1));
            pct = Ui.text(c, "", compact ? 18 : 26, true);
            r1.addView(pct);
            in.addView(r1, Blocks.match());
            bar = new Blocks.BlockBar(c, blocks);
            bar.noPercent();
            bar.setMax(100);
            LinearLayout.LayoutParams bp = Blocks.match(); bp.topMargin = Ui.dp(c, 6);
            in.addView(bar, bp);
            l1 = Ui.text(c, "", compact ? 14 : 16, true);
            l1.setPadding(0, Ui.dp(c, 6), 0, 0);
            in.addView(l1, Blocks.match());
            l2 = Ui.text(c, "", compact ? 12 : 14, false);
            l2.setPadding(0, Ui.dp(c, 4), 0, 0);
            in.addView(l2, Blocks.match());
            steps = Ui.text(c, "", compact ? 12 : 14, false);
            steps.setPadding(0, Ui.dp(c, 8), 0, 0);
            in.addView(steps, Blocks.match());
            extra = new LinearLayout(c);
            extra.setGravity(Gravity.CENTER_VERTICAL);
            in.addView(extra, Blocks.match());
            addView(in, Blocks.match());
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            CtlWatch.add(this);
            refresh();
        }

        @Override protected void onDetachedFromWindow() {
            attached = false;
            CtlWatch.remove(this);
            h.removeCallbacksAndMessages(null);
            pending = false;
            super.onDetachedFromWindow();
        }

        @Override public void onCtl(String name) {
            if (!file.equals(name)) return;
            h.post(new Runnable() { @Override public void run() { schedule(); } });
        }

        private final Runnable redraw = new Runnable() { @Override public void run() { pending = false; refresh(); } };
        private final Runnable tick = new Runnable() { @Override public void run() { if (attached) { tickElapsed(); h.postDelayed(this, 5000); } } };

        /** 最多 2 秒画一次：文件一秒写一次（下载时），墨水屏不跟着每秒刷。 */
        void schedule() {
            if (pending) return;
            long wait = 2000 - (System.currentTimeMillis() - lastDraw);
            if (wait <= 0) refresh();
            else { pending = true; h.postDelayed(redraw, wait); }
        }

        void refresh() {
            lastDraw = System.currentTimeMillis();
            m = read(file);
            bind(m);
            h.removeCallbacks(tick);
            if (attached && getVisibility() == VISIBLE && active(m.get("state"))) h.postDelayed(tick, 5000);
        }

        /** 已用时间：宿主上次写的 elapsed（或 start）加上写完到现在的秒数。 */
        long elapsedNow(Map<String, String> m) {
            long t = num(m, "time", 0), st = num(m, "start", 0), e = num(m, "elapsed", -1);
            if (e >= 0 && t > 0) return e + Math.max(0, now() - t);
            if (st > 0) return now() - st;
            return -1;
        }

        void tickElapsed() {
            long e = elapsedNow(m);
            set(elapsed, e >= 0 ? "已用 " + mmss(e) : "");
            long age = now() - num(m, "time", now());
            if (age > 90 && active(m.get("state"))) set(l2, staleText(age));
        }

        String staleText(long age) {
            return "已经 " + (age / 60) + " 分钟没有新的进度：可能已经中断。可以过一会儿再试一次，日志在 Kindle 的 veradroid/logs/install.log（老版本在根目录的 veradroid-install.log）。"; // root-legacy
        }

        void showIf(boolean v) { int x = v ? VISIBLE : GONE; if (getVisibility() != x) setVisibility(x); }

        abstract void bind(Map<String, String> m);
    }

    /** 系统更新（关于页的整张卡片；compact = 我的 页里的小卡片）。 */
    static final class Update extends Base {
        final boolean compact;
        Update(Context c, boolean compact) { super(c, UPDATE, 20, compact); this.compact = compact; setVisibility(GONE); }

        @Override void bind(Map<String, String> m) {
            String state = m.get("state");
            long age = now() - num(m, "time", 0);
            boolean act = active(state) && age < 3600;
            boolean recent = ("done".equals(state) || "error".equals(state)) && age < (compact ? 600 : 1800);
            showIf(act || recent);
            if (!(act || recent)) return;
            String mode = m.get("mode");
            String who = "auto".equals(mode) ? "后台自动更新" : "local".equals(mode) ? "离线更新" : "系统更新";
            String stepsCsv = m.get("steps");
            String step = m.get("step");
            int idx = (int) num(m, "index", 0), n = stepsCsv == null ? 0 : stepsCsv.split(",").length;
            if ("error".equals(state)) set(head, who + "没有完成 · 代码 " + (m.get("code") == null ? "?" : m.get("code")));
            else if ("done".equals(state)) set(head, "latest".equals(m.get("result")) ? who + " · 已是最新" : who + " · 完成");
            else set(head, who + (idx > 0 && n > 0 ? " · 第 " + idx + " 步 / 共 " + n + " 步" : ""));
            setCaps(caps, stepCaps(step));
            String label = m.get("label");
            set(title, label == null ? "正在准备" : label);
            long p = num(m, "pct", -1);
            set(pct, act && p >= 0 ? p + "%" : "");
            bar.setValue(p >= 0 ? (int) p : "done".equals(state) ? 100 : 0);
            bar.setVisibility(act || "done".equals(state) ? VISIBLE : GONE);
            set(l1, m.get("line1"));
            String two = m.get("line2");
            if (act && age > 90) two = staleText(age);
            if (compact && two != null && two.indexOf('\n') > 0) two = two.substring(0, two.indexOf('\n'));
            set(l2, two);
            set(steps, compact ? "" : stepLine(stepsCsv, step, "done".equals(state)));
            long e = elapsedNow(m);
            set(elapsed, act && e >= 0 ? "已用 " + mmss(e) : "");
        }
    }

    /** 安装 APK：正在装哪个、已用多久；装完 / 失败留 10 分钟。 */
    static final class Apk extends Base {
        Apk(Context c) { super(c, APK, 12, true); setVisibility(GONE); bar.setVisibility(GONE); }
        @Override void bind(Map<String, String> m) {
            String state = m.get("state");
            long age = now() - num(m, "time", 0);
            boolean act = "installing".equals(state) && age < 1800;
            boolean recent = ("done".equals(state) || "error".equals(state)) && age < 600;
            showIf(act || recent);
            if (!(act || recent)) return;
            String name = m.get("name") == null ? "" : m.get("name");
            set(head, act ? "正在安装应用" : "done".equals(state) ? "应用已装好" : "应用没有装上");
            setCaps(caps, "Install app");
            set(title, name);
            set(pct, "");
            long e = elapsedNow(m);
            set(elapsed, act && e >= 0 ? "已用 " + mmss(e) : "");
            set(l1, act ? "大的应用要装几分钟（系统在优化它），装好会自动出现在应用页" : "");
            String r = m.get("result");
            set(l2, "error".equals(state) ? r : "");
            set(steps, "");
        }
        @Override String staleText(long age) { return ""; }
    }

    /** 后台优化应用（compile.sh）：第几个 / 一共几个、应用名。 */
    static final class Compile extends Base {
        Compile(Context c) { super(c, COMPILE, 12, true); setVisibility(GONE); }
        @Override void bind(Map<String, String> m) {
            String state = m.get("state");
            long age = now() - num(m, "time", now());
            boolean run = "running".equals(state);
            // 用户点了“立即优化”（宿主写 manual=1）还在等：也显示出来，写明在等什么，不让人以为没反应
            boolean wait = !run && "1".equals(m.get("manual")) && ("waiting".equals(state) || "paused".equals(state));
            showIf(run || wait);
            if (!run && !wait) return;
            long i = num(m, "index", 0), n = num(m, "total", 0);
            if (wait) {
                String msg = m.get("msg");
                set(head, "等待优化应用" + (n > 0 ? " · 还有 " + Math.max(1, n - i) : ""));
                setCaps(caps, "Optimizing apps · waiting");
                String code = m.get("code");
                set(title, ("foreground".equals(code) || "running".equals(code)) ? label(getContext(), m.get("pkg")) : "");
                set(pct, "");
                bar.setValue(n > 0 ? (int) (i * 100 / n) : 0);
                set(l1, msg != null && msg.length() > 0 ? msg : "条件满足后马上开始");
                set(l2, ""); set(steps, ""); set(elapsed, "");
                return;
            }
            set(head, "正在优化应用" + (n > 0 ? " " + i + " / " + n : ""));
            setCaps(caps, "Optimizing apps");
            set(title, label(getContext(), m.get("pkg")));
            set(pct, n > 0 ? (i * 100 / n) + "%" : "");
            bar.setValue(n > 0 ? (int) (i * 100 / n) : 0);
            set(l1, "优化好的应用打开更快；在后台慢慢做，不影响使用");
            set(l2, "");
            set(steps, "");
            set(elapsed, "");
        }
        @Override String staleText(long age) { return ""; }
        static String label(Context c, String pkg) {
            if (pkg == null) return "";
            try {
                android.content.pm.PackageManager pm = c.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Throwable t) { return pkg; }
        }
    }

    /** 网络诊断（存储与连接）：按钮发宿主 netdiag，结果一行一项，最后一句结论。 */
    static final class Net extends Base {
        Net(Context c) {
            super(c, NET, 8, false);
            bar.setVisibility(GONE);
            TextView go = Blocks.button(c, "开始诊断", true, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    HostLink.cmd("netdiag");
                    set(head, "网络诊断 · 正在检查");
                    set(title, "正在检查…");
                    set(l1, "Wi-Fi → 域名解析 → 服务器 → 下载速度，大约 20 秒");
                    set(l2, "");
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            lp.topMargin = Ui.dp(c, 10);
            extra.addView(go, lp);
        }
        @Override void bind(Map<String, String> m) {
            String state = m.get("state");
            if (state == null) {
                set(head, "网络诊断");
                setCaps(caps, "Network check");
                set(title, "网络好不好，一查就知道");
                set(l1, "检查 Wi-Fi、域名解析、能不能连上 VeraDroid 服务器，再测几秒下载速度。只连我们自己的服务器。");
                set(l2, ""); set(pct, ""); set(steps, ""); set(elapsed, "");
                return;
            }
            boolean act = active(state);
            set(head, act ? "网络诊断 · 正在检查" : "网络诊断 · 结果");
            setCaps(caps, "Network check");
            String c = m.get("conclusion");
            set(title, act ? "正在检查…" : c == null ? "" : c);
            set(pct, "");
            set(l1, act ? m.get("line1") : m.get("lines"));
            set(l2, act ? m.get("lines") : m.get("line2"));
            set(steps, "");
            long e = elapsedNow(m);
            set(elapsed, act && e >= 0 ? "已用 " + mmss(e) : "");
        }
        @Override String staleText(long age) { return "诊断 " + age + " 秒没有结果：再点一次开始诊断"; }
    }

    /** 我的 页：后台在做的事（更新 / 装应用 / 优化应用），每样一张小卡片，没有就整块不显示。 */
    static LinearLayout strip(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(new Update(c, true), Blocks.gap(c, 8));
        l.addView(new Apk(c), Blocks.gap(c, 8));
        l.addView(new Compile(c), Blocks.gap(c, 8));
        return l;
    }
}
