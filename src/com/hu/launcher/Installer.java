package com.hu.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installs APKs sent over the PC link (uploaded, or picked up from the Bluetooth inbox).
 * Streams straight into a PackageInstaller session - no copy in memory or on disk - then the
 * accessibility helper presses Install/Update on Android's confirmation screen.
 */
final class Installer {
    static final long MAX_APK = 200L * 1024 * 1024;
    private static volatile String last = "nothing installed yet";

    static String lastResult() { return last; }

    /** Writes {@code length} bytes from {@code in} into a new session and commits it. */
    static int install(Context ctx, InputStream in, long length, String name) throws IOException {
        Context c = ctx.getApplicationContext();
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (length > 0) p.setSize(length);
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        boolean committed = false;
        try {
            try (OutputStream out = s.openWrite("base.apk", 0, length > 0 ? length : -1)) {
                byte[] buf = new byte[64 * 1024];
                long left = length;
                while (left != 0) {
                    int want = left > 0 ? (int) Math.min(buf.length, left) : buf.length;
                    int r = in.read(buf, 0, want);
                    if (r < 0) {
                        if (left > 0) throw new IOException("upload ended early");
                        break;
                    }
                    out.write(buf, 0, r);
                    if (left > 0) left -= r;
                }
                s.fsync(out);
            }
            last = "installing " + name + " - waiting for the confirm screen";
            PcLink.expectUpdate(c); // if this is HU Launcher itself, the link comes back afterwards
            PendingIntent done = PendingIntent.getBroadcast(c, id,
                    new Intent(c, Result.class).putExtra("name", name), PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(done.getIntentSender());
            committed = true;
        } finally {
            if (!committed) { try { s.abandon(); } catch (RuntimeException ignored) { } }
            s.close();
        }
        return id;
    }

    static int installFile(Context c, File f) throws IOException {
        if (!f.isFile() || !f.getName().toLowerCase(java.util.Locale.US).endsWith(".apk")) throw new IOException("not an APK: " + f);
        if (f.length() > MAX_APK) throw new IOException("APK too large");
        try (InputStream in = new FileInputStream(f)) { return install(c, in, f.length(), f.getName()); }
    }

    /** Newest .apk in the usual Bluetooth inbox folders (and Download), or null. */
    static File newestBluetoothApk() {
        File root = Environment.getExternalStorageDirectory();
        String[] dirs = {"bluetooth", "Bluetooth", "Download/Bluetooth", "Download/bluetooth", "Download"};
        File best = null;
        for (String d : dirs) {
            File[] fs = new File(root, d).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (!f.isFile() || !f.getName().toLowerCase(java.util.Locale.US).endsWith(".apk")) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        return best;
    }

    static JSONObject status(Context c) throws Exception {
        android.content.pm.PackageInfo me = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return new JSONObject().put("last", last).put("helper", AutoClicker.available())
                .put("versionName", me.versionName).put("versionCode", me.versionCode);
    }

    /** Session result: shows the confirm screen (and lets the helper press it), or records the outcome. */
    public static class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String name = i.getStringExtra("name");
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) { last = name + ": no confirm screen"; return; }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); }
                catch (RuntimeException e) { last = name + ": " + e.getMessage(); return; }
                String label = appLabel(c, i);
                boolean auto = AutoClicker.available() && label != null;
                last = name + ": confirm screen shown" + (auto ? " - pressing Install" : " - tap Install on the unit");
                AutoClicker.confirmInstall(label);
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                AutoClicker.installFinished();
                last = name + ": installed";
            } else {
                AutoClicker.installFinished();
                String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                last = name + ": failed (" + st + (msg == null ? "" : ", " + msg) + ")";
            }
        }
    }

    /** Label of the app in THIS session, so the helper only confirms our own install. */
    private static String appLabel(Context c, Intent i) {
        String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        try {
            int sid = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            PackageInstaller.SessionInfo si = c.getPackageManager().getPackageInstaller().getSessionInfo(sid);
            if (si != null && si.getAppLabel() != null) return si.getAppLabel().toString();
            // Pie leaves the label empty but knows the package once the APK is validated.
            if (pkg == null && si != null) pkg = si.getAppPackageName();
        } catch (RuntimeException ignored) { }
        // Updates (like HU Launcher itself) use the installed label; brand-new apps: you tap Install.
        if (pkg == null) return null;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); // update: installed label
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private Installer() { }
}
