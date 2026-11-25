#!/usr/bin/env python3
"""VeraDroid per-app e-ink settings in the framework (branch feat/fw-oec).
The class doing the work is android/overlay/frameworks/base/core/java/com/android/internal/vd/OecConfig.java; the
hooks below only call it. Every hook is inert unless the host's /dev/kfb/appoec has a rule for the app's package, so an
image built with them behaves like the release image on an unchanged host. Same rules as small-patches.py: exact string
replacement, error when the old text is not found, skipped when already applied.

  ActivityThread.handleBindApplication  OecConfig.init(package) before the app's resources exist
  ActivityThread.updateDefaultDensity   the same dpi as the process default density (DENSITY_DEVICE, Bitmap default)
  ResourcesImpl.updateConfiguration     per-app density (OEC_DPI / DENSITY): Configuration copy with the app's dpi
  DisplayManagerGlobal.getDisplayInfo   the same dpi in Display.getMetrics()
  Activity.performResume                re-read the table when it changed (settings page)
  OverScroller / Scroller               OEC_FLING (fling jumps to its end), OEC_SMOOTH (startScroll without animation)
  AbsListView.smoothScrollToPosition*  OEC_SMOOTH: jump to the position (setSelectionFromTop)
  Toast.TN.handleShow                   OEC_TOAST
  Dialog.show                           OEC_DLGBORDER
  PhoneWindow                           OEC_LIGHTBAR
  DecorView.dispatchKeyEvent            OEC_PAGEKEY
  WebView.setWebViewClient              OEC_W* (CSS / text zoom), via OecWebViewClient
"""
import sys, os
SRC = sys.argv[1]
B = "frameworks/base/core/java/"
O = "com.android.internal.vd.OecConfig"
PATCHES = [
    (B + "android/app/ActivityThread.java",
     "        mBoundApplication = data;\n",
     "        mBoundApplication = data;\n"
     "        " + O + ".init(data.appInfo != null ? data.appInfo.packageName : null);   // VeraDroid per-app settings\n"),
    # ro.vd.oec=2: the process default density (DisplayMetrics.DENSITY_DEVICE, Bitmap default density) follows the app's dpi,
    # as with the global density switch. 多看 draws its page into a Bitmap made at the default density and puts it on a
    # 376 dpi canvas: with only Resources at 376 the page came out 1.25x too big (1.00, pw6-a8ffca4).
    (B + "android/app/ActivityThread.java",
     "    private void updateDefaultDensity() {\n        final int densityDpi = mCurDefaultDisplayDpi;\n",
     "    private void updateDefaultDensity() {\n"
     "        final int densityDpi = " + O + ".defaultDensity(mCurDefaultDisplayDpi);   // VeraDroid: per-app density\n"),
    (B + "android/content/res/ResourcesImpl.java",
     "                mDisplayAdjustments.getCompatibilityInfo().applyToDisplayMetrics(mMetrics);\n\n"
     "                final @Config int configChanges = calcConfigChanges(config);\n",
     "                mDisplayAdjustments.getCompatibilityInfo().applyToDisplayMetrics(mMetrics);\n\n"
     "                config = " + O + ".adjustConfiguration(config, mMetrics);   // VeraDroid: per-app density\n"
     "                final @Config int configChanges = calcConfigChanges(config);\n"),
    (B + "android/hardware/display/DisplayManagerGlobal.java",
     "                info = mDm.getDisplayInfo(displayId);\n                if (info == null) {\n                    return null;\n                }\n",
     "                info = mDm.getDisplayInfo(displayId);\n                if (info == null) {\n                    return null;\n                }\n"
     "                " + O + ".adjustDisplayInfo(info);   // VeraDroid: per-app density\n"),
    (B + "android/app/Activity.java",
     "        mCalled = false;\n        // mResumed is set by the instrumentation\n",
     "        " + O + ".refresh();   // VeraDroid: per-app settings changed?\n"
     "        mCalled = false;\n        // mResumed is set by the instrumentation\n"),
    (B + "android/widget/OverScroller.java",
     "    public void startScroll(int startX, int startY, int dx, int dy, int duration) {\n        mMode = SCROLL_MODE;\n",
     "    public void startScroll(int startX, int startY, int dx, int dy, int duration) {\n"
     "        if (" + O + ".sNoSmooth) duration = 0;   // VeraDroid: no smooth-scroll animation for this app\n"
     "        mMode = SCROLL_MODE;\n"),
    (B + "android/widget/OverScroller.java",
     "        mMode = FLING_MODE;\n        mScrollerX.fling(startX, velocityX, minX, maxX, overX);\n"
     "        mScrollerY.fling(startY, velocityY, minY, maxY, overY);\n    }\n",
     "        mMode = FLING_MODE;\n        mScrollerX.fling(startX, velocityX, minX, maxX, overX);\n"
     "        mScrollerY.fling(startY, velocityY, minY, maxY, overY);\n"
     "        if (" + O + ".sNoFling) {   // VeraDroid: jump to where the fling stops\n"
     "            int fx = Math.max(minX, Math.min(maxX, getFinalX())), fy = Math.max(minY, Math.min(maxY, getFinalY()));\n"
     "            mMode = SCROLL_MODE;\n"
     "            mScrollerX.startScroll(startX, fx - startX, 0);\n"
     "            mScrollerY.startScroll(startY, fy - startY, 0);\n"
     "        }\n    }\n"),
    (B + "android/widget/Scroller.java",
     "        mMode = SCROLL_MODE;\n        mFinished = false;\n        mDuration = duration;\n",
     "        mMode = SCROLL_MODE;\n        mFinished = false;\n"
     "        mDuration = " + O + ".sNoSmooth ? 0 : duration;   // VeraDroid: no smooth-scroll animation for this app\n"),
    (B + "android/widget/Scroller.java",
     "        mFinalY = Math.min(mFinalY, mMaxY);\n        mFinalY = Math.max(mFinalY, mMinY);\n    }\n",
     "        mFinalY = Math.min(mFinalY, mMaxY);\n        mFinalY = Math.max(mFinalY, mMinY);\n"
     "        if (" + O + ".sNoFling) mDuration = 0;   // VeraDroid: the fling jumps to its end\n    }\n"),
    (B + "android/widget/AbsListView.java",
     "        mPositionScroller.start(position);\n",
     "        if (" + O + ".jumpToPosition(this, position, -1)) return;   // VeraDroid: OEC_SMOOTH\n"
     "        mPositionScroller.start(position);\n"),
    (B + "android/widget/AbsListView.java",
     "        mPositionScroller.startWithOffset(position, offset, duration);\n",
     "        if (" + O + ".jumpToPosition(this, position, Math.max(0, offset))) return;   // VeraDroid: OEC_SMOOTH\n"
     "        mPositionScroller.startWithOffset(position, offset, duration);\n"),
    (B + "android/widget/AbsListView.java",
     "        mPositionScroller.startWithOffset(position, offset);\n",
     "        if (" + O + ".jumpToPosition(this, position, Math.max(0, offset))) return;   // VeraDroid: OEC_SMOOTH\n"
     "        mPositionScroller.startWithOffset(position, offset);\n"),
    (B + "android/widget/AbsListView.java",
     "        mPositionScroller.start(position, boundPosition);\n",
     "        if (" + O + ".jumpToPosition(this, position, -1)) return;   // VeraDroid: OEC_SMOOTH\n"
     "        mPositionScroller.start(position, boundPosition);\n"),
    (B + "android/widget/Toast.java",
     "                mView = mNextView;\n",
     "                mView = mNextView;\n                " + O + ".styleToast(mView);   // VeraDroid: readable toast\n"),
    (B + "android/app/Dialog.java",
     "        mWindowManager.addView(mDecor, l);\n",
     "        " + O + ".styleDialog(mWindow, mDecor);   // VeraDroid: dialog border\n"
     "        mWindowManager.addView(mDecor, l);\n"),
    (B + "com/android/internal/policy/PhoneWindow.java",
     "        if (!mForcedNavigationBarColor) {\n            mNavigationBarColor = a.getColor(R.styleable.Window_navigationBarColor, 0xFF000000);\n        }\n",
     "        if (!mForcedNavigationBarColor) {\n            mNavigationBarColor = a.getColor(R.styleable.Window_navigationBarColor, 0xFF000000);\n        }\n"
     "        mStatusBarColor = " + O + ".barColor(mStatusBarColor);   // VeraDroid: light system bars\n"
     "        mNavigationBarColor = " + O + ".barColor(mNavigationBarColor);\n"
     "        if (" + O + ".sLightBar) decor.setSystemUiVisibility(" + O + ".barFlags(decor.getSystemUiVisibility()));\n"),
    (B + "com/android/internal/policy/PhoneWindow.java",
     "    public void setStatusBarColor(int color) {\n        mStatusBarColor = color;\n",
     "    public void setStatusBarColor(int color) {\n        mStatusBarColor = " + O + ".barColor(color);   // VeraDroid\n"),
    (B + "com/android/internal/policy/PhoneWindow.java",
     "    public void setNavigationBarColor(int color) {\n        mNavigationBarColor = color;\n",
     "    public void setNavigationBarColor(int color) {\n        mNavigationBarColor = " + O + ".barColor(color);   // VeraDroid\n"),
    (B + "com/android/internal/policy/DecorView.java",
     "    public boolean dispatchKeyEvent(KeyEvent event) {\n        final int keyCode = event.getKeyCode();\n",
     "    public boolean dispatchKeyEvent(KeyEvent event) {\n"
     "        if (" + O + ".sPageKey != 0 && !" + O + ".isInFastKey()) {   // VeraDroid: page keys as volume keys / a swipe for this app\n"
     "            event = " + O + ".mapPageKey(this, event);\n"
     "            if (event == null) return true;\n"
     "        }\n"
     "        final boolean vdFastKey = " + O + ".sFastKey && !" + O + ".isInFastKey();\n"
     "        if (vdFastKey) {\n"
     "            event = " + O + ".filterFastKey(event);\n"
     "            if (event == null) return true;\n"
     "        }\n"
     "        final int keyCode = event.getKeyCode();\n"),
    (B + "com/android/internal/policy/DecorView.java",
     "            final boolean handled = cb != null && mFeatureId < 0 ? cb.dispatchKeyEvent(event)\n"
     "                    : super.dispatchKeyEvent(event);\n"
     "            if (handled) {\n"
     "                return true;\n"
     "            }\n"
     "        }",
     "            final boolean handled = cb != null && mFeatureId < 0 ? cb.dispatchKeyEvent(event)\n"
     "                    : super.dispatchKeyEvent(event);\n"
     "            if (vdFastKey && isDown && handled && " + O + ".afterFastKeyDown(this, event)) {\n"
     "                return true;\n"
     "            }\n"
     "            if (handled) {\n"
     "                return true;\n"
     "            }\n"
     "        }"),
    (B + "android/webkit/WebView.java",
     "    public void setWebViewClient(WebViewClient client) {\n        checkThread();\n        mProvider.setWebViewClient(client);\n",
     "    public void setWebViewClient(WebViewClient client) {\n        checkThread();\n"
     "        mProvider.setWebViewClient(com.android.internal.vd.OecWebViewClient.wrap(client));   // VeraDroid: per-app CSS\n"),
]
for rel, old, new in PATCHES:
    p = os.path.join(SRC, rel)
    s = open(p, encoding="utf-8").read()
    if new in s:
        print("already patched:", rel); continue
    if s.count(old) != 1:
        print("ERROR: text not found once (%d) in %s" % (s.count(old), rel)); sys.exit(1)
    open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
    print("patched:", rel)
