package com.hu.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One screen listing every permission the launcher can use. Shown once on first start and
 * always reachable from Tools. Each card opens the right system page; the tick refreshes when
 * you come back. Everything is optional: features without their permission just stay off.
 */
public class Setup extends Activity {
    static final String PREFS = "home", DONE = "setupDone";

    private interface Check { boolean ok(); }

    private static final class Item {
        final int icon; final String title, why; final Check check; final Runnable open;
        ImageView mark; TextView state;
        Item(int icon, String title, String why, Check check, Runnable open) {
            this.icon = icon; this.title = title; this.why = why; this.check = check; this.open = open;
        }
    }

    private Item[] items;
    private LinearLayout done;
    private TextView doneText;
    private SharedPreferences prefs;

    static boolean needed(Context c) { return !c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(DONE, false); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(DONE, true).apply(); // shown once, even if you leave halfway

        items = new Item[]{
            new Item(R.drawable.ic_home, "Home screen", "Home button opens HU Launcher",
                    () -> Home.isDefaultHome(this), () -> Home.chooseHome(this)),
            new Item(R.drawable.ic_music, "Music widget", "Notification access: song, cover, controls",
                    () -> MediaListener.hasAccess(this),
                    () -> open("Turn on \"" + getString(R.string.app_name) + "\", then Allow",
                            new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                                            MediaListener.component(this).flattenToString()),
                            highlight(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                                    MediaListener.component(this).flattenToString()))),
            new Item(R.drawable.ic_pc, "PC remote", "Accessibility: touch control, auto-disable",
                    () -> accessibilityOn(this),
                    // No direct page: Android reserves the per-service one for system apps.
                    () -> open("Tap \"" + getString(R.string.remote_label) + "\", turn it on, then OK",
                            null,
                            highlight(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                    remoteComponent().flattenToString()))),
            new Item(R.drawable.ic_info, "Activity", "Usage access: per-app time and data",
                    () -> Monitor.hasUsageAccess(this),
                    () -> open("Tap \"" + getString(R.string.app_name) + "\", then turn it on",
                            new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName())),
                            highlight(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), getPackageName()))),
            new Item(R.drawable.ic_folder, "Files", "Storage: browse, rename, unzip",
                    this::hasStorage, this::askStorage),
            new Item(R.drawable.ic_download, "Install APKs", "Install unknown apps from Files",
                    () -> getPackageManager().canRequestPackageInstalls(),
                    () -> open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())))),
        };
        setContentView(build());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) { refresh(); }

    private LinearLayout build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);
        page.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(24), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.line(this, 26, Ui.TEXT, Ui.MEDIUM);
        title.setText("Set up HU Launcher");
        TextView sub = Ui.line(this, 16, Ui.TEXT_2, Ui.REGULAR);
        sub.setText("Tap a card to allow it. All optional — find this later in Tools.");
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2);
        sl.topMargin = Ui.dp(6);
        titles.addView(title);
        titles.addView(sub, sl);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        done = Ui.pill(this, R.drawable.ic_check, "Done", Ui.RAISED, Ui.TEXT, 22);
        doneText = (TextView) done.getChildAt(1);
        done.setMinimumWidth(Ui.dp(180));
        done.setOnClickListener(v -> finish());
        header.addView(done, Ui.lp(-2, Ui.dp(64)));
        page.addView(header, Ui.lp(-1, Ui.dp(72)));

        // Sized so all six cards fit the unit's ~400dp-tall screen without scrolling.
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, Ui.dp(6), 0, Ui.dp(8));
        for (int i = 0; i < items.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            for (int j = i; j < i + 2; j++) {
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, Ui.dp(92), 1);
                if (j == i) cl.rightMargin = Ui.dp(10);
                if (j < items.length) row.addView(card(items[j]), cl);
                else row.addView(Ui.space(this), cl);
            }
            LinearLayout.LayoutParams rl = Ui.lp(-1, -2);
            rl.bottomMargin = Ui.dp(8);
            grid.addView(row, rl);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(grid);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private LinearLayout card(Item it) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(Ui.dp(18), 0, Ui.dp(18), 0);
        c.setBackground(Ui.button(Ui.CARD_SOLID, 22));
        c.addView(Ui.roundIconButton(this, it.icon, Ui.RAISED, Ui.TEXT, 28, 60), Ui.lp(Ui.dp(60), Ui.dp(60)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(16), 0, Ui.dp(10), 0);
        TextView t = Ui.line(this, 22, Ui.TEXT, Ui.MEDIUM);
        t.setText(it.title);
        TextView w = Ui.text(this, 15, Ui.TEXT_2, Ui.REGULAR);
        w.setText(it.why);
        w.setMaxLines(2);
        LinearLayout.LayoutParams wl = Ui.lp(-1, -2);
        wl.topMargin = Ui.dp(6);
        texts.addView(t);
        texts.addView(w, wl);
        c.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER);
        it.mark = Ui.roundIconButton(this, R.drawable.ic_check, Ui.SPOTIFY, 0xFF000000, 26, 48);
        it.state = Ui.line(this, 15, Ui.TEXT_2, Ui.MEDIUM);
        it.state.setGravity(Gravity.CENTER);
        right.addView(it.mark, Ui.lp(Ui.dp(48), Ui.dp(48)));
        LinearLayout.LayoutParams stl = Ui.lp(-2, -2);
        stl.topMargin = Ui.dp(6);
        right.addView(it.state, stl);
        c.addView(right, Ui.lp(Ui.dp(76), -2));

        c.setOnClickListener(v -> it.open.run());
        return c;
    }

    private void refresh() {
        int missing = 0;
        for (Item it : items) {
            boolean ok;
            try { ok = it.check.ok(); } catch (RuntimeException e) { ok = false; }
            if (!ok) missing++;
            it.mark.setImageResource(ok ? R.drawable.ic_check : R.drawable.ic_forward);
            it.mark.setBackground(Ui.circleButton(ok ? Ui.SPOTIFY : Ui.AMBER));
            it.state.setText(ok ? "On" : "Allow");
            it.state.setTextColor(ok ? Ui.SPOTIFY : Ui.AMBER);
        }
        done.setBackground(Ui.button(missing == 0 ? Ui.SPOTIFY : Ui.RAISED, 40));
        doneText.setTextColor(missing == 0 ? 0xFF000000 : Ui.TEXT);
        ((ImageView) done.getChildAt(0)).setColorFilter(missing == 0 ? 0xFF000000 : Ui.TEXT);
        doneText.setText(missing == 0 ? "All set" : "Done");
    }

    private void open(Intent i) { open(null, i); }

    /**
     * Opens the first page this unit actually has: the app's own page when the system offers
     * one, else the list (with our row highlighted where Settings supports it) plus a hint.
     */
    private void open(String listHint, Intent... candidates) {
        PackageManager pm = getPackageManager();
        for (int n = 0; n < candidates.length; n++) {
            Intent i = candidates[n];
            if (i == null || i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i);
                if (n > 0 && listHint != null) Toast.makeText(this, listHint, Toast.LENGTH_LONG).show();
                return;
            } catch (RuntimeException ignored) { }
        }
        Toast.makeText(this, "This unit hides that setting", Toast.LENGTH_LONG).show();
    }

    /** AOSP Settings scrolls to and flashes the row whose key matches (ignored where unsupported). */
    private static Intent highlight(Intent i, String key) {
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", key);
        return i.putExtra(":settings:fragment_args_key", key).putExtra(":settings:show_fragment_args", args);
    }

    private android.content.ComponentName remoteComponent() {
        return new android.content.ComponentName(this, RemoteControl.class);
    }

    private boolean hasStorage() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askStorage() {
        if (hasStorage()) return;
        // After "Don't ask again" the dialog no longer appears: send the user to App info instead.
        if (prefs.getBoolean("storageAsked", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            Toast.makeText(this, "Open Permissions → Storage", Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        prefs.edit().putBoolean("storageAsked", true).apply();
        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    /** The service may be switched on but not bound yet, so read the setting too. */
    static boolean accessibilityOn(Context c) {
        if (RemoteControl.ready()) return true;
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        String full = c.getPackageName() + "/" + RemoteControl.class.getName();
        String shortName = c.getPackageName() + "/.RemoteControl";
        for (String part : s.split(":")) if (part.equalsIgnoreCase(full) || part.equalsIgnoreCase(shortName)) return true;
        return false;
    }
}
