package com.hu.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * "Keep Spotify ready": holds a connection to Spotify's media service (the one Android Auto and
 * car head units use). Android keeps a service's process alive while an important client is
 * bound to it - and this launcher is important (home app, plus a notification listener the
 * system keeps bound). If Spotify is killed anyway, Android restarts it for the open connection.
 * Nothing is sent over the connection and nothing polls: zero CPU while idle. Spotify itself uses
 * its usual RAM all the time, which is the point. No root needed.
 */
final class SpotifyKeeper {
    private static final String PREFS = "spotify";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean bound;
    private static long lastBind;
    private static int failures;
    private static String state = "off";

    static boolean enabled(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("keep", false); }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("keep", on).apply();
        if (on) ensure(c); else stop();
    }

    /** One-line status for the menu / PC link. */
    static String state() { return state; }

    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            failures = 0;
            state = "running";
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            // Spotify's process died: the binding stays, and Android restarts it for us.
            state = "restarting";
        }
        @Override public void onBindingDied(ComponentName n) { // updated, disabled or force-stopped
            state = "reconnecting";
            unbind();
            retry();
        }
        @Override public void onNullBinding(ComponentName n) {
            state = "Spotify refused the connection";
            unbind();
            retry();
        }
    };

    private static final Runnable RETRY = () -> { if (app != null && enabled(app)) bind(); };

    /** Called when home starts and when the media listener connects; cheap if already bound. */
    static void ensure(Context c) {
        app = c.getApplicationContext();
        if (!enabled(app)) { state = "off"; return; }
        if (!bound) bind();
    }

    static void stop() {
        H.removeCallbacks(RETRY);
        unbind();
        state = "off";
    }

    private static void bind() {
        if (bound || app == null) return;
        ComponentName svc = mediaService(app);
        if (svc == null) { state = "Spotify isn't installed"; retryLater(10 * 60_000L); return; }
        try {
            bound = app.bindService(new Intent("android.media.browse.MediaBrowserService").setComponent(svc),
                    CONN, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            bound = false;
        }
        lastBind = SystemClock.uptimeMillis();
        if (!bound) { state = "Spotify is disabled or blocked"; retry(); }
        else if (!"running".equals(state)) state = "starting";
    }

    private static void unbind() {
        if (!bound || app == null) return;
        try { app.unbindService(CONN); } catch (RuntimeException ignored) { }
        bound = false;
    }

    /** 5 s, 10 s, 20 s ... capped at 10 min, so a broken Spotify can't make us spin. */
    private static void retry() {
        failures = Math.min(failures + 1, 8);
        retryLater(Math.min(10 * 60_000L, 5000L << (failures - 1)));
    }

    private static void retryLater(long ms) {
        H.removeCallbacks(RETRY);
        H.postDelayed(RETRY, ms);
    }

    static ComponentName mediaService(Context c) {
        try {
            List<ResolveInfo> l = c.getPackageManager().queryIntentServices(
                    new Intent("android.media.browse.MediaBrowserService").setPackage(MediaListener.SPOTIFY), 0);
            if (l == null || l.isEmpty() || l.get(0).serviceInfo == null) return null;
            return new ComponentName(l.get(0).serviceInfo.packageName, l.get(0).serviceInfo.name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private SpotifyKeeper() { }
}
