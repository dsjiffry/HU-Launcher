package com.hu.launcher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;


/**
 * Presses system buttons for you through the accessibility service: Disable / Force stop on an
 * app's App info page (plus the confirmation), and the screen-share "Start now" prompt.
 * Polls the front window every 300 ms only while a job runs - zero cost otherwise.
 * Matches button labels exactly (English UI) and never clicks anything else.
 */
final class AutoClicker {
    interface Done { void onDone(boolean ok, String message); }

    static final int DISABLE = 0, FORCE_STOP = 1;
    private static final long JOB_TIMEOUT = 12000, STEP_TIMEOUT = 4000;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static final class Job {
        Context ctx;
        String pkg;
        int action, step;
        long started, stepStarted;
        Done done;
    }

    private static Job job;
    private static final Runnable POLL = AutoClicker::poll;

    static boolean available() { return RemoteControl.ready(); }

    static boolean busy() { return job != null; }

    /** Opens the app's App info page and presses the button(s). Callback on the main thread. */
    static void run(Context c, String pkg, int action, Done done) {
        cancel();
        Job j = new Job();
        j.ctx = c.getApplicationContext();
        j.pkg = pkg;
        j.action = action;
        j.done = done;
        j.started = j.stepStarted = SystemClock.uptimeMillis();
        job = j;
        Home.appInfo(j.ctx, pkg);
        H.postDelayed(POLL, 700);
    }

    static void cancel() {
        H.removeCallbacks(POLL);
        job = null;
    }

    private static void poll() {
        Job j = job;
        if (j == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - j.started > JOB_TIMEOUT) { finish(false, "timed out"); return; }
        AccessibilityNodeInfo root = RemoteControl.root();
        if (root == null) { again(); return; }
        boolean settings = is(root, SETTINGS);

        if (j.step == 0) { // App info page: find the main button
            if (!settings) { again(); return; }
            if (j.action == DISABLE) {
                if (find(root, "Enable") != null) { finish(true, "already disabled"); return; }
                // Only ever "Disable" (reversible). Never presses Uninstall: that deletes data.
                AccessibilityNodeInfo b = find(root, "Disable");
                if (b == null) {
                    stepTimeout(j, now, find(root, "Uninstall") != null
                            ? "installed by you - uninstall it yourself if you want it gone" : "no Disable button on this page");
                    return;
                }
                if (!b.isEnabled()) {
                    Keeper.add(j.ctx, j.pkg); // next best thing: keep it force-stopped
                    finish(false, "Android doesn't allow disabling this app - it will be kept stopped instead");
                    return;
                }
                click(b, j, now);
            } else {
                AccessibilityNodeInfo b = find(root, "Force stop");
                if (b == null) { stepTimeout(j, now, "no Force stop button"); return; }
                if (!b.isEnabled()) {
                    // Settings greys the button while it loads: only trust it if the app really is stopped.
                    if (took(j)) finish(true, "already stopped");
                    else stepTimeout(j, now, "Force stop stayed greyed out");
                    return;
                }
                click(b, j, now);
            }
        } else if (j.step == 1) { // confirmation dialog - only Settings' own, never another app's
            AccessibilityNodeInfo ok = settings ? find(root, "Disable app", "OK", "Force stop") : null;
            if (ok != null && ok.isEnabled() && find(root, "Cancel") != null) {
                click(ok, j, now);
            } else if (now - j.stepStarted > STEP_TIMEOUT) {
                j.step = 2; // no dialog appeared: just verify
                j.stepStarted = now;
                again();
            } else {
                again();
            }
        } else { // verify it took effect
            if (took(j)) { finish(true, j.action == DISABLE ? "disabled" : "stopped"); return; }
            if (now - j.stepStarted > STEP_TIMEOUT) finish(false, "the system didn't apply it");
            else again();
        }
    }

    private static boolean took(Job j) {
        if (j.action == DISABLE) return !PkgScan.state(j.ctx, j.pkg).equals("active");
        try {
            ApplicationInfo ai = j.ctx.getPackageManager().getApplicationInfo(j.pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    private static void click(AccessibilityNodeInfo b, Job j, long now) {
        b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        j.step++;
        j.stepStarted = now;
        H.postDelayed(POLL, 600);
    }

    private static void stepTimeout(Job j, long now, String why) {
        if (now - j.stepStarted > STEP_TIMEOUT) finish(false, why); else again();
    }

    private static void again() { H.postDelayed(POLL, 300); }

    private static void finish(boolean ok, String msg) {
        Job j = job;
        job = null;
        H.removeCallbacks(POLL);
        if (j == null) return;
        // Leave App info if it's still in front.
        AccessibilityNodeInfo root = RemoteControl.root();
        if (is(root, SETTINGS)) RemoteControl.globalBack();
        final Done d = j.done;
        H.postDelayed(() -> { if (d != null) d.onDone(ok, msg); }, 500);
    }

    // Exact system packages the helper may click in (no substring matches: "com.evil.settings").
    static final String[] SETTINGS = {"com.android.settings"};
    static final String[] INSTALLER = {"com.android.packageinstaller", "com.google.android.packageinstaller"};
    static final String[] SYSTEMUI = {"com.android.systemui"};

    static boolean is(AccessibilityNodeInfo root, String[] pkgs) {
        CharSequence p = root == null ? null : root.getPackageName();
        if (p == null) return false;
        for (String s : pkgs) if (s.contentEquals(p)) return true;
        return false;
    }

    /** Waits up to timeoutMs for a button with one of these labels in one of these packages and clicks it once. */
    static void clickWhenSeen(final long timeoutMs, final String[] inPkgs, final String... labels) {
        final long end = SystemClock.uptimeMillis() + timeoutMs;
        H.postDelayed(new Runnable() {
            @Override public void run() {
                AccessibilityNodeInfo root = RemoteControl.root();
                AccessibilityNodeInfo b = is(root, inPkgs) ? find(root, labels) : null;
                if (b != null && b.isEnabled()) { b.performAction(AccessibilityNodeInfo.ACTION_CLICK); return; }
                if (SystemClock.uptimeMillis() < end) H.postDelayed(this, 300);
            }
        }, 400);
    }

    private static final Runnable INSTALL_POLL = AutoClicker::installPoll;
    private static long installEnd;
    private static boolean installClicked;
    private static String installLabel;

    /**
     * Presses Install/Update on Android's own package-installer screen for OUR session's app
     * (the screen must show that app's label), then Done. Never "Install anyway" (Play Protect).
     * Stops as soon as the session reports a result (see {@link #installFinished()}).
     */
    static void confirmInstall(String appLabel) {
        H.removeCallbacks(INSTALL_POLL);
        if (!available() || appLabel == null || appLabel.trim().isEmpty()) return; // unknown app: you tap
        installLabel = appLabel.trim();
        installEnd = SystemClock.uptimeMillis() + 90000;
        installClicked = false;
        H.postDelayed(INSTALL_POLL, 500);
    }

    /** Install succeeded, failed or was cancelled: press nothing more (except a final Done). */
    static void installFinished() {
        if (!installClicked) { H.removeCallbacks(INSTALL_POLL); installEnd = 0; }
        else installEnd = Math.min(installEnd, SystemClock.uptimeMillis() + 5000); // just the Done button
    }

    private static void installPoll() {
        if (SystemClock.uptimeMillis() > installEnd) return;
        AccessibilityNodeInfo root = RemoteControl.root();
        if (is(root, INSTALLER) && findLabel(root, installLabel, 0) != null) {
            AccessibilityNodeInfo b = installClicked ? find(root, "Done") : find(root, "Install", "Update");
            if (b != null && b.isEnabled()) {
                b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (installClicked) return; // pressed Done: finished
                installClicked = true;
            }
        }
        H.postDelayed(INSTALL_POLL, 400);
    }

    private static final Runnable NP_POLL = AutoClicker::nowPlayingPoll;
    private static String npPkg, npTitle;
    private static long npEnd;

    /**
     * Spotify has no intent for its full player in the landscape/tablet layout, so once it is in
     * front, tap its mini-player: the current title shown in the lower part of the screen. If the
     * title sits higher up, the full player is already open and nothing is clicked.
     */
    static void openNowPlaying(String pkg, String title) {
        H.removeCallbacks(NP_POLL);
        if (!available() || title == null || title.trim().isEmpty()) return;
        npPkg = pkg;
        npTitle = title.trim();
        npEnd = SystemClock.uptimeMillis() + 15000; // cold start on the unit takes ~10 s
        H.postDelayed(NP_POLL, 800);
    }

    private static void nowPlayingPoll() {
        if (SystemClock.uptimeMillis() > npEnd) return;
        AccessibilityNodeInfo root = RemoteControl.root();
        CharSequence front = root == null ? null : root.getPackageName();
        if (front == null || !npPkg.contentEquals(front)) { H.postDelayed(NP_POLL, 400); return; }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        java.util.List<AccessibilityNodeInfo> hits = new java.util.ArrayList<>();
        collectTitle(root, npTitle, hits, 0);
        if (hits.isEmpty()) { H.postDelayed(NP_POLL, 400); return; } // still loading
        Rect r = new Rect();
        for (AccessibilityNodeInfo t : hits) {
            t.getBoundsInScreen(r);
            if (r.top < screen.top + screen.height() * 0.6f) continue;
            // The title itself links to the album; the cover art to its left opens the player.
            // (Spotify's mini-player puts the cover left of the title in both phone and tablet layouts.)
            if (r.left - screen.left < 24) continue; // no room for a cover: not the mini-player
            RemoteControl.tapAt(screen.left + (r.left - screen.left) / 2f, r.centerY());
            return;
        }
        // Title only shown high up: the full player is already open.
    }

    private static void collectTitle(AccessibilityNodeInfo n, String title, java.util.List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        if (startsWith(n.getText(), title) || startsWith(n.getContentDescription(), title)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectTitle(n.getChild(i), title, out, depth + 1);
    }

    private static boolean startsWith(CharSequence s, String title) {
        return s != null && s.toString().trim().regionMatches(true, 0, title, 0, title.length());
    }

    /**
     * Clickable control for the first label found (exact text or description, case-insensitive).
     * Works for classic Buttons (Pie) and for layouts where the label sits inside a separate
     * clickable container (newer Settings): then the smallest clickable area around it is used.
     */
    static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            AccessibilityNodeInfo t = findLabel(root, label, 0);
            if (t == null) continue;
            AccessibilityNodeInfo c = t;
            for (int up = 0; c != null && !c.isClickable() && up < 6; up++) c = c.getParent();
            if (c != null && c.isClickable()) return c;
            Rect r = new Rect();
            t.getBoundsInScreen(r);
            AccessibilityNodeInfo box = smallestClickable(root, r, null, 0);
            if (box != null) return box;
        }
        return null;
    }

    private static boolean matches(CharSequence s, String label) {
        return s != null && s.toString().trim().equalsIgnoreCase(label);
    }

    private static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo n, String label, int depth) {
        if (n == null || depth > 40) return null;
        if (matches(n.getText(), label) || matches(n.getContentDescription(), label)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findLabel(n.getChild(i), label, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static AccessibilityNodeInfo smallestClickable(AccessibilityNodeInfo n, Rect target,
                                                           AccessibilityNodeInfo best, int depth) {
        if (n == null || depth > 40) return best;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (!b.contains(target)) return best;
        if (n.isClickable()) {
            if (best == null) best = n;
            else {
                Rect bb = new Rect();
                best.getBoundsInScreen(bb);
                if ((long) b.width() * b.height() < (long) bb.width() * bb.height()) best = n;
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) best = smallestClickable(n.getChild(i), target, best, depth + 1);
        return best;
    }

    private AutoClicker() { }
}
