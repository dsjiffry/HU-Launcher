package com.hu.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import java.io.IOException;

/**
 * Keeps the PC link (HTTP server + remote screen) alive while you use other apps.
 * Shows a persistent notification with the address and PIN so it's never on silently.
 */
public class PcLink extends Service implements LanServer.Events {
    interface Listener { void onPcEvent(String message, boolean verdictsChanged); }

    static volatile Listener listener;
    private static volatile LanServer server;
    private static volatile int port;

    static boolean running() { return server != null; }
    static String pin() { LanServer s = server; return s == null ? "" : s.pin; }
    static String address() {
        String ip = LanServer.address();
        return (ip == null ? "no-wifi" : ip) + ":" + port;
    }

    static void start(Context c) { c.startService(new Intent(c, PcLink.class)); }

    /** You pressed Stop (or too many wrong PINs): link off, and the next start gets a new PIN. */
    static void stop(Context c) {
        forget(c);
        ScreenCapture.stop();
        c.stopService(new Intent(c, PcLink.class));
    }

    /**
     * The PIN survives app updates so the PC can install a new version and carry on; it changes
     * when you stop the link or the unit reboots (the link doesn't come back after a reboot).
     */
    private static String pinFor(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("pclink", MODE_PRIVATE);
        String pin = p.getString("pin", null), key = bootKey(c), saved = p.getString("bootKey", null);
        // Same boot (or boot unknown - then BootReceiver clears it at every boot): keep the PIN.
        if (pin == null || (key != null && !key.equals(saved))) pin = LanServer.newPin();
        p.edit().putString("pin", pin).putBoolean("on", true).putString("bootKey", key).remove("boot").apply();
        return pin;
    }

    /**
     * Identifies this boot without trusting the clock (units without a clock battery restart at
     * the same date): Android's boot counter, else the kernel's random boot id, else null.
     */
    static String bootKey(Context c) {
        try {
            int n = Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
            if (n >= 0) return "c" + n;
        } catch (RuntimeException ignored) { }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id = r.readLine();
            if (id != null && !id.trim().isEmpty()) return "i" + id.trim();
        } catch (IOException | RuntimeException ignored) { }
        return null;
    }

    private static boolean sameBoot(Context c, android.content.SharedPreferences p) {
        String key = bootKey(c);
        return key != null && key.equals(p.getString("bootKey", null));
    }

    /** Called when an install over the link starts: if HU Launcher replaces itself, come back. */
    static void expectUpdate(Context c) {
        c.getSharedPreferences("pclink", MODE_PRIVATE).edit().putLong("installAt", System.currentTimeMillis()).apply();
    }

    /**
     * Home's backup path for the restart after an update: only within 10 min of an install over
     * the link, in the same boot. A force stop or crash otherwise leaves the link off.
     */
    static boolean resumeAfterInstall(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("pclink", MODE_PRIVATE);
        long at = p.getLong("installAt", 0), age = System.currentTimeMillis() - at;
        return p.getBoolean("on", false) && sameBoot(c, p) && at > 0 && age >= 0 && age < 10 * 60000;
    }

    static void forget(Context c) {
        c.getSharedPreferences("pclink", MODE_PRIVATE).edit().clear().apply();
    }

    /** After an app update: bring the link back if it was on before. */
    public static class Updated extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
            android.content.SharedPreferences p = c.getSharedPreferences("pclink", MODE_PRIVATE);
            if (running() || !p.getBoolean("on", false)) return;
            String key = bootKey(c);
            if (key != null && !key.equals(p.getString("bootKey", null))) return; // different boot
            try { c.startForegroundService(new Intent(c, PcLink.class)); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null) {
            LanServer s = new LanServer(this, new Verdicts(this), this, pinFor(this));
            try {
                port = s.start();
                server = s;
            } catch (IOException e) {
                startForeground(7, notification()); // required after startForegroundService
                notifyListener("Could not open network port: " + e.getMessage(), false);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        startForeground(7, notification());
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        LanServer s = server;
        server = null;
        if (s != null) s.stop();
        ScreenCapture.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("pclink", "PC link", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "pclink");
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        return b.setSmallIcon(R.drawable.ic_pc)
                .setContentTitle("PC link on — PIN " + pin())
                .setContentText("Open http://" + address() + " on your PC")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private static void notifyListener(String msg, boolean verdicts) {
        Listener l = listener;
        if (l != null) l.onPcEvent(msg, verdicts);
    }

    @Override public void onVerdicts(int count) {
        notifyListener("Got " + count + " verdicts from the PC ✓", true);
    }

    @Override public void onStopped(String reason) {
        notifyListener(reason, false);
        stop(this);
    }
}
