package com.titan.keytester;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Sees hardware key events before the IME. Never consumes them. */
public class KeyService extends AccessibilityService {
    static volatile boolean running = false;

    @Override protected void onServiceConnected() { running = true; MainActivity.notifyServiceState(); }
    @Override public boolean onUnbind(android.content.Intent i) { running = false; MainActivity.notifyServiceState(); return super.onUnbind(i); }
    @Override public void onDestroy() { running = false; MainActivity.notifyServiceState(); super.onDestroy(); }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        MainActivity a = MainActivity.instance;
        if (a != null) a.onRawKey(event);
        return false; // pass through
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
}
