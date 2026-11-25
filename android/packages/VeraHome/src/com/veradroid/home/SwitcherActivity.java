// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

/** Switch between the apps that are running (recent tasks), or go to the home screen. */
public class SwitcherActivity extends Activity {
    /** 包名 -> 名称 / 图标，进程内缓存。应用更新后名称图标可能变：装卸应用时 HomeActivity 会清掉。 */
    static final java.util.Map<String, String> LABELS = new java.util.HashMap<String, String>();
    static final java.util.Map<String, android.graphics.drawable.Drawable.ConstantState> ICONS = new java.util.HashMap<String, android.graphics.drawable.Drawable.ConstantState>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        w.setWindowAnimations(0);
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);   // no dark shade behind (e-ink: it would show as a gray block)
        setFinishOnTouchOutside(true);

        LinearLayout root = new LinearLayout(this);
        this.root = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.HORIZONTAL);
        hd.setGravity(Gravity.CENTER_VERTICAL);
        hd.addView(new Icons.IconView(this, Icons.Kind.SWITCH), new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        android.widget.TextView ht = Ui.text(this, "切换应用", 22, true);
        ht.setPadding(Ui.dp(this, 10), 0, 0, 0);
        hd.addView(ht);
        root.addView(hd);
        root.addView(Ui.rule(this, 2, Color.BLACK));
        blk = Blocks.on(this);
        if (blk) {   // 方块风格：整张单子是双线框 + 投影，标题上面一行英文小字，标题下面一条粗黑线
            hd.removeView(ht);
            hd.addView(Blocks.titled(this, "Switch apps", "切换应用", 22, true));
            ((LinearLayout.LayoutParams) hd.getChildAt(0).getLayoutParams()).rightMargin = Ui.dp(this, 10);
        }

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = blk ? new PageScrollView(this) : new ScrollView(this);
        sv.addView(list);
        // 列表可以长过一屏（一页一页翻）；底下的内存和“全部关闭”固定在列表下面，不跟着翻走（weight：放不下时先压列表）
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footBox = new LinearLayout(this);
        footBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(footBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fill();
        if (blk) {   // 方块风格：单子左右留出边，框和投影整个看得见；单子以外是透明的（下面的应用照旧）
            setContentView(Blocks.sheet(this, root));
        } else
        setContentView(root);
        Perf.measure(root, "switcher");
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout list, footBox;
    private View root;
    private boolean blk;

    /** 正在运行的应用 + “桌面”。每次打开都重新取（任务会变），窗口和外框留着复用。 */
    private void fill() {
        list.removeAllViews();
        final PackageManager pm = getPackageManager();
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int n = 0;
        try {
            @SuppressWarnings("deprecation")
            List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(12, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (final ActivityManager.RecentTaskInfo t : tasks) {
                if (t.baseIntent == null) continue;
                ComponentName cn = t.baseIntent.getComponent();
                if (cn == null) continue;
                String pkg = cn.getPackageName();
                if (pkg.equals(getPackageName())) continue;
                String label = LABELS.get(pkg);
                android.graphics.drawable.Drawable icon = null;
                android.graphics.drawable.Drawable.ConstantState cs = ICONS.get(pkg);
                if (label == null || cs == null) {
                    // 第一次见到这个应用才去问 PackageManager、解码图标；之后用进程里缓存的（切换器每次打开都要快）
                    try {
                        label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                        icon = pm.getApplicationIcon(pkg);
                        LABELS.put(pkg, label);
                        if (icon.getConstantState() != null) ICONS.put(pkg, icon.getConstantState());
                    } catch (Exception ignored) { if (label == null) label = pkg; }
                } else icon = cs.newDrawable(getResources());
                final Intent open = new Intent(t.baseIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                final int id = t.id;
                final String tpkg = pkg;
                // 关闭用 persistentId：开机从磁盘恢复、还没起来过的任务 id 是 -1（7.1 getRecentTasks），removeTask 要的是 persistentId
                list.addView(item(label, null, icon, tpkg, t.persistentId, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // 和桌面点图标一样：先让宿主换好这个应用的显示参数 / 显示密度、显示桥挡住纯色的启动窗口，再把任务调到前面
                        // （以前只靠任务栈通知，宿主晚 0.1–5 秒才知道；多看要换密度，阅读页会先按旧密度重建一次、再重建一次）
                        if (AppProf.host()) { HostService.launchedPkg = tpkg; AppProf.launching(tpkg, id >= 0); }
                        try {
                            if (id >= 0) am.moveTaskToFront(id, 0);
                            else startActivity(open);
                        } catch (Exception e) { startActivity(open); }
                        overridePendingTransition(0, 0);
                        finish();
                    }
                }));
                n++;
            }
        } catch (Throwable ignored) { }
        if (n == 0) {
            android.widget.TextView none = Ui.text(this, "没有正在运行的应用", 18, false);
            none.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
            list.addView(none);
        }
        list.addView(item("桌面", Icons.Kind.GRID, null, null, -1, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(HomeActivity.homeIntent(SwitcherActivity.this));
                finish();
            }
        }));
        footBox.removeAllViews();
        footBox.addView(footer(n));
    }

    // ------------------------------------------------------------------ 关闭 / 锁 / 全部关闭

    /**
     * 底下一行：左边“可用 / 总内存”，右边“全部关闭”。点垃圾桶不问，锁着的留下，关完回桌面。
     * 内存只在打开切换器、关掉一个应用以后各读一次（/proc/meminfo），不定时刷新。
     */
    private View footer(int apps) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(0, Ui.dp(this, 12), 0, 0);
        mem = Ui.text(this, memLine(), 15, false);
        f.addView(mem, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (apps > 0) {
            View.OnClickListener all = new View.OnClickListener() { @Override public void onClick(View v) { closeAll(); } };
            f.addView(blk ? Blocks.button(this, "全部关闭", false, all) : Ui.button(this, "全部关闭", 16, false, all));
        }
        return f;
    }

    private android.widget.TextView mem;

    static String memLine() {
        long total = 0, avail = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"));
            try {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("MemTotal:")) total = Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
                    else if (l.startsWith("MemAvailable:")) avail = Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
                }
            } finally { r.close(); }
        } catch (Throwable ignored) { }
        return total <= 0 ? "" : "可用内存 " + avail + " MB / 共 " + total + " MB";
    }

    /** 这个应用能不能在切换器里关：VeraHome、输入法、系统应用不关（关了也会马上被拉起来）。 */
    private boolean closable(String pkg) {
        return pkg != null && !pkg.equals(getPackageName()) && Cleaner.userApp(this, pkg);
    }

    /** 关掉一个应用：把它的任务从最近任务里拿掉，再在后台结束它的进程（和“清后台”同一套：有前台服务的不杀）。 */
    private void close(final String pkg, int taskId, View row) {
        removeTask(taskId);
        if (row != null && row.getParent() == list) list.removeView(row);
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() { @Override public void run() {
            Cleaner.closeSync(SwitcherActivity.this, pkg);
            h.post(new Runnable() { @Override public void run() { if (mem != null) mem.setText(memLine()); } });
        } }, "VeraSwitchClose").start();
    }

    /** 全部关闭：锁着的（保持后台）留下，其余的关掉，然后回桌面，关完报一句关了几个、多出多少内存。 */
    private void closeAll() {
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final java.util.List<String> pkgs = new java.util.ArrayList<String>();
        try {
            @SuppressWarnings("deprecation")
            List<ActivityManager.RecentTaskInfo> tasks = am.getRecentTasks(24, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            for (ActivityManager.RecentTaskInfo t : tasks) {
                if (t.baseIntent == null || t.baseIntent.getComponent() == null) continue;
                String pkg = t.baseIntent.getComponent().getPackageName();
                if (!closable(pkg) || Cleaner.keepAlive(this, pkg)) continue;
                removeTask(t.persistentId);   // 恢复出来的任务 t.id 是 -1
                if (!pkgs.contains(pkg)) pkgs.add(pkg);
            }
        } catch (Throwable ignored) { }
        final android.content.Context app = getApplicationContext();
        startActivity(HomeActivity.homeIntent(this));   // MAIN / HOME: the existing home task (perf/general a357cf1)
        finish();
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() { @Override public void run() {
            long before = Cleaner.memAvailableMb();
            int n = 0;
            for (String p : pkgs) if (Cleaner.closeSync(app, p)) n++;
            final int closed = pkgs.size();
            final long freed = Math.max(0, Cleaner.memAvailableMb() - before);
            h.post(new Runnable() { @Override public void run() {
                VeraToast.show(app, closed == 0 ? "没有可以关闭的应用（锁着的不关）" : "已关闭 " + closed + " 个应用，多出约 " + freed + " MB 内存");
            } });
        } }, "VeraSwitchCloseAll").start();
    }

    /** IActivityManager.removeTask（7.1 隐藏接口，要 REMOVE_TASKS，VeraHome 是平台签名）。 */
    private static void removeTask(int taskId) {
        if (taskId < 0) return;
        try {
            Object amn = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
            amn.getClass().getMethod("removeTask", int.class).invoke(amn, taskId);
        } catch (Throwable t) { android.util.Log.w("VeraHome", "switcher removeTask " + taskId + ": " + t); }
    }

    /** 切换器常驻（singleInstance）：关掉时把它的任务放到后面，下次打开回到这个实例，只重新填列表。 */
    @Override protected void onResume() {
        super.onResume();
        Panel.closeOnDraw(root);
    }

    @Override public void finish() {
        if (!moveTaskToBack(true)) super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Perf.measure(root, "switcher (reused)");
        fill();
    }

    private View item(String label, Icons.Kind kind, android.graphics.drawable.Drawable icon, final String pkg, final int taskId, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        if (kind != null) {
            r.addView(new Icons.IconView(this, kind), new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        }
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            r.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        }
        android.widget.TextView t = Ui.text(this, label, blk ? 20 : 22, blk);
        t.setPadding(Ui.dp(this, (icon != null || kind != null) ? 16 : 0), 0, 0, 0);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.setOnClickListener(l);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        if (closable(pkg)) {
            // 锁 = 保持后台（和应用长按菜单里的是同一个开关）：点一下就换，不问；锁着的不会被“全部关闭”和自动清理关掉
            final View lock = Icons.bar(this, Cleaner.keepAlive(this, pkg) ? Icons.Kind.LOCK : Icons.Kind.UNLOCK, 26, null);
            lock.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                boolean on = !Cleaner.keepAlive(SwitcherActivity.this, pkg);
                Cleaner.setKeepAlive(SwitcherActivity.this, pkg, on);
                ((Icons.IconView) ((ViewGroup) lock).getChildAt(0)).setKind(on ? Icons.Kind.LOCK : Icons.Kind.UNLOCK);
            } });
            lock.setContentDescription("锁定");
            r.addView(lock);
            View x = Icons.bar(this, Icons.Kind.CLOSE, 26, new View.OnClickListener() {
                @Override public void onClick(View v) { close(pkg, taskId, box); }
            });
            x.setContentDescription("关闭");
            LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            xl.leftMargin = Ui.dp(this, 6);
            r.addView(x, xl);
        }
        box.addView(r);
        box.addView(blk ? Blocks.hair(this) : Ui.rule(this, 1, Color.LTGRAY));
        if (blk) { r.setClickable(true); Blocks.fast(r); }
        return box;
    }
}
