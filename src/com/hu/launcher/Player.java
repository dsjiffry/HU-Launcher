package com.hu.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.view.KeyEvent;

import java.util.List;

/** Transport actions. Works even without notification access (falls back to media keys). */
final class Player {
    static void playPause(Context c) {
        MediaController mc = MediaListener.controller(c);
        if (mc != null) {
            if (MediaListener.isPlaying(mc.getPlaybackState())) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        } else if (!resumeSpotify(c)) {
            key(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    static void next(Context c) {
        MediaController mc = MediaListener.controller(c);
        if (mc != null) mc.getTransportControls().skipToNext(); else key(c, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    static void prev(Context c) {
        MediaController mc = MediaListener.controller(c);
        if (mc != null) mc.getTransportControls().skipToPrevious(); else key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    /** Opens the player app; for Spotify, then taps its mini-player to reach Now Playing. */
    static void open(Context c) {
        MediaController mc = MediaListener.controller(c);
        String pkg = mc == null ? MediaListener.SPOTIFY : mc.getPackageName();
        if (MediaListener.SPOTIFY.equals(pkg)) {
            // Spotify ignores its old "player.SHOW" action and its session/notification intents
            // open Home (checked on the unit), so open it normally and tap the mini-player.
            MediaMetadata md = mc == null ? null : mc.getMetadata();
            String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (launch(c, MediaListener.SPOTIFY)) { AutoClicker.openNowPlaying(MediaListener.SPOTIFY, title); return; }
        }
        if (mc != null) {
            PendingIntent pi = mc.getSessionActivity();
            if (pi != null) {
                try { pi.send(); return; } catch (PendingIntent.CanceledException ignored) { }
            }
            if (launch(c, mc.getPackageName())) return;
        }
        launch(c, MediaListener.SPOTIFY);
    }

    static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); return true; } catch (RuntimeException e) { return false; }
    }

    private static void key(Context c, int code) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    /**
     * No live session (e.g. Spotify was killed): bind Spotify's MediaBrowserService - the same
     * route the system's resume card uses - and send play through the session it returns.
     */
    private static boolean resumeSpotify(Context ctx) {
        final Context c = ctx.getApplicationContext();
        List<ResolveInfo> found = c.getPackageManager().queryIntentServices(
                new Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(MediaListener.SPOTIFY), 0);
        if (found == null || found.isEmpty()) return false;
        ComponentName svc = new ComponentName(MediaListener.SPOTIFY, found.get(0).serviceInfo.name);
        Bundle hints = new Bundle();
        hints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
        final Handler h = new Handler(Looper.getMainLooper());
        final MediaBrowser[] b = new MediaBrowser[1];
        final boolean[] done = new boolean[1];
        final Runnable fallback = () -> {
            if (done[0]) return;
            done[0] = true;
            try { b[0].disconnect(); } catch (RuntimeException ignored) { }
            key(c, KeyEvent.KEYCODE_MEDIA_PLAY);
        };
        b[0] = new MediaBrowser(c, svc, new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (done[0]) return;
                done[0] = true;
                MediaController.TransportControls t = new MediaController(c, b[0].getSessionToken()).getTransportControls();
                t.prepare();
                t.play();
                h.postDelayed(() -> { try { b[0].disconnect(); } catch (RuntimeException ignored) { } }, 15000);
            }
            @Override public void onConnectionFailed() { fallback.run(); }
            @Override public void onConnectionSuspended() { fallback.run(); }
        }, hints);
        try { b[0].connect(); } catch (RuntimeException e) { return false; }
        h.postDelayed(fallback, 3000);
        return true;
    }

    private Player() { }
}
