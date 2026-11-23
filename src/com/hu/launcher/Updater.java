package com.hu.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Update check against this project's GitHub releases.
 *  - At most once a day, in the background, when the home screen comes up (can be turned off in
 *    Tools → Updates). The only request is GET api.github.com/.../releases/latest: no IDs, no data.
 *  - A newer release shows a silent, low-importance notification - shade only, no sound/pop-up.
 *  - "Update" downloads the release's .apk over HTTPS, checks it is HU Launcher, and hands it to the
 *    package installer (Android itself refuses an APK signed with a different key).
 */
final class Updater {
    static final String REPO = "dsjiffry/HU-Launcher";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String CHANNEL = "updates";
    private static final int NOTIFY_ID = 11;
    private static final long DAY = 24 * 3600_000L, MAX_APK = 50L * 1024 * 1024;
    private static volatile boolean busy;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("updater", Context.MODE_PRIVATE); }

    static boolean autoEnabled(Context c) { return prefs(c).getBoolean("auto", true); }
    static void setAuto(Context c, boolean on) { prefs(c).edit().putBoolean("auto", on).apply(); }

    /** Called when home comes up: checks at most once a day, off the main thread. */
    static void maybeCheck(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (busy) return;
        File old = new File(c.getCacheDir(), "update.apk"); // left over from the last update
        if (old.exists()) Apps.BG.execute(old::delete);
        if (!autoEnabled(c)) return;
        long last = prefs(c).getLong("lastCheck", 0), now = System.currentTimeMillis();
        if (now - last >= 0 && now - last < DAY) return;
        Apps.BG.execute(() -> check(c, false));
    }

    /** Result for the "Check now" button: null = up to date, else the new version name. */
    interface Done { void onDone(String newVersion, String error); }

    static void checkNow(Context ctx, Done done) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            String[] r = check(c, true);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> done.onDone(r[0], r[1]));
        }, "update-check").start();
    }

    /** Returns {newVersionOrNull, errorOrNull}. */
    private static String[] check(Context c, boolean manual) {
        if (busy) return new String[]{null, "already checking"};
        busy = true;
        try {
            prefs(c).edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            JSONObject rel = new JSONObject(new String(get(API, 512 * 1024), "UTF-8"));
            if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) return new String[]{null, null};
            String tag = rel.optString("tag_name");
            String apkUrl = null;
            JSONArray assets = rel.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").toLowerCase(java.util.Locale.US).endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break; }
            }
            String mine = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            if (apkUrl == null || !apkUrl.startsWith("https://github.com/" + REPO + "/releases/download/")
                    || compare(tag, mine) <= 0) {
                if (manual) cancel(c);
                return new String[]{null, null};
            }
            String ver = tag.replaceFirst("^[vV]", "");
            prefs(c).edit().putString("url", apkUrl).putString("ver", ver).apply();
            notifyAvailable(c, ver, mine);
            return new String[]{ver, null};
        } catch (Exception e) {
            return new String[]{null, "couldn't reach GitHub"};
        } finally {
            busy = false;
        }
    }

    /** Compares "v2.1.10" with "2.1.3" numerically, part by part. */
    static int compare(String a, String b) {
        String[] x = a.replaceFirst("^[vV]", "").split("[.\\-+]"), y = b.replaceFirst("^[vV]", "").split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    // ---- notification ----------------------------------------------------------------------------

    private static Notification.Builder builder(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            // LOW = shown in the shade only: no sound, no pop-up over the map.
            NotificationChannel ch = new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            return new Notification.Builder(c, CHANNEL);
        }
        return new Notification.Builder(c).setPriority(Notification.PRIORITY_LOW);
    }

    private static void notifyAvailable(Context c, String ver, String mine) {
        PendingIntent go = PendingIntent.getBroadcast(c, 1, new Intent(c, Act.class).setAction("update"),
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = builder(c)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("HU Launcher " + ver + " is available")
                .setContentText("You have " + mine + ". Tap Update to install it.")
                .setContentIntent(go)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Update", go).build())
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .build();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFY_ID, n);
    }

    private static void status(Context c, String title, String text, boolean ongoing) {
        Notification n = builder(c).setSmallIcon(R.drawable.ic_download).setContentTitle(title).setContentText(text)
                .setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing).build();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFY_ID, n);
    }

    static void cancel(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIFY_ID);
    }

    // ---- download + install --------------------------------------------------------------------

    /** The notification's Update button. */
    public static class Act extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent i) {
            final Context c = ctx.getApplicationContext();
            // Collapse the shade so the installer's confirm screen is visible.
            try { c.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)); } catch (RuntimeException ignored) { }
            install(c);
        }
    }

    static void install(final Context c) {
        final String url = prefs(c).getString("url", null), ver = prefs(c).getString("ver", "");
        if (url == null || busy) return;
        busy = true;
        status(c, "Downloading HU Launcher " + ver + "…", "From GitHub releases", true);
        new Thread(() -> {
            File apk = new File(c.getCacheDir(), "update.apk");
            try {
                download(url, apk);
                PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (pi == null || !c.getPackageName().equals(pi.packageName)) throw new IOException("not a HU Launcher APK");
                status(c, "Installing HU Launcher " + ver, AutoClicker.available() ? "Confirming…" : "Tap Update on the screen", false);
                Installer.installFile(c, apk);
                cancel(c);
            } catch (Exception e) {
                status(c, "Update failed", e.getMessage() == null ? "Download error" : e.getMessage(), false);
            } finally {
                busy = false;
            }
        }, "update-download").start();
    }

    // ---- http ------------------------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        h.setRequestProperty("User-Agent", "HU-Launcher-updater"); // GitHub requires one; nothing identifying
        h.setInstanceFollowRedirects(true);
        if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
        if (!"https".equals(h.getURL().getProtocol())) throw new IOException("insecure redirect");
        return h;
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection h = open(url);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int r; (r = in.read(buf)) > 0; ) { b.write(buf, 0, r); if (b.size() > max) throw new IOException("response too large"); }
            return b.toByteArray();
        } finally { h.disconnect(); }
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection h = open(url);
        long total = 0;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) {
                total += r;
                if (total > MAX_APK) throw new IOException("APK too large");
                out.write(buf, 0, r);
            }
        } finally { h.disconnect(); }
        if (total < 1024) throw new IOException("download incomplete");
    }

    private Updater() { }
}
