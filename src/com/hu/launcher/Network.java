package com.hu.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Firewall screen: one big switch plus an Allowed/Blocked chip per app. */
public class Network extends Activity {
    /** Apps sharing one Linux UID are one firewall unit (Android routes VPN traffic per UID). */
    static final class Group {
        final List<Firewall.NetApp> apps = new ArrayList<>();
        boolean allowed;
        Bitmap icon;
        Firewall.NetApp head() { return apps.get(0); }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Group> groups = new ArrayList<>();
    private TextView status, toggleText;
    private LinearLayout toggle;
    private BaseAdapter adapter;
    private final Runnable tick = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 2000); }
    };

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
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(18), 0, Ui.dp(12), 0);
        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.MEDIUM);
        title.setText("Network firewall");
        status = Ui.line(this, 16, Ui.TEXT_3, Ui.REGULAR);
        titles.addView(title);
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2);
        sl.topMargin = Ui.dp(4);
        titles.addView(status, sl);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        toggle = Ui.pill(this, R.drawable.ic_check, "", Ui.RAISED, Ui.TEXT, 21);
        toggleText = (TextView) toggle.getChildAt(1);
        toggle.setOnClickListener(v -> flip());
        header.addView(toggle, Ui.lp(Ui.dp(230), Ui.dp(72)));
        page.addView(header, Ui.lp(-1, Ui.dp(84)));

        TextView hint = Ui.text(this, 16, Ui.TEXT_2, Ui.REGULAR);
        hint.setText("Only system parts are allowed at first. Tap the apps you use (Spotify, Maps…) to allow them. "
                + "Allowed apps run at full speed; everything else, including new apps, is cut off.");
        LinearLayout.LayoutParams hl = Ui.lp(-1, -2);
        hl.topMargin = Ui.dp(6);
        page.addView(hint, hl);

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(0));
        list.setDividerHeight(Ui.dp(8));
        list.setSelector(new ColorDrawable(0));
        list.setFastScrollEnabled(true);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(12), 0, Ui.dp(20));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Group g = groups.get(pos);
            if (g.allowed && essential(g)) {
                BigMenu.show(this, "Block " + g.head().label + "?",
                        "This is part of the system that other apps rely on (network checks, clock, sign-in, "
                                + "downloads or GPS). Blocking it can break things in ways that are hard to trace.",
                        new String[]{"Keep allowed", "Block anyway"}, idx -> { if (idx == 1) flipRow(g); });
                return;
            }
            flipRow(g);
        });
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        load();
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }

    private void flipRow(Group g) {
        g.allowed = !g.allowed;
        List<String> pkgs = new ArrayList<>();
        for (Firewall.NetApp a : g.apps) pkgs.add(a.pkg);
        Firewall.setAllowed(this, pkgs, g.allowed);
        adapter.notifyDataSetChanged();
        updateStatus();
    }

    private static boolean essential(Group g) {
        for (Firewall.NetApp a : g.apps) if (Firewall.essential(a.pkg)) return true;
        return false;
    }

    private void load() {
        status.setText("Loading apps…");
        Apps.BG.execute(() -> {
            Set<String> allow = Firewall.allowed(this);
            Map<Integer, Group> byUid = new LinkedHashMap<>();
            for (Firewall.NetApp a : Firewall.internetApps(this)) {
                Group g = byUid.get(a.uid);
                if (g == null) { g = new Group(); byUid.put(a.uid, g); }
                g.apps.add(a);
                if (allow.contains(a.pkg)) g.allowed = true;
            }
            final List<Group> out = new ArrayList<>(byUid.values());
            handler.post(() -> { groups.clear(); groups.addAll(out); adapter.notifyDataSetChanged(); updateStatus(); });
            int px = Ui.dp(52), n = 0;
            for (Group g : out) {
                try { g.icon = render(getPackageManager().getApplicationIcon(g.head().pkg), px); } catch (Exception ignored) { }
                if (++n % 15 == 0) handler.post(adapter::notifyDataSetChanged);
            }
            handler.post(adapter::notifyDataSetChanged);
        });
    }

    private static Bitmap render(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, px, px);
        d.draw(new Canvas(b));
        return b;
    }

    private void updateStatus() {
        boolean on = Firewall.enabled(this), run = Firewall.running();
        int blocked = 0;
        for (Group g : groups) if (!g.allowed) blocked++;
        status.setText(!on ? "Off — every app can use the internet"
                : run ? "On — " + blocked + " apps blocked · " + Firewall.blockedPackets.get() + " packets dropped"
                : "Starting…");
        toggleText.setText(on ? "Firewall ON" : "Turn on");
        toggle.setBackground(Ui.button(on ? Ui.SPOTIFY : Ui.RAISED, 40));
        toggleText.setTextColor(on ? 0xFF000000 : Ui.TEXT);
        ((ImageView) toggle.getChildAt(0)).setColorFilter(on ? 0xFF000000 : Ui.TEXT);
    }

    private void flip() {
        if (Firewall.enabled(this)) { Firewall.setEnabled(this, false); updateStatus(); return; }
        Intent consent = VpnService.prepare(this);
        if (consent == null) { Firewall.setEnabled(this, true); updateStatus(); remindOnce(); return; }
        try {
            startActivityForResult(consent, 1);
        } catch (RuntimeException e) {
            Toast.makeText(this, "This unit blocks VPN apps", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * First time only: point to Android's Always-on VPN setting so the firewall comes back by
     * itself (boot, crash, low memory). "Block connections without VPN" must stay OFF on
     * Android 9: lockdown blocks every app that bypasses the VPN, i.e. exactly the apps this
     * firewall allows (they skip the VPN so they run at full speed).
     */
    private void remindOnce() {
        android.content.SharedPreferences p = getSharedPreferences("firewall", MODE_PRIVATE);
        if (p.getBoolean("vpnTipShown", false)) return;
        p.edit().putBoolean("vpnTipShown", true).apply();
        BigMenu.show(this, "One more step (once)",
                "In VPN settings, tap the gear next to \"HU Launcher firewall\" and turn ON \"Always-on VPN\". "
                        + "Android then restarts the firewall at boot or if it gets killed, so blocked apps never slip through.\n\n"
                        + "Leave \"Block connections without VPN\" OFF: on this Android version it would also cut off "
                        + "the apps you allowed (Spotify, Maps...).",
                new String[]{"Open VPN settings", "Later"}, idx -> {
                    if (idx != 0) return;
                    try { startActivity(new Intent(android.provider.Settings.ACTION_VPN_SETTINGS)); }
                    catch (RuntimeException e) {
                        Toast.makeText(this, "Open Settings → Network → VPN", Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == 1 && result == RESULT_OK) { Firewall.setEnabled(this, true); remindOnce(); }
        updateStatus();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return groups.size(); }
        @Override public Group getItem(int p) { return groups.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout row = new LinearLayout(Network.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Ui.button(Ui.CARD_SOLID, 20));
                row.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
                row.setMinimumHeight(Ui.dp(88));
                row.addView(new ImageView(Network.this), Ui.lp(Ui.dp(52), Ui.dp(52)));
                LinearLayout col = new LinearLayout(Network.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(18), 0, Ui.dp(12), 0);
                col.addView(Ui.line(Network.this, 20, Ui.TEXT, Ui.MEDIUM));
                col.addView(Ui.line(Network.this, 14, Ui.TEXT_3, Ui.REGULAR));
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                TextView chip = Ui.text(Network.this, 17, 0xFF000000, Ui.MEDIUM);
                chip.setGravity(Gravity.CENTER);
                row.addView(chip, Ui.lp(Ui.dp(130), Ui.dp(52)));
                v = row;
            }
            Group g = getItem(p);
            LinearLayout row = (LinearLayout) v;
            ((ImageView) row.getChildAt(0)).setImageBitmap(g.icon);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            Firewall.NetApp h = g.head();
            ((TextView) col.getChildAt(0)).setText(g.apps.size() > 1 ? h.label + " + " + (g.apps.size() - 1) + " more" : h.label);
            ((TextView) col.getChildAt(1)).setText(essential(g) ? "Needed by the system  ·  " + h.pkg
                    : h.pkg + (h.system ? (h.updated ? "  ·  system, updated" : "  ·  preinstalled") : "  ·  you installed"));
            TextView chip = (TextView) row.getChildAt(2);
            chip.setText(g.allowed ? "ALLOWED" : "BLOCKED");
            chip.setBackground(Ui.round(g.allowed ? Ui.SPOTIFY : Ui.RED, 26));
            return v;
        }
    }
}
