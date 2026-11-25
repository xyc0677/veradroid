#!/usr/bin/env python3
"""VeraDroid e-ink framework changes (branch feat/fw-eink).

Exact text replacements like small-patches.py: an anchor that is missing (source changed) is an error, an already
applied patch is skipped. The new classes live in overlay/frameworks/base/core/java/android/eink/
(EpdHint, EinkConfig, EinkText). Everything is off or unchanged by default except:
  - no starting windows (persist.vd.splash=1 brings them back, persist.vd.splash.allow lists exceptions)
  - VeraIME's main process is capped at OOM adj 200 (persist.vd.oomcap, "none" = off)
"""
import os, re, sys
SRC = sys.argv[1]
FB = "frameworks/base/"

VIEW_API = r'''

    // ---- VeraDroid: e-ink View API (android.eink.EpdHint), read by some reader apps through reflection. ----
    /** @hide */ public static final int EINK_WAVEFORM_MODE_MASK = android.eink.EpdHint.WAVEFORM_MASK;
    /** @hide */ public static final int EINK_WAVEFORM_MODE_INIT = android.eink.EpdHint.WAVEFORM_INIT;
    /** @hide */ public static final int EINK_WAVEFORM_MODE_DU = android.eink.EpdHint.WAVEFORM_DU;
    /** @hide */ public static final int EINK_WAVEFORM_MODE_GC16 = android.eink.EpdHint.WAVEFORM_GC16;
    /** @hide */ public static final int EINK_WAVEFORM_MODE_GC4 = android.eink.EpdHint.WAVEFORM_GC4;
    /** @hide */ public static final int EINK_WAVEFORM_MODE_ANIM = android.eink.EpdHint.WAVEFORM_ANIM;
    /** @hide */ public static final int EINK_WAVEFORM_MODE_REAGL = android.eink.EpdHint.WAVEFORM_REAGL;
    /** @hide */ public static final int EINK_WAVEFORM_MODE_AUTO = android.eink.EpdHint.WAVEFORM_AUTO;
    /** @hide */ public static final int EINK_UPDATE_MODE_PARTIAL = 0;
    /** @hide */ public static final int EINK_UPDATE_MODE_FULL = android.eink.EpdHint.UPDATE_FULL;
    /** @hide */ public static final int EINK_UPDATE_MODE_MASK = android.eink.EpdHint.UPDATE_FULL;
    /** @hide */ public static final int EINK_WAIT_MODE_NOWAIT = 0;
    /** @hide */ public static final int EINK_WAIT_MODE_WAIT = android.eink.EpdHint.WAIT;
    /** @hide */ public static final int EINK_WAIT_MODE_MASK = android.eink.EpdHint.WAIT;
    /** @hide */ public static final int EINK_AUTO_MODE_REGIONAL = 0;
    /** @hide */ public static final int EINK_AUTO_MODE_AUTOMATIC = android.eink.EpdHint.AUTO_AUTOMATIC;
    /** @hide */ public static final int EINK_AUTO_MODE_MASK = android.eink.EpdHint.AUTO_AUTOMATIC;
    /** @hide */ public static final int EINK_REAGL_MODE_REAGL = android.eink.EpdHint.REAGL;
    /** @hide */ public static final int EINK_REAGL_MODE_REAGLD = android.eink.EpdHint.REAGLD;
    /** @hide */ public static final int EINK_REAGL_MODE_MASK = android.eink.EpdHint.REAGL | android.eink.EpdHint.REAGLD;
    /** @hide */ public static final int EINK_ONYX_AUTO_MASK = android.eink.EpdHint.ONYX_AUTO;
    /** @hide */ public static final int EINK_ONYX_GC_MASK = android.eink.EpdHint.ONYX_GC;
    /** @hide */ public static final int UI_DEFAULT_MODE = 0;
    /** @hide */ public static final int UI_GC_MODE = android.eink.EpdHint.UPDATE_FULL | android.eink.EpdHint.WAVEFORM_GC16;
    /** @hide */ public static final int UI_GC4_MODE = android.eink.EpdHint.WAVEFORM_GC4;
    /** @hide */ public static final int UI_A2_QUALITY_MODE = android.eink.EpdHint.WAVEFORM_ANIM;
    /** @hide */ public static final int UI_A2_PERFORMANCE_MODE = android.eink.EpdHint.WAVEFORM_DU;

    /** @hide Invalidate the whole view; its next frame uses this e-ink update mode. */
    public void invalidate(int updateMode) {
        android.eink.EpdHint.requestView(this, updateMode, null);
        invalidate();
    }

    /** @hide */
    public void invalidate(int l, int t, int r, int b, int updateMode) {
        android.eink.EpdHint.requestView(this, updateMode, new Rect(l, t, r, b));
        invalidate(l, t, r, b);
    }

    /** @hide */
    public void postInvalidate(int updateMode) {
        android.eink.EpdHint.request(android.eink.EpdHint.letter(updateMode), null);
        postInvalidate();
    }

    /** @hide Refresh what is on the panel now (full / GC modes flash). */
    public void refreshScreen(int updateMode) { android.eink.EpdHint.refresh(updateMode); }
    /** @hide */
    public void refreshScreen(int l, int t, int r, int b, int updateMode) { android.eink.EpdHint.refresh(updateMode); }
    /** @hide Every frame of this process uses this mode until reset (0 = automatic). */
    public void setDefaultUpdateMode(int updateMode) { android.eink.EpdHint.setDefault(updateMode); }
    /** @hide */
    public int getDefaultUpdateMode() { return android.eink.EpdHint.getDefault(); }
    /** @hide */
    public void resetUpdateMode() { android.eink.EpdHint.setDefault(0); }
    /** @hide */
    public static int getGlobalUpdateMode() { return android.eink.EpdHint.getDefault(); }
    /** @hide */
    public static void setGlobalUpdateMode(int updateMode) { android.eink.EpdHint.setDefault(updateMode); }
    /** @hide */
    public static void setFirstDrawUpdateMode(int updateMode) { android.eink.EpdHint.request(android.eink.EpdHint.letter(updateMode), null); }
    /** @hide */
    public void setUpdatePolicy(int updatePolicy, int guInterval) { }
    /** @hide The next frame is a full (flashing) refresh. */
    public static void applyGCOnce() { android.eink.EpdHint.gcOnce(); }
    /** @hide A2 / DU for this app until disableA2 (the display bridge caps it at 10 minutes). */
    public void enableA2() { android.eink.EpdHint.fast(true); }
    /** @hide */
    public void disableA2() { android.eink.EpdHint.fast(false); }
    /** @hide */
    public static void applyApplicationFastMode(String application, boolean enable, boolean clear) {
        android.eink.EpdHint.fast(enable);
    }
    /** @hide */
    public static boolean inSystemFastMode() { return android.eink.EpdHint.inFast(); }
    /** @hide false: the panel stops updating (at most 10 s); true: one update of everything that changed. */
    public static void enableScreenUpdate(boolean enable) { android.eink.EpdHint.freeze(!enable); }
    /** @hide */
    public static void setWaveformAndScheme(int mode, int scheme, int count) { android.eink.EpdHint.setDefault(mode); }
    /** @hide */
    public static void resetWaveformAndScheme() { android.eink.EpdHint.setDefault(0); }
    /** @hide */
    public static void waitForUpdateFinished() { }
    /** @hide */
    public static int getEpdWidth() { return Resources.getSystem().getDisplayMetrics().widthPixels; }
    /** @hide */
    public static int getEpdHeight() { return Resources.getSystem().getDisplayMetrics().heightPixels; }
    // ---- end VeraDroid e-ink View API ----
'''

PATCHES = [
    # 1f. shorten PAUSE_TIMEOUT (500 -> 180 ms) so slow synchronous SQLite/LevelDB writes in 3rd-party onPause do not stall task switches
    (FB + "services/core/java/com/android/server/am/ActivityStack.java",
     "    static final int PAUSE_TIMEOUT = 500;\n",
     "    static final int PAUSE_TIMEOUT = 180; // VeraDroid: fast foreground handoff on e-ink\n"),
    (FB + "services/core/java/com/android/server/am/ActivityStack.java",
     "        // Find the first activity that is not finishing.\n"
     "        final ActivityRecord next = topRunningActivityLocked();\n",
     "        // Find the first activity that is not finishing.\n"
     "        final ActivityRecord next = topRunningActivityLocked();\n"
     "        if (next != null && next != prev) android.eink.EpdHint.boost(); // VeraDroid: CPU + EPDC pre-warm for intra-app Activity transitions\n"),
    # 1g. silence TaskPersister error spam when recent_images directory is absent (thumbnails disabled)
    (FB + "services/core/java/com/android/server/am/TaskPersister.java",
     "        if (files == null) {\n"
     "            Slog.e(TAG, \"File error accessing recents directory (directory doesn't exist?).\");\n"
     "            return;\n"
     "        }",
     "        if (files == null) {\n"
     "            return; // VeraDroid: recent_images unused when thumbnails are off\n"
     "        }"),
    # 1d. skip failing task-thumbnail readback on vdnull GLES (~400 ms closeTransactionSync under AMS/WMS locks on every task switch)
    (FB + "services/core/java/com/android/server/am/ActivityStack.java",
     "    public final Bitmap screenshotActivitiesLocked(ActivityRecord who) {\n",
     "    public final Bitmap screenshotActivitiesLocked(ActivityRecord who) {\n"
     "        if (!android.os.SystemProperties.getBoolean(\"persist.vd.recents_thumb\", false)) return null; // VeraDroid: no SystemUI thumbnail readback on vdnull GLES\n"),
    # 1e. zero-scale animations must return null/false without attaching a 0ms Animation (prevents alpha/matrix frames forcing GLES fallback)
    (FB + "services/core/java/com/android/server/wm/AppTransition.java",
     "    Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter, int uiMode,\n"
     "            int orientation, Rect frame, Rect displayFrame, Rect insets,\n"
     "            @Nullable Rect surfaceInsets, boolean isVoiceInteraction, boolean freeform,\n"
     "            int taskId) {\n",
     "    Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter, int uiMode,\n"
     "            int orientation, Rect frame, Rect displayFrame, Rect insets,\n"
     "            @Nullable Rect surfaceInsets, boolean isVoiceInteraction, boolean freeform,\n"
     "            int taskId) {\n"
     "        if (mService.getTransitionAnimationScaleLocked() == 0f) return null; // VeraDroid: keep 100% HWC overlay\n"),
    (FB + "services/core/java/com/android/server/wm/WindowStateAnimator.java",
     "    boolean applyAnimationLocked(int transit, boolean isEntrance) {\n",
     "    boolean applyAnimationLocked(int transit, boolean isEntrance) {\n"
     "        if (mService.getWindowAnimationScaleLocked() == 0f) { clearAnimation(); return false; } // VeraDroid: keep 100% HWC overlay\n"),
    # 2. no starting window by default
    (FB + "services/core/java/com/android/server/wm/WindowManagerService.java",
     "            // There is no existing starting window, and the caller doesn't\n            // want us to create one, so that's it!\n            if (!createIfNeeded) {\n                return false;\n            }",
     "            // There is no existing starting window, and the caller doesn't\n            // want us to create one, so that's it!\n            if (!createIfNeeded) {\n                return false;\n            }\n            // VeraDroid: a starting window is one more full-screen panel update per launch on e-ink\n            if (!android.eink.EinkConfig.allowStartingWindow(pkg)) {\n                return false;\n            }"),
    # 3. OOM cap for listed packages without making them persistent
    (FB + "services/core/java/com/android/server/am/ActivityManagerService.java",
     "            r.persistent = true;\n        }\n        addProcessNameLocked(r);\n        return r;\n    }",
     "            r.persistent = true;\n        }\n        if (!isolated && proc.equals(info.packageName)) {\n            // VeraDroid: cap the OOM adj of listed packages (persist.vd.oomcap), e.g. the IME stays perceptible\n            final int vdCap = android.eink.EinkConfig.oomCap(info.packageName);\n            if (vdCap >= 0 && vdCap < r.maxAdj) r.maxAdj = vdCap;\n        }\n        addProcessNameLocked(r);\n        return r;\n    }"),
    # 1b. per-frame e-ink mode: the hint goes to the bridge right before the frame is posted
    (FB + "core/java/android/view/ViewRootImpl.java",
     "        // Draw with software renderer.\n        final Canvas canvas;\n        try {",
     "        // Draw with software renderer.\n        final Canvas canvas;\n        final Rect vdEpdDirty = new Rect();   // VeraDroid: region of this frame, for android.eink.EpdHint\n        try {"),
    (FB + "core/java/android/view/ViewRootImpl.java",
     "            canvas = mSurface.lockCanvas(dirty);\n",
     "            canvas = mSurface.lockCanvas(dirty);\n            vdEpdDirty.set(dirty);\n"),
    (FB + "core/java/android/view/ViewRootImpl.java",
     "            try {\n                surface.unlockCanvasAndPost(canvas);",
     "            try {\n                android.eink.EpdHint.onPost(vdEpdDirty, attachInfo.mWindowLeft, attachInfo.mWindowTop); // VeraDroid\n                surface.unlockCanvasAndPost(canvas);"),
    # 5. End-to-end HWC 1.5 surfaceDamage for software Canvas + HWC2On1Adapter:
    ("frameworks/native/libs/gui/Surface.cpp",
     "    if (mConnectedToCpu || mDirtyRegion.bounds() == Rect::INVALID_RECT) {\n"
     "        input.setSurfaceDamage(Region::INVALID_REGION);\n"
     "    } else {",
     "    if (mConnectedToCpu) {\n"
     "        if (mLockedBuffer != 0 && mLockedBuffer.get() == buffer &&\n"
     "                (mTransform ^ mStickyTransform) == 0 &&\n"
     "                mSlots[i].dirtyRegion.bounds() != Rect::INVALID_RECT) {\n"
     "            input.setSurfaceDamage(mSlots[i].dirtyRegion);\n"
     "        } else {\n"
     "            input.setSurfaceDamage(Region::INVALID_REGION);\n"
     "        }\n"
     "    } else if (mDirtyRegion.bounds() == Rect::INVALID_RECT) {\n"
     "        input.setSurfaceDamage(Region::INVALID_REGION);\n"
     "    } else {"),
    ("frameworks/native/libs/gui/BufferQueueProducer.cpp",
     "                // Overwrite the droppable buffer with the incoming one\n"
     "                mCore->mQueue.editItemAt(mCore->mQueue.size() - 1) = item;",
     "                // Merge surfaceDamage from the dropped frame so HWC never misses dirty rows\n"
     "                if (last.mSurfaceDamage.bounds() == Rect::INVALID_RECT ||\n"
     "                        item.mSurfaceDamage.bounds() == Rect::INVALID_RECT) {\n"
     "                    item.mSurfaceDamage = Region::INVALID_REGION;\n"
     "                } else {\n"
     "                    item.mSurfaceDamage.orSelf(last.mSurfaceDamage);\n"
     "                }\n"
     "                // Overwrite the droppable buffer with the incoming one\n"
     "                mCore->mQueue.editItemAt(mCore->mQueue.size() - 1) = item;"),
    ("frameworks/native/services/surfaceflinger/DisplayHardware/HWC2.cpp",
     "        std::vector<hwc_rect_t> hwcRects;\n"
     "        for (size_t rect = 0; rect < rectCount; ++rect) {",
     "        std::vector<hwc_rect_t> hwcRects;\n"
     "        if (rectCount == 0) {\n"
     "            hwcRects.push_back({0, 0, 0, 0});\n"
     "            rectCount = 1;\n"
     "        } else for (size_t rect = 0; rect < rectCount; ++rect) {"),
    ("frameworks/native/services/surfaceflinger/DisplayHardware/HWC2On1Adapter.cpp",
     "        for (size_t l = 0; l < contents->numHwLayers; ++l) {\n"
     "            auto& layer = contents->hwLayers[l];\n"
     "            std::free(const_cast<hwc_rect_t*>(layer.visibleRegionScreen.rects));\n"
     "        }",
     "        for (size_t l = 0; l < contents->numHwLayers; ++l) {\n"
     "            auto& layer = contents->hwLayers[l];\n"
     "            std::free(const_cast<hwc_rect_t*>(layer.visibleRegionScreen.rects));\n"
     "            std::free(const_cast<hwc_rect_t*>(layer.surfaceDamage.rects));\n"
     "        }"),
    ("frameworks/native/services/surfaceflinger/DisplayHardware/HWC2On1Adapter.cpp",
     "static void cloneHWCRegion(hwc_region_t& region)\n"
     "{\n"
     "    auto size = sizeof(hwc_rect_t) * region.numRects;",
     "static void cloneHWCRegion(hwc_region_t& region)\n"
     "{\n"
     "    if (region.numRects == 0 || region.rects == nullptr) {\n"
     "        region.numRects = 0;\n"
     "        region.rects = nullptr;\n"
     "        return;\n"
     "    }\n"
     "    auto size = sizeof(hwc_rect_t) * region.numRects;"),
    ("frameworks/native/services/surfaceflinger/DisplayHardware/HWC2On1Adapter.cpp",
     "        hwc1VisibleRegion.rects = const_cast<const hwc_rect_t*>(newRects);\n"
     "        hwc1VisibleRegion.numRects = pending.size();\n"
     "        mVisibleRegion.latch();\n"
     "    }\n"
     "}",
     "        hwc1VisibleRegion.rects = const_cast<const hwc_rect_t*>(newRects);\n"
     "        hwc1VisibleRegion.numRects = pending.size();\n"
     "        mVisibleRegion.latch();\n"
     "    }\n"
     "    if (minorVersion >= 5) {\n"
     "        auto& hwc1Damage = hwc1Layer.surfaceDamage;\n"
     "        std::free(const_cast<hwc_rect_t*>(hwc1Damage.rects));\n"
     "        if (mSurfaceDamage.empty()) {\n"
     "            hwc1Damage.rects = nullptr;\n"
     "            hwc1Damage.numRects = 0;\n"
     "        } else {\n"
     "            hwc_rect_t* dmgRects = static_cast<hwc_rect_t*>(\n"
     "                    std::malloc(sizeof(hwc_rect_t) * mSurfaceDamage.size()));\n"
     "            std::copy(mSurfaceDamage.begin(), mSurfaceDamage.end(), dmgRects);\n"
     "            hwc1Damage.rects = const_cast<const hwc_rect_t*>(dmgRects);\n"
     "            hwc1Damage.numRects = mSurfaceDamage.size();\n"
     "        }\n"
     "    }\n"
     "}"),
    # 1c. e-ink View API (thin wrappers over EpdHint)
    (FB + "core/java/android/view/View.java",
     "    public void invalidate() {\n        invalidate(true);\n    }\n",
     "    public void invalidate() {\n        invalidate(true);\n    }\n" + VIEW_API),
]

# 4. text contrast: every native text draw in Canvas goes through EinkText.begin / end
CANVAS = FB + "graphics/java/android/graphics/Canvas.java"
CANVAS_RE = re.compile(r'(\n([ \t]+))(native_draw(?:Text|TextRun|TextOnPath)\(mNativeCanvasWrapper[^;]*\);)')

def patch(rel, old, new):
    p = os.path.join(SRC, rel)
    s = open(p, encoding="utf-8").read()
    if new in s:
        print("already: %s" % rel); return
    if s.count(old) != 1:
        sys.exit("eink-patches: anchor not found once in %s:\n%s" % (rel, old[:200]))
    open(p, "w", encoding="utf-8").write(s.replace(old, new))
    print("patched: %s" % rel)

for rel, old, new in PATCHES:
    patch(rel, old, new)

p = os.path.join(SRC, CANVAS)
s = open(p, encoding="utf-8").read()
if "android.eink.EinkText.begin" in s:
    print("already: %s" % CANVAS)
else:
    s2, n = CANVAS_RE.subn(lambda m: "%s{ final long vdTc = android.eink.EinkText.begin(paint); // VeraDroid text contrast%s    %s%s    android.eink.EinkText.end(paint, vdTc); }"
                           % (m.group(1), m.group(1), m.group(3).replace("\n", "\n    "), m.group(1)), s)
    if n != 10:
        sys.exit("eink-patches: expected 10 native text draws in Canvas.java, found %d" % n)
    open(p, "w", encoding="utf-8").write(s2)
    print("patched: %s (%d text draws)" % (CANVAS, n))
