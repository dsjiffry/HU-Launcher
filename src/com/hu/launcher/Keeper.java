package com.hu.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Keep stopped": for apps Android won't let us disable (vendor-signed bloat).
 *
 * Nothing here reacts instantly to an app coming back, so a respawning app can't drag us into a
 * kill/restart loop:
 *  - A check runs at most once a minute, only when the home screen comes to the front, and
 *    costs one package-flag lookup per app on a background thread (~0.1 ms each).
 *  - An app whose "stopped" flag got cleared is first killed silently (killBackgroundProcesses:
 *    invisible, but its alarms/boot receivers still work).
 *  - A real Force stop (sets the flag, so its auto-starts stay off even across reboots) needs the
 *    App info screen for ~2 s. It runs for at most ONE app per check, only while home is in
 *    front and untouched for 10 s, and each app backs off 10 min, 20, 40 ... up to 24 h every
 *    time it comes back after one.
 */
final class Keeper {
    private static final String PREFS = "keeper", LIST = "pkgs";
    private static final long CHECK_EVERY = 60_000, IDLE_NEEDED = 10_000, SILENT_EVERY = 60_000;
    private static final long BASE_BACKOFF = 10 * 60_000L, MAX_BACKOFF = 24 * 3600_000L, DECAY = 12 * 3600_000L;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private static long lastCheck = -CHECK_EVERY, lastTouch;
    private static volatile boolean homeInFront;
    private static String pendingDeep;
    /** True while the night screen is up: no bright App-info pages pop up then (silent kills still run). */
    private static volatile boolean quiet;

    static void setQuiet(boolean q) { quiet = q; }
    private static final Runnable DEEP = Keeper::deepStopNow;
    private static Context app;

    // ---- list ----------------------------------------------------------------------------------

    static Set<String> list(Context c) {
        return new HashSet<>(prefs(c).getStringSet(LIST, new HashSet<>()));
    }

    private static final int MAX_LIST = 200;
    private static final java.util.regex.Pattern PKG = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    // Never stop these: the system itself, the screens the helper needs, or this app.
    private static final Set<String> NEVER = new HashSet<>(java.util.Arrays.asList(
            "android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.providers.settings", "com.android.inputmethod.latin", "com.google.android.gms"));

    /** Adds an app; returns why it was refused, or null if it's on the list. */
    static String add(Context c, String pkg) {
        if (pkg == null || !PKG.matcher(pkg).matches() || pkg.length() > 150) return "not a package name";
        if (pkg.equals(c.getPackageName()) || NEVER.contains(pkg)) return "protected system app";
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) return "core system app";
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
        String ime = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.startsWith(pkg + "/")) return "your keyboard";
        android.content.pm.ResolveInfo home = pm.resolveActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null && pkg.equals(home.activityInfo.packageName)) return "your home app";
        Set<String> s = list(c);
        if (!s.contains(pkg) && s.size() >= MAX_LIST) return "list is full";
        if (s.add(pkg)) prefs(c).edit().putStringSet(LIST, s).apply();
        lastCheck = -CHECK_EVERY; // look at it on the next home visit
        return null;
    }

    static void remove(Context c, String pkg) {
        Set<String> s = list(c);
        if (s.remove(pkg)) prefs(c).edit().putStringSet(LIST, s).remove("st_" + pkg).apply();
    }

    static boolean has(Context c, String pkg) { return list(c).contains(pkg); }

    // ---- hooks from Home -------------------------------------------------------------------------

    static void touched() {
        lastTouch = SystemClock.uptimeMillis();
        if (pendingDeep != null) { H.removeCallbacks(DEEP); H.postDelayed(DEEP, IDLE_NEEDED); }
    }

    static void homeResumed(Context c) {
        homeInFront = true;
        lastTouch = SystemClock.uptimeMillis(); // arriving home counts as activity: idle starts now
        app = c.getApplicationContext();
        long now = SystemClock.uptimeMillis();
        if (now - lastCheck < CHECK_EVERY) return;
        lastCheck = now;
        final Context ctx = app;
        Apps.BG.execute(() -> check(ctx));
    }

    static void homePaused() {
        homeInFront = false;
        H.removeCallbacks(DEEP); // never pull App info over another app; retried on the next visit
    }

    // ---- the check (background thread) -----------------------------------------------------------

    private static void check(Context c) {
        Set<String> pkgs = list(c);
        if (pkgs.isEmpty()) return;
        PackageManager pm = c.getPackageManager();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        String media = mediaApp(c);
        long wall = System.currentTimeMillis();
        String deep = null;
        SharedPreferences.Editor ed = prefs(c).edit();
        for (String p : pkgs) {
            if (p.equals(media)) continue; // never stop what's playing
            Boolean stopped = stopped(pm, p);
            if (stopped == null) continue; // uninstalled
            St s = St.load(c, p);
            if (stopped) {
                if (s.strikes > 0 && wall - s.lastStrike > DECAY) { s.strikes--; s.lastStrike = wall; }
            } else {
                if (s.armed) { // it came back after our force stop: back off harder
                    s.strikes++;
                    s.lastStrike = wall;
                    s.armed = false;
                }
                if (wall - s.lastSilent > SILENT_EVERY) {
                    try { am.killBackgroundProcesses(p); } catch (RuntimeException ignored) { }
                    s.lastSilent = wall;
                    s.silentKills++;
                }
                if (deep == null && wall >= s.nextDeep()) deep = p;
            }
            s.save(ed, p);
        }
        ed.apply();
        if (deep != null) {
            final String d = deep;
            H.post(() -> scheduleDeep(d));
        }
    }

    private static void scheduleDeep(String pkg) {
        if (!homeInFront || !AutoClicker.available()) return;
        pendingDeep = pkg;
        long idleFor = SystemClock.uptimeMillis() - lastTouch;
        H.removeCallbacks(DEEP);
        H.postDelayed(DEEP, Math.max(500, IDLE_NEEDED - idleFor));
    }

    private static void deepStopNow() {
        final String pkg = pendingDeep;
        final Context c = app;
        if (pkg == null || c == null) return;
        if (!homeInFront || quiet || AutoClicker.busy() || SystemClock.uptimeMillis() - lastTouch < IDLE_NEEDED) return;
        pendingDeep = null;
        AutoClicker.run(c, pkg, AutoClicker.FORCE_STOP, (ok, msg) -> Apps.BG.execute(() -> {
            St s = St.load(c, pkg);
            long wall = System.currentTimeMillis();
            if (ok) { s.armed = true; s.lastDeep = wall; s.deepStops++; }
            else s.retryAt = wall + 30 * 60_000L; // couldn't press it: try again in 30 min
            SharedPreferences.Editor ed = prefs(c).edit();
            s.save(ed, pkg);
            ed.apply();
        }));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** true = stopped, false = has been started since, null = not installed. */
    private static Boolean stopped(PackageManager pm, String p) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            return (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String mediaApp(Context c) {
        try {
            android.media.session.MediaController mc = MediaListener.controller(c);
            return mc == null ? null : mc.getPackageName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Per-app state, stored as one comma-separated pref so a check is a single read. */
    private static final class St {
        int strikes, silentKills, deepStops;
        long lastStrike, lastSilent, lastDeep, retryAt;
        boolean armed;

        static St load(Context c, String p) {
            St s = new St();
            String[] f = prefs(c).getString("st_" + p, "").split(",");
            if (f.length >= 8) {
                try {
                    s.strikes = Integer.parseInt(f[0]); s.lastStrike = Long.parseLong(f[1]);
                    s.lastSilent = Long.parseLong(f[2]); s.lastDeep = Long.parseLong(f[3]);
                    s.retryAt = Long.parseLong(f[4]); s.armed = f[5].equals("1");
                    s.silentKills = Integer.parseInt(f[6]); s.deepStops = Integer.parseInt(f[7]);
                } catch (NumberFormatException ignored) { }
            }
            return s;
        }

        void save(SharedPreferences.Editor ed, String p) {
            ed.putString("st_" + p, strikes + "," + lastStrike + "," + lastSilent + "," + lastDeep + ","
                    + retryAt + "," + (armed ? 1 : 0) + "," + silentKills + "," + deepStops);
        }

        long backoff() { return strikes == 0 ? 0 : Math.min(MAX_BACKOFF, BASE_BACKOFF << Math.min(10, strikes - 1)); }

        long nextDeep() { return Math.max(retryAt, lastDeep + backoff()); }
    }

    /** For the PC link: GET/POST /keep. */
    static JSONObject report(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        JSONArray a = new JSONArray();
        List<String> sorted = new ArrayList<>(list(c));
        java.util.Collections.sort(sorted);
        long wall = System.currentTimeMillis();
        for (String p : sorted) {
            St s = St.load(c, p);
            Boolean st = stopped(pm, p);
            a.put(new JSONObject().put("pkg", p)
                    .put("state", st == null ? "not installed" : st ? "stopped" : "running")
                    .put("strikes", s.strikes).put("silentKills", s.silentKills).put("forceStops", s.deepStops)
                    .put("nextForceStopMin", Math.max(0, (s.nextDeep() - wall) / 60000)));
        }
        return new JSONObject().put("apps", a).put("helper", AutoClicker.available());
    }

    /** Returns {pkg: reason} for every package that was refused. */
    static JSONObject applyRemote(Context c, JSONObject req) throws Exception {
        JSONObject refused = new JSONObject();
        JSONArray add = req.optJSONArray("add"), rem = req.optJSONArray("remove");
        if (add != null) for (int i = 0; i < add.length() && i < MAX_LIST; i++) {
            String why = add(c, add.optString(i));
            if (why != null) refused.put(add.optString(i), why);
        }
        if (rem != null) for (int i = 0; i < rem.length(); i++) remove(c, rem.optString(i));
        return refused;
    }

    private Keeper() { }
}
