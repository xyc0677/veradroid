package com.veradroid.home;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 方块风格主页上的卡片。主页上面是一列卡片，放哪几张、什么顺序在“我的 → 首页卡片”里定（偏好 blk_cards）。
 *
 * 墨水屏规则：每张卡片只在自己的内容变了时改自己的控件（时钟卡每分钟只改那一行字，不重搭整页）；
 * 天气只在 Wi-Fi 开着、离上次超过一小时、主页显示出来时才在后台取一次；Android 小部件的更新合并成至多一分钟一次。
 */
final class BlocksCards {
    private BlocksCards() { }

    static final String KEY = "blk_cards";
    static final String DEFAULT = "continue,stats,recent";
    static final String[] IDS = { "continue", "stats", "recent", "clock", "calendar", "weather", "poem", "apps" };
    static final String[] NAMES = { "继续阅读", "阅读统计", "最近阅读", "时钟与日期", "日历", "天气", "每日诗词", "快捷应用" };
    static final String[] EN = { "Continue", "Reading stats", "Recent", "Clock", "Calendar", "Weather", "Daily poem", "Quick apps" };

    static List<String> order(Context c) {
        List<String> out = new ArrayList<String>();
        for (String s : Store.prefs(c, "home").getString(KEY, DEFAULT).split(",")) if (s.trim().length() > 0 && !out.contains(s.trim())) out.add(s.trim());
        return out;
    }

    static void save(Context c, List<String> l) { Store.prefs(c, "home").edit().putString(KEY, TextUtils.join(",", l)).apply(); }

    static String name(Context c, String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        if (BlocksCards.isWidget(id)) {
            AppWidgetProviderInfo pi = widgetInfo(c, widgetId(id));
            return "小部件：" + (pi == null ? "（已失效）" : pi.loadLabel(c.getPackageManager()));
        }
        return id;
    }

    static String en(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return EN[i];
        return "Widget";
    }

    static int dp(Context c, int v) { return Ui.dp(c, v); }

    static String ago(long t) {
        if (t <= 1) return "很久以前";
        long d = System.currentTimeMillis() - t;
        if (d < 60000) return "刚刚";
        if (d < 3600000) return (d / 60000) + " 分钟前";
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(t);
        now.set(Calendar.HOUR_OF_DAY, 0); now.set(Calendar.MINUTE, 0); now.set(Calendar.SECOND, 0); now.set(Calendar.MILLISECOND, 0);
        then.set(Calendar.HOUR_OF_DAY, 0); then.set(Calendar.MINUTE, 0); then.set(Calendar.SECOND, 0); then.set(Calendar.MILLISECOND, 0);
        long diffMs = now.getTimeInMillis() - then.getTimeInMillis();
        if (diffMs <= 0) return "今天";
        int days = (int) ((diffMs + 43200000L) / 86400000L);
        if (days <= 0) return "今天";
        if (days == 1) return "昨天";
        return days + " 天前";
    }

    static String size(long b) { return b >= 1048576 ? (b * 10 / 1048576) / 10.0 + " MB" : Math.max(1, b / 1024) + " KB"; }

    // ------------------------------------------------------------------ 最近用过的第三方应用：上次的画面

    /**
     * 显示桥的共享缓冲（/dev/kfb/fb，两页 RGBX，Android 的逻辑尺寸）里留着刚离开的那个应用最后画的画面。
     * 主页恢复时（自己还没画）各页抽稀取一张小灰度图，和离开主页时取的那张比，变了的那一页就是应用的画面。
     * 只读像素、不碰别的应用的数据。
     */
    private static final java.util.concurrent.ExecutorService fbExec = Ui.lowExecutor("fb-sample");
    private static byte[][] homeSample, capSample;
    private static byte[] lineReuse;
    private static int[] pxReuse;
    private static java.nio.MappedByteBuffer sFbMap;
    private static long sFbLen = -1;
    private static int sampleW, sampleH;

    private static byte[][] ensureSampleBuf(byte[][] reuse, int len) {
        if (reuse != null && reuse.length == 2 && reuse[0] != null && reuse[0].length == len) {
            java.util.Arrays.fill(reuse[1], (byte) 0);
            return reuse;
        }
        return new byte[2][len];
    }

    private static java.nio.MappedByteBuffer cachedMap(File f, long need) throws Exception {
        long flen = f.length();
        if (sFbMap != null && sFbLen == flen && sFbMap.capacity() >= need) return sFbMap;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        try {
            java.nio.MappedByteBuffer m = raf.getChannel().map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, need);
            sFbMap = m; sFbLen = flen;
            return m;
        } finally { raf.close(); }
    }

    private static byte[][] sampleFb(Context c, boolean forHome) {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels, h = dm.heightPixels;
            File f = new File(HostLink.KFB + "fb");
            long page = (long) w * h * 4;
            int pages = new File(HostLink.KFB + "onepage").exists() ? 1 : 2;
            if (f.length() < page * pages) return null;
            java.nio.MappedByteBuffer mb = cachedMap(f, page * pages);
            int step = Math.max(2, w / 260);
            sampleW = w / step; sampleH = h / step;
            byte[][] out = ensureSampleBuf(forHome ? homeSample : capSample, sampleW * sampleH);
            if (forHome) homeSample = out; else capSample = out;
            int needLine = (sampleW - 1) * step * 4 + 3;
            if (lineReuse == null || lineReuse.length < needLine) lineReuse = new byte[needLine];
            byte[] line = lineReuse;
            for (int pg = 0; pg < pages; pg++) {
                for (int y = 0; y < sampleH; y++) {
                    mb.position((int) (pg * page + (long) (y * step) * w * 4));
                    mb.get(line, 0, needLine);
                    int base = y * sampleW;
                    for (int x = 0, o = 0; x < sampleW; x++, o += step * 4) {
                        int r = line[o] & 255, g = line[o + 1] & 255, b = line[o + 2] & 255;
                        out[pg][base + x] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                    }
                }
            }
            return out;
        } catch (Throwable t) { return null; }
    }

    /** 离开主页去开应用时：在启动外部应用前同步抽稀记下主页画面，避免单页缓冲下异步采到新应用首帧。 */
    static void onLeaveHome(Context c) {
        if (!wantsLastApp(c)) return;
        homeSample = sampleFb(c.getApplicationContext(), true);
    }

    private static boolean wantsLastApp(Context c) { return Blocks.on(c) && order(c).contains("continue"); }

    /** 回到主页的那一刻（onResume，主页还没画）：前一个应用是第三方应用就存它的缩略图和时间。 */
    /** 算作“阅读应用”的包（继续阅读卡片只记这些）：默认一份常见的阅读器，用户可以在“首页卡片”里增减（键 blk_readers）。 */
    static final String READERS_DEFAULT = "com.tencent.weread.eink,com.tencent.weread,org.koreader.launcher,org.koreader.launcher.fdroid,com.flyersoft.moonreader,com.flyersoft.moonreaderp,"
        + "com.duokan.reader,com.duokan.einkreader,com.chaozh.iReaderFree,com.chaozh.iReader,com.zhangyue.read.iReader.eink,com.zhangyue.iReader.Eink,com.amazon.kindle,"
        + "com.dragon.read,com.jjwxc.reader,com.qidian.QDReader,com.kmxs.reader,com.jd.app.reader,com.jingdong.app.reader.campus,net.cpwxw.cpfiction,"
        + "com.douban.book.reader,com.shuqi.controller,com.qq.reader,com.ss.android.lark.read,com.tadu.android,org.geometerplus.zlibrary.ui.android,com.ireadercity,"
        + "io.legado.app.release,io.legado.ink,com.gedoor.monkeybook";
    static List<String> readers(Context c) {
        List<String> l = new ArrayList<String>();
        for (String x : Store.prefs(c, "home").getString("blk_readers", READERS_DEFAULT).split(",")) if (x.trim().length() > 0) l.add(x.trim());
        return l;
    }

    /** 首页上没有“继续阅读”卡片时连最近任务都不查（那是一次跨进程调用，单核机型上回到主页时要等它）。 */
    static void captureLastApp(HomeActivity a) {
        if (!wantsLastApp(a)) return;
        captureLastApp(a, lastForeground(a));
    }

    static void captureLastApp(HomeActivity a, final String pkg) {
        if (pkg == null || !wantsLastApp(a) || a.findApp(pkg) == null || !readers(a).contains(pkg)) return;
        final Context app = a.getApplicationContext();
        // 先立即记录最近阅读应用与时间，避免单页缓冲下帧采样跳过导致漏记
        Store.prefs(app, "home").edit().putString("la_pkg", pkg).putLong("la_time", System.currentTimeMillis()).apply();
        // 在桌面首帧绘入 /dev/kfb/fb 前同步做轻量抽稀采样，PNG 编码与落盘留给后台线程
        byte[][] now = sampleFb(app, false);
        if (now == null) return;
        int pick = 0;
        if (homeSample != null && homeSample[0].length == now[0].length) {
            long d0 = diff(now[0], homeSample[0]), d1 = diff(now[1], homeSample[1]);
            pick = d1 > d0 ? 1 : 0;
            if (Math.max(d0, d1) == 0) return;   // 画面与离开主页时一致：应用没画过新帧
        }
        final int w = sampleW, h = sampleH;
        final byte[] snap = java.util.Arrays.copyOf(now[pick], now[pick].length);
        fbExec.execute(new Runnable() { @Override public void run() {
            int n = w * h;
            if (pxReuse == null || pxReuse.length != n) pxReuse = new int[n];
            int[] px = pxReuse;
            for (int i = 0; i < n; i++) { int v = snap[i] & 255; px[i] = 0xff000000 | (v << 16) | (v << 8) | v; }
            final Bitmap bm = Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565);
            ui.post(new Runnable() { @Override public void run() {
                thumb = bm; thumbPkg = pkg;
            } });
            File out = new File(Store.cache("lastapp"), "thumb.png");
            File tmp = new File(Store.cache("lastapp"), "thumb.png.tmp");
            FileOutputStream o = null;
            boolean ok = false;
            try {
                o = new FileOutputStream(tmp);
                ok = bm.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (Throwable ignored) {
            } finally {
                try { if (o != null) o.close(); } catch (Throwable ignored) { }
            }
            if (ok) tmp.renameTo(out); else tmp.delete();
        } });
    }

    /**
     * 刚离开的是哪个应用：最近任务表里第一个不是 VeraHome 的（从自己的阅读器等页面回来的情况由 HomeActivity 的 ownStart 排除；返回手势把应用的最后一个页面关掉以后，它的任务已经不在运行中，
     * 但还在最近任务里，所以不用 getRunningTasks）。
     */
    static String lastForeground(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RecentTaskInfo t : am.getRecentTasks(6, android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
                Intent i = t.baseIntent;
                String pkg = t.origActivity != null ? t.origActivity.getPackageName() : i != null && i.getComponent() != null ? i.getComponent().getPackageName() : null;
                if (pkg != null && !pkg.equals(c.getPackageName())) return pkg;
            }
        } catch (Throwable ignored) { }
        return Cleaner.previousApp(c);
    }

    private static long diff(byte[] a, byte[] b) {
        long s = 0;
        for (int i = 0; i < a.length; i += 3) s += Math.abs((a[i] & 255) - (b[i] & 255));
        return s;
    }

    private static Bitmap thumb;
    private static String thumbPkg;

    static Bitmap lastAppThumb(Context c, String pkg) {
        if (thumb != null && pkg.equals(thumbPkg)) return thumb;
        File f = new File(Store.cache("lastapp"), "thumb.png");
        if (!f.isFile()) return null;
        thumb = BitmapFactory.decodeFile(f.getAbsolutePath());
        thumbPkg = pkg;
        return thumb;
    }

    // ------------------------------------------------------------------ 天气（open-meteo，无需密钥）

    interface Changed { void changed(); }
    private static boolean fetching;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    /** WMO 天气代码 → 中文。 */
    static String wmo(int c) {
        if (c == 0) return "晴";
        if (c == 1) return "大致晴";
        if (c == 2) return "多云";
        if (c == 3) return "阴";
        if (c == 45 || c == 48) return "雾";
        if (c >= 51 && c <= 57) return "毛毛雨";
        if (c == 61 || c == 80) return "小雨";
        if (c == 63 || c == 81) return "中雨";
        if (c == 65 || c == 82) return "大雨";
        if (c == 66 || c == 67) return "冻雨";
        if (c == 71 || c == 85) return "小雪";
        if (c == 73) return "中雪";
        if (c == 75 || c == 86) return "大雪";
        if (c == 77) return "雪粒";
        if (c == 95) return "雷阵雨";
        if (c >= 96) return "雷阵雨伴冰雹";
        return "—";
    }

    /**
     * 天气来源可以换：每个来源把自己的回答换成同一种格式（{@link #norm}），卡片只认这个格式。
     * 实测（2026-11-13，两处不同的网络）：api.open-meteo.com 的天气接口连不上（超时），它的地名搜索能用；wttr.in 能用。
     * 所以默认先试 wttr.in，失败再试 Open-Meteo；都失败就安静地保留上次的数据（卡片写“离线”）。
     */
    interface WeatherSource {
        String id();
        String attribution();
        /** 后台线程里调：返回统一格式的 JSON，失败返回 null。 */
        String fetch(String lat, String lon);
    }

    /** 统一格式：{"src", "t" 当前温度, "desc" 当前天气, "hum" 湿度, "days": [{"desc","lo","hi"} × 3]}。 */
    static String norm(String src, double t, String desc, int hum, String[] dd, double[] lo, double[] hi) throws org.json.JSONException {
        org.json.JSONObject o = new org.json.JSONObject();
        o.put("src", src); o.put("t", t); o.put("desc", desc); o.put("hum", hum);
        org.json.JSONArray days = new org.json.JSONArray();
        for (int i = 0; i < dd.length; i++) { org.json.JSONObject d = new org.json.JSONObject(); d.put("desc", dd[i]); d.put("lo", lo[i]); d.put("hi", hi[i]); days.put(d); }
        o.put("days", days);
        return o.toString();
    }

    static final WeatherSource WTTR = new WeatherSource() {
        public String id() { return "wttr"; }
        public String attribution() { return "天气数据：wttr.in"; }
        public String fetch(String lat, String lon) {
            String body = httpGet("https://wttr.in/" + lat + "," + lon + "?format=j1&lang=zh");
            if (body == null) return null;
            try {
                org.json.JSONObject o = new org.json.JSONObject(body);
                org.json.JSONObject cur = o.getJSONArray("current_condition").getJSONObject(0);
                org.json.JSONArray w = o.getJSONArray("weather");
                int n = Math.min(3, w.length());
                String[] dd = new String[n]; double[] lo = new double[n], hi = new double[n];
                for (int i = 0; i < n; i++) {
                    org.json.JSONObject d = w.getJSONObject(i);
                    org.json.JSONArray hourly = d.getJSONArray("hourly");
                    dd[i] = zh(hourly.getJSONObject(Math.min(4, hourly.length() - 1)));
                    lo[i] = d.getDouble("mintempC"); hi[i] = d.getDouble("maxtempC");
                }
                return norm(id(), cur.getDouble("temp_C"), zh(cur), cur.optInt("humidity", 0), dd, lo, hi);
            } catch (Throwable t) { return null; }
        }
        /** wttr.in 的 lang_zh 常常还是英文：按它的天气代码（WorldWeatherOnline）自己翻。 */
        private String zh(org.json.JSONObject x) {
            String w = wwo(x.optInt("weatherCode", -1));
            if (w != null) return w;
            String en = "";
            try { en = x.getJSONArray("weatherDesc").getJSONObject(0).getString("value").toLowerCase(Locale.US); } catch (Throwable u) { }
            String[][] kw = { { "haze", "霾" }, { "smoke", "霾" }, { "mist", "薄雾" }, { "fog", "雾" }, { "thunder", "雷阵雨" }, { "snow", "雪" }, { "sleet", "雨夹雪" },
                { "drizzle", "毛毛雨" }, { "heavy rain", "大雨" }, { "rain", "雨" }, { "overcast", "阴" }, { "cloud", "多云" }, { "sun", "晴" }, { "clear", "晴" } };
            for (String[] k : kw) if (en.contains(k[0])) return k[1];
            try { return x.getJSONArray("lang_zh").getJSONObject(0).getString("value").trim(); } catch (Throwable u) { return "—"; }
        }
    };

    /** WorldWeatherOnline 天气代码（wttr.in 用的）→ 中文。 */
    static String wwo(int c) {
        switch (c) {
        case 113: return "晴";
        case 116: return "局部多云";
        case 119: return "多云";
        case 122: return "阴";
        case 143: return "薄雾";
        case 248: case 260: return "雾";
        case 176: case 353: return "零星小雨";
        case 263: case 266: return "毛毛雨";
        case 293: case 296: return "小雨";
        case 299: case 302: case 356: return "中雨";
        case 305: case 308: case 359: return "大雨";
        case 185: case 281: case 284: case 311: case 314: return "冻雨";
        case 182: case 317: case 320: case 362: case 365: return "雨夹雪";
        case 179: case 323: case 326: case 368: return "小雪";
        case 329: case 332: case 371: return "中雪";
        case 335: case 338: case 227: case 230: return "大雪";
        case 350: case 374: case 377: return "冰粒";
        case 200: case 386: return "雷阵雨";
        case 389: return "强雷阵雨";
        case 392: case 395: return "雷阵雪";
        default: return null;
        }
    }

    static final WeatherSource OPEN_METEO = new WeatherSource() {
        public String id() { return "openmeteo"; }
        public String attribution() { return "天气数据：Open-Meteo.com（CC BY 4.0）"; }
        public String fetch(String lat, String lon) {
            String body = httpGet("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,relative_humidity_2m&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=3");
            if (body == null) return null;
            try {
                org.json.JSONObject o = new org.json.JSONObject(body), cu = o.getJSONObject("current"), d = o.getJSONObject("daily");
                org.json.JSONArray code = d.getJSONArray("weather_code"), mx = d.getJSONArray("temperature_2m_max"), mn = d.getJSONArray("temperature_2m_min");
                int n = Math.min(3, code.length());
                String[] dd = new String[n]; double[] lo = new double[n], hi = new double[n];
                for (int i = 0; i < n; i++) { dd[i] = wmo(code.getInt(i)); lo[i] = mn.getDouble(i); hi[i] = mx.getDouble(i); }
                return norm(id(), cu.getDouble("temperature_2m"), wmo(cu.optInt("weather_code", -1)), cu.optInt("relative_humidity_2m", 0), dd, lo, hi);
            } catch (Throwable t) { return null; }
        }
    };

    static final WeatherSource[] SOURCES = { WTTR, OPEN_METEO };

    static WeatherSource source(String id) { for (WeatherSource w : SOURCES) if (w.id().equals(id)) return w; return WTTR; }

    /** 该取天气了就在后台取（Wi-Fi 开着、设了城市、离上次超过一小时）；按顺序试各个来源，取到以后回主线程调 cb。失败不提示。 */
    static void maybeFetchWeather(final Context c, final Changed cb) {
        final SharedPreferences p = Store.prefs(c, "home");
        final String lat = p.getString("wx_lat", ""), lon = p.getString("wx_lon", "");
        if (lat.length() == 0 || fetching) return;
        if (System.currentTimeMillis() - p.getLong("wx_try", 0) < 3600000L) return;
        if ("0".equals(HostLink.kv(HostLink.CTL + "status").get("wifi"))) return;
        fetching = true;
        p.edit().putLong("wx_try", System.currentTimeMillis()).apply();
        final String pref = p.getString("wx_src", "wttr");
        new Thread(new Runnable() { @Override public void run() {
            String got = null;
            WeatherSource first = source(pref);
            got = first.fetch(lat, lon);
            if (got == null) for (WeatherSource w : SOURCES) if (w != first && (got = w.fetch(lat, lon)) != null) break;
            final boolean ok = got != null;
            if (ok) p.edit().putString("wx_norm", got).putLong("wx_at", System.currentTimeMillis()).apply();
            android.util.Log.i("VeraHome", "weather: " + (ok ? "ok" : "all sources failed"));
            ui.post(new Runnable() { @Override public void run() { fetching = false; if (ok && cb != null) cb.changed(); } });
        } }, "weather").start();
    }

    static String httpGet(String u) {
        java.net.HttpURLConnection con = null;
        java.io.InputStream in = null;
        try {
            con = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            con.setConnectTimeout(6000); con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", "VeraHome");
            if (con.getResponseCode() != 200) return null;
            in = con.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0 && bo.size() < 200000) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        } catch (Throwable t) { return null; }
        finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (con != null) con.disconnect();
        }
    }

    /** 设置城市：输入名字 → open-meteo 地名搜索（只发这个名字）→ 选一个。 */
    static void cityDialog(final HomeActivity a, final Runnable done) {
        final EditText in = Ui.editText(a);
        in.setSingleLine(true);
        in.setTextSize(20);
        in.setTypeface(Fonts.typeface(a));
        in.setBackground(Ui.frame(a, false));
        in.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        in.setText(Store.prefs(a, "home").getString("wx_city", ""));
        new VeraDialog.Builder(a).title("天气城市")
            .message("输入城市名（例如 杭州）。只会把这个名字发给 open-meteo.com 的地名搜索查经纬度，之后只把经纬度发给天气来源（wttr.in，取不到再试 Open-Meteo）；不用定位、不发设备信息。")
            .view(in).negative("取消", null).positive("查找", new Runnable() { @Override public void run() {
                final String q = in.getText().toString().trim();
                if (q.length() == 0) return;
                VeraToast.show(a, "正在查找…");
                new Thread(new Runnable() { @Override public void run() {
                    String body = null;
                    try { body = httpGet("https://geocoding-api.open-meteo.com/v1/search?count=8&language=zh&format=json&name=" + java.net.URLEncoder.encode(q, "UTF-8")); } catch (Throwable ignored) { }
                    final List<String[]> res = new ArrayList<String[]>();
                    try {
                        org.json.JSONArray arr = new org.json.JSONObject(body).getJSONArray("results");
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.getJSONObject(i);
                            String label = o.optString("name") + "（" + o.optString("admin1", "") + (o.optString("country", "").length() > 0 ? " · " + o.optString("country") : "") + "）";
                            res.add(new String[] { label, o.optString("name"), String.valueOf(o.getDouble("latitude")), String.valueOf(o.getDouble("longitude")) });
                        }
                    } catch (Throwable ignored) { }
                    ui.post(new Runnable() { @Override public void run() {
                        if (a.isFinishing()) return;
                        if (res.isEmpty()) { VeraToast.show(a, "没找到（没有网络，或者换个写法）"); return; }
                        String[] names = new String[res.size()];
                        for (int i = 0; i < names.length; i++) names[i] = res.get(i)[0];
                        new VeraDialog.Builder(a).title("选择城市").items(names, new VeraDialog.OnPick() {
                            @Override public void onPick(int w) {
                                String[] r = res.get(w);
                                Store.prefs(a, "home").edit().putString("wx_city", r[1]).putString("wx_lat", r[2]).putString("wx_lon", r[3])
                                    .remove("wx_norm").putLong("wx_try", 0).apply();
                                if (done != null) done.run();
                            }
                        }).negative("取消", null).show();
                    } });
                } }, "geocode").start();
            } }).show();
    }

    // ------------------------------------------------------------------ 日历（每月算一次，缓存）

    private static String calKey;
    private static String[] calCells;

    /** 这个月每一天格子里的小字（农历日 / 初一写月份 / 节气）。农历按天递推，只在月初和每月 29 日以后问 ICU。 */
    static String[] monthLabels(int y, int m, int days) {
        String key = y + "-" + m;
        if (key.equals(calKey) && calCells != null) return calCells;
        String[] out = new String[days + 1];
        String[] lun = null; int ld = 0;
        for (int d = 1; d <= days; d++) {
            if (lun == null || ld >= 29) {
                lun = Lunar.lunar(y, m, d);
                ld = lun == null ? 0 : lunarDayIndex(lun[1]);
            } else {
                ld++;
                lun = new String[] { lun[0], Lunar.dayName(ld) };
            }
            String t = Lunar.term(y, m, d);
            out[d] = t != null ? t : lun == null ? "" : ld == 1 ? lun[0] : lun[1];
        }
        calKey = key; calCells = out;
        return out;
    }

    private static int lunarDayIndex(String name) {
        for (int i = 1; i <= 30; i++) if (Lunar.dayName(i).equals(name)) return i;
        return 1;
    }

    // ------------------------------------------------------------------ 每日诗词（assets/poems.txt，和开机画面同一份）

    private static List<String[]> poems;
    static String[] poemOfDay(Context c) {
        if (poems == null) {
            poems = new ArrayList<String[]>();
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getAssets().open("poems.txt"), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) { String[] f = line.split("\\|"); if (f.length >= 4) poems.add(f); }
            } catch (Throwable ignored) {
            } finally {
                if (r != null) try { r.close(); } catch (Throwable ignored) { }
            }
        }
        if (poems.isEmpty()) return null;
        int day = (int) ((System.currentTimeMillis() + TimeZoneOffset()) / 86400000L);
        return poems.get(Math.abs(day) % poems.size());
    }
    private static long TimeZoneOffset() { return java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()); }

    // ------------------------------------------------------------------ Android 小部件（AppWidgetHost）

    static final int HOST_ID = 0x5648;
    private static AppWidgetHost host;

    /** 墨水屏用的小部件外壳：应用推来的更新至多一分钟用一次（中间的合并掉），没有布局动画。 */
    static final class EinkHostView extends AppWidgetHostView {
        private long last;
        private RemoteViews pending;
        private final Runnable apply = new Runnable() { @Override public void run() {
            if (pending != null) { RemoteViews r = pending; pending = null; last = SystemClock.uptimeMillis(); EinkHostView.super.updateAppWidget(r); }
        } };
        EinkHostView(Context c) { super(c); setLayoutTransition(null); }
        @Override public void updateAppWidget(RemoteViews rv) {
            long now = SystemClock.uptimeMillis();
            if (last == 0 || now - last >= 60000) { last = now; pending = null; removeCallbacks(apply); super.updateAppWidget(rv); return; }
            pending = rv;
            removeCallbacks(apply);
            postDelayed(apply, 60000 - (now - last));
        }
    }

    static AppWidgetHost host(Context c) {
        if (host == null) host = new AppWidgetHost(c.getApplicationContext(), HOST_ID) {
            @Override protected AppWidgetHostView onCreateView(Context ctx, int id, AppWidgetProviderInfo info) { return new EinkHostView(ctx); }
        };
        return host;
    }

    static void startListening(Context c) { try { if (hasWidgets(c)) host(c).startListening(); } catch (Throwable t) { android.util.Log.w("VeraHome", "widget host: " + t); } }
    static void stopListening(Context c) { try { if (host != null) host.stopListening(); } catch (Throwable ignored) { } }

    static boolean hasWidgets(Context c) { for (String s : order(c)) if (BlocksCards.isWidget(s)) return true; return false; }
    /** 小部件卡片的 id 是 “w” + 数字（“weather” 不是）。 */
    static boolean isWidget(String id) { return id != null && id.matches("w\\d+"); }
    static int widgetId(String card) { try { return Integer.parseInt(card.substring(1)); } catch (Throwable t) { return -1; } }

    static AppWidgetProviderInfo widgetInfo(Context c, int id) {
        try { return AppWidgetManager.getInstance(c).getAppWidgetInfo(id); } catch (Throwable t) { return null; }
    }

    /** 小部件卡片的高度：小 / 中 / 大。 */
    static final String[] WSIZE = { "小", "中", "大" };
    static int widgetHeightDp(Context c, int id) { return new int[] { 110, 190, 280 }[Math.max(0, Math.min(2, Store.prefs(c, "home").getInt("wsz_" + id, 1)))]; }

    static View widgetView(Context c, int id) {
        AppWidgetProviderInfo pi = widgetInfo(c, id);
        if (pi == null) return null;
        try {
            host(c).startListening();
            AppWidgetHostView v = host(c).createView(c, id, pi);
            v.setAppWidget(id, pi);
            return v;
        } catch (Throwable t) { android.util.Log.w("VeraHome", "widget " + id + ": " + t); return null; }
    }

    /** 选一个已安装应用提供的小部件放进卡片：列出全部、绑定（平台签名的系统应用有 BIND_APPWIDGET）、需要配置的先打开它的配置页。 */
    static void pickWidget(final HomeActivity a, final Runnable done) {
        final List<AppWidgetProviderInfo> all = AppWidgetManager.getInstance(a).getInstalledProviders();
        if (all.isEmpty()) {
            new VeraDialog.Builder(a).title("添加小部件").message("已安装的应用里没有提供小部件。").positive("知道了", null).show();
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < names.length; i++) {
            AppWidgetProviderInfo pi = all.get(i);
            String app = pi.provider.getPackageName();
            try { app = String.valueOf(a.getPackageManager().getApplicationLabel(a.getPackageManager().getApplicationInfo(app, 0))); } catch (Throwable ignored) { }
            float den = a.getResources().getDisplayMetrics().density;   // minWidth / minHeight 是像素
            names[i] = pi.loadLabel(a.getPackageManager()) + "\n" + app + " · " + Math.max(1, Math.round((pi.minWidth / den + 30) / 70)) + "×" + Math.max(1, Math.round((pi.minHeight / den + 30) / 70));
        }
        new VeraDialog.Builder(a).title("添加小部件").items(names, new VeraDialog.OnPick() {
            @Override public void onPick(int w) {
                AppWidgetProviderInfo pi = all.get(w);
                int id = host(a).allocateAppWidgetId();
                boolean ok = false;
                try { ok = AppWidgetManager.getInstance(a).bindAppWidgetIdIfAllowed(id, pi.provider); } catch (Throwable t) { android.util.Log.w("VeraHome", "bind: " + t); }
                if (!ok) { host(a).deleteAppWidgetId(id); VeraToast.show(a, "绑定小部件失败"); return; }
                if (pi.configure != null) {
                    Intent ci = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(pi.configure).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    a.pendingWidget = id; a.pendingWidgetDone = done;
                    try { a.startActivityForResult(ci, HomeActivity.REQ_WIDGET_CONFIG); return; }
                    catch (Throwable t) { a.pendingWidget = 0; }
                }
                addWidget(a, id);
                if (done != null) done.run();
            }
        }).negative("取消", null).show();
    }

    static void addWidget(Context c, int id) {
        List<String> l = order(c);
        l.add("w" + id);
        save(c, l);
        startListening(c);
    }

    static void removeWidget(Context c, int id) {
        List<String> l = order(c);
        l.remove("w" + id);
        save(c, l);
        try { host(c).deleteAppWidgetId(id); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ helpers for the cards

    static TextView line(Context c, String s, float sp, boolean bold) {
        TextView t = Ui.text(c, s, sp, bold);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static String weekday(Calendar cal) { return new String[] { "日", "一", "二", "三", "四", "五", "六" }[cal.get(Calendar.DAY_OF_WEEK) - 1]; }

    static String dateLine() {
        Calendar cal = Calendar.getInstance();
        int y = cal.get(Calendar.YEAR), m = cal.get(Calendar.MONTH) + 1, d = cal.get(Calendar.DAY_OF_MONTH);
        String[] l = Lunar.lunar(y, m, d);
        String t = Lunar.term(y, m, d);
        return m + "月" + d + "日  星期" + weekday(cal) + (l != null ? "  农历" + l[0] + l[1] : "") + (t != null ? "  " + t : "");
    }

    static ImageView thumbView(Context c, Bitmap bm) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bm);
        iv.setBackground(Blocks.plain(c));
        iv.setPadding(2, 2, 2, 2);
        return iv;
    }

}
