package com.hu.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

/**
 * Touch injection for the PC remote. Without root, an accessibility service is the only way a
 * normal app can tap/swipe on other apps. It listens to no events and does nothing until the
 * PC sends input over the PIN-protected link.
 */
public class RemoteControl extends AccessibilityService {
    private static volatile RemoteControl instance;
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean ready() { return instance != null; }

    /** Root node of whatever window is in front (for AutoClicker), or null. */
    static AccessibilityNodeInfo root() {
        RemoteControl s = instance;
        if (s == null) return null;
        try { return s.getRootInActiveWindow(); } catch (RuntimeException e) { return null; }
    }

    /** A real finger tap at screen pixels (for controls that don't expose a click action). */
    static boolean tapAt(float x, float y) {
        RemoteControl s = instance;
        return s != null && s.gesture(x, y, x, y, 40);
    }

    static void globalBack() {
        RemoteControl s = instance;
        if (s != null) s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    /** Handles one /input request. Coordinates are fractions (0..1) of the screen. */
    static JSONObject input(Context c, JSONObject req) throws Exception {
        String type = req.optString("type");
        if (type.equals("launch")) {
            boolean ok = Player.launch(c, req.optString("pkg"));
            return new JSONObject().put("ok", ok);
        }
        final RemoteControl s = instance;
        if (s == null) {
            return new JSONObject().put("ok", false)
                    .put("error", "Touch control is off: Settings > Accessibility > HU Launcher remote");
        }
        Point real = new Point();
        ((WindowManager) s.getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(real);
        float x = (float) req.optDouble("x", 0.5) * real.x, y = (float) req.optDouble("y", 0.5) * real.y;
        float x2 = (float) req.optDouble("x2", req.optDouble("x", 0.5)) * real.x;
        float y2 = (float) req.optDouble("y2", req.optDouble("y", 0.5)) * real.y;
        long ms = Math.max(1, Math.min(5000, req.optLong("ms", 0)));
        boolean ok;
        switch (type) {
            case "tap": ok = s.gesture(x, y, x, y, 40); break;
            case "long": ok = s.gesture(x, y, x, y, Math.max(ms, 700)); break;
            case "swipe": ok = s.gesture(x, y, x2, y2, Math.max(ms, 120)); break;
            case "back": ok = s.performGlobalAction(GLOBAL_ACTION_BACK); break;
            case "home": ok = s.performGlobalAction(GLOBAL_ACTION_HOME); break;
            case "recents": ok = s.performGlobalAction(GLOBAL_ACTION_RECENTS); break;
            case "notifications": ok = s.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); break;
            case "text": ok = s.typeText(req.optString("text"), req.optBoolean("replace", false)); break;
            default: return new JSONObject().put("ok", false).put("error", "unknown type " + type);
        }
        return new JSONObject().put("ok", ok);
    }

    private boolean gesture(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(Math.max(0, x1), Math.max(0, y1));
        p.lineTo(Math.max(0, x2), Math.max(0, y2));
        final GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
        main.post(() -> dispatchGesture(g, null, null));
        return true;
    }

    /** Types into the focused text field (appends unless replace=true). */
    private boolean typeText(String text, boolean replace) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null || !f.isEditable()) return false;
        CharSequence cur = f.getText();
        String now = replace || cur == null || f.isShowingHintText() ? text : cur + text;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, now);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }
}
