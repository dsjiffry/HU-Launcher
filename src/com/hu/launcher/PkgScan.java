package com.hu.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every installed package (including disabled ones), with the facts needed to judge bloat. */
final class PkgScan {
    /** MATCH_DISABLED_COMPONENTS / GET_DISABLED_COMPONENTS - same value on every version. */
    private static final int DISABLED = 0x00000200;

    static final class Entry {
        String pkg, label, installer, path, version;
        boolean system, updated, enabled, launchable, isHome;
        long sizeKb;
        Bitmap icon;
        BloatRules.Verdict verdict;
    }

    static List<Entry> scan(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> launchable = packagesFor(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        Set<String> homes = packagesFor(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        List<ApplicationInfo> infos = pm.getInstalledApplications(DISABLED);
        List<Entry> out = new ArrayList<>(infos.size());
        for (ApplicationInfo ai : infos) {
            Entry e = new Entry();
            e.pkg = ai.packageName;
            CharSequence l = ai.loadLabel(pm);
            e.label = l == null ? ai.packageName : l.toString().trim();
            e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            e.updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            e.enabled = isEnabled(pm, ai);
            e.launchable = launchable.contains(ai.packageName);
            e.isHome = homes.contains(ai.packageName);
            e.path = ai.sourceDir;
            try { e.installer = pm.getInstallerPackageName(ai.packageName); } catch (RuntimeException ignored) { }
            try { e.version = pm.getPackageInfo(ai.packageName, DISABLED).versionName; } catch (Exception ignored) { }
            if (ai.sourceDir != null) e.sizeKb = new File(ai.sourceDir).length() / 1024;
            out.add(e);
        }
        return out;
    }

    private static Set<String> packagesFor(PackageManager pm, Intent i) {
        Set<String> s = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(i, DISABLED)) s.add(r.activityInfo.packageName);
        return s;
    }

    static boolean isEnabled(PackageManager pm, ApplicationInfo ai) {
        if (!ai.enabled) return false;
        try {
            int st = pm.getApplicationEnabledSetting(ai.packageName);
            return st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** "active", "disabled" or "removed" - read fresh, used after returning from App info. */
    static String state(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, DISABLED);
            return isEnabled(pm, ai) ? "active" : "disabled";
        } catch (PackageManager.NameNotFoundException e) {
            return "removed";
        }
    }

    /** Full report for the PC: device facts + every package with components and permissions. */
    static JSONObject report(Context c, Verdicts verdicts) throws JSONException {
        PackageManager pm = c.getPackageManager();
        JSONObject root = new JSONObject();
        JSONObject dev = new JSONObject();
        dev.put("model", Build.MODEL).put("board", Build.BOARD).put("hardware", Build.HARDWARE)
                .put("manufacturer", Build.MANUFACTURER).put("release", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                .put("display", Build.DISPLAY).put("self", c.getPackageName());
        root.put("device", dev);
        JSONArray arr = new JSONArray();
        for (Entry e : scan(c)) {
            JSONObject o = new JSONObject();
            o.put("pkg", e.pkg).put("label", e.label).put("system", e.system).put("updatedSystem", e.updated)
                    .put("enabled", e.enabled).put("launchable", e.launchable).put("isHome", e.isHome)
                    .put("installer", e.installer == null ? JSONObject.NULL : e.installer)
                    .put("path", e.path).put("version", e.version == null ? JSONObject.NULL : e.version)
                    .put("sizeKb", e.sizeKb);
            details(pm, e.pkg, o);
            BloatRules.Verdict r = BloatRules.classify(e, c.getPackageName());
            o.put("rules", new JSONObject().put("v", BloatRules.name(r.level)).put("why", r.why));
            BloatRules.Verdict v = verdicts.resolve(e, c.getPackageName());
            o.put("current", new JSONObject().put("v", BloatRules.name(v.level)).put("why", v.why).put("source", v.source));
            arr.put(o);
        }
        root.put("apps", arr);
        return root;
    }

    /** Permissions and component names help tell a CAN-bus service from junk. Best effort. */
    private static void details(PackageManager pm, String pkg, JSONObject o) throws JSONException {
        PackageInfo pi;
        try {
            pi = pm.getPackageInfo(pkg, DISABLED | PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES);
        } catch (Exception | LinkageError e) {
            try { pi = pm.getPackageInfo(pkg, DISABLED | PackageManager.GET_PERMISSIONS); }
            catch (Exception e2) { return; }
        }
        JSONArray perms = new JSONArray();
        if (pi.requestedPermissions != null) {
            for (String p : pi.requestedPermissions) perms.put(p.startsWith("android.permission.") ? p.substring(19) : p);
        }
        o.put("perms", perms);
        o.put("services", names(pi.services));
        o.put("receivers", names(pi.receivers));
        o.put("activities", names(pi.activities));
        o.put("sharedUserId", pi.sharedUserId == null ? JSONObject.NULL : pi.sharedUserId);
    }

    private static JSONArray names(Object[] items) {
        JSONArray a = new JSONArray();
        if (items == null) return a;
        int n = 0;
        for (Object it : items) {
            if (n++ >= 25) { a.put("..."); break; }
            String name = it instanceof ActivityInfo ? ((ActivityInfo) it).name
                    : it instanceof android.content.pm.ServiceInfo ? ((android.content.pm.ServiceInfo) it).name : String.valueOf(it);
            a.put(name);
        }
        return a;
    }

    private PkgScan() { }
}
