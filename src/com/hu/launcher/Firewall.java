package com.hu.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Whitelist firewall built on VpnService. Two modes, chosen by Android's "Block connections
 * without VPN" (lockdown) setting:
 *  - lockdown off: allowed apps are excluded from the tunnel (full speed); every other app is
 *    routed in and its packets are dropped (drain).
 *  - lockdown on: only the allowed apps are in the tunnel, and Relay forwards their traffic to the
 *    internet. Blocked apps have no route, and lockdown keeps them off the physical network.
 */
public class Firewall extends VpnService {
    static final String STOP = "stop";
    /**
     * Allowed by default: only the system parts that other things quietly depend on. Everything
     * else - including apps you installed, Spotify and Maps - starts blocked until you allow it.
     * Missing packages are simply skipped.
     */
    private static final String[] ESSENTIAL = {
            // Android itself (uid 1000): clock sync (NTP), Wi-Fi "connected, no internet" check, Settings.
            "android", "com.android.settings",
            // Wi-Fi / mobile data plumbing: network checks and hotel/hotspot sign-in pages.
            "com.android.networkstack", "com.google.android.networkstack", "com.android.captiveportallogin",
            "com.google.android.captiveportallogin", "com.android.networkstack.tethering",
            // SIM units: mobile data setup, MMS, carrier settings.
            "com.android.phone", "com.android.mms.service", "com.android.carrierconfig",
            // Download manager: other apps hand their downloads to it.
            "com.android.providers.downloads",
            // Google Play services + framework: push messages, location, sign-in, Maps/Spotify login,
            // app updates (Play Store), and offline voices for navigation.
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.tts",
            // Faster GPS fix: assistance-data download (MediaTek / Qualcomm).
            "com.mediatek.location.lppe.main", "com.mediatek.location.mtknlp", "com.mediatek.nlpservice",
            "com.qualcomm.location", "com.android.location.fused",
            // This launcher: update check and PC link.
            "com.hu.launcher"};
    private static final Set<String> ESSENTIAL_SET = new HashSet<>(Arrays.asList(ESSENTIAL));
    private static final int DEFAULTS_VERSION = 3;

    /** True for the system parts allowed by default (shown as "needed by the system"). */
    static boolean essential(String pkg) { return ESSENTIAL_SET.contains(pkg); }

    static final AtomicLong blockedPackets = new AtomicLong();
    private static volatile boolean running;
    private ParcelFileDescriptor tun;
    private Relay relay;
    private String applied;                 // signature of the tunnel in place
    private long lastDeath, retryDelay;

    static final class NetApp {
        String pkg, label;
        int uid;
        boolean system, updated;
    }

    // ---- Settings ----------------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("firewall", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }
    static boolean running() { return running; }

    /** Every app that asks for internet access, sorted user apps first. */
    static List<NetApp> internetApps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<NetApp> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0x200)) {
            String[] perms;
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS | 0x200);
                perms = pi.requestedPermissions;
            } catch (Exception e) {
                continue;
            }
            if (perms == null || !Arrays.asList(perms).contains("android.permission.INTERNET")) continue;
            NetApp a = new NetApp();
            a.pkg = ai.packageName;
            CharSequence l = ai.loadLabel(pm);
            a.label = l == null ? a.pkg : l.toString().trim();
            a.uid = ai.uid;
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            a.updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            out.add(a);
        }
        final Collator col = Collator.getInstance();
        Collections.sort(out, (x, y) -> x.system != y.system ? (x.system ? 1 : -1) : col.compare(x.label, y.label));
        return out;
    }

    /** Default allow-list: only the system essentials. You allow the apps you use. */
    private static Set<String> defaults() { return new HashSet<>(ESSENTIAL_SET); }

    static Set<String> allowed(Context c) {
        SharedPreferences p = prefs(c);
        Set<String> s = p.getStringSet("allow", null);
        int v = p.getInt("defaultsV", 1);
        if (s == null || (v < 3 && !enabled(c))) {
            // New install, or an older version's list for a firewall that was never switched on:
            // start from the minimal list.
            s = defaults();
            p.edit().putStringSet("allow", s).putInt("defaultsV", DEFAULTS_VERSION).apply();
        } else if (v < DEFAULTS_VERSION) {
            // Firewall already in use: keep your choices, just add any new essentials.
            s = new HashSet<>(s);
            s.addAll(ESSENTIAL_SET);
            p.edit().putStringSet("allow", s).putInt("defaultsV", DEFAULTS_VERSION).apply();
        }
        Set<String> out = new HashSet<>(s);
        // The system names are trusted only on system apps: an installed app that borrows one stays
        // blocked until you allow it yourself.
        Set<String> chosen = p.getStringSet("chosen", Collections.<String>emptySet());
        for (String pkg : ESSENTIAL) {
            if (out.contains(pkg) && !chosen.contains(pkg) && userInstalled(c, pkg)) out.remove(pkg);
        }
        out.add(c.getPackageName()); // never cut off the PC link
        return out;
    }

    private static boolean userInstalled(Context c, String pkg) {
        try {
            return (c.getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static void setAllowed(Context c, Iterable<String> pkgs, boolean allow) {
        save(c, pkgs, allow);
        if (running) start(c);
    }

    private static void save(Context c, Iterable<String> pkgs, boolean allow) {
        Set<String> s = allowed(c);
        Set<String> chosen = new HashSet<>(prefs(c).getStringSet("chosen", Collections.<String>emptySet()));
        for (String p : pkgs) {
            if (allow) { s.add(p); chosen.add(p); } else { s.remove(p); chosen.remove(p); }
        }
        prefs(c).edit().putStringSet("allow", s).putStringSet("chosen", chosen).apply();
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (on) start(c); else c.startService(new Intent(c, Firewall.class).setAction(STOP));
    }

    /**
     * Starts the firewall if it's switched on and consent was given before. Safe to call often:
     * a tunnel that is already right is left alone.
     */
    static void ensure(Context c) {
        if (enabled(c) && VpnService.prepare(c) == null) start(c);
    }

    private static void start(Context c) {
        if (VpnService.prepare(c) != null) return; // needs the one-time consent dialog first
        try {
            c.startService(new Intent(c, Firewall.class));
        } catch (IllegalStateException | SecurityException ignored) {
            // Android refused a start from the background: the next call tries again
        }
    }

    // ---- PC link -----------------------------------------------------------------------------

    static JSONObject report(Context c) throws Exception {
        Set<String> allow = allowed(c);
        Set<Integer> allowedUids = new HashSet<>();
        List<NetApp> apps = internetApps(c);
        for (NetApp a : apps) if (allow.contains(a.pkg)) allowedUids.add(a.uid);
        JSONArray arr = new JSONArray();
        for (NetApp a : apps) {
            arr.put(new JSONObject().put("pkg", a.pkg).put("label", a.label).put("uid", a.uid)
                    .put("system", a.system).put("allowed", allowedUids.contains(a.uid)));
        }
        return new JSONObject().put("enabled", enabled(c)).put("running", running)
                .put("consentNeeded", VpnService.prepare(c) != null)
                .put("blockedPackets", blockedPackets.get()).put("apps", arr);
    }

    static void applyRemote(Context c, JSONObject req) throws Exception {
        List<String> allow = packages(req.optJSONArray("allow")), block = packages(req.optJSONArray("block"));
        Boolean on = req.has("enabled") ? req.getBoolean("enabled") : null;
        if (!allow.isEmpty()) save(c, allow, true);
        if (!block.isEmpty()) save(c, block, false);
        // one rebuild for the whole request: each rebuild interrupts connections
        if (on != null) setEnabled(c, on);
        else if (running && !(allow.isEmpty() && block.isEmpty())) start(c);
    }

    private static List<String> packages(JSONArray a) throws Exception {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            Object o = a.get(i);
            if (!(o instanceof String) || !((String) o).matches("[A-Za-z0-9_.]{1,255}")) {
                throw new IllegalArgumentException("not a package name");
            }
            out.add((String) o);
        }
        return out;
    }

    // ---- VPN ---------------------------------------------------------------------------------

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // A null intent is Android restarting us after the process died.
        if (intent != null ? STOP.equals(intent.getAction()) : !enabled(this)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        establish();
        return START_STICKY;
    }

    /** Builds the tunnel for the current mode and allow-list, unless the one in place already fits. */
    private void establish() {
        boolean lockdown = lockdown(this);
        Set<String> allow = allowed(this);
        String wanted = signature(lockdown, allow);
        if (tun != null && wanted.equals(applied)) return;
        Builder b = new Builder();
        b.setSession("HU Launcher firewall");
        b.addAddress("10.88.0.1", 32);
        b.addRoute("0.0.0.0", 0);   // IPv4 only: IPv6 is not routed, so apps fall back to IPv4
        b.setBlocking(true);
        if (lockdown) {
            // Lockdown on: "Block connections without VPN" cuts off every app outside the tunnel.
            // So only the allowed apps go in, and Relay forwards their traffic. Blocked apps have no route.
            b.setMtu(Relay.MTU);
            try { b.addAllowedApplication(getPackageName()); } catch (PackageManager.NameNotFoundException ignored) { }
            for (String pkg : allow) {
                try { b.addAllowedApplication(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
            }
        } else {
            // Lockdown off: allowed apps stay outside the tunnel (full speed); everything else is
            // routed in and dropped by drain().
            b.setMtu(1500);
            // Name lookups of blocked apps go to an address inside the tunnel, so they are dropped too.
            b.addDnsServer("10.88.0.2");
            for (String pkg : allow) {
                try { b.addDisallowedApplication(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
            }
        }
        ParcelFileDescriptor next;
        try {
            next = b.establish();
        } catch (RuntimeException e) {
            next = null;
        }
        if (next == null) { // consent revoked
            prefs(this).edit().putBoolean("on", false).apply();
            shutdown();
            stopSelf();
            return;
        }
        ParcelFileDescriptor old = tun;
        Relay oldRelay = relay;
        final ParcelFileDescriptor made = next;
        Runnable died = () -> main.post(() -> tunnelDied(made));
        tun = next;
        applied = wanted;
        running = true;
        if (lockdown) {
            relay = new Relay(this, next, died);
            relay.start();
        } else {
            relay = null;
            drain(next, died);
        }
        if (oldRelay != null) oldRelay.stop();
        close(old);
    }

    /** Everything the tunnel depends on: the mode, and which apps (by UID) are allowed. */
    private String signature(boolean lockdown, Set<String> allow) {
        StringBuilder s = new StringBuilder(lockdown ? "lock" : "drain");
        for (String pkg : new TreeSet<>(allow)) {
            try {
                s.append(' ').append(pkg).append(':').append(getPackageManager().getApplicationInfo(pkg, 0).uid);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return s.toString();
    }

    /** The tunnel stopped working without us closing it: build a new one, waiting longer if it repeats. */
    private void tunnelDied(ParcelFileDescriptor dead) {
        if (!running || tun != dead) return;
        long now = SystemClock.elapsedRealtime();
        retryDelay = now - lastDeath < 2 * 60000L ? Math.min(60000L, Math.max(1000L, retryDelay * 2)) : 0;
        lastDeath = now;
        main.postDelayed(() -> {
            if (!running || tun != dead) return;
            applied = null;
            establish();
        }, retryDelay);
    }

    /** Reads and discards what blocked apps send through the tunnel (lockdown-off mode). */
    private static void drain(final ParcelFileDescriptor pfd, final Runnable died) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[32 * 1024];
            // Not closed here: the ParcelFileDescriptor owns the fd; closing it ends this loop.
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            try {
                while (in.read(buf) >= 0) blockedPackets.incrementAndGet();
            } catch (IOException ignored) { }
            died.run();   // ignored if we replaced or closed the tunnel ourselves
        }, "fw-drain");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * True when Android's "Block connections without VPN" (lockdown) is on for this app. If Android
     * names a different always-on app, it counts as off: that mode blocks apps without relying on
     * lockdown. Newer Android versions hide the name, and then the lockdown setting decides.
     */
    static boolean lockdown(Context c) {
        ContentResolver r = c.getContentResolver();
        if (Settings.Secure.getInt(r, LOCKDOWN_KEY, 0) != 1) return false;
        String app = null;
        try { app = Settings.Secure.getString(r, ALWAYS_ON_KEY); } catch (SecurityException ignored) { }
        return app == null || app.equals(c.getPackageName());
    }

    private static final String LOCKDOWN_KEY = "always_on_vpn_lockdown";
    private static final String ALWAYS_ON_KEY = "always_on_vpn_app";
    private final Handler main = new Handler(Looper.getMainLooper());
    private ContentObserver lockdownWatch;

    /** Re-applies the firewall when lockdown is switched, since the tunnel's mode depends on it. */
    @Override public void onCreate() {
        super.onCreate();
        lockdownWatch = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { if (running) establish(); }
        };
        for (String key : new String[]{LOCKDOWN_KEY, ALWAYS_ON_KEY}) {
            getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, lockdownWatch);
        }
    }

    private void shutdown() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (relay != null) relay.stop();
        relay = null;
        ParcelFileDescriptor p = tun;
        tun = null;
        applied = null;
        close(p);
    }

    private static void close(ParcelFileDescriptor p) {
        if (p != null) try { p.close(); } catch (IOException ignored) { }
    }

    @Override public void onRevoke() {
        prefs(this).edit().putBoolean("on", false).apply();
        shutdown();
        stopSelf();
    }

    @Override public void onDestroy() {
        if (lockdownWatch != null) getContentResolver().unregisterContentObserver(lockdownWatch);
        shutdown();
        super.onDestroy();
    }
}
