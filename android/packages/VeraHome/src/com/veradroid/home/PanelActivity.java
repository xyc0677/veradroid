// SPDX-License-Identifier: GPL-3.0-or-later
package com.veradroid.home;

import android.app.Activity;
import android.os.Bundle;

/**
 * 下拉面板现在是悬浮窗口（见 {@link Panel}），不再是 Activity。这个类只为旧的入口留着：谁启动它，就打开面板然后自己退出。
 */
public class PanelActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!LockScreen.showing()) Panel.open(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
