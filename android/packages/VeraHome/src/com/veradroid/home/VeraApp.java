package com.veradroid.home;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Global e-ink UI polish for every VeraHome Activity:
 * Theme.Holo.Light.NoActionBar inherits android:windowContentOverlay = @drawable/ab_solid_shadow_holo,
 * which draws a ~1.5 mm gray gradient bar across the very top of every Activity window.
 * Clearing android.R.id.content's foreground before the first frame removes that gray bar globally
 * without needing AOSP resource changes.
 */
public class VeraApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) { clearTopShadow(a); }
            @Override public void onActivityStarted(Activity a) { clearTopShadow(a); }
            @Override public void onActivityResumed(Activity a) { clearTopShadow(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    static void clearTopShadow(Activity a) {
        if (a == null) return;
        try {
            View c = a.findViewById(android.R.id.content);
            if (c instanceof FrameLayout && ((FrameLayout) c).getForeground() != null) {
                ((FrameLayout) c).setForeground(null);
            }
        } catch (Throwable ignored) { }
    }
}
