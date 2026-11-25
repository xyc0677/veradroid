package com.veradroid.home;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 方块风格“我的”下面的设置页：用步进器“‹ 值 ›”和方块级别条，不用滑条；每页几个黑底标题分组。
 * 这里只放最常用的几项，偏好键和宿主设置键都是现有的（KFB_SMOOTH / KFB_FLASH / KFB_CONTRAST / KFB_INVERT /
 * RETURN_MODE / TIME_12_24、light / warmth / sleeptime 命令）；其余的从每页最下面“更多…”进经典设置的对应分类，一项不少。
 */
final class BlocksSettings {
    private final BlocksHome h;
    private final HomeActivity a;
    private final SharedPreferences prefs;

    static final String[] SMOOTH_L = { "自定义", "快速", "均衡", "高清", "极速" };
    static final String[] FLASH_L = { "不按页数", "每页", "3 页", "6 页", "8 页", "10 页", "20 页" };
    static final String[] FLASH_V = { "0", "1", "3", "6", "8", "10", "20" };
    static final String[] CONTRAST_L = { "1.0 柔和", "1.3 标准", "1.6 较黑", "1.9 很黑", "2.2 最黑" };
    static final String[] CONTRAST_V = { "1.0", "1.3", "1.6", "1.9", "2.2" };
    static final String[] SLEEP_L = { "5 分钟", "10 分钟", "30 分钟", "1 小时" };
    static final int[] SLEEP_S = { 300, 600, 1800, 3600 };
    static final String[] RETURN_L = { "挂起", "关闭" };
    static final String[] RETURN_V = { "freeze", "off" };
    static final String[] TIME_L = { "跟随 Kindle", "24 小时", "12 小时" };
    static final String[] TIME_V = { "auto", "24", "12" };
    static final String[] MODE_L = { "均衡", "灰度最准", "最快", "自动" };
    static final String[] MODE_V = { "0", "1", "2", "3" };
    static final String[] ROTATE_L = { "竖屏 0°", "倒置 180°", "横屏 90°", "横屏 270°" };
    static final String[] ROTATE_V = { "0", "180", "90", "270" };
    static final String[] HZ_L = { "30 Hz 省电", "60 Hz 跟手" };
    static final String[] HZ_V = { "30", "60" };
    static final String[] REAGL_L = { "三成", "翻页默认", "一半", "七成", "近整屏" };
    static final String[] REAGL_V = { "30", "35", "50", "70", "85" };
    static final String[] DNS_L = { "自动（路由器）", "阿里 223", "腾讯 119", "Google 8.8" };
    static final String[] DNS_V = { "auto", "ali", "dnspod", "google" };
    static final String[] FONT_L = { "小", "标准", "大", "特大" };
    static final float[] FONT_S = { 0.85f, 1.0f, 1.15f, 1.3f };

    BlocksSettings(BlocksHome home) { h = home; a = home.a; prefs = home.prefs; }

    private int dp(int v) { return Ui.dp(a, v); }

    static final String[] CAT_EN = { "Wi-Fi & Bluetooth", "Display & refresh", "Gestures", "Apps & home", "Transfer", "Device & update", "About" };

    String sig(int key) {
        if (BlocksHome.isCat(key)) {
            int t = key - BlocksHome.S_CATS - 1;
            // “关于”页显示可用内存：每分钟算新的一次；其余按设置文件 + 偏好（和经典设置页同一个签名）
            return a.settingsSig() + (t == HomeActivity.CAT_ABOUT_ ? "|" + System.currentTimeMillis() / 60000 : "");
        }
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        String cfg = new java.util.TreeMap<String, String>(HostLink.kv(HostLink.KFB + "settings")).toString();
        String em = UiMode.isExpert(a) ? "E|" : "S|";
        switch (key) {
        case BlocksHome.S_DISPLAY: return em + cfg + prefs.getString("quick", "") + prefs.getString("panelname", "") + AppFont.user(a) + st.get("light") + st.get("warmth") + st.get("lightmax") + st.get("haslight") + st.get("haswarmth") + Blocks.on(a);
        case BlocksHome.S_POWER: return em + cfg + st.get("sleep") + prefs.getBoolean("clock12", false) + "|" + java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR);
        case BlocksHome.S_STORAGE: return em + st.get("wifi") + "|" + Transfer.get(a).running() + "|" + AdbWifi.on() + "|" + BlocksHome.storageUsed();
        case BlocksHome.S_CARDS: return em + prefs.getString(BlocksCards.KEY, BlocksCards.DEFAULT) + prefs.getString("blk_quick", "") + prefs.getString("wx_city", "") + prefs.getString("blk_readers", "") + a.appsVersion();
        default: return em;
        }
    }

    View build(int key) {
        if (BlocksHome.isCat(key)) {
            int t = key - BlocksHome.S_CATS - 1;
            return shell(t < 0 ? "All settings" : CAT_EN[t], HomeActivity.catName(t), (LinearLayout) a.blocksCategory(t));
        }
        switch (key) {
        case BlocksHome.S_DISPLAY: return display();
        case BlocksHome.S_POWER: return power();
        case BlocksHome.S_STORAGE: return storage();
        case BlocksHome.S_CARDS: return cards();
        default: return about();
        }
    }

    /** 设置页的外壳：标题区（右边一个返回方块）+ 可以翻页的内容。 */
    private LinearLayout shell(String en, String zh, LinearLayout content) {
        LinearLayout pg = new LinearLayout(a);
        pg.setOrientation(LinearLayout.VERTICAL);
        pg.addView(Blocks.pageHead(a, en, zh, Blocks.squareButton(a, Icons.Kind.BACK, new View.OnClickListener() {
            @Override public void onClick(View v) { h.back(); }
        })));
        pg.addView(h.paged(content), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return pg;
    }

    private LinearLayout content() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), 0, dp(16), dp(8));
        return l;
    }

    /** 一个分组：黑底标题条 + 外框，里面的行用细线隔开。 */
    private LinearLayout group(LinearLayout content, String title) {
        LinearLayout g = new LinearLayout(a);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Blocks.frame(a));
        int st = Blocks.stroke(a), sh = Blocks.shade(a);
        g.setPadding(st, st, st + sh, st + sh);
        g.addView(Blocks.header(a, title));
        content.addView(g, Blocks.gap(a, content.getChildCount() == 0 ? 4 : 14));
        return g;
    }

    private void add(LinearLayout g, View row) {
        if (g.getChildCount() > 1) g.addView(Blocks.hair(a));
        g.addView(row, Blocks.match());
    }

    /** 标签在左、宽控件（级别条 / 分段）占满右边的一行。 */
    private LinearLayout wideRow(String zh, String en, View control) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(10), dp(8));
        r.setMinimumHeight(dp(60));
        LinearLayout left = new LinearLayout(a);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(Ui.text(a, zh, 17, true));
        left.addView(Blocks.cap(a, en));
        r.addView(left, Blocks.lp(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(control, Blocks.weight(1));
        return r;
    }

    private static int idx(String[] vals, String cur, int def) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(cur)) return i;
        return def;
    }

    private void host(String kv) { if (!HostLink.settings(kv + "\n")) VeraToast.show(a, "保存失败"); }

    private View toggle(boolean on, Icons.Toggle.Listener l) { return new Blocks.Switch(a, on, l); }

    // ------------------------------------------------------------------ 显示与刷新

    private View display() {
        LinearLayout c = content();
        final Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        final Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");
        int max = HostLink.num(st, "lightmax", 24);
        if (HostLink.num(st, "haslight", 1) == 1) {
            LinearLayout g = group(c, "屏幕光");
            Blocks.BlockBar cool = new Blocks.BlockBar(a, 12);
            cool.setMax(max);
            cool.setValue(HostLink.num(st, "light", 0));
            cool.setListener(new Blocks.BlockBar.Listener() { @Override public void onCommit(int v) { HostLink.cmd("light " + v); } });
            add(g, wideRow("亮度", "Front light", cool));
            if (HostLink.num(st, "haswarmth", 0) == 1) {
                Blocks.BlockBar warm = new Blocks.BlockBar(a, 12);
                warm.setMax(max);
                warm.setValue(HostLink.num(st, "warmth", 0));
                warm.setListener(new Blocks.BlockBar.Listener() { @Override public void onCommit(int v) { HostLink.cmd("warmth " + v); } });
                add(g, wideRow("暖光", "Warm light", warm));
            }
        }
        if ("1".equals(cfg.get("PAGE_KEYS_HW"))) {
            LinearLayout pk = group(c, "实体翻页键");
            add(pk, Blocks.row(a, "上下翻页键对换", "对调机身两个翻页键方向，立即生效", toggle("1".equals(cfg.get("PAGE_KEYS_FLIP")), new Icons.Toggle.Listener() {
                @Override public void onToggle(boolean on) {
                    host("PAGE_KEYS_FLIP=" + (on ? 1 : 0));
                    UiMode.guide(a, on ? "已开启上下翻页键对换（立即生效）" : "已恢复默认翻页键方向（立即生效）");
                } })));
            final String[] pkL = { "音量键", "翻页键", "方向键" };
            final String[] pkV = { "volume", "page", "dpad" };
            add(pk, Blocks.row(a, "翻页键键值", "阅读软件开启音量键翻页即可使用", new Blocks.Stepper(a, pkL, idx(pkV, cfg.get("PAGE_KEYS_MAP"), 0), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) {
                    host("PAGE_KEYS_MAP=" + pkV[i]);
                    UiMode.guide(a, "实体翻页键键值已设为「" + pkL[i] + "」（立即生效）");
                } })));
        }
        final boolean expert = UiMode.isExpert(a);
        LinearLayout g = group(c, expert ? "刷新与画质" : "一键阅读场景");
        int sm = Math.max(0, Math.min(4, HostLink.num(cfg, "KFB_SMOOTH", 2)));
        LinearLayout smRow = new LinearLayout(a);
        smRow.setOrientation(LinearLayout.VERTICAL);
        smRow.setPadding(dp(12), dp(8), dp(10), dp(10));
        if (!expert) {
            // 小白模式：3 个直观场景预设（纯阅读高清 / 日常均衡 / 极速刷列表），开箱即最优，绝不出现复杂底层词
            smRow.addView(Ui.text(a, "画面与流畅预设", 17, true));
            TextView smHint = Ui.text(a, "选一项即自动配好刷新与去残影：纯阅读最清晰 · 日常均衡最省心 · 极速刷网页最跟手", 13, false);
            smHint.setPadding(0, dp(3), 0, dp(8));
            smRow.addView(smHint);
            final int[] simpleMap = { 3, 2, 4 };
            int simpleSel = sm == 3 ? 0 : (sm == 4 || sm == 1 ? 2 : 1);
            smRow.addView(new Blocks.SegBar(a, new String[] { "纯阅读高清", "日常均衡（推荐）", "极速刷列表" }, simpleSel, new Blocks.SegBar.Listener() {
                @Override public void onPick(int i) {
                    int m = simpleMap[i];
                    host("KFB_SMOOTH=" + m);
                    UiMode.guide(a, UiMode.smoothEffect(m));
                }
            }), Blocks.match());
            add(g, smRow);
        } else {
            smRow.addView(Ui.text(a, "刷新模式（全局）", 17, true));
            smRow.addView(Blocks.cap(a, "Refresh mode · unified"));
            TextView smHint = Ui.text(a, "高清最锐利 · 均衡推荐 · 快速快刷+自动补刷 · 极速 A2 最跟手+自动全清（单应用可覆盖）", 12, false);
            smHint.setPadding(0, dp(2), 0, dp(6));
            smRow.addView(smHint);
            smRow.addView(new Blocks.SegBar(a, SMOOTH_L, sm, new Blocks.SegBar.Listener() {
                @Override public void onPick(int i) {
                    host("KFB_SMOOTH=" + i);
                    UiMode.guide(a, UiMode.smoothEffect(i));
                    h.show(BlocksHome.S_DISPLAY);
                }
            }), Blocks.match());
            add(g, smRow);
            if (sm == 0) {
                add(g, Blocks.row(a, "局部刷新方式", "仅自定义模式有效", new Blocks.Stepper(a, MODE_L, idx(MODE_V, cfg.get("KFB_MODE"), 0), new Blocks.Stepper.Listener() {
                    @Override public void onStep(int i) { host("KFB_MODE=" + MODE_V[i]); UiMode.guide(a, UiMode.keyEffect("KFB_MODE", MODE_V[i], MODE_L[i])); } })));
                add(g, Blocks.row(a, "快速滑动", "拖动时先黑白快刷，停手补刷高清", toggle(HostLink.num(cfg, "KFB_SCROLLDU", 0) > 0, new Icons.Toggle.Listener() {
                    @Override public void onToggle(boolean on) { host("KFB_SCROLLDU=" + (on ? 300 : 0)); UiMode.guide(a, UiMode.keyEffect("KFB_SCROLLDU", on ? "300" : "0", on ? "开" : "关")); } })));
                add(g, Blocks.row(a, "极速 A2 滚动", "更高帧率拖动列表，停手自动清残影", toggle("1".equals(cfg.get("KFB_SCROLLWFM")), new Icons.Toggle.Listener() {
                    @Override public void onToggle(boolean on) { host("KFB_SCROLLWFM=" + (on ? 1 : 0)); UiMode.guide(a, UiMode.keyEffect("KFB_SCROLLWFM", on ? "1" : "0", on ? "开" : "关")); } })));
                add(g, Blocks.row(a, "图片灰度增强", "漫画/封面用 16 阶灰度，文字照常快刷", toggle(HostLink.num(cfg, "KFB_IMG", 0) > 0, new Icons.Toggle.Listener() {
                    @Override public void onToggle(boolean on) { host("KFB_IMG=" + (on ? 25 : 0)); UiMode.guide(a, UiMode.keyEffect("KFB_IMG", on ? "25" : "0", on ? "开" : "关")); } })));
                add(g, Blocks.row(a, "整页判定面积", "变化超过此比例走高清整页刷新", new Blocks.Stepper(a, REAGL_L, idx(REAGL_V, cfg.get("KFB_REAGL_MIN"), 1), new Blocks.Stepper.Listener() {
                    @Override public void onStep(int i) { host("KFB_REAGL_MIN=" + REAGL_V[i]); UiMode.guide(a, UiMode.keyEffect("KFB_REAGL_MIN", REAGL_V[i], REAGL_L[i])); } })));
            }
            add(g, Blocks.row(a, "全刷周期", "每翻几页全屏闪刷彻底清残影", new Blocks.Stepper(a, FLASH_L, idx(FLASH_V, cfg.get("KFB_FLASH"), 4), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) { host("KFB_FLASH=" + FLASH_V[i]); UiMode.guide(a, UiMode.keyEffect("KFB_FLASH", FLASH_V[i], FLASH_L[i])); } })));
        }
        add(g, Blocks.row(a, "对比度", "让浅字更黑", new Blocks.Stepper(a, CONTRAST_L, idx(CONTRAST_V, cfg.get("KFB_CONTRAST"), 1), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { host("KFB_CONTRAST=" + CONTRAST_V[i]); UiMode.guide(a, UiMode.keyEffect("KFB_CONTRAST", CONTRAST_V[i], CONTRAST_L[i])); } })));
        add(g, Blocks.row(a, "浅色过滤", "把浅灰底漂白", new Blocks.Stepper(a, Panel.LIGHTF_L, idx(Panel.LIGHTF_V, cfg.get("KFB_LIGHTF"), 0), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { host("KFB_LIGHTF=" + Panel.LIGHTF_V[i]); UiMode.guide(a, UiMode.keyEffect("KFB_LIGHTF", Panel.LIGHTF_V[i], Panel.LIGHTF_L[i])); } })));
        add(g, Blocks.row(a, "深色加深", "把淡灰细字加黑", toggle("1".equals(cfg.get("KFB_DARKEN")), new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) { host("KFB_DARKEN=" + (on ? 1 : 0)); UiMode.guide(a, UiMode.keyEffect("KFB_DARKEN", on ? "1" : "0", on ? "开" : "关")); } })));
        add(g, Blocks.row(a, "反色（夜间）", "黑底白字显示", toggle("1".equals(cfg.get("KFB_INVERT")), new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) { host("KFB_INVERT=" + (on ? 1 : 0)); UiMode.guide(a, UiMode.keyEffect("KFB_INVERT", on ? "1" : "0", on ? "开" : "关")); } })));
        add(g, Blocks.row(a, "立即整屏去残影", "全屏黑白闪刷一次彻底干净", Blocks.button(a, "去残影", false, new View.OnClickListener() {
            @Override public void onClick(View v) { HostLink.cmd("clear"); UiMode.guide(a, "已执行整屏刷新，残影已清除"); } })));
        if (expert) {
            int am = HostLink.num(cfg, "KFB_ANIM_MODE", 1);
            final int[] animLast = { am > 0 ? am : 1 };
            add(g, Blocks.row(a, "翻页动画", "阅读翻页滑动动画", toggle(am > 0, new Icons.Toggle.Listener() {
                @Override public void onToggle(boolean on) { host("KFB_ANIM_MODE=" + (on ? animLast[0] : 0)); UiMode.guide(a, UiMode.keyEffect("KFB_ANIM_MODE", on ? "1" : "0", on ? "开" : "关")); } })));
        }
        LinearLayout fg = group(c, "字体与单应用优化");
        float fs = AppFont.user(a);
        int fi = 0;
        for (int i = 0; i < FONT_S.length; i++) if (Math.abs(FONT_S[i] - fs) < Math.abs(FONT_S[fi] - fs)) fi = i;
        add(fg, Blocks.row(a, "应用字号", "调整阅读器与软件文字大小", new Blocks.Stepper(a, FONT_L, fi, new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { AppFont.setUser(a, FONT_S[i]); UiMode.guide(a, "应用字号已设为「" + FONT_L[i] + "」：重新打开应用即可看到新字号"); } })));
        add(fg, Blocks.navRow(a, "单应用优化", expert ? "给每个软件单独设刷新、缩放、弹窗黑边、取消惯性等 15 项" : "已内置常见阅读器推荐配置，点这里可单独微调某款软件", new View.OnClickListener() {
            @Override public void onClick(View v) { appDisplayPicker(); } }));
        if (expert) {
            LinearLayout hw = group(c, "屏幕方向与硬件");
            add(hw, Blocks.row(a, "屏幕旋转", "横屏 (90°/270°) 会自动重启安卓", new Blocks.Stepper(a, ROTATE_L, idx(ROTATE_V, cfg.get("KFB_ROTATE"), 0), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) { host("KFB_ROTATE=" + ROTATE_V[i]); UiMode.guide(a, UiMode.keyEffect("KFB_ROTATE", ROTATE_V[i], ROTATE_L[i])); } })));
            add(hw, Blocks.row(a, "界面刷新节奏", "30 Hz 省电，60 Hz 更跟手（重启生效）", new Blocks.Stepper(a, HZ_L, idx(HZ_V, cfg.get("KFB_HZ"), 0), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) { host("KFB_HZ=" + HZ_V[i]); UiMode.guide(a, UiMode.keyEffect("KFB_HZ", HZ_V[i], HZ_L[i])); } })));
            if ("1".equals(cfg.get("WEBVIEW_ALT_OK"))) {
                add(hw, Blocks.navRow(a, "实验性网页内核（Chromium 119）", "兼容性更好（支持现代网页），会多占内存，点此下载或切换", new View.OnClickListener() {
                    @Override public void onClick(View v) { HostSettingsActivity.open(a, "webview"); } }));
            }
        }
        return shell(expert ? "System / display" : "Reading / display", expert ? "显示与刷新" : "阅读与显示", c);
    }

    // ------------------------------------------------------------------ 电源、输入与系统

    private View power() {
        LinearLayout c = content();
        final boolean expert = UiMode.isExpert(a);
        Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");
        LinearLayout g = group(c, "电源与返回");
        if (HostLink.num(st, "hassleeptime", 1) == 1) {
            int sl = HostLink.num(st, "sleep", 600), bi = 1;
            for (int i = 0; i < SLEEP_S.length; i++) if (Math.abs(SLEEP_S[i] - sl) < Math.abs(SLEEP_S[bi] - sl)) bi = i;
            add(g, Blocks.row(a, "自动休眠", "静置后自动锁屏省电", new Blocks.Stepper(a, SLEEP_L, bi, new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) {
                    HostLink.cmd("sleeptime " + SLEEP_S[i]);
                    UiMode.guide(a, "自动休眠已设为 " + SLEEP_L[i] + "：不操作屏幕后自动休眠省电");
                } })));
        }
        add(g, Blocks.row(a, "回到 Kindle", "切回原系统时挂起或关闭安卓", new Blocks.Stepper(a, RETURN_L, idx(RETURN_V, cfg.get("RETURN_MODE"), 0), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) {
                host("RETURN_MODE=" + RETURN_V[i]);
                UiMode.guide(a, UiMode.keyEffect("RETURN_MODE", RETURN_V[i], RETURN_L[i]));
            } })));
        LinearLayout btns = new LinearLayout(a);
        btns.setPadding(dp(10), dp(10), dp(10), dp(10));
        btns.addView(Blocks.button(a, "回 Kindle", false, new View.OnClickListener() { @Override public void onClick(View v) { HostLink.cmd("exit"); } }), Blocks.weight(1));
        View s1 = new View(a); btns.addView(s1, Blocks.lp(dp(8), 1));
        btns.addView(Blocks.button(a, "重启系统", false, new View.OnClickListener() { @Override public void onClick(View v) { a.confirmRestart(); } }), Blocks.weight(1));
        View s2 = new View(a); btns.addView(s2, Blocks.lp(dp(8), 1));
        btns.addView(Blocks.button(a, "关机…", true, new View.OnClickListener() { @Override public void onClick(View v) { a.powerMenu(); } }), Blocks.weight(1));
        add(g, btns);
        LinearLayout tools = new LinearLayout(a);
        tools.setPadding(dp(10), dp(10), dp(10), dp(10));
        tools.addView(Blocks.button(a, "切换应用", false, new View.OnClickListener() { @Override public void onClick(View v) {
            a.startActivity(new Intent(a, SwitcherActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); a.overridePendingTransition(0, 0); } }), Blocks.weight(1));
        View s3 = new View(a); tools.addView(s3, Blocks.lp(dp(8), 1));
        final TextView clean = Blocks.button(a, "清理后台", false, null);
        clean.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            clean.setText("清理中…");
            Cleaner.run(a, null, new Cleaner.Done() { @Override public void done(int stopped, long freedMb) {
                String msg = stopped == 0 ? "没有后台应用" : "已清理 " + stopped + " 个 · 约 " + freedMb + " MB";
                clean.setText(msg);
                UiMode.guide(a, stopped == 0 ? "当前没有多余后台应用" : "已释放 " + freedMb + " MB 内存，系统更流畅");
            } });
        } });
        tools.addView(clean, Blocks.weight(1));
        add(g, tools);
        if (expert) {
            LinearLayout bk = new LinearLayout(a);
            bk.setOrientation(LinearLayout.VERTICAL);
            bk.addView(Blocks.row(a, "后台保留应用", "超出上限的后台应用自动停掉省内存", new Blocks.Stepper(a, Cleaner.LIMIT_L, Math.max(0, Math.min(4, prefs.getInt(Cleaner.LIMIT_KEY, Cleaner.defaultLimit()))), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) {
                    prefs.edit().putInt(Cleaner.LIMIT_KEY, i).apply();
                    UiMode.guide(a, "后台保留应用已设为「" + Cleaner.LIMIT_L[i] + "」：超出数量的闲置应用切走 3 秒后自动释放内存");
                } })), Blocks.match());
            TextView bkHint = Ui.text(a, "长按应用设为「保持后台」的软件不受此上限影响。", 12, false);
            bkHint.setPadding(dp(12), 0, dp(12), dp(10));
            bk.addView(bkHint);
            add(g, bk);
        }
        add(g, Blocks.navRow(a, "锁屏密码", "开机 / 唤醒时输入 PIN", new View.OnClickListener() {
            @Override public void onClick(View v) { a.startActivity(new Intent(a, LockSettingsActivity.class)); } }));
        add(g, Blocks.navRow(a, "屏保", "休眠时显示的图片", new View.OnClickListener() {
            @Override public void onClick(View v) { a.startActivity(new Intent(a, ScreensaverActivity.class)); } }));

        if ("1".equals(cfg.get("PAGE_KEYS_HW"))) {
            LinearLayout pk = group(c, "实体翻页键");
            add(pk, Blocks.row(a, "上下翻页键对换", "对调机身两个翻页键方向，立即生效", toggle("1".equals(cfg.get("PAGE_KEYS_FLIP")), new Icons.Toggle.Listener() {
                @Override public void onToggle(boolean on) {
                    host("PAGE_KEYS_FLIP=" + (on ? 1 : 0));
                    UiMode.guide(a, on ? "已开启上下翻页键对换（立即生效）" : "已恢复默认翻页键方向（立即生效）");
                } })));
            final String[] pkL = { "音量键", "翻页键", "方向键" };
            final String[] pkV = { "volume", "page", "dpad" };
            add(pk, Blocks.row(a, "翻页键键值", "阅读软件开启音量键翻页即可使用", new Blocks.Stepper(a, pkL, idx(pkV, cfg.get("PAGE_KEYS_MAP"), 0), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) {
                    host("PAGE_KEYS_MAP=" + pkV[i]);
                    UiMode.guide(a, "实体翻页键键值已设为「" + pkL[i] + "」（立即生效）");
                } })));
        }
        LinearLayout op = group(c, expert ? "输入与手势" : "输入法与系统更新");
        add(op, Blocks.navRow(a, "输入法切换", "启用或切换已安装的第三方输入法", new View.OnClickListener() {
            @Override public void onClick(View v) { a.startActivity(new Intent(a, ImeSettingsActivity.class)); a.overridePendingTransition(0, 0); } }));
        if (expert) {
            add(op, Blocks.navRow(a, "手势与导航键", "边缘滑动 · 双指手势 · 四角长按 · 导航键", new View.OnClickListener() {
                @Override public void onClick(View v) { h.openCat(2); } }));
        } else {
            c.addView(new TaskCards.Update(a, false), Blocks.gap(a, 10));
            add(op, Blocks.navRow(a, "检查系统更新", "有新版本优先下载增量补丁", new View.OnClickListener() {
                @Override public void onClick(View v) { a.fullUpdateDialog(); } }));
            add(op, Blocks.navRow(a, "阅读统计", "查看电量与阅读时长（仅存本机）", new View.OnClickListener() {
                @Override public void onClick(View v) { a.startActivity(new Intent(a, StatsActivity.class)); a.overridePendingTransition(0, 0); } }));
            add(op, Blocks.navRow(a, "问题反馈", "遇到卡顿或报错可一键附日志反馈", new View.OnClickListener() {
                @Override public void onClick(View v) { a.feedbackDialog(); } }));
        }

        if (expert) {
            LinearLayout t = group(c, "时间与日期");
            add(t, Blocks.row(a, "制式", "12 / 24 小时制", new Blocks.Stepper(a, TIME_L, idx(TIME_V, cfg.get("TIME_12_24"), 0), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) {
                    host("TIME_12_24=" + TIME_V[i]);
                    prefs.edit().putBoolean("clock12", i == 2).apply();
                    a.refreshStatusNow();
                    UiMode.guide(a, "时间制式已设为「" + TIME_L[i] + "」并立即生效");
                } })));
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            int off = tz.getOffset(System.currentTimeMillis()) / 60000;
            add(t, Blocks.row(a, "时区", "Time zone", Ui.text(a, "GMT" + (off >= 0 ? "+" : "-") + Math.abs(off) / 60 + (off % 60 != 0 ? ":" + Math.abs(off % 60) : "") + "（跟随 Kindle）", 15, false)));
            java.util.Calendar cal = java.util.Calendar.getInstance();
            add(t, Blocks.row(a, "日期", "Date", Ui.text(a, cal.get(java.util.Calendar.YEAR) + " 年 " + (cal.get(java.util.Calendar.MONTH) + 1) + " 月 " + cal.get(java.util.Calendar.DAY_OF_MONTH) + " 日", 15, false)));
            TextView note = Ui.text(a, "日期、时间和时区跟 Kindle 同步（Kindle 联网自动对时）。要改请回到 Kindle 的“设置／设备选项／设备时间”。", 12, false);
            note.setPadding(dp(12), dp(6), dp(12), dp(10));
            add(t, note);
        }
        return shell(expert ? "Power / interaction" : "System / update", expert ? "电源与交互" : "系统与更新", c);
    }

    // ------------------------------------------------------------------ 网络与传书

    private View storage() {
        LinearLayout c = content();
        final boolean expert = UiMode.isExpert(a);
        final Map<String, String> st = HostLink.kv(HostLink.CTL + "status");
        final Map<String, String> cfg = HostLink.kv(HostLink.KFB + "settings");

        LinearLayout n = group(c, "网络与蓝牙");
        add(n, Blocks.row(a, "Wi-Fi", "无线联网开关", toggle(HostLink.num(st, "wifi", 1) == 1, new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) {
                HostLink.cmd("wifi " + (on ? 1 : 0)); HostLink.cmd("status");
                UiMode.guide(a, on ? "已开启 Wi-Fi，正在连接已保存网络" : "已关闭 Wi-Fi，待机更省电");
            } })));
        add(n, Blocks.navRow(a, "Wi-Fi 网络", "切换已保存的网络，或连接附近的新网络", new View.OnClickListener() {
            @Override public void onClick(View v) { WifiActivity.open(a); } }));
        if (BluetoothActivity.supported()) add(n, Blocks.navRow(a, "蓝牙翻页器", "扫描、配对、连接蓝牙翻页器", new View.OnClickListener() {
            @Override public void onClick(View v) { BluetoothActivity.open(a); } }));
        if (expert) {
            add(n, Blocks.row(a, "DNS 解析", "国内推荐自动或阿里 223", new Blocks.Stepper(a, DNS_L, idx(DNS_V, cfg.get("DNS_MODE"), 0), new Blocks.Stepper.Listener() {
                @Override public void onStep(int i) {
                    host("DNS_MODE=" + DNS_V[i]);
                    UiMode.guide(a, "DNS 已设为「" + DNS_L[i] + "」并立即生效");
                } })));
        }

        LinearLayout w = group(c, "手机网页传书");
        final Transfer t = Transfer.get(a);
        add(w, Blocks.row(a, "网页传书", "同一 Wi-Fi 下用手机/电脑传电子书和 APK", toggle(t.running(), new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) {
                if (on) {
                    t.setIdleMinutes(prefs.getInt("xfer_idle", 30));
                    if (!t.start()) VeraToast.show(a, "打不开传输服务：" + t.lastError());
                    else UiMode.guide(a, "已开启网页传书：用手机或电脑浏览器打开下方地址即可传书");
                } else {
                    t.stop();
                    UiMode.guide(a, "已关闭网页传书服务");
                }
                HostLink.cmd("status");
                a.handler().postDelayed(new Runnable() { @Override public void run() { if (h.current() == BlocksHome.S_STORAGE) { h.show(BlocksHome.S_STORAGE); a.changed(); } } }, 900);
            } })));
        String ip = st.get("lanip");
        TextView addr = Ui.text(a, t.running() ? (ip == null || ip.length() == 0 ? "正在获取地址…" : "http://" + ip + ":" + t.port()) + "\nPIN  " + t.pin()
            : "打开后，手机和 Kindle 连同一个 Wi-Fi，在浏览器里输入这里显示的地址和 PIN。", t.running() ? 20 : 13, t.running());
        addr.setPadding(dp(12), dp(8), dp(12), dp(10));
        add(w, addr);
        add(w, Blocks.navRow(a, "大字传书页面", "全屏大字显示传书网址与 PIN 码", new View.OnClickListener() {
            @Override public void onClick(View v) { a.startActivity(new Intent(a, TransferActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)); a.overridePendingTransition(0, 0); } }));
        if (expert) {
            // 无线调试（adb）：仅资深模式显示
            add(w, Blocks.row(a, "无线调试 (adb)", "仅给电脑 ADB 调试用（30 分钟自动关）", toggle(AdbWifi.on(), new Icons.Toggle.Listener() {
                @Override public void onToggle(boolean on) {
                    AdbWifi.set(a, on, 30, new Runnable() { @Override public void run() {
                        if (h.current() == BlocksHome.S_STORAGE) { h.show(BlocksHome.S_STORAGE); a.changed(); }
                    } });
                    UiMode.guide(a, on ? "正在开启无线 ADB 调试（30 分钟后自动关闭）" : "已关闭无线 ADB 调试");
                } })));
            TextView adbNote = Ui.text(a, AdbWifi.DESC, 13, false);
            adbNote.setPadding(dp(12), dp(4), dp(12), dp(10));
            add(w, adbNote);
            c.addView(new TaskCards.Net(a), Blocks.gap(a, 14));
        }

        LinearLayout g = group(c, "存储空间");
        LinearLayout use = new LinearLayout(a);
        use.setOrientation(LinearLayout.VERTICAL);
        use.setPadding(dp(12), dp(10), dp(12), dp(10));
        long total = 0, free = 0;
        try { android.os.StatFs s = new android.os.StatFs("/data/media/0"); total = s.getTotalBytes(); free = s.getAvailableBytes(); } catch (Throwable ignored) { }
        use.addView(Ui.text(a, "已用 " + gb(total - free) + " / 共 " + gb(total) + "，剩 " + gb(free), 16, true));
        Blocks.BlockBar bar = new Blocks.BlockBar(a, 20);
        bar.setMax(100);
        bar.setValue(BlocksHome.storageUsed());
        LinearLayout.LayoutParams bp = Blocks.match(); bp.topMargin = dp(6);
        use.addView(bar, bp);
        add(g, use);
        add(g, Blocks.navRow(a, "扩容与空间管理", "调大调小 Android 可用空间、快满自动扩容", new View.OnClickListener() {
            @Override public void onClick(View v) { a.startActivity(new Intent(a, StorageActivity.class)); } }));
        return shell("Network / transfer", "网络与传书", c);
    }

    private String quickNames() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String id : prefs.getString("quick", Panel.QUICK_DEFAULT).split(",")) {
            for (int i = 0; i < Panel.QUICK_ID.length; i++) if (Panel.QUICK_ID[i].equals(id.trim()) && n < BlocksPanel.MAX_TILES) {
                if ("bt".equals(id.trim()) && !BluetoothActivity.supported()) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(Panel.QUICK_NAME[i]); n++;
            }
        }
        return sb.toString();
    }

    /** 每个应用的显示效果：挑一个应用，进它的显示效果页（和长按应用图标 → 显示效果是同一页）。 */
    private void appDisplayPicker() {
        final List<ResolveInfo> apps = new java.util.ArrayList<ResolveInfo>(a.appList());
        if (apps.isEmpty()) { VeraToast.show(a, "还没有安装应用"); return; }
        // 新宿主（appprof.sh）：每个应用后面写上现在用的是什么；单独设置过的排在前面，最上面一项“全部恢复默认”
        final boolean nh = AppProf.host();
        final java.util.Map<String, String[]> t = nh ? AppProf.table() : null;
        int users = 0;
        if (nh) {
            java.util.List<ResolveInfo> mine = new java.util.ArrayList<ResolveInfo>(), rest = new java.util.ArrayList<ResolveInfo>();
            for (ResolveInfo ri : apps) (AppProf.lookup(t, ri.activityInfo.packageName)[0].startsWith("u") ? mine : rest).add(ri);
            users = mine.size();
            apps.clear(); apps.addAll(mine); apps.addAll(rest);
        }
        final int off = users > 0 ? 1 : 0;
        String[] names = new String[apps.size() + off];
        if (off > 0) names[0] = "全部恢复默认（" + users + " 个单独设置过的应用）";
        for (int i = 0; i < apps.size(); i++) {
            String n = a.labelFor(apps.get(i));
            if (nh) {
                String[] r = AppProf.lookup(t, apps.get(i).activityInfo.packageName);
                if (r[0].startsWith("u")) n += "  · 已单独设置：" + AppProf.summary(r[1]);
                else if (r[0].equals("b")) n += "  · 推荐：" + AppProf.summary(r[1]);
            }
            names[i + off] = n;
        }
        new VeraDialog.Builder(a).title("优化哪个应用").items(names, new VeraDialog.OnPick() {
            @Override public void onPick(int w) {
                if (off > 0 && w == 0) {
                    new VeraDialog.Builder(a).title("全部恢复默认").message("单独设置过的应用都回到内置推荐 / 全局设置（字号的单独设置不变）。")
                        .negative("取消", null).positive("恢复", new Runnable() { @Override public void run() {
                            AppProf.save("*", ""); VeraToast.show(a, "已恢复默认"); } }).show();
                    return;
                }
                ResolveInfo ri = apps.get(w - off);
                Intent i = new Intent(a, AppDisplayActivity.class);
                i.putExtra("pkg", ri.activityInfo.packageName);
                i.putExtra("label", a.labelFor(ri));
                a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
                a.overridePendingTransition(0, 0);
            }
        }).negative("取消", null).show();
    }

    private static String gb(long b) { return b >= 1073741824L ? String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0) : (b / 1048576) + " MB"; }

    // ------------------------------------------------------------------ 设备与关于

    private View about() {
        LinearLayout c = content();
        LinearLayout g = group(c, "设备");
        android.app.ActivityManager am = (android.app.ActivityManager) a.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        add(g, Blocks.row(a, "机型", "Model", Ui.text(a, h.model(), 15, false)));
        add(g, Blocks.row(a, "系统", "Android", Ui.text(a, "Android " + Build.VERSION.RELEASE, 15, false)));
        add(g, Blocks.row(a, "桌面版本", "Launcher", Ui.text(a, h.version() + " · " + h.buildDate(), 15, false)));
        add(g, Blocks.row(a, "内存", "Memory", Ui.text(a, (mi.availMem >> 20) + " / " + (mi.totalMem >> 20) + " MB 可用", 15, false)));
        // 系统更新的进度（宿主写 ctl/update.status；没有在更新时不占地方）
        c.addView(new TaskCards.Update(a, false), Blocks.gap(a, 14));
        LinearLayout m = group(c, "性能与维护");
        String[][] rows = {
            { "系统与性能", "Wi-Fi 省电 · 低内存策略 · Kindle 界面 · 应用预编译" },
            { "检查系统更新", "有新版本优先下载增量补丁" },
            { "阅读统计", "电量和阅读时长（仅存本机）" },
            { "问题反馈", "连同系统日志一起上传" },
            { "备份、日志与恢复/卸载", "绑定账号 · 导出日志 · 恢复默认 · 卸载 VeraDroid" } };
        for (int i = 0; i < rows.length; i++) {
            final int k = i;
            add(m, Blocks.navRow(a, rows[i][0], rows[i][1], new View.OnClickListener() { @Override public void onClick(View v) {
                switch (k) {
                case 0: HostSettingsActivity.open(a, "system"); break;
                case 1: a.fullUpdateDialog(); break;
                case 2: a.startActivity(new Intent(a, StatsActivity.class)); a.overridePendingTransition(0, 0); break;
                case 3: a.feedbackDialog(); break;
                default: h.openCat(5); break;
                }
            } }));
        }
        return shell("System / maintenance", "系统与维护", c);
    }

    // ------------------------------------------------------------------ 桌面与外观

    private View cards() {
        LinearLayout c = content();
        final List<String> order = BlocksCards.order(a);
        List<String> rows = new ArrayList<String>(order);
        for (String id : BlocksCards.IDS) if (!rows.contains(id)) rows.add(id);
        LinearLayout g = group(c, "首页卡片（从上到下）");
        for (final String id : rows) {
            final boolean on = order.contains(id);
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(6), dp(8), dp(6));
            r.setMinimumHeight(dp(56));
            LinearLayout left = new LinearLayout(a);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView name = BlocksCards.line(a, BlocksCards.name(a, id), 16, on);
            left.addView(name);
            left.addView(Blocks.cap(a, BlocksCards.en(id)));
            r.addView(left, Blocks.weight(1));
            if (BlocksCards.isWidget(id)) {
                final int wid = BlocksCards.widgetId(id);
                r.addView(Blocks.squareButton(a, Icons.Kind.MORE, new View.OnClickListener() { @Override public void onClick(View v) { widgetMenu(wid); } }));
            } else {
                r.addView(toggle(on, new Icons.Toggle.Listener() { @Override public void onToggle(boolean v) {
                    List<String> l = BlocksCards.order(a);
                    if (v && !l.contains(id)) { l.add(id); h.flashCard = id; } else if (!v) l.remove(id);
                    saveOrder(l);
                    UiMode.guide(a, (v ? "已在首页显示「" : "已在首页隐藏「") + BlocksCards.name(a, id) + "」卡片");
                } }));
            }
            if (on) {
                View sp = new View(a); r.addView(sp, Blocks.lp(dp(8), 1));
                r.addView(Blocks.squareButton(a, Icons.Kind.CHEV_U, new View.OnClickListener() { @Override public void onClick(View v) { move(id, -1); } }));
                View sp2 = new View(a); r.addView(sp2, Blocks.lp(dp(6), 1));
                r.addView(Blocks.squareButton(a, Icons.Kind.CHEV_D, new View.OnClickListener() { @Override public void onClick(View v) { move(id, 1); } }));
            }
            add(g, r);
        }
        LinearLayout more = group(c, "卡片内容");
        add(more, Blocks.navRow(a, "添加 Android 小部件", "已安装应用提供的小部件，更新最多一分钟一次", new View.OnClickListener() {
            @Override public void onClick(View v) { BlocksCards.pickWidget(a, new Runnable() { @Override public void run() { refresh(); } }); } }));
        add(more, Blocks.navRow(a, "继续阅读认哪些应用", "已认 " + installedReaders() + " 个已安装的阅读应用（浏览器默认不算）", new View.OnClickListener() {
            @Override public void onClick(View v) { readersDialog(); } }));
        String q = prefs.getString("blk_quick", "");
        add(more, Blocks.navRow(a, "快捷应用", q.length() == 0 ? "还没挑" : "已挑 " + q.split(",").length + " 个", new View.OnClickListener() {
            @Override public void onClick(View v) { quickApps(); } }));
        String city = prefs.getString("wx_city", "");
        add(more, Blocks.navRow(a, "天气城市", city.length() == 0 ? "还没设" : city, new View.OnClickListener() {
            @Override public void onClick(View v) { BlocksCards.cityDialog(a, new Runnable() { @Override public void run() { refresh(); } }); } }));

        LinearLayout ui = group(c, "桌面外观与面板");
        add(ui, Blocks.row(a, "界面风格", "经典简约或方块风格", new Blocks.Stepper(a, new String[] { "经典", "方块" }, 1, new Blocks.Stepper.Listener() {
            @Override public void onStep(final int i) { if (i == 0) a.setStyle("classic"); } }).wrap()));
        add(ui, Blocks.row(a, "桌面翻页动画", "切页时的水墨过渡效果", new Blocks.Stepper(a, Blocks.ANIM_L, Blocks.anim(a), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { prefs.edit().putInt(Blocks.ANIM_KEY, i).apply(); UiMode.guide(a, "桌面翻页动画已设为「" + Blocks.ANIM_L[i] + "」"); } })));
        add(ui, Blocks.navRow(a, "下拉面板快捷开关", "最多 5 个：" + quickNames(), new View.OnClickListener() {
            @Override public void onClick(View v) { a.quickDialog(); } }));
        add(ui, Blocks.navRow(a, "面板名称", prefs.getString("panelname", "").length() == 0 ? "（不显示）" : prefs.getString("panelname", ""), new View.OnClickListener() {
            @Override public void onClick(View v) { a.textDialog("面板名称", "最多 16 个字；留空就不显示。", "panelname", "", 16); } }));
        add(ui, Blocks.row(a, "界面字重", "调整桌面与设置文字粗细", new Blocks.Stepper(a, Fonts.WEIGHT_L, Fonts.weight(a), new Blocks.Stepper.Listener() {
            @Override public void onStep(int i) { prefs.edit().putInt(Fonts.WEIGHT_KEY, i).commit(); a.reloadBlocks(BlocksHome.S_CARDS); } })));
        add(ui, Blocks.row(a, "英文装饰小字", "标题上方的英文辅助标签", toggle(Blocks.caps(a), new Icons.Toggle.Listener() {
            @Override public void onToggle(boolean on) { prefs.edit().putBoolean(Blocks.CAPS_KEY, on).commit(); h.show(BlocksHome.S_CARDS); a.changed(); } })));
        add(ui, Blocks.navRow(a, "壁纸、图标与状态栏", "自定义壁纸 · 图标包 · 顶部状态栏开关", new View.OnClickListener() {
            @Override public void onClick(View v) { h.openCat(3); } }));
        return shell("Home / appearance", "桌面与外观", c);
    }

    private void refresh() { h.invalidate(BlocksHome.HOME); if (h.current() == BlocksHome.S_CARDS) { h.show(BlocksHome.S_CARDS); a.changed(); } }

    private void saveOrder(List<String> l) {
        BlocksCards.save(a, l);
        if (BlocksCards.hasWidgets(a)) BlocksCards.startListening(a);
        refresh();
    }

    private void move(String id, int dir) {
        List<String> l = BlocksCards.order(a);
        int i = l.indexOf(id), j = i + dir;
        if (i < 0 || j < 0 || j >= l.size()) return;
        l.set(i, l.get(j));
        l.set(j, id);
        saveOrder(l);
    }

    private void widgetMenu(final int id) {
        int sz = prefs.getInt("wsz_" + id, 1);
        new VeraDialog.Builder(a).title(BlocksCards.name(a, "w" + id))
            .items(new String[] { "高度：小", "高度：中", "高度：大", "移除这个小部件" }, new VeraDialog.OnPick() {
                @Override public void onPick(int w) {
                    if (w < 3) prefs.edit().putInt("wsz_" + id, w).apply();
                    else BlocksCards.removeWidget(a, id);
                    refresh();
                }
            }).selected(sz).negative("取消", null).show();
    }

    private int installedReaders() { int n = 0; for (String p : BlocksCards.readers(a)) if (a.findApp(p) != null) n++; return n; }

    /** 继续阅读认哪些应用：已安装的应用一个个列出来，点一下勾上 / 去掉。 */
    private void readersDialog() {
        final List<ResolveInfo> apps = a.appList();
        if (apps.isEmpty()) { VeraToast.show(a, "还没有安装应用"); return; }
        final List<String> chosen = BlocksCards.readers(a);
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView[] rows = new TextView[apps.size()];
        final Runnable paint = new Runnable() { @Override public void run() {
            for (int i = 0; i < rows.length; i++) rows[i].setText((chosen.contains(apps.get(i).activityInfo.packageName) ? "[x] " : "[  ] ") + a.labelFor(apps.get(i)));
        } };
        for (int i = 0; i < rows.length; i++) {
            final String pkg = apps.get(i).activityInfo.packageName;
            TextView r = Ui.text(a, "", 18, false);
            r.setPadding(dp(8), dp(9), dp(8), dp(9));
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (!chosen.remove(pkg)) chosen.add(pkg); paint.run(); } });
            rows[i] = r;
            box.addView(r);
        }
        paint.run();
        PageScrollView sv = new PageScrollView(a);
        sv.addView(box);
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(420), a.getResources().getDisplayMetrics().heightPixels / 2)));
        new VeraDialog.Builder(a).title("继续阅读认哪些应用").message("从这些应用回到桌面时，首页“继续阅读”会记下它和离开时的画面。").view(sv)
            .negative("恢复默认", new Runnable() { @Override public void run() { prefs.edit().remove("blk_readers").apply(); refresh(); } })
            .positive("保存", new Runnable() { @Override public void run() { prefs.edit().putString("blk_readers", android.text.TextUtils.join(",", chosen)).apply(); refresh(); } }).show();
    }

    /** 快捷应用：点一下加入（按点的先后排），再点一下拿掉；最多 5 个。 */
    private void quickApps() {
        final List<ResolveInfo> apps = a.appList();
        if (apps.isEmpty()) { VeraToast.show(a, "还没有安装应用"); return; }
        final List<String> chosen = new ArrayList<String>();
        for (String s : prefs.getString("blk_quick", "").split(",")) if (s.length() > 0) chosen.add(s);
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView[] rows = new TextView[apps.size()];
        final Runnable paint = new Runnable() { @Override public void run() {
            for (int i = 0; i < rows.length; i++) {
                int at = chosen.indexOf(apps.get(i).activityInfo.packageName);
                rows[i].setText((at >= 0 ? "[" + (at + 1) + "] " : "[  ] ") + a.labelFor(apps.get(i)));
            }
        } };
        for (int i = 0; i < rows.length; i++) {
            final String pkg = apps.get(i).activityInfo.packageName;
            TextView r = Ui.text(a, "", 18, false);
            r.setPadding(dp(8), dp(9), dp(8), dp(9));
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                if (chosen.contains(pkg)) chosen.remove(pkg); else if (chosen.size() < 5) chosen.add(pkg); else VeraToast.show(a, "最多 5 个");
                paint.run();
            } });
            rows[i] = r;
            box.addView(r);
        }
        paint.run();
        PageScrollView sv = new PageScrollView(a);
        sv.addView(box);
        sv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(420), a.getResources().getDisplayMetrics().heightPixels / 2)));
        new VeraDialog.Builder(a).title("快捷应用").message("点一下加入（数字是顺序），再点一下拿掉。最多 5 个。").view(sv).negative("取消", null)
            .positive("保存", new Runnable() { @Override public void run() {
                prefs.edit().putString("blk_quick", android.text.TextUtils.join(",", chosen)).apply();
                if (!BlocksCards.order(a).contains("apps") && !chosen.isEmpty()) { List<String> l = BlocksCards.order(a); l.add("apps"); BlocksCards.save(a, l); }
                refresh();
            } }).show();
    }

}
