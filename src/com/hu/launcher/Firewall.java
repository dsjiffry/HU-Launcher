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
    private static final String[] ESSENTIAL = {"android", "com.hu.launcher", "com.android.providers.downloads",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.webview",
            "com.android.webview", "com.google.android.tts", "com.android.captiveportallogin",
            "com.android.networkstack", "com.google.android.networkstack",
            // Known-safe Google apps, always allowed by default:
            "com.google.android.apps.maps", "com.android.chrome", "com.google.android.youtube"};
    private static final int DEFAULTS_VERSION = 2;

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

    /** Default whitelist: things you installed or updated from Play, plus the plumbing they need. */
    private static Set<String> defaults(Context c) {
        Set<String> s = new HashSet<>(Arrays.asList(ESSENTIAL));
        for (NetApp a : internetApps(c)) {
            if (!a.system || a.updated) s.add(a.pkg);
        }
        s.add(MediaListener.SPOTIFY);
        s.add("com.google.android.apps.maps");
        return s;
    }

    static Set<String> allowed(Context c) {
        SharedPreferences p = prefs(c);
        Set<String> s = p.getStringSet("allow", null);
        if (s == null) {
            s = defaults(c);
            p.edit().putStringSet("allow", s).putInt("defaultsV", DEFAULTS_VERSION).apply();
        } else if (p.getInt("defaultsV", 1) < DEFAULTS_VERSION) {
            // Upgrade an existing list with newly added safe defaults (your own blocks stay).
            s = new HashSet<>(s);
            s.addAll(Arrays.asList(ESSENTIAL));
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
