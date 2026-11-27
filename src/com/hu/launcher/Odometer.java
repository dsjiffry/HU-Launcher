package com.hu.launcher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

import java.util.Locale;

/**
 * Distance driven since the last reset, from GPS, shown as is or counted down from a set value.
 * Off until turned on in Tools. A foreground service, because Android stops location updates to
 * background apps while you use maps or music. Only the total is stored: no positions are kept
 * or sent anywhere.
 */
public class Odometer extends Service implements LocationListener {
    private static final long SAVE_EVERY_CM = 200 * 100; // head units lose power without warning

    interface Listener { void onDistanceChanged(); }

    /** Set by the home screen while it is visible. Everything here runs on the main thread. */
    static Listener listener;

    private static long totalCm = -1, savedCm;
    private final Trip trip = new Trip();
    private LocationManager lm;
    private boolean listening;

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean gpsOn(Context c) {
        try {
            return ((LocationManager) c.getSystemService(LOCATION_SERVICE))
                    .isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException e) { return false; }
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }

    /** Turning it off stops the GPS and keeps the total. */
    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (on) ensure(c);
        else c.stopService(new Intent(c, Odometer.class));
        changed();
    }

    /** Starts counting if turned on and location is allowed; safe to call repeatedly. */
    static void ensure(Context c) {
        if (!enabled(c) || !allowed(c)) return;
        Intent i = new Intent(c, Odometer.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException ignored) { }
    }

    static boolean miles(Context c) { return prefs(c).getBoolean("miles", false); }

    static void setMiles(Context c, boolean miles) {
        prefs(c).edit().putBoolean("miles", miles).apply();
        changed();
    }

    static String unit(Context c) { return miles(c) ? "MI" : "KM"; }

    /** Whole kilometres (or miles) in a stored distance. */
    private static long whole(Context c, long cm) { return (long) (cm / unitCm(c)); }

    private static double unitCm(Context c) { return miles(c) ? 160_934.4 : 100_000; }

    /** "2352 KM"; counting down, what is left, going negative once it is used up. */
    static String text(Context c) {
        long done = whole(c, total(c)), from = countdownFrom(c);
        return String.format(Locale.US, "%d %s", from > 0 ? from - done : done, unit(c));
    }

    /** The countdown's starting value in the shown unit, or 0 when counting up. */
    static long countdownFrom(Context c) { return Math.round(prefs(c).getLong("fromCm", 0) / unitCm(c)); }

    /** Counts down from this many km (or miles), starting now. */
    static void startCountdown(Context c, long value) {
        prefs(c).edit().putLong("fromCm", Math.round(value * unitCm(c))).apply();
        reset(c);
    }

    /** Back to showing the distance driven since the last reset. */
    static void countUp(Context c) {
        prefs(c).edit().remove("fromCm").apply();
        changed();
    }

    static void reset(Context c) {
        totalCm = 0;
        save(c);
        changed();
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("odometer", MODE_PRIVATE); }

    private static long total(Context c) {
        if (totalCm < 0) totalCm = savedCm = Math.max(0, prefs(c).getLong("cm", 0));
        return totalCm;
    }

    private static void save(Context c) {
        savedCm = totalCm;
        prefs(c).edit().putLong("cm", totalCm).apply();
    }

    private static void changed() {
        Listener l = listener;
        if (l != null) l.onDistanceChanged();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(8, notification());
        if (!listening) {
            try {
                lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this, Looper.getMainLooper());
                listening = true;
            } catch (RuntimeException e) { // permission withdrawn, or no GPS on this unit
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (listening) lm.removeUpdates(this);
        if (totalCm >= 0 && totalCm != savedCm) save(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onLocationChanged(Location l) {
        double m = trip.add(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : -1,
                l.hasSpeed() ? l.getSpeed() : -1, l.getElapsedRealtimeNanos() / 1_000_000);
        if (m <= 0) {
            // Standing still: write what is left, in case the ignition goes off now.
            if (totalCm >= 0 && totalCm != savedCm && l.hasSpeed() && l.getSpeed() < 1) save(this);
            return;
        }
        long before = total(this);
        totalCm = before + Math.round(m * 100);
        if (totalCm - savedCm >= SAVE_EVERY_CM) save(this);
        if (whole(this, totalCm) != whole(this, before)) changed();
    }

    // All three must exist: Android 9 calls them and has no default versions.
    @Override public void onProviderEnabled(String provider) { changed(); }
    @Override public void onProviderDisabled(String provider) { changed(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("odometer", "Distance counter",
                    NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "odometer");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0);
        return b.setSmallIcon(R.drawable.ic_app_fg)
                .setContentTitle("Counting distance (GPS)")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
