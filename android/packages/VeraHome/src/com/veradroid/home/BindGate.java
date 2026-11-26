package com.veradroid.home;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 商店下载「账号绑定」拦截与手机扫码快捷绑定引导（墨水屏友好）：
 * - 未绑定时允许浏览应用与字体列表，点击下载时拦截并展示清晰原因 + 手机扫码快捷绑定 + 6 位绑定码兜底；
 * - 手机扫码确认后自动获取 token 并续下刚才点击的应用/字体；
 * - 已绑定设备下载前向 /api/store/sign 申请 10 分钟防盗链签名 URL。
 */
final class BindGate {
    static final String REASON_TEXT =
        "由于服务器带宽负载过高，为防范恶意爬虫与不明流量攻击、保障正常用户下载体验，需绑定账号后方可下载。";
    static final String OTA_REASON_TEXT =
        "初次安装免费免绑定；为保障系统在线 OTA 更新通道带宽稳定与防恶意刷流，请先绑定 VeraDroid 账号后再检查并下载更新（同一账号支持绑定多台设备）。";

    static final class BindRequiredException extends Exception {
        BindRequiredException(String msg) { super(msg); }
    }

    private BindGate() { }

    private static boolean gone(Activity a) {
        return a == null || a.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed());
    }

    /** 已绑定返回 true；未绑定弹出墨水屏友好引导框并返回 false，绑定成功后自动执行 onBound。 */
    static boolean ensureBound(final Activity a, final String itemName, final Runnable onBound) {
        return ensureBound(a, itemName, REASON_TEXT, onBound);
    }

    static boolean ensureBound(final Activity a, final String itemName, final String reasonText, final Runnable onBound) {
        String t = Token.get(a);
        if (t != null && t.length() > 0) return true;
        showBindDialog(a, itemName, reasonText, onBound);
        return false;
    }

    /** 向服务器申请 10 分钟防盗链签名下载链接；若设备已解绑或 token 失效则清理本地 token 并抛 BindRequiredException。 */
    static String signUrl(Context ctx, String kind, int id, String fallbackUrl, String token) throws Exception {
        String did = Update.deviceId(ctx);
        String url = StoreNet.base() + "/api/store/sign?kind=" + StoreNet.enc(kind)
            + "&id=" + id + "&device_id=" + StoreNet.enc(did) + "&token=" + StoreNet.enc(token);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        InputStream in = null;
        int code;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try {
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setRequestProperty("User-Agent", "VeraDroid/Store");
            code = h.getResponseCode();
            in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            byte[] b = new byte[4096]; int n;
            while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
        }
        String raw = bo.toString("UTF-8");
        JSONObject j = null;
        try { j = new JSONObject(raw); } catch (Exception ignored) { }
        if (code == 401 || code == 403) {
            String errCode = j != null ? j.optString("code", "") : "";
            String errMsg = j != null ? j.optString("error", REASON_TEXT) : REASON_TEXT;
            if ("bind_required".equals(errCode) || "token_invalid".equals(errCode) || (code == 401 && j != null)) {
                Token.clear(ctx);
                throw new BindRequiredException(errMsg);
            }
            // e.g. ip_blocked / account disabled / CDN 403: surface the real error without wiping a valid token or showing a bind dialog
            throw new Exception(errMsg);
        }
        if (code == 429) {
            throw new Exception(j != null ? j.optString("error", "下载请求过于频繁，请稍后再试") : "下载请求过于频繁，请稍后再试");
        }
        if (code >= 400) {
            // 老服务器无 /api/store/sign 时平滑退回带 device_id/token 的原地址
            if (code == 404) return fallbackUrl;
            throw new Exception(j != null ? j.optString("error", "HTTP " + code) : "HTTP " + code);
        }
        String signed = j != null ? j.optString("url", "") : "";
        return signed.length() > 0 ? signed : fallbackUrl;
    }

    static void showBindDialog(final Activity a, final String itemName, final Runnable onBound) {
        showBindDialog(a, itemName, REASON_TEXT, onBound);
    }

    static void showBindDialog(final Activity a, final String itemName, final String customReason, final Runnable onBound) {
        if (gone(a)) return;
        final Handler ui = new Handler(Looper.getMainLooper());
        final boolean[] alive = { true };
        final String[] activeSid = { null };
        final String rText = (customReason != null && customReason.length() > 0) ? customReason : REASON_TEXT;

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView reason = Ui.text(a, rText, 14, false);
        reason.setPadding(0, 0, 0, Ui.dp(a, 10));
        box.addView(reason);

        TextView nextTip = Ui.text(a, "绑定完成后将自动继续下载「" + (itemName != null ? itemName : "所选内容") + "」：", 14, true);
        nextTip.setPadding(0, 0, 0, Ui.dp(a, 8));
        box.addView(nextTip);

        // 手机扫码区（默认优先展示，低阻力）
        LinearLayout qrBox = new LinearLayout(a);
        qrBox.setOrientation(LinearLayout.VERTICAL);
        qrBox.setGravity(Gravity.CENTER_HORIZONTAL);
        qrBox.setBackground(Blocks.on(a) ? Blocks.frame(a) : Ui.frame(a, false));
        int p = Ui.dp(a, 10);
        qrBox.setPadding(p, p, p, p);

        final TextView qrTitle = Ui.text(a, "方式一：手机扫码快捷绑定（推荐）", 15, true);
        qrBox.addView(qrTitle);

        final ImageView qrImg = new ImageView(a);
        int qsz = Ui.dp(a, 180);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qsz, qsz);
        qlp.topMargin = Ui.dp(a, 8);
        qlp.bottomMargin = Ui.dp(a, 6);
        qrImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrImg.setBackgroundColor(Color.WHITE);
        qrImg.setVisibility(View.GONE);
        qrBox.addView(qrImg, qlp);

        final TextView qrStatus = Ui.text(a, "正在生成绑定二维码…", 13, false);
        qrStatus.setGravity(Gravity.CENTER);
        qrBox.addView(qrStatus);
        box.addView(qrBox);

        // 方式二：6 位绑定码兜底
        TextView mTitle = Ui.text(a, "方式二：输入 6 位绑定码（在 veradroid.wenjin.jp 个人中心获取）", 13, true);
        mTitle.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 6));
        box.addView(mTitle);

        final EditText codeInput = Ui.editText(a);
        codeInput.setHint("输入 6 位数字绑定码");
        codeInput.setSingleLine(true);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setTextSize(18);
        codeInput.setTextColor(Color.BLACK);
        codeInput.setTypeface(Fonts.typeface(a));
        codeInput.setBackground(Ui.frame(a, false));
        codeInput.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));
        box.addView(codeInput);

        final Dialog[] dlgRef = { null };
        dlgRef[0] = new VeraDialog.Builder(a)
            .title("绑定账号后下载")
            .view(box)
            .negative("暂不绑定", new Runnable() {
                @Override public void run() { alive[0] = false; }
            })
            .positive("使用绑定码绑定", true, new Runnable() {
                @Override public void run() {
                    if (gone(a)) return;
                    String code = codeInput.getText().toString().trim();
                    if (!code.matches("\\d{6}")) {
                        VeraToast.show(a, "请输入 6 位数字绑定码，或直接手机扫码绑定");
                        return;
                    }
                    alive[0] = false;
                    if (dlgRef[0] != null) try { dlgRef[0].dismiss(); } catch (Throwable ignored) { }
                    VeraToast.show(a, "正在验证绑定码…");
                    Update.bind(a, code, new Update.BindResult() {
                        @Override public void done(final boolean ok, final String msg) {
                            ui.post(new Runnable() { @Override public void run() {
                                if (gone(a)) return;
                                if (ok) {
                                    VeraToast.show(a, "绑定成功，正在继续下载…");
                                    if (onBound != null) onBound.run();
                                } else {
                                    VeraToast.show(a, "绑定失败：" + msg);
                                }
                            } });
                        }
                    });
                }
            })
            .show();

        if (dlgRef[0] != null) {
            dlgRef[0].setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface d) { alive[0] = false; }
            });
        }

        // 后台申请二维码会话并轮询绑定状态
        final String did = Update.deviceId(a);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int vc = 98;
                    String vn = "1.08";
                    try {
                        android.content.pm.PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
                        vc = pi.versionCode;
                        if (pi.versionName != null) vn = pi.versionName;
                    } catch (Throwable ignored) { }
                    JSONObject body = new JSONObject();
                    body.put("device_id", did);
                    body.put("model", Update.model(a));
                    body.put("version_code", vc);
                    body.put("version_name", vn);
                    JSONObject init = postJson(StoreNet.base() + "/api/device/qr-init", body.toString());
                    if (!init.optBoolean("ok", false)) {
                        final String em = init.optString("error", "无法生成二维码");
                        ui.post(new Runnable() { @Override public void run() {
                            if (!alive[0] || gone(a)) return;
                            qrStatus.setText(em + "（可使用下方 6 位绑定码）");
                        } });
                        return;
                    }
                    final String sid = init.optString("sid", "");
                    final String qrUrl = init.optString("url", "");
                    final String imgUrl = init.optString("qr_image", "");
                    activeSid[0] = sid;
                    StoreNet.evictPrefix("qr-");
                    final Bitmap bm = imgUrl.length() > 0 ? StoreNet.image(imgUrl, "qr-" + sid, 512) : null;
                    ui.post(new Runnable() { @Override public void run() {
                        if (!alive[0] || gone(a)) return;
                        if (bm != null) {
                            qrImg.setImageBitmap(bm);
                            qrImg.setVisibility(View.VISIBLE);
                            qrStatus.setText("用手机扫描上方二维码确认绑定（5 分钟有效）");
                        } else {
                            qrStatus.setText("手机浏览器打开：\n" + qrUrl + "\n确认绑定后自动继续下载");
                        }
                    } });

                    // 每 2 秒轮询一次，最长 5 分钟
                    for (int i = 0; i < 150 && alive[0] && !gone(a); i++) {
                        Thread.sleep(2000);
                        if (!alive[0] || gone(a)) break;
                        try {
                            JSONObject pr = StoreNet.json(StoreNet.base() + "/api/device/qr-poll?sid=" + StoreNet.enc(sid) + "&device_id=" + StoreNet.enc(did));
                            String st = pr.optString("status", "");
                            if ("bound".equals(st)) {
                                final String tok = pr.optString("token", "");
                                final String uname = pr.optString("username", "");
                                if (tok.length() > 0) {
                                    Token.put(a, tok);
                                    alive[0] = false;
                                    ui.post(new Runnable() { @Override public void run() {
                                        if (gone(a)) return;
                                        if (dlgRef[0] != null) try { dlgRef[0].dismiss(); } catch (Throwable ignored) { }
                                        VeraToast.show(a, "已绑定账号" + (uname.length() > 0 ? "「" + uname + "」" : "") + "，正在继续下载…");
                                        if (onBound != null) onBound.run();
                                    } });
                                    return;
                                }
                            } else if ("expired".equals(st)) {
                                ui.post(new Runnable() { @Override public void run() {
                                    if (!alive[0] || gone(a)) return;
                                    qrStatus.setText("二维码已过期，请关闭后重新点下载刷新");
                                } });
                                return;
                            }
                        } catch (Throwable ignored) { }
                    }
                } catch (final Throwable e) {
                    ui.post(new Runnable() { @Override public void run() {
                        if (!alive[0] || gone(a)) return;
                        qrStatus.setText("二维码获取失败，请直接用下方 6 位绑定码");
                    } });
                }
            }
        }, "BindQrPoll").start();
    }

    private static JSONObject postJson(String url, String jsonBody) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        OutputStream os = null;
        InputStream in = null;
        try {
            h.setConnectTimeout(15000);
            h.setReadTimeout(20000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            h.setRequestProperty("User-Agent", "VeraDroid/Store");
            byte[] data = jsonBody.getBytes("UTF-8");
            os = h.getOutputStream();
            os.write(data);
            os.close(); os = null;
            int code = h.getResponseCode();
            in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while (in != null && (n = in.read(b)) > 0) bo.write(b, 0, n);
            return new JSONObject(bo.toString("UTF-8"));
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            h.disconnect();
        }
    }
}
