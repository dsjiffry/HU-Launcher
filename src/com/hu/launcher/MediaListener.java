package com.hu.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Notification access is what lets us see other apps' media sessions. This service tracks the
 * session worth showing (whatever is playing; otherwise Spotify; otherwise the newest) and pokes
 * the home screen when it changes. Notifications themselves are never read.
 */
public class MediaListener extends NotificationListenerService {
    static final String SPOTIFY = "com.spotify.music";

    interface Listener { void onMediaChanged(); }

    static Listener listener;
    private static MediaController current;
    private static List<MediaController> all;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable push = () -> { if (listener != null) listener.onMediaChanged(); };
    private MediaSessionManager sessions;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { schedule(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) {
            // Another app may have started playing while ours paused: re-pick.
            current = pick(all);
            schedule();
        }
        @Override public void onSessionDestroyed() { attach(null); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener onSessions = this::attach;

    static ComponentName component(Context c) { return new ComponentName(c, MediaListener.class); }

    /** Reads the secure setting directly: works on every version, no API-level branching. */
    static boolean hasAccess(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(component(c).flattenToString());
    }

    static MediaController controller(Context c) {
        if (current != null) return current;
        if (!hasAccess(c)) return null;
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            return pick(m.getActiveSessions(component(c)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean isPlaying(PlaybackState s) {
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                || st == PlaybackState.STATE_CONNECTING;
    }

    private static MediaController pick(List<MediaController> list) {
        if (list == null || list.isEmpty()) return null;
        for (MediaController c : list) if (isPlaying(c.getPlaybackState())) return c;
        for (MediaController c : list) if (SPOTIFY.equals(c.getPackageName())) return c;
        return list.get(0);
    }

    @Override public void onListenerConnected() {
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessions.addOnActiveSessionsChangedListener(onSessions, component(this), handler);
            attach(sessions.getActiveSessions(component(this)));
        } catch (SecurityException e) {
            attach(null);
        }
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(onSessions);
        attach(null);
    }

    private void attach(List<MediaController> list) {
        // Watch every session so a pause/play anywhere triggers a re-pick.
        if (all != null) for (MediaController c : all) c.unregisterCallback(callback);
        all = list;
        if (list != null) for (MediaController c : list) c.registerCallback(callback, handler);
        current = pick(list);
        schedule();
    }

    /** Players fire several callbacks per track change; coalesce them into one redraw. */
    private void schedule() {
        handler.removeCallbacks(push);
        handler.postDelayed(push, 50);
    }
}
