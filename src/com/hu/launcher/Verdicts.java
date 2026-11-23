package com.hu.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Verdicts from the PC ("pc") and your own overrides ("you"), persisted as JSON in prefs.
 * Priority when classifying: you > pc > on-device rules.
 */
final class Verdicts {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    Verdicts(Context c) { prefs = c.getApplicationContext().getSharedPreferences("verdicts", Context.MODE_PRIVATE); }

    private JSONObject read(String key) {
        try { return new JSONObject(prefs.getString(key, "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    BloatRules.Verdict resolve(PkgScan.Entry e, String self) {
        synchronized (LOCK) {
            String mine = read("you").optString(e.pkg, null);
            if (mine != null) return new BloatRules.Verdict(BloatRules.parse(mine), "Your choice", "you");
            JSONObject pc = read("pc").optJSONObject(e.pkg);
            if (pc != null && !e.pkg.equals(self)) {
                return new BloatRules.Verdict(BloatRules.parse(pc.optString("v")), pc.optString("why", "Decided on PC"), "pc");
            }
        }
        return BloatRules.classify(e, self);
    }

    JSONObject pc() { synchronized (LOCK) { return read("pc"); } }

    JSONObject mine() { synchronized (LOCK) { return read("you"); } }

    void setMine(String pkg, String level) {
        synchronized (LOCK) {
            JSONObject o = read("you");
            try {
                if (level == null) o.remove(pkg); else o.put(pkg, level);
            } catch (JSONException ignored) { }
            prefs.edit().putString("you", o.toString()).apply();
        }
    }

    /** Merges (or replaces) PC verdicts. Accepts {"pkg":{"v":"bloat","why":"..."}} or {"pkg":"bloat"}. */
    int putPc(JSONObject incoming, boolean replace) throws JSONException {
        synchronized (LOCK) {
            JSONObject o = replace ? new JSONObject() : read("pc");
            int n = 0;
            for (Iterator<String> it = incoming.keys(); it.hasNext(); ) {
                String pkg = it.next();
                Object val = incoming.get(pkg);
                JSONObject v = new JSONObject();
                if (val instanceof JSONObject) {
                    v.put("v", BloatRules.name(BloatRules.parse(((JSONObject) val).optString("v"))));
                    v.put("why", ((JSONObject) val).optString("why", "Decided on PC"));
                } else {
                    v.put("v", BloatRules.name(BloatRules.parse(String.valueOf(val))));
                    v.put("why", "Decided on PC");
                }
                o.put(pkg, v);
                n++;
            }
            prefs.edit().putString("pc", o.toString()).apply();
            return n;
        }
    }
}
