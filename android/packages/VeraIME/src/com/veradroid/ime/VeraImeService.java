// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * A small black-and-white IME for the e-ink panel: English QWERTY, and a pinyin mode that decodes whole phrases
 * and sentences (PinyinDecoder over assets/pinyin.dat), suggests the next word after a commit (联想) and learns
 * what the user types (UserDict, files/userdict.txt).  It deliberately has no animations, no popups and no glossy
 * graphics: every key press is one local e-ink refresh, and the candidate bar is one view that redraws only itself.
 */
public class VeraImeService extends InputMethodService {
    private static final String TAG = "VeraIME";
    /** API 26 constant (EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); defined here for API 25 builds. */
    private static final int IME_FLAG_NO_PERSONALIZED_LEARNING = 0x1000000;

    private LinearLayout root, keyboard;
    private CandidateBar bar;
    private TextView composing, optionsKey;
    private final List<TextView> letterKeys = new ArrayList<TextView>();
    private TextView shiftKey, modeKey, spaceKey, commaKey, periodKey;
    private int rowH = 140;
    private int lastScreenW, lastScreenH;

    private boolean chinese = true, shift = false, numeric = false, symbols = false, passwordField = false;
    private boolean userChinese = true;   // 用户自己按“中/英”选的模式；密码 / 网址框临时切英文后要回到它
    /** Edit panel (copy / paste / cursor) shown instead of the letter keys; selecting = arrows extend the selection. */
    private boolean editMode = false, selecting = false;
    private int selAnchor = -1;
    private long editHiddenAt;
    private String editHiddenPkg;
    /** Last text we copied (cleared on clip change / input finish; never pasted as fallback). */
    private String lastCopied;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener =
        new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override public void onPrimaryClipChanged() { lastCopied = null; }
        };
    private TextView selectKey;
    private final StringBuilder pinyin = new StringBuilder();

    // ---- pinyin engine
    private PinyinDict dict;
    private PinyinDecoder decoder;
    private UserDict user;
    /** a candidate chosen for the first part of the pinyin; the rest is still being chosen */
    private static final class Pick {
        final String text, raw;
        final PinyinDecoder.Cand cand;
        Pick(String t, String r, PinyinDecoder.Cand c) { text = t; raw = r; cand = c; }
    }
    private final ArrayList<Pick> picks = new ArrayList<Pick>();
    private boolean justPicked;
    /** non-null: the bar shows words that may follow the last commit (联想) */
    private List<PinyinDecoder.Cand> predictions;
    /** the last committed phrase: context for decoding and suggestions; -1 / null = unknown */
    private int ctxId = -1;
    private String ctxText;
    private boolean awaitSel;
    private int ctxAt = -1;
    // ---- options (the 选项 key in the top line)
    private boolean optionsMode, predictOn = true, learnOn = true, noLearn = false;
    private long clearArmed;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService saveWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger saveGen = new java.util.concurrent.atomic.AtomicInteger();
    private float keySp = 24f;
    private float smallKeySp = 16f;
    private float editKeySp = 22f;

    @Override public void onCreate() {
        super.onCreate();
        SharedPreferences sp = getSharedPreferences("ime", MODE_PRIVATE);
        predictOn = sp.getBoolean("predict", true);
        learnOn = sp.getBoolean("learn", true);
        try {
            ClipboardManager cm = clipboard();
            if (cm != null) cm.addPrimaryClipChangedListener(clipListener);
        } catch (Throwable ignored) { }
        openEngine();
    }

    @Override public void onDestroy() {
        lastCopied = null;
        try {
            ClipboardManager cm = clipboard();
            if (cm != null) cm.removePrimaryClipChangedListener(clipListener);
        } catch (Throwable ignored) { }
        saveUser();
        saveWorker.shutdown();
        super.onDestroy();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.BLACK);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private StateListDrawable keyBackground() {
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.WHITE);
        normal.setStroke(dp(2), Color.BLACK);
        normal.setCornerRadius(dp(6));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.BLACK);
        pressed.setStroke(dp(2), Color.BLACK);
        pressed.setCornerRadius(dp(6));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_pressed }, pressed);
        s.addState(new int[] { android.R.attr.state_selected }, pressed);
        s.addState(new int[] {}, normal);
        return s;
    }

    private TextView key(String label, float weight, View.OnClickListener l) {
        TextView t = text(label, keySp, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(keyBackground());
        t.setTextColor(new android.content.res.ColorStateList(
            new int[][] { { android.R.attr.state_pressed }, { android.R.attr.state_selected }, {} },
            new int[] { Color.WHITE, Color.WHITE, Color.BLACK }));
        t.setClickable(true);
        t.setFocusable(false);
        t.setSoundEffectsEnabled(false);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    @Override public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(6), dp(2), dp(6), dp(4));

        // Fixed height: the candidate bar must not make the keyboard jump under the finger when the first
        // pinyin letter turns into candidates. Adapt height cleanly to landscape panels so row 4 is never clipped.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        lastScreenW = dm.widthPixels;
        lastScreenH = dm.heightPixels;
        boolean land = dm.widthPixels > dm.heightPixels;
        int padY = root.getPaddingTop() + root.getPaddingBottom();
        int topH = dp(land ? 24 : 26), candH = dp(land ? 40 : 44);
        int targetTotal = (int) (dm.heightPixels * (land ? 0.50f : 0.44f));
        int minKb = dp(land ? 180 : 280);
        int maxKb = Math.max(dp(160), (int) (dm.heightPixels * (land ? 0.58f : 0.52f)) - topH - candH - padY);
        int kbH = Math.min(maxKb, Math.max(minKb, targetTotal - topH - candH - padY));
        rowH = Math.max(dp(38), kbH / 4);
        kbH = rowH * 4;
        keySp = rowH < dp(52) ? 18f : rowH < dp(62) ? 21f : 24f;
        smallKeySp = rowH < dp(52) ? 13f : rowH < dp(62) ? 15f : 16f;
        editKeySp = rowH < dp(52) ? 16f : rowH < dp(62) ? 19f : 22f;
        int total = padY + topH + candH + kbH;
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, total));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), 0, dp(8), 0);
        composing = text("", 18, true);
        composing.setSingleLine(true);
        top.addView(composing, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        optionsKey = text("选项", 16, false);
        optionsKey.setGravity(Gravity.CENTER);
        optionsKey.setPadding(dp(14), 0, dp(6), 0);
        optionsKey.setSoundEffectsEnabled(false);
        optionsKey.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openOptions(!optionsMode); }
        });
        top.addView(optionsKey, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topH));

        bar = new CandidateBar(this);
        bar.setListener(new CandidateBar.Listener() {
            @Override public void onPick(int index) { pick(index); }
        });
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, candH));

        keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.VERTICAL);
        root.addView(keyboard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, kbH));

        buildKeyboard();
        updateCandidates();
        return root;
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (dm.widthPixels == lastScreenW && dm.heightPixels == lastScreenH) return;
        setInputView(onCreateInputView());
    }

    private static boolean isNoLearnEditor(EditorInfo info) {
        if (info == null) return false;
        if ((info.imeOptions & IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            if ((info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0) return true;
            if (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) return true;
        } else if (cls == InputType.TYPE_CLASS_NUMBER) {
            if (var == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true;
        }
        return false;
    }

    private boolean canLearn() {
        return learnOn && !noLearn && user != null;
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        pinyin.setLength(0);
        picks.clear();
        predictions = null;
        clearContext();
        selecting = false; selAnchor = -1;
        // 变体是枚举值，不是位：以前按位与 PASSWORD(0x80)，网页输入框(0xa0)、搜索过滤框(0xb0)也被当成密码框切成英文，
        // 而且之后所有普通输入框都一直是英文。现在密码 / 网址 / 邮箱框临时用英文，别的框回到用户自己选的模式。
        chinese = userChinese;
        passwordField = false;
        noLearn = isNoLearnEditor(info);
        if (info != null) {
            int cls = info.inputType & InputType.TYPE_MASK_CLASS;
            int var = info.inputType & InputType.TYPE_MASK_VARIATION;
            boolean text = cls == InputType.TYPE_CLASS_TEXT;
            boolean password = (text && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            passwordField = password;
            if (password) chinese = false;
            numeric = cls == InputType.TYPE_CLASS_NUMBER
                || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;
            symbols = text && (var == InputType.TYPE_TEXT_VARIATION_URI
                || var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
            if (symbols) { chinese = false; numeric = false; }
        }
        if (root != null) {
            if (!page().equals(builtPage) || keyboard.getChildCount() == 0) buildKeyboard();
            updateKeyLabels();
            updateCandidates();
        }
    }

    /** Keyboard hidden: the next time it comes up it shows the letters again -- except when the user only moved
     *  to another field of the same app (the keyboard hides and comes back within a moment): the edit panel
     *  stays, so copy in one field and paste in the next works without reopening it. */
    @Override public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (finishingInput) lastCopied = null;
        if (optionsMode) { optionsMode = false; if (root != null) { buildKeyboard(); updateCandidates(); } }
        if (editMode) {
            EditorInfo ei = getCurrentInputEditorInfo();
            editHiddenAt = SystemClock.uptimeMillis();
            editHiddenPkg = ei != null ? ei.packageName : null;
            editMode = false;
            if (root != null) { buildKeyboard(); updateCandidates(); }
        }
    }

    /**
     * Keyboard window gone: what was under it comes back as one big change. In 均衡 the display bridge sends a change of
     * less than KFB_REAGL_MIN (35 %) of the screen as DU (1-bit, does not clean), so the keys stayed on the panel as a
     * ghost. A one-shot hint to the bridge (display/epd.c HINT, the same one the pull-down panel uses): for the next
     * 2.5 s (the app relayouts in several frames), changes of at least 5 % of the screen go out as REAGL (16 grays, cleans ghosts, no flash). Non-blocking:
     * no bridge reading the FIFO = dropped.
     */
    @Override public void onWindowHidden() {
        super.onWindowHidden();
        saveUser();
        toBridge("HINT 2500 5\n");
    }

    /**
     * Keyboard window coming up: the app relayouts above it and the keyboard covers the bottom half - a large change
     * that is not a page turn. NOFLIP (display/epd.c page_like) keeps it out of the bridge's KFB_FLASH page count, so
     * a reader app with a page-count flash does not end a few keyboard openings in a full black flash (0.96 report).
     * A bridge that does not know NOFLIP ignores the line.
     */
    @Override public void onWindowShown() {
        super.onWindowShown();
        toBridge("NOFLIP 2500\n");
    }

    private static void toBridge(String line) {
        java.io.FileDescriptor fd = null;
        try {
            fd = android.system.Os.open("/dev/kfb/epd.fifo", android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NONBLOCK, 0);
            byte[] b = line.getBytes();
            android.system.Os.write(fd, b, 0, b.length);
        } catch (Throwable ignored) {
        } finally {
            if (fd != null) try { android.system.Os.close(fd); } catch (Throwable ignored) { }
        }
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        noLearn = isNoLearnEditor(info != null ? info : getCurrentInputEditorInfo());
        boolean back = editHiddenAt > 0 && SystemClock.uptimeMillis() - editHiddenAt < 2000
            && info != null && editHiddenPkg != null && editHiddenPkg.equals(info.packageName);
        editHiddenAt = 0;
        if (back && !editMode && root != null) {
            editMode = true;
            buildKeyboard();
            updateCandidates();
        }
    }

    @Override public void onFinishInput() {
        pinyin.setLength(0);
        picks.clear();
        predictions = null;
        lastCopied = null;
        saveUser();
        super.onFinishInput();
    }

    /** The cursor moved somewhere we did not put it: the last commit is no context any more, 联想 goes away. */
    @Override public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                            int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (awaitSel) { awaitSel = false; ctxAt = newSelEnd; return; }
        if (ctxAt >= 0 && (newSelStart != ctxAt || newSelEnd != ctxAt)) {
            clearContext();
            if (predictions != null && pinyin.length() == 0 && picks.isEmpty()) { predictions = null; updateCandidates(); }
        }
    }

    private void clearContext() { ctxId = -1; ctxText = null; ctxAt = -1; awaitSel = false; }

    @Override public boolean onEvaluateFullscreenMode() {
        return false;
    }

    // ------------------------------------------------------------------ dictionary

    /**
     * Map assets/pinyin.dat (stored uncompressed in the APK: aapt -0 .dat) read-only: the dictionary costs no heap and
     * only the pages a lookup touches count towards the process' memory.  Then the user's own dictionary.
     */
    private void openEngine() {
        long t0 = SystemClock.uptimeMillis();
        try {
            ByteBuffer buf;
            AssetFileDescriptor fd = null;
            try { fd = getAssets().openFd("pinyin.dat"); } catch (Exception compressed) { }
            if (fd != null) {
                try {
                    FileInputStream in = fd.createInputStream();
                    try {
                        buf = in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getLength());
                    } finally {
                        try { in.close(); } catch (Throwable ignored) { }
                    }
                } finally {
                    try { fd.close(); } catch (Throwable ignored) { }
                }
            } else {                            // stored compressed after all: read it once into native memory
                InputStream in = getAssets().open("pinyin.dat");
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(1 << 22);
                try {
                    byte[] b = new byte[65536];
                    for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                }
                buf = ByteBuffer.allocateDirect(o.size());
                buf.put(o.toByteArray()).flip();
            }
            dict = new PinyinDict(buf);
            decoder = new PinyinDecoder(dict);
            user = new UserDict(dict);
            decoder.setUser(user);
            File f = userFile();
            if (f.exists()) {
                InputStreamReader r = new InputStreamReader(new FileInputStream(f), "UTF-8");
                try { user.load(r); } finally { r.close(); }
            }
            Log.i(TAG, "pinyin: " + dict.nWords + " words, " + dict.nBi + " bigrams, user " + user.phraseCount() + "/"
                + user.pairCount() + ", " + (SystemClock.uptimeMillis() - t0) + " ms" + (fd == null ? " (compressed asset)" : ""));
        } catch (Throwable t) {
            Log.e(TAG, "pinyin dictionary not usable, letters are committed as typed", t);
            dict = null; decoder = null;
        }
    }

    private File userFile() { return new File(getFilesDir(), "userdict.txt"); }

    /** Write the user dictionary if it changed: built on this thread (small), written by another, atomically. */
    private long lastSave;

    private void saveUser() {
        if (user == null || !user.dirty || saveWorker.isShutdown()) return;
        lastSave = SystemClock.uptimeMillis();
        final StringWriter w = new StringWriter();
        try { user.save(w); } catch (Exception e) { return; }
        final UserDict u = user;
        final int rev = u.rev;
        final String snapshot = w.toString();
        final int gen = saveGen.incrementAndGet();
        final File f = userFile(), tmp = new File(f.getPath() + ".tmp");
        try {
            saveWorker.execute(new Runnable() {
                @Override public void run() {
                    boolean ok = false;
                    synchronized (VeraImeService.class) {
                        if (saveGen.get() != gen) return;
                        FileOutputStream fos = null;
                        try {
                            fos = new FileOutputStream(tmp);
                            Writer o = new OutputStreamWriter(fos, "UTF-8");
                            o.write(snapshot);
                            o.flush();
                            fos.getFD().sync();
                            o.close();
                            fos = null;
                            if (saveGen.get() != gen) { tmp.delete(); return; }
                            if (tmp.renameTo(f)) {
                                ok = true;
                            } else {
                                tmp.delete();
                                Log.w(TAG, "userdict: rename failed");
                            }
                        } catch (Exception e) {
                            tmp.delete();
                            Log.w(TAG, "userdict: " + e);
                        } finally {
                            if (fos != null) try { fos.close(); } catch (Exception ignored) { }
                        }
                    }
                    if (ok) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (user == u && user.rev == rev && saveGen.get() == gen) user.dirty = false;
                            }
                        });
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private void clearUser() {
        final int gen = saveGen.incrementAndGet();
        if (user != null) {
            user.clear();
            user.dirty = false;
        }
        clearContext();
        final File f = userFile(), tmp = new File(f.getPath() + ".tmp");
        if (!saveWorker.isShutdown()) {
            try {
                saveWorker.execute(new Runnable() {
                    @Override public void run() {
                        synchronized (VeraImeService.class) {
                            if (saveGen.get() != gen) return;
                            f.delete();
                            tmp.delete();
                        }
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) { }
        }
        Log.i(TAG, "userdict cleared");
    }

    // ------------------------------------------------------------------ candidates

    private boolean composingNow() { return pinyin.length() > 0 || !picks.isEmpty(); }

    private int contextId() {
        if (!picks.isEmpty()) return picks.get(picks.size() - 1).cand.lastId();
        return ctxId;
    }

    private void updateCandidates() {
        if (bar == null || composing == null) return;
        if (optionsMode) { composing.setText("输入法选项"); bar.setSource(null); return; }
        if (editMode) {
            composing.setText(selecting ? "编辑 · 选择中：用方向键扩大选区" : "编辑");
            bar.setSource(null);
            return;
        }
        if (numeric) { composing.setText("数字"); bar.setSource(null); return; }
        if (symbols) { composing.setText("符号 / 网址"); bar.setSource(null); return; }
        if (!chinese) {
            composing.setText("English");
            bar.setSource(null);
            return;
        }
        if (!composingNow()) {
            final List<PinyinDecoder.Cand> p = predictions;
            composing.setText(p != null ? "联想" : "拼音输入");
            bar.setSource(p == null ? null : new CandidateBar.Source() {
                @Override public boolean has(int i) { return i < p.size(); }
                @Override public String get(int i) { return p.get(i).text; }
            });
            return;
        }
        predictions = null;
        StringBuilder line = new StringBuilder();
        for (Pick p : picks) line.append(p.text);
        if (line.length() > 0 && pinyin.length() > 0) line.append(' ');
        line.append(pinyin);
        composing.setText(line);
        if (decoder == null) { bar.setSource(null); return; }
        long t0 = SystemClock.uptimeMillis();
        decoder.decode(pinyin.toString(), contextId());
        final PinyinDecoder dec = decoder;
        bar.setSource(new CandidateBar.Source() {
            @Override public boolean has(int i) { return dec.has(i); }
            @Override public String get(int i) { return dec.get(i).text; }
        });
        if (Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "decode " + pinyin + " " + (SystemClock.uptimeMillis() - t0) + " ms, " + dec.visited + " nodes; bar " + bar.describe());
    }

    /** A candidate was tapped (or chosen with space / punctuation). */
    private void pick(int index) {
        if (!composingNow()) {
            List<PinyinDecoder.Cand> p = predictions;
            if (p == null || index >= p.size()) return;
            PinyinDecoder.Cand c = p.get(index);
            String prev = ctxText;
            commitRaw(c.text);
            if (canLearn()) user.learnPair(prev, c.text);
            ctxId = c.lastId();
            ctxText = c.text;
            suggest();
            return;
        }
        if (decoder == null || !decoder.has(index)) return;
        PinyinDecoder.Cand c = decoder.get(index);
        choose(c);
        if (hasLetters()) { justPicked = true; updateCandidates(); }
        else finish();
    }

    /** take candidate c for the first c.end characters of the pinyin (and the separators after them) */
    private void choose(PinyinDecoder.Cand c) {
        int used = Math.min(c.end, pinyin.length());
        while (used < pinyin.length() && pinyin.charAt(used) == '\'') used++;
        picks.add(new Pick(c.text, pinyin.substring(0, used), c));
        pinyin.delete(0, used);
    }

    private boolean hasLetters() {
        for (int i = 0; i < pinyin.length(); i++) if (PinyinDecoder.isLetter(pinyin.charAt(i))) return true;
        return false;
    }

    /** Everything is chosen: commit it, learn it, show what may follow. */
    private void finish() {
        StringBuilder t = new StringBuilder();
        for (Pick p : picks) t.append(p.text);
        String text = t.toString();
        if (canLearn() && decoder != null) {
            user.beginBatch();
            try {
                // every phrase chosen gets cheaper; several choices make one new phrase of the user's own
                StringBuilder key = new StringBuilder();
                boolean whole = true;
                for (Pick p : picks) {
                    int[] syl = decoder.syllables(p.cand);
                    if (syl == null) { whole = false; continue; }
                    for (int s : syl) key.append((char) s);
                    user.learn(syl, p.text);
                }
                if (whole && picks.size() > 1 && key.length() == text.length()) {
                    int[] syl = new int[key.length()];
                    for (int i = 0; i < syl.length; i++) syl[i] = key.charAt(i);
                    user.learn(syl, text);
                }
                user.learnPair(ctxText, text);
            } finally {
                user.endBatch();
            }
        }
        int last = picks.isEmpty() ? -1 : picks.get(picks.size() - 1).cand.lastId();
        if (last < 0 && decoder != null && !picks.isEmpty() && text.length() > 0) {
            // a phrase of the user's own: the dictionary's followers of its last character
            int[] syl = decoder.syllables(picks.get(picks.size() - 1).cand);
            if (syl != null && syl.length > 0) last = dict.find(new int[] { syl[syl.length - 1] }, text.substring(text.length() - 1));
        }
        picks.clear();
        pinyin.setLength(0);
        justPicked = false;
        commitRaw(text);
        ctxId = last;
        ctxText = text;
        suggest();
        // also while the keyboard stays up for a long time (it is saved when it hides), at most once a minute
        if (user != null && user.dirty && SystemClock.uptimeMillis() - lastSave > 60000) saveUser();
    }

    /** choose the best candidate until nothing is left (space, punctuation) */
    private void finishAll() {
        for (int guard = 0; guard < 64 && hasLetters(); guard++) {
            if (decoder == null || (decoder.decode(pinyin.toString(), contextId()) != null && !decoder.has(0))) {
                picks.add(new Pick(pinyin.toString().replace("'", ""), pinyin.toString(), new PinyinDecoder.Cand()));
                pinyin.setLength(0);
                break;
            }
            choose(decoder.get(0));
        }
        if (!picks.isEmpty()) finish();
        else { pinyin.setLength(0); updateCandidates(); }
    }

    /** 联想: what may follow the last commit, in the bar */
    private void suggest() {
        predictions = null;
        if (predictOn && decoder != null && (ctxId >= 0 || ctxText != null)) {
            long t0 = SystemClock.uptimeMillis();
            List<PinyinDecoder.Cand> p = decoder.predict(ctxId, ctxText, 40);
            if (!p.isEmpty()) predictions = p;
            t0 = SystemClock.uptimeMillis() - t0;
            updateCandidates();
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "predict " + ctxText + " " + t0 + " ms; bar " + bar.describe());
            return;
        }
        updateCandidates();
    }

    /** any key but a candidate: the suggestions go */
    private void dismiss() {
        if (predictions != null) { predictions = null; if (!composingNow()) updateCandidates(); }
    }

    // ------------------------------------------------------------------ options

    private void openOptions(boolean on) {
        if (on) {
            pinyin.setLength(0);
            picks.clear();
            predictions = null;
            editMode = false;
        }
        optionsMode = on;
        clearArmed = 0;
        buildKeyboard();
        updateCandidates();
    }

    private void setPref(String k, boolean v) {
        getSharedPreferences("ime", MODE_PRIVATE).edit().putBoolean(k, v).apply();
    }

    /** Options page, same 4-row height as the letters:  联想 / 学习 / 清除输入记录 / 返回键盘 */
    private void buildOptions() {
        LinearLayout r1 = row();
        final TextView pk = editButton(r1, "联想（上屏后显示可能的下一个词）：" + (predictOn ? "开" : "关"), 1f, null);
        pk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                predictOn = !predictOn; setPref("predict", predictOn);
                pk.setText("联想（上屏后显示可能的下一个词）：" + (predictOn ? "开" : "关"));
            }
        });
        keyboard.addView(r1, rowParams());
        LinearLayout r2 = row();
        final TextView lk = editButton(r2, "记住我打的词：" + (learnOn ? "开" : "关"), 1f, null);
        lk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                learnOn = !learnOn; setPref("learn", learnOn);
                lk.setText("记住我打的词：" + (learnOn ? "开" : "关"));
            }
        });
        keyboard.addView(r2, rowParams());
        LinearLayout r3 = row();
        final TextView ck = editButton(r3, clearLabel(), 1f, null);
        ck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long now = SystemClock.uptimeMillis();
                if (clearArmed > 0 && now - clearArmed < 5000) {
                    clearUser();
                    clearArmed = 0;
                    ck.setText("已清除输入记录");
                } else {
                    clearArmed = now;
                    ck.setText("再点一次确认清除（不能恢复）");
                }
            }
        });
        keyboard.addView(r3, rowParams());
        LinearLayout r4 = row();
        editButton(r4, "返回键盘", 1f, new View.OnClickListener() { @Override public void onClick(View v) { openOptions(false); } });
        keyboard.addView(r4, rowParams());
    }

    private String clearLabel() {
        int n = user == null ? 0 : user.phraseCount() + user.pairCount();
        return "清除输入记录（" + n + " 条）";
    }

    // ------------------------------------------------------------------ keyboard

    /** which page the keyboard shows now; onStartInput does not rebuild an identical one */
    private String builtPage;

    private String page() {
        return optionsMode ? "options" : editMode ? "edit" : symbols ? "symbols" : numeric ? "numeric" : "letters";
    }

    private void settleComposing() {
        if (chinese && composingNow()) finishAll();
        pinyin.setLength(0);
        picks.clear();
        justPicked = false;
        predictions = null;
        clearContext();
    }

    private void buildKeyboard() {
        builtPage = page();
        keyboard.removeAllViews();
        letterKeys.clear();
        selectKey = null;
        modeKey = null;
        spaceKey = null;
        commaKey = null;
        periodKey = null;
        shiftKey = null;

        if (optionsMode) { buildOptions(); return; }
        if (editMode) { buildEditPanel(); return; }
        if (symbols) { buildSymbolKeyboard(); return; }
        if (numeric) {
            buildNumericKeyboard();
            return;
        }

        LinearLayout row1 = row();
        for (char c : "qwertyuiop".toCharArray()) addLetter(row1, c);
        keyboard.addView(row1, rowParams());

        LinearLayout row2 = row();
        for (char c : "asdfghjkl".toCharArray()) addLetter(row2, c);
        keyboard.addView(row2, rowParams());

        LinearLayout row3 = row();
        shiftKey = key("Shift", 1.5f, new View.OnClickListener() {
            @Override public void onClick(View v) { shift = !shift; updateKeyLabels(); }
        });
        shiftKey.setTextSize(smallKeySp);
        row3.addView(shiftKey);
        for (char c : "zxcvbnm".toCharArray()) addLetter(row3, c);
        TextView back = key("退格", 1.5f, new View.OnClickListener() {
            @Override public void onClick(View v) { backspace(); }
        });
        back.setTextSize(smallKeySp);
        row3.addView(back);
        keyboard.addView(row3, rowParams());

        LinearLayout row4 = row();
        modeKey = key("中", 1.4f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (chinese && composingNow()) finishAll();
                chinese = !chinese; userChinese = chinese; pinyin.setLength(0); predictions = null; clearContext();
                updateKeyLabels(); updateCandidates();
            }
        });
        row4.addView(modeKey);
        commaKey = key("，", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { punct(useFullWidthPunct() ? "，" : ","); }
        });
        row4.addView(commaKey);
        spaceKey = key("空格", 2.6f, new View.OnClickListener() { @Override public void onClick(View v) { space(); } });
        row4.addView(spaceKey);
        periodKey = key("。", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { punct(useFullWidthPunct() ? "。" : "."); }
        });
        row4.addView(periodKey);
        TextView num = key("123", 1.2f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                settleComposing();
                numeric = true; symbols = false; buildKeyboard(); updateCandidates();
            }
        });
        num.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                settleComposing();
                symbols = true; numeric = false; buildKeyboard(); updateCandidates(); return true;
            }
        });
        row4.addView(num);
        row4.addView(editKey());
        row4.addView(key("回车", 1.6f, new View.OnClickListener() { @Override public void onClick(View v) { enter(); } }));
        keyboard.addView(row4, rowParams());

        updateKeyLabels();
    }

    /** Numeric page: big keys for 6-digit binding codes, Wi-Fi passwords and amounts. */
    private void buildNumericKeyboard() {
        LinearLayout r1 = row();
        for (char c : "123".toCharArray()) addDigit(r1, c);
        keyboard.addView(r1, rowParams());
        LinearLayout r2 = row();
        for (char c : "456".toCharArray()) addDigit(r2, c);
        keyboard.addView(r2, rowParams());
        LinearLayout r3 = row();
        for (char c : "789".toCharArray()) addDigit(r3, c);
        keyboard.addView(r3, rowParams());
        LinearLayout r4 = row();
        TextView abc = key("ABC", 1.4f, new View.OnClickListener() {
            @Override public void onClick(View v) { numeric = false; buildKeyboard(); updateCandidates(); }
        });
        abc.setTextSize(smallKeySp);
        r4.addView(abc);
        addDigit(r4, '0');
        TextView back = key("退格", 1.5f, new View.OnClickListener() {
            @Override public void onClick(View v) { backspace(); }
        });
        back.setTextSize(smallKeySp);
        r4.addView(back);
        r4.addView(key("符", 1.2f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                settleComposing();
                symbols = true; numeric = false; buildKeyboard(); updateCandidates();
            }
        }));
        r4.addView(editKey());
        r4.addView(key("回车", 1.6f, new View.OnClickListener() { @Override public void onClick(View v) { enter(); } }));
        keyboard.addView(r4, rowParams());
        if (composing != null) composing.setText("数字");
    }

    /** URL / symbol page: . / : ? = & % and the rest of what a book-source URL needs. */
    private void buildSymbolKeyboard() {
        LinearLayout r1 = row();
        for (char c : "1234567890".toCharArray()) addSymbol(r1, String.valueOf(c));
        keyboard.addView(r1, rowParams());
        LinearLayout r2 = row();
        for (String s : new String[] { "-", "/", ":", ";", "(", ")", "$", "&", "@" }) addSymbol(r2, s);
        keyboard.addView(r2, rowParams());
        LinearLayout r3 = row();
        for (String s : new String[] { ".", ",", "?", "!", "'", "\"", "_", "+", "=", "%", "#" }) addSymbol(r3, s);
        keyboard.addView(r3, rowParams());
        LinearLayout r4 = row();
        r4.addView(key("ABC", 1.4f, new View.OnClickListener() {
            @Override public void onClick(View v) { symbols = false; numeric = false; buildKeyboard(); updateCandidates(); }
        }));
        r4.addView(key("空格", 2.0f, new View.OnClickListener() {
            @Override public void onClick(View v) { space(); }
        }));
        r4.addView(editKey());
        r4.addView(key("退格", 1.5f, new View.OnClickListener() {
            @Override public void onClick(View v) { backspace(); }
        }));
        r4.addView(key("回车", 1.6f, new View.OnClickListener() {
            @Override public void onClick(View v) { enter(); }
        }));
        keyboard.addView(r4, rowParams());
        if (composing != null) composing.setText("符号 / 网址");
    }


    /** The one key that opens the edit panel; it is on every keyboard page. */
    private TextView editKey() {
        TextView k = key("编辑", 1.2f, new View.OnClickListener() {
            @Override public void onClick(View v) { openEdit(true); }
        });
        k.setTextSize(18);
        return k;
    }

    private void openEdit(boolean on) {
        pinyin.setLength(0);            // unfinished pinyin is dropped, never left half-typed under the panel
        picks.clear();
        predictions = null;
        optionsMode = false;
        InputConnection c = ic();
        if (c != null) c.finishComposingText();
        editMode = on;
        selecting = false;
        selAnchor = -1;
        buildKeyboard();
        updateCandidates();
    }

    private TextView editButton(LinearLayout row, String label, float weight, View.OnClickListener l) {
        TextView k = key(label, weight, l);
        k.setTextSize(editKeySp);
        row.addView(k);
        return k;
    }

    /**
     * Edit panel, same 4-row height as the letters so nothing moves on the panel:
     *   全选 复制 剪切 粘贴 / 选择 行首 上移 行尾 / 撤销 左移 下移 右移 / 返回键盘 退格 回车
 *   (the system font has no arrow glyphs, so the labels are words)
     * Each tap is one local e-ink refresh; nothing animates.
     */
    private void buildEditPanel() {
        LinearLayout r1 = row();
        editButton(r1, "全选", 1f, new View.OnClickListener() { @Override public void onClick(View v) { selectAll(); } });
        editButton(r1, "复制", 1f, new View.OnClickListener() { @Override public void onClick(View v) { copy(false); } });
        editButton(r1, "剪切", 1f, new View.OnClickListener() { @Override public void onClick(View v) { copy(true); } });
        editButton(r1, "粘贴", 1f, new View.OnClickListener() { @Override public void onClick(View v) { paste(); } });
        keyboard.addView(r1, rowParams());

        LinearLayout r2 = row();
        selectKey = editButton(r2, "选择", 1.3f, new View.OnClickListener() { @Override public void onClick(View v) { toggleSelect(); } });
        selectKey.setSelected(selecting);
        editButton(r2, "行首", 1f, new View.OnClickListener() { @Override public void onClick(View v) { moveLine(false); } });
        editButton(r2, "上移", 1f, new View.OnClickListener() { @Override public void onClick(View v) { arrow(KeyEvent.KEYCODE_DPAD_UP); } });
        editButton(r2, "行尾", 1f, new View.OnClickListener() { @Override public void onClick(View v) { moveLine(true); } });
        keyboard.addView(r2, rowParams());

        LinearLayout r3 = row();
        editButton(r3, "撤销", 1.3f, new View.OnClickListener() { @Override public void onClick(View v) { undo(); } });
        editButton(r3, "左移", 1f, new View.OnClickListener() { @Override public void onClick(View v) { moveChar(-1); } });
        editButton(r3, "下移", 1f, new View.OnClickListener() { @Override public void onClick(View v) { arrow(KeyEvent.KEYCODE_DPAD_DOWN); } });
        editButton(r3, "右移", 1f, new View.OnClickListener() { @Override public void onClick(View v) { moveChar(1); } });
        keyboard.addView(r3, rowParams());

        LinearLayout r4 = row();
        editButton(r4, "返回键盘", 2f, new View.OnClickListener() { @Override public void onClick(View v) { openEdit(false); } });
        editButton(r4, "退格", 1.1f, new View.OnClickListener() { @Override public void onClick(View v) { backspace(); } });
        editButton(r4, "回车", 1.2f, new View.OnClickListener() { @Override public void onClick(View v) { enter(); } });
        keyboard.addView(r4, rowParams());
    }

    private void status(String s) {
        Log.i(TAG, "edit: " + s);
        if (composing != null) composing.setText(s);
    }

    private ExtractedText extracted() {
        InputConnection c = ic();
        if (c == null) return null;
        try {
            ExtractedText et = c.getExtractedText(new ExtractedTextRequest(), 0);
            return (et != null && et.text != null && et.selectionStart >= 0 && et.selectionEnd >= 0) ? et : null;
        } catch (Throwable t) { return null; }
    }

    private ClipboardManager clipboard() {
        return (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private void selectAll() {
        InputConnection c = ic();
        if (c == null) return;
        selecting = false; selAnchor = -1;
        if (selectKey != null) selectKey.setSelected(false);
        if (!c.performContextMenuAction(android.R.id.selectAll)) {
            ExtractedText et = extracted();
            if (et != null) c.setSelection(et.startOffset, et.startOffset + et.text.length());
            else { c.sendKeyEvent(keyEv(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON));
                   c.sendKeyEvent(keyEv(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON)); }
        }
        status("已全选");
    }

    /** Copy (or cut) the selection.  We put the text on the clipboard ourselves when the app tells us what is
     *  selected; only apps that do not (getSelectedText == null) get the context-menu action. */
    private void copy(boolean cut) {
        InputConnection c = ic();
        if (c == null) return;
        CharSequence sel = null;
        try { sel = c.getSelectedText(0); } catch (Throwable ignored) { }
        if (sel == null) {
            ExtractedText et = extracted();
            if (et != null && et.selectionStart == et.selectionEnd) sel = "";   // the app reports no selection
        }
        if (sel != null && sel.length() > 0) {
            boolean copied = false;
            try {
                String s = sel.toString();
                ClipboardManager cm = clipboard();
                if (cm == null) throw new IllegalStateException("no clipboard");
                cm.setPrimaryClip(ClipData.newPlainText("VeraIME", s));
                lastCopied = s;
                copied = true;
            } catch (Throwable t) {
                lastCopied = null;
                if (!c.performContextMenuAction(cut ? android.R.id.cut : android.R.id.copy)) {
                    status(cut ? "剪切失败" : "复制失败");
                    return;
                }
            }
            if (cut && copied) c.commitText("", 1);
            status((cut ? "已剪切 " : "已复制 ") + sel.length() + " 字");
        } else if (sel == null && c.performContextMenuAction(cut ? android.R.id.cut : android.R.id.copy)) {
            status(cut ? "已剪切" : "已复制");
        } else {
            status("请先选中文字（全选，或 选择 + 方向键）");
            return;
        }
        selecting = false; selAnchor = -1;
        if (selectKey != null) selectKey.setSelected(false);
    }

    /** Paste the clipboard as plain text (works the same in every app, also when another app filled the
     *  clipboard); non-text clips are left to the app's own paste action. */
    private void paste() {
        InputConnection c = ic();
        if (c == null) return;
        CharSequence text = null;
        try {
            ClipboardManager cm = clipboard();
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    CharSequence t = clip.getItemAt(i).coerceToText(this);
                    if (t != null) { if (b.length() > 0) b.append('\n'); b.append(t); }
                }
                text = b;
            }
        } catch (Throwable ignored) { }
        if (text == null || text.length() == 0) {
            lastCopied = null;
        }
        if (text != null && text.length() > 0) {
            c.commitText(text.toString(), 1);
            status("已粘贴 " + text.length() + " 字");
        } else if (c.performContextMenuAction(android.R.id.paste)) {
            status("已粘贴");
        } else {
            status("剪贴板是空的");
        }
        selecting = false; selAnchor = -1;
        if (selectKey != null) selectKey.setSelected(false);
    }

    private void undo() {
        InputConnection c = ic();
        if (c == null) return;
        // TextView, WebView and most editors take Ctrl+Z as a key shortcut.
        int meta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        c.sendKeyEvent(keyEv(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, meta));
        c.sendKeyEvent(keyEv(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, meta));
        status("撤销");
    }

    private void toggleSelect() {
        selecting = !selecting;
        selAnchor = -1;
        if (selecting) {
            ExtractedText et = extracted();
            if (et != null) selAnchor = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
        }
        if (selectKey != null) selectKey.setSelected(selecting);
        status(selecting ? "编辑 · 选择中：用方向键扩大选区" : "编辑");
    }

    private KeyEvent keyEv(int action, int code, int meta) {
        long t = SystemClock.uptimeMillis();
        return new KeyEvent(t, t, action, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE);
    }

    /** Arrow key as a key event (shift held while selecting): used for up/down and when the app gives no text. */
    private void arrow(int code) {
        InputConnection c = ic();
        if (c == null) return;
        int meta = selecting ? (KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON) : 0;
        c.sendKeyEvent(keyEv(KeyEvent.ACTION_DOWN, code, meta));
        c.sendKeyEvent(keyEv(KeyEvent.ACTION_UP, code, meta));
        // the key may have moved the selection; re-read the anchor next time
        if (selecting && selAnchor < 0) {
            ExtractedText et = extracted();
            if (et != null) selAnchor = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
        }
    }

    /** Current cursor (the moving end of the selection), absolute offset. */
    private int cursorOf(ExtractedText et) {
        int a = et.startOffset + et.selectionStart, b = et.startOffset + et.selectionEnd;
        if (selecting && selAnchor >= 0) return a == selAnchor ? b : a;
        return b;
    }

    private void placeCursor(InputConnection c, ExtractedText et, int pos) {
        int len = et.startOffset + et.text.length();
        pos = Math.max(0, Math.min(len, pos));
        if (selecting) {
            if (selAnchor < 0) selAnchor = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
            c.setSelection(selAnchor, pos);
        } else {
            c.setSelection(pos, pos);
        }
    }

    private void moveChar(int dir) {
        InputConnection c = ic();
        if (c == null) return;
        ExtractedText et = extracted();
        if (et == null) { arrow(dir < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT); return; }
        CharSequence t = et.text;
        int a = et.startOffset + Math.min(et.selectionStart, et.selectionEnd);
        int b = et.startOffset + Math.max(et.selectionStart, et.selectionEnd);
        int pos;
        if (!selecting && a != b) {
            pos = dir < 0 ? a : b;          // collapse a selection to its edge, like a hardware keyboard
        } else {
            pos = cursorOf(et);
            int i = pos - et.startOffset;
            i = Math.max(0, Math.min(t.length(), i));
            if (dir < 0 && i > 0) i -= (i >= 2 && Character.isLowSurrogate(t.charAt(i - 1)) && Character.isHighSurrogate(t.charAt(i - 2))) ? 2 : 1;
            else if (dir > 0 && i < t.length()) i += (i + 1 < t.length() && Character.isHighSurrogate(t.charAt(i)) && Character.isLowSurrogate(t.charAt(i + 1))) ? 2 : 1;
            pos = et.startOffset + i;
        }
        placeCursor(c, et, pos);
    }

    private void moveLine(boolean end) {
        InputConnection c = ic();
        if (c == null) return;
        ExtractedText et = extracted();
        if (et == null) { arrow(end ? KeyEvent.KEYCODE_MOVE_END : KeyEvent.KEYCODE_MOVE_HOME); return; }
        CharSequence t = et.text;
        int i = cursorOf(et) - et.startOffset;
        i = Math.max(0, Math.min(t.length(), i));
        String s = t.toString();
        int pos;
        if (end) { int n = s.indexOf('\n', i); pos = n < 0 ? s.length() : n; }
        else pos = i == 0 ? 0 : s.lastIndexOf('\n', i - 1) + 1;
        placeCursor(c, et, et.startOffset + pos);
    }

    private void addSymbol(LinearLayout row, final String s) {
        TextView b = key(s, 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { commit(s); }
        });
        b.setTextSize(keySp);
        row.addView(b);
    }

    private void addDigit(LinearLayout row, final char c) {
        TextView b = key(String.valueOf(c), 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { commit(String.valueOf(c)); }
        });
        b.setTextSize(rowH < dp(52) ? 22f : rowH < dp(62) ? 26f : 30f);
        row.addView(b);
    }

    /** a row of keys: pixel edges computed from the row's width (KeyRow), the same 3 dp gaps as before */
    private LinearLayout row() {
        return new KeyRow(this, 2 * dp(3), dp(2));
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH);
    }

    private void addLetter(LinearLayout row, final char c) {
        final TextView b = key(String.valueOf(c), 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { letter(c); }
        });
        letterKeys.add(b);
        row.addView(b);
    }

    private boolean useFullWidthPunct() {
        return chinese && !passwordField;
    }

    private void updateKeyLabels() {
        if (numeric) return;
        boolean fw = useFullWidthPunct();
        if (modeKey != null) modeKey.setText(chinese ? "中" : "EN");
        if (commaKey != null) commaKey.setText(fw ? "，" : ",");
        if (spaceKey != null) spaceKey.setText(chinese ? "空格" : "space");
        if (periodKey != null) periodKey.setText(fw ? "。" : ".");
        if (shiftKey != null) shiftKey.setText(shift ? "shift" : "Shift");
        for (TextView b : letterKeys) {
            String s = String.valueOf(b.getText());
            if (s.length() == 1) {
                char c = s.charAt(0);
                b.setText(String.valueOf(shift ? Character.toUpperCase(c) : Character.toLowerCase(c)));
            }
        }
    }

    // ------------------------------------------------------------------ editing

    private InputConnection ic() { return getCurrentInputConnection(); }

    /** commit text that is not a candidate choice: not a context for what follows */
    private void commit(String s) {
        if (chinese && composingNow()) finishAll();
        commitRaw(s);
        clearContext();
        dismiss();
    }

    private void commitRaw(String s) {
        InputConnection c = ic();
        if (c != null) c.commitText(s, 1);
        awaitSel = true;
    }

    private void letter(char c) {
        if (!chinese) {
            String s = String.valueOf(shift ? Character.toUpperCase(c) : Character.toLowerCase(c));
            commit(s);
            if (shift) { shift = false; updateKeyLabels(); }
        } else if (pinyin.length() < PinyinDecoder.MAX_LETTERS) {
            predictions = null;             // the context stays: what was committed last helps decoding
            justPicked = false;
            pinyin.append(Character.toLowerCase(c));
            updateCandidates();
        }
    }

    private void backspace() {
        if (chinese && composingNow()) {
            if ((justPicked || pinyin.length() == 0) && !picks.isEmpty()) {
                Pick p = picks.remove(picks.size() - 1);       // undo the last choice
                pinyin.insert(0, p.raw);
                justPicked = !picks.isEmpty() && justPicked;
            } else {
                pinyin.deleteCharAt(pinyin.length() - 1);
                justPicked = false;
            }
            updateCandidates();
            return;
        }
        clearContext();
        dismiss();
        InputConnection c = ic();
        if (c == null) return;
        // 选中了文字（全选 / 选择 + 方向键）：退格删掉选中的部分。deleteSurroundingText 只删选区前面的一个字
        CharSequence sel = null;
        try { sel = c.getSelectedText(0); } catch (Throwable ignored) { }
        if (sel != null && sel.length() > 0) c.commitText("", 1);
        else c.deleteSurroundingText(1, 0);
    }

    /** space: the first candidate (normally the whole sentence); nothing typed: a space */
    private void space() {
        if (chinese && composingNow()) {
            if (decoder != null && hasLetters() && decoder.has(0)) pick(0);
            else finishAll();
            return;
        }
        commit(" ");
    }

    /** ， 。: the best sentence, then the mark */
    private void punct(String s) {
        commit(s);
    }

    /** enter while typing pinyin: what is chosen plus the letters as typed (names, English words) */
    private void enter() {
        if (chinese && composingNow()) {
            StringBuilder t = new StringBuilder();
            for (Pick p : picks) t.append(p.text);
            t.append(pinyin.toString().replace("'", ""));
            picks.clear();
            pinyin.setLength(0);
            justPicked = false;
            commit(t.toString());
            updateCandidates();
            return;
        }
        clearContext();
        dismiss();
        if (!sendDefaultEditorAction(true)) {
            InputConnection c = ic();
            if (c != null) c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            if (c != null) c.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }
}
