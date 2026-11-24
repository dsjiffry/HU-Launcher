package com.hu.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Whitelist firewall built on VpnService, designed to cost nothing for allowed apps:
 * allowed apps are *excluded* from the VPN (addDisallowedApplication) so their traffic never
 * touches this code. Everyone else is routed into the tun interface and simply dropped. No DNS
 * server is given to the VPN, so blocked apps fail fast at name lookup instead of hanging.
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
        out.add(c.getPackageName()); // never cut off the PC link
        return out;
    }

    static void setAllowed(Context c, Iterable<String> pkgs, boolean allow) {
        Set<String> s = allowed(c);
        for (String p : pkgs) { if (allow) s.add(p); else s.remove(p); }
        prefs(c).edit().putStringSet("allow", s).apply();
        if (running) start(c);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (on) start(c); else c.startService(new Intent(c, Firewall.class).setAction(STOP));
    }

    /** Starts (or re-applies) the firewall if it's switched on and consent was given before. */
    static void ensure(Context c) {
        if (enabled(c) && VpnService.prepare(c) == null) start(c);
    }

    private static void start(Context c) {
        if (VpnService.prepare(c) != null) return; // needs the one-time consent dialog first
        c.startService(new Intent(c, Firewall.class));
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
        List<String> allow = new ArrayList<>(), block = new ArrayList<>();
        JSONArray a = req.optJSONArray("allow"), b = req.optJSONArray("block");
        if (a != null) for (int i = 0; i < a.length(); i++) allow.add(a.getString(i));
        if (b != null) for (int i = 0; i < b.length(); i++) block.add(b.getString(i));
        if (!allow.isEmpty()) setAllowed(c, allow, true);
        if (!block.isEmpty()) setAllowed(c, block, false);
        if (req.has("enabled")) setEnabled(c, req.getBoolean("enabled"));
    }

    // ---- VPN ---------------------------------------------------------------------------------

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        establish();
        return START_STICKY;
    }

    private void establish() {
        Builder b = new Builder();
        b.setSession("HU Launcher firewall");
        b.addAddress("10.88.0.1", 32);
        b.addRoute("0.0.0.0", 0);
        try { b.addAddress("fd00:88::1", 128); b.addRoute("::", 0); } catch (RuntimeException ignored) { }
        b.setMtu(1500);
        b.setBlocking(true);
        for (String pkg : allowed(this)) {
            try { b.addDisallowedApplication(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
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
        tun = next;
        running = true;
        drain(next);
        close(old);
    }

    /** Reads and discards what blocked apps try to send (sleeps in read() when they're quiet). */
    private static void drain(final ParcelFileDescriptor pfd) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[32 * 1024];
            // Not closed here: the ParcelFileDescriptor owns the fd; closing it ends this loop.
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            try {
                while (in.read(buf) >= 0) blockedPackets.incrementAndGet();
            } catch (IOException ignored) { }
        }, "fw-drain");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void shutdown() {
        running = false;
        close(tun);
        tun = null;
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
        shutdown();
        super.onDestroy();
    }
}
