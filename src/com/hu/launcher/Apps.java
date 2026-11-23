package com.hu.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launchable apps with pre-rendered icon bitmaps. Icons are rasterised once off the main thread
 * (adaptive/vector icons are expensive to draw on a Mali-T720) and reused across reloads.
 */
final class Apps {
    static final class App {
        final ComponentName cn;
        final String label;
        final Bitmap icon;
        App(ComponentName cn, String label, Bitmap icon) { this.cn = cn; this.label = label; this.icon = icon; }
        String key() { return cn.flattenToShortString(); }
    }

    interface Listener { void onAppsChanged(); }

    static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final int MAX_PINS = 5;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final int iconPx;
    private final Listener listener;
    private volatile List<App> list = Collections.emptyList();
    private final SharedPreferences prefs;

    Apps(Context ctx, int iconPx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.iconPx = iconPx;
        this.listener = listener;
        this.prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    List<App> all() { return list; }

    App find(String key) {
        for (App a : list) if (a.key().equals(key)) return a;
        return null;
    }

    void reload() {
        BG.execute(() -> {
            PackageManager pm = ctx.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> found = pm.queryIntentActivities(i, 0);
            List<App> out = new ArrayList<>(found.size());
            Map<String, Bitmap> keep = new HashMap<>();
            for (ResolveInfo r : found) {
                if (ctx.getPackageName().equals(r.activityInfo.packageName)) continue;
                ComponentName cn = new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
                String key = cn.flattenToShortString() + "@" + lastUpdate(pm, cn.getPackageName());
                Bitmap b;
                synchronized (iconCache) { b = iconCache.get(key); }
                if (b == null) b = render(sharpIcon(pm, r));
                keep.put(key, b);
                CharSequence l = r.loadLabel(pm);
                out.add(new App(cn, l == null ? cn.getPackageName() : l.toString().trim(), b));
            }
            final Collator col = Collator.getInstance();
            Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
            synchronized (iconCache) { iconCache.clear(); iconCache.putAll(keep); }
            List<App> result = Collections.unmodifiableList(out);
            main.post(() -> { list = result; listener.onAppsChanged(); });
        });
    }

    private static long lastUpdate(PackageManager pm, String pkg) {
        try { return pm.getPackageInfo(pkg, 0).lastUpdateTime; } catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    /**
     * loadIcon() returns the icon for the screen's own (low) density - a 48px bitmap on this unit -
     * which blurs when drawn ~110dp tall. Ask the app for its highest-density version instead and
     * scale it down. Done once per app on the background thread.
     */
    private static Drawable sharpIcon(PackageManager pm, ResolveInfo r) {
        int res = r.getIconResource();
        if (res != 0) {
            try {
                android.content.res.Resources rs = pm.getResourcesForApplication(r.activityInfo.applicationInfo);
                Drawable d = rs.getDrawableForDensity(res, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
                if (d != null) return d;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        }
        return r.loadIcon(pm);
    }

    private Bitmap render(Drawable d) {
        Bitmap b = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
        if (d != null) {
            Canvas c = new Canvas(b);
            c.setDrawFilter(new android.graphics.PaintFlagsDrawFilter(0, android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG));
            d.setBounds(0, 0, iconPx, iconPx);
            d.draw(c);
        }
        return b;
    }

    // ---- Dock pins -------------------------------------------------------------------------

    List<String> pins() {
        String s = prefs.getString("pins", null);
        List<String> out = new ArrayList<>();
        if (s == null) {
            // First run: sensible car defaults that are actually installed.
            for (String pkg : new String[]{MediaListener.SPOTIFY, "com.google.android.apps.maps", "com.waze",
                    "com.google.android.youtube", "com.android.settings"}) {
                for (App a : list) {
                    if (a.cn.getPackageName().equals(pkg)) { out.add(a.key()); break; }
                }
                if (out.size() >= MAX_PINS) break;
            }
            if (!list.isEmpty()) savePins(out);
            return out;
        }
        for (String k : s.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    boolean isPinned(App a) { return pins().contains(a.key()); }

    /** Returns false when the dock is full. */
    boolean togglePin(App a) {
        List<String> p = pins();
        if (p.remove(a.key())) { savePins(p); return true; }
        if (p.size() >= MAX_PINS) return false;
        p.add(a.key());
        savePins(p);
        return true;
    }

    private void savePins(List<String> p) {
        StringBuilder sb = new StringBuilder();
        for (String k : p) sb.append(k).append('\n');
        prefs.edit().putString("pins", sb.toString()).apply();
    }
}
