package com.hu.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Activity" screen. Android 9 hides other apps' processes, CPU and RAM from normal apps, so this
 * shows what *is* available: device RAM / CPU clocks / temperature live, plus per-app screen time,
 * data used and run state for today (needs "Usage access").
 */
public class Monitor extends Activity {
    static final class AppUse {
        String pkg, label;
        long fgMs, lastUsed, rx, tx;
        boolean stopped, system;
        Bitmap icon;
        long bytes() { return rx + tx; }
    }

    static final class Device {
        long totalRam, availRam;
        boolean lowMemory;
        List<Integer> cpuMhz = new ArrayList<>();
        int cpuMaxMhz;
        int cpuPct = -1;
        float tempC = Float.NaN;
        long uptimeMs;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AppUse> apps = new ArrayList<>();
    private int cpuTries;
    private TextView ram, cpu, temp, note;
    private Bar ramBar;
    private LinearLayout grant;
    private BaseAdapter adapter;
    private final Runnable tick = new Runnable() {
        @Override public void run() { showDevice(); handler.postDelayed(this, 2000); }
    };

    // ---- Data --------------------------------------------------------------------------------

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(APP_OPS_SERVICE);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    private static int[] coresCache;

    /** Core numbers from /sys/devices/system/cpu/present ("0-3", "0-3,6"), else 0..N-1. */
    static int[] presentCores() {
        if (coresCache != null) return coresCache;
        List<Integer> out = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/sys/devices/system/cpu/present"))) {
            String s = r.readLine();
            if (s != null) {
                for (String part : s.trim().split(",")) {
                    if (part.isEmpty()) continue;
                    String[] ab = part.split("-");
                    int a = Integer.parseInt(ab[0].trim()), b = ab.length > 1 ? Integer.parseInt(ab[1].trim()) : a;
                    for (int i = a; i <= b && out.size() < 64; i++) out.add(i);
                }
            }
        } catch (java.io.IOException | RuntimeException ignored) { out.clear(); }
        if (out.isEmpty()) {
            int n = Math.max(1, Runtime.getRuntime().availableProcessors());
            for (int i = 0; i < n; i++) out.add(i);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        coresCache = arr;
        return arr;
    }

    static Device device(Context c) {
        Device d = new Device();
        ActivityManager am = (ActivityManager) c.getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        d.totalRam = mi.totalMem;
        d.availRam = mi.availMem;
        d.lowMemory = mi.lowMemory;
        // MediaTek kernels make cpu0..cpu9 folders ("possible" cores) even on 4-core chips, so
        // only count the cores the kernel lists as physically present.
        for (int i : presentCores()) {
            File dir = new File("/sys/devices/system/cpu/cpu" + i);
            if (!dir.exists()) continue;
            int cur = readInt(new File(dir, "cpufreq/scaling_cur_freq"));
            d.cpuMhz.add(cur > 0 ? cur / 1000 : 0); // 0 = core asleep (hot-unplugged)
            d.cpuMaxMhz = Math.max(d.cpuMaxMhz, readInt(new File(dir, "cpufreq/cpuinfo_max_freq")) / 1000);
        }
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones != null) {
            for (File z : zones) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                int t = readInt(new File(z, "temp"));
                if (t <= 0) continue;
                float cTemp = t > 1000 ? t / 1000f : t;
                if (cTemp > 5 && cTemp < 130 && (Float.isNaN(d.tempC) || cTemp > d.tempC)) d.tempC = cTemp;
            }
        }
        d.cpuPct = cpuPercent(d.cpuMhz.size());
        d.uptimeMs = SystemClock.elapsedRealtime();
        return d;
    }

    // ---- CPU % --------------------------------------------------------------------------------
    // Android 9 blocks /proc/stat for apps, so the fallback reads each core's cpuidle residency
    // (time spent idle) and compares it with wall time. Per-core, so MediaTek's constant core
    // hot-plugging doesn't cause spikes (a core that was off counts as idle).
    private static final Object CPU_LOCK = new Object();
    private static long statIdle = -1, statTotal = -1, prevWall = -1;
    private static long[] prevCore;
    private static boolean statBlocked;
    static volatile boolean approximate;
    private static int lastPct = -1;

    /** Whole-device CPU use since the previous call, 0..100, or -1 when not known yet. */
    static int cpuPercent(int cores) {
        synchronized (CPU_LOCK) {
            if (!statBlocked) {
                long[] s = procStat();
                if (s != null) {
                    long dIdle = s[0] - statIdle, dTotal = s[1] - statTotal;
                    boolean first = statTotal < 0;
                    statIdle = s[0];
                    statTotal = s[1];
                    if (!first && dTotal > 0) lastPct = clampPct(100.0 * (dTotal - dIdle) / dTotal);
                    return lastPct;
                }
                statBlocked = true;
            }
            long wall = SystemClock.elapsedRealtimeNanos() / 1000;
            int[] ids = presentCores();
            long[] cur = new long[Math.max(1, ids.length)];
            boolean any = false;
            for (int c = 0; c < ids.length; c++) { cur[c] = coreIdleUs(ids[c]); any |= cur[c] >= 0; }
            if (!any) {
                // Last resort: 1-minute load average per core (slow-moving, so shown as "~").
                try (BufferedReader r = new BufferedReader(new FileReader("/proc/loadavg"))) {
                    float load = Float.parseFloat(r.readLine().trim().split(" ")[0]);
                    approximate = true;
                    return lastPct = clampPct(100.0 * load / cur.length);
                } catch (Exception e) {
                    return -1;
                }
            }
            approximate = false;
            if (prevCore != null && prevCore.length == cur.length && wall > prevWall) {
                long dWall = wall - prevWall, busy = 0;
                for (int c = 0; c < cur.length; c++) {
                    if (cur[c] < 0 || prevCore[c] < 0) continue; // off at either end: idle
                    busy += dWall - Math.max(0, Math.min(dWall, cur[c] - prevCore[c]));
                }
                lastPct = clampPct(100.0 * busy / (dWall * cur.length));
            }
            prevCore = cur;
            prevWall = wall;
            return lastPct;
        }
    }

    private static int clampPct(double v) { return (int) Math.max(0, Math.min(100, Math.round(v))); }

    private static long[] procStat() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String[] f = r.readLine().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            return new long[]{idle, total};
        } catch (Exception e) {
            return null;
        }
    }

    /** Total idle µs of one core across its idle states, or -1 if the core is off/unreadable. */
    private static long coreIdleUs(int c) {
        String base = "/sys/devices/system/cpu/cpu" + c;
        File online = new File(base, "online");
        if (online.exists() && readInt(online) == 0) return -1;
        File[] states = new File(base, "cpuidle").listFiles();
        if (states == null) return -1;
        long sum = 0;
        boolean got = false;
        for (File st : states) {
            if (!st.getName().startsWith("state")) continue;
            long t = readLong(new File(st, "time"));
            if (t >= 0) { sum += t; got = true; }
        }
        return got ? sum : -1;
    }

    private static long readLong(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            return Long.parseLong(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static int readInt(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static long startOfToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** Today's per-app usage, busiest first. Empty without usage access. */
    static List<AppUse> usage(Context c) {
        List<AppUse> out = new ArrayList<>();
        if (!hasUsageAccess(c)) return out;
        PackageManager pm = c.getPackageManager();
        long from = startOfToday(), now = System.currentTimeMillis();
        Map<String, AppUse> byPkg = new HashMap<>();
        Map<Integer, List<AppUse>> byUid = new HashMap<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            AppUse a = new AppUse();
            a.pkg = ai.packageName;
            CharSequence l = ai.loadLabel(pm);
            a.label = l == null ? a.pkg : l.toString().trim();
            a.stopped = (ai.flags & ApplicationInfo.FLAG_STOPPED) != 0;
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            byPkg.put(a.pkg, a);
            List<AppUse> list = byUid.get(ai.uid);
            if (list == null) { list = new ArrayList<>(); byUid.put(ai.uid, list); }
            list.add(a);
        }
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(USAGE_STATS_SERVICE);
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, now);
        if (stats != null) {
            for (UsageStats s : stats) {
                AppUse a = byPkg.get(s.getPackageName());
                if (a == null) continue;
                a.fgMs += s.getTotalTimeInForeground();
                a.lastUsed = Math.max(a.lastUsed, s.getLastTimeUsed());
            }
        }
        // Data per UID (shared UIDs are credited to their first package).
        try {
            NetworkStatsManager nsm = (NetworkStatsManager) c.getSystemService(NETWORK_STATS_SERVICE);
            for (int type : new int[]{ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET, ConnectivityManager.TYPE_MOBILE}) {
                NetworkStats ns;
                try { ns = nsm.querySummary(type, null, from, now); } catch (Exception e) { continue; }
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (ns.hasNextBucket()) {
                    ns.getNextBucket(b);
                    List<AppUse> owners = byUid.get(b.getUid());
                    if (owners == null || owners.isEmpty()) continue;
                    owners.get(0).rx += b.getRxBytes();
                    owners.get(0).tx += b.getTxBytes();
                }
                ns.close();
            }
        } catch (RuntimeException ignored) { }
        for (AppUse a : byPkg.values()) {
            if (a.fgMs > 0 || a.bytes() > 0 || (a.lastUsed >= from)) out.add(a);
        }
        Collections.sort(out, (x, y) -> {
            int byData = Long.compare(y.bytes(), x.bytes());
            return byData != 0 ? byData : Long.compare(y.fgMs, x.fgMs);
        });
        return out;
    }

    static JSONObject report(Context c) throws Exception {
        Device d = device(c);
        JSONArray mhz = new JSONArray();
        for (int m : d.cpuMhz) mhz.put(m);
        JSONObject dev = new JSONObject().put("ramTotalMb", d.totalRam / 1048576).put("ramAvailMb", d.availRam / 1048576)
                .put("lowMemory", d.lowMemory).put("cpuPercent", d.cpuPct).put("cores", d.cpuMhz.size()).put("cpuMhz", mhz).put("cpuMaxMhz", d.cpuMaxMhz)
                .put("tempC", Float.isNaN(d.tempC) ? JSONObject.NULL : (double) d.tempC)
                .put("uptimeMin", d.uptimeMs / 60000)
                .put("firewall", Firewall.running()).put("firewallDropped", Firewall.blockedPackets.get());
        JSONArray arr = new JSONArray();
        for (AppUse a : usage(c)) {
            arr.put(new JSONObject().put("pkg", a.pkg).put("label", a.label).put("foregroundMin", a.fgMs / 60000)
                    .put("lastUsed", a.lastUsed).put("rxKb", a.rx / 1024).put("txKb", a.tx / 1024)
                    .put("stopped", a.stopped).put("system", a.system));
        }
        return new JSONObject().put("device", dev).put("usageAccess", hasUsageAccess(c)).put("today", arr);
    }

    // ---- UI ----------------------------------------------------------------------------------

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);
        page.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = Ui.roundIconButton(this, R.drawable.ic_back, Ui.RAISED, Ui.TEXT, 32, 72);
        back.setOnClickListener(v -> finish());
        header.addView(back, Ui.lp(Ui.dp(72), Ui.dp(72)));
        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.MEDIUM);
        title.setText("Activity");
        title.setPadding(Ui.dp(18), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout refresh = Ui.pill(this, R.drawable.ic_forward, "Refresh", Ui.RAISED, Ui.TEXT, 20);
        refresh.setOnClickListener(v -> loadApps());
        LinearLayout stopAll = Ui.pill(this, R.drawable.ic_close, "Stop background apps", Ui.AMBER, 0xFF000000, 19);
        stopAll.setOnClickListener(v -> stopBackground());
        LinearLayout.LayoutParams sal = Ui.lp(-2, Ui.dp(72));
        sal.rightMargin = Ui.dp(12);
        header.addView(stopAll, sal);
        header.addView(refresh, Ui.lp(-2, Ui.dp(72)));
        page.addView(header, Ui.lp(-1, Ui.dp(84)));

        // Device card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(Ui.CARD_SOLID, 22));
        card.setPadding(Ui.dp(22), Ui.dp(16), Ui.dp(22), Ui.dp(16));
        LinearLayout stats = new LinearLayout(this);
        ram = Ui.line(this, 20, Ui.TEXT, Ui.MEDIUM);
        cpu = Ui.line(this, 20, Ui.TEXT, Ui.MEDIUM);
        temp = Ui.line(this, 20, Ui.TEXT, Ui.MEDIUM);
        stats.addView(ram, new LinearLayout.LayoutParams(0, -2, 1.1f));
        stats.addView(cpu, new LinearLayout.LayoutParams(0, -2, 1.3f));
        stats.addView(temp, new LinearLayout.LayoutParams(0, -2, 0.6f));
        card.addView(stats);
        ramBar = new Bar(this);
        LinearLayout.LayoutParams rb = Ui.lp(-1, Ui.dp(10));
        rb.topMargin = Ui.dp(12);
        card.addView(ramBar, rb);
        LinearLayout.LayoutParams cl = Ui.lp(-1, -2);
        cl.topMargin = Ui.dp(8);
        page.addView(card, cl);

        grant = Ui.pill(this, R.drawable.ic_info, "Allow \"Usage access\" to see per-app activity", Ui.SPOTIFY, 0xFF000000, 19);
        grant.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
            catch (RuntimeException e) { Toast.makeText(this, "This unit hides the Usage access setting", Toast.LENGTH_LONG).show(); }
        });
        LinearLayout.LayoutParams gl = Ui.lp(-1, Ui.dp(76));
        gl.topMargin = Ui.dp(12);
        page.addView(grant, gl);

        note = Ui.line(this, 15, Ui.TEXT_3, Ui.REGULAR);
        LinearLayout.LayoutParams nl = Ui.lp(-1, -2);
        nl.topMargin = Ui.dp(12);
        page.addView(note, nl);

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(0));
        list.setDividerHeight(Ui.dp(8));
        list.setSelector(new ColorDrawable(0));
        list.setFastScrollEnabled(true);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(10), 0, Ui.dp(20));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            final AppUse a = apps.get(pos);
            final boolean kept = Keeper.has(this, a.pkg);
            BigMenu.show(this, a.label, a.pkg, new String[]{"Force stop", "App info",
                    kept ? "Stop keeping it stopped" : "Keep it stopped", "Cancel"}, idx -> {
                if (idx == 0) forceStop(java.util.Collections.singletonList(a), 0);
                else if (idx == 1) Home.appInfo(this, a.pkg);
                else if (idx == 2) {
                    String refused = null;
                    if (kept) Keeper.remove(this, a.pkg); else refused = Keeper.add(this, a.pkg);
                    Toast.makeText(this, kept ? a.label + " may run again"
                            : refused != null ? "Can't keep " + a.label + " stopped: " + refused
                            : a.label + " will be stopped whenever it starts by itself", Toast.LENGTH_LONG).show();
                }
            });
        });
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(tick);
        loadApps();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void showDevice() {
        Device d = device(this);
        long used = d.totalRam - d.availRam;
        ram.setText("RAM  " + mb(used) + " / " + mb(d.totalRam));
        String speed = d.cpuMhz.size() + " cores" + (d.cpuMaxMhz > 0
                ? String.format(Locale.US, " · max %.1f GHz", d.cpuMaxMhz / 1000f) : "");
        android.text.SpannableString s = new android.text.SpannableString(
                "CPU " + (d.cpuPct < 0 ? (cpuTries++ < 2 ? "…" : "n/a") : (approximate ? "~" : "") + d.cpuPct + "%")
                        + "   " + speed);
        int from = s.length() - speed.length();
        s.setSpan(new android.text.style.AbsoluteSizeSpan(15, true), from, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(Ui.TEXT_3), from, s.length(), 0);
        cpu.setText(s);
        cpu.setTextColor(d.cpuPct >= 85 ? Ui.AMBER : Ui.TEXT);
        temp.setText(Float.isNaN(d.tempC) ? "" : String.format(Locale.US, "%.0f°C", d.tempC));
        temp.setTextColor(!Float.isNaN(d.tempC) && d.tempC >= 75 ? Ui.RED : Ui.TEXT);
        ramBar.set(d.totalRam == 0 ? 0 : used / (float) d.totalRam, d.lowMemory ? Ui.RED : used > d.totalRam * 0.8 ? Ui.AMBER : Ui.SPOTIFY);
    }

    private void loadApps() {
        boolean access = hasUsageAccess(this);
        grant.setVisibility(access ? View.GONE : View.VISIBLE);
        if (!access) { note.setText("Per-app activity needs Usage access (one-time)."); return; }
        note.setText("Loading…");
        Apps.BG.execute(() -> {
            final List<AppUse> out = usage(this);
            handler.post(() -> {
                apps.clear();
                apps.addAll(out);
                adapter.notifyDataSetChanged();
                int awake = 0;
                for (AppUse a : out) if (!a.stopped) awake++;
                note.setText("Today: " + out.size() + " apps active · " + awake
                        + " not force-stopped · tap an app to force-stop it");
            });
            int px = Ui.dp(52), n = 0;
            for (AppUse a : out) {
                try { a.icon = render(getPackageManager().getApplicationIcon(a.pkg), px); } catch (Exception ignored) { }
                if (++n % 15 == 0) handler.post(adapter::notifyDataSetChanged);
            }
            handler.post(adapter::notifyDataSetChanged);
        });
    }

    /**
     * Force-stops apps you installed that aren't stopped, except this launcher and whatever is
     * playing music. System apps are never touched (stopping them can break the unit).
     */
    private void stopBackground() {
        if (!hasUsageAccess(this)) { Toast.makeText(this, "Allow Usage access first", Toast.LENGTH_LONG).show(); return; }
        android.media.session.MediaController mc = MediaListener.controller(this);
        String playing = mc != null && MediaListener.isPlaying(mc.getPlaybackState()) ? mc.getPackageName() : "";
        final List<AppUse> targets = new ArrayList<>();
        for (AppUse a : apps) {
            if (a.stopped || a.system || a.pkg.equals(getPackageName()) || a.pkg.equals(playing)) continue;
            targets.add(a);
        }
        if (targets.isEmpty()) { Toast.makeText(this, "Nothing to stop", Toast.LENGTH_SHORT).show(); return; }
        StringBuilder names = new StringBuilder();
        for (AppUse a : targets) names.append(names.length() == 0 ? "" : ", ").append(a.label);
        BigMenu.show(this, "Stop " + targets.size() + " apps?", names + (playing.isEmpty() ? "" : "\n\n(Skipping the app playing music.)"),
                new String[]{"Stop them", "Cancel"}, idx -> { if (idx == 0) forceStop(targets, 0); });
    }

    private void forceStop(final List<AppUse> targets, final int i) {
        if (i >= targets.size()) { loadApps(); return; }
        final AppUse a = targets.get(i);
        if (!AutoClicker.available()) {
            Toast.makeText(this, "Turn on \"HU Launcher remote & helper\" in Accessibility to stop apps automatically", Toast.LENGTH_LONG).show();
            Home.appInfo(this, a.pkg);
            return;
        }
        note.setText("Stopping " + a.label + " (" + (i + 1) + "/" + targets.size() + ")…");
        AutoClicker.run(this, a.pkg, AutoClicker.FORCE_STOP, (ok, msg) -> {
            if (!ok) Toast.makeText(this, a.label + ": " + msg, Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> forceStop(targets, i + 1), 400);
        });
    }

    private static Bitmap render(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, px, px);
        d.draw(new Canvas(b));
        return b;
    }

    static String mb(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.US, "%.1f GB", bytes / 1073741824f)
                : (bytes >> 20) + " MB";
    }

    private static String dur(long ms) {
        long m = ms / 60000;
        return m < 1 ? "<1 min" : m < 60 ? m + " min" : (m / 60) + " h " + (m % 60) + " min";
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public AppUse getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout row = new LinearLayout(Monitor.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Ui.button(Ui.CARD_SOLID, 20));
                row.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
                row.setMinimumHeight(Ui.dp(84));
                row.addView(new ImageView(Monitor.this), Ui.lp(Ui.dp(52), Ui.dp(52)));
                LinearLayout col = new LinearLayout(Monitor.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(18), 0, Ui.dp(12), 0);
                col.addView(Ui.line(Monitor.this, 20, Ui.TEXT, Ui.MEDIUM));
                col.addView(Ui.line(Monitor.this, 15, Ui.TEXT_3, Ui.REGULAR));
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                TextView data = Ui.text(Monitor.this, 18, Ui.TEXT, Ui.MEDIUM);
                data.setGravity(Gravity.END);
                row.addView(data);
                v = row;
            }
            AppUse a = getItem(p);
            LinearLayout row = (LinearLayout) v;
            ((ImageView) row.getChildAt(0)).setImageBitmap(a.icon);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(a.label);
            ((TextView) col.getChildAt(1)).setText("On screen " + dur(a.fgMs)
                    + (a.stopped ? "   ·   stopped" : "   ·   may run in background"));
            TextView data = (TextView) row.getChildAt(2);
            data.setText(a.bytes() == 0 ? "no data" : "↓ " + Files.size(a.rx) + "\n↑ " + Files.size(a.tx));
            data.setTextColor(a.bytes() > 50L * 1048576 ? Ui.AMBER : Ui.TEXT_2);
            return v;
        }
    }

    /** Thin usage bar. */
    static final class Bar extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private float f;
        Bar(Context c) { super(c); track.setColor(0x26FFFFFF); }
        void set(float fraction, int color) { f = fraction; fill.setColor(color); invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight();
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, h / 2, h / 2, track);
            r.right = Math.max(h, getWidth() * f);
            c.drawRoundRect(r, h / 2, h / 2, fill);
        }
    }
}
