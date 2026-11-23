package com.hu.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.util.TypedValue;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Home screen: giant clock, music card, a dock of pinned apps, and the full-screen app drawer. */
public class Home extends Activity implements MediaListener.Listener, Apps.Listener, MusicCard.Host {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Apps apps;
    private MusicCard music;
    private ImageView backdrop;
    private LinearLayout dock;
    private View allAppsBtn;
    private FrameLayout drawer;
    private TextView drawerCount;
    private AppPager pager;
    private Dialog dialog;
    private SharedPreferences prefs;
    private boolean onboardShown;
    private int accent = Ui.SPOTIFY;

    private final Runnable reloadApps = () -> apps.reload();
    private final Runnable onboardStep = this::onboard;
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            handler.removeCallbacks(reloadApps);
            handler.postDelayed(reloadApps, 400);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        apps = new Apps(this, AppPager.iconPx(this), this);
        setContentView(build());
        apps.reload();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Apps.BG.execute(() -> Firewall.ensure(this)); // home starts at boot: bring the firewall up
        if (!PcLink.running() && PcLink.resumeAfterInstall(this)) PcLink.start(this); // updated over the link

        if (MediaListener.hasAccess(this)) {
            try { NotificationListenerService.requestRebind(MediaListener.component(this)); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        MediaListener.listener = this;
        music.start();
        Keeper.homeResumed(this);
        if (!onboardShown) {
            handler.removeCallbacks(onboardStep);
            handler.postDelayed(onboardStep, 600);
        }
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        Keeper.touched();
        if (swipeUp(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }

    @Override protected void onPause() {
        Keeper.homePaused();
        MediaListener.listener = null;
        music.stop();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        // Coming back home should land on the home page, not the drawer.
        hideDrawer(false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (dialog != null) dialog.dismiss();
        hideDrawer(true);
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) hideDrawer(true);
        // A launcher never finishes on back.
    }

    @Override public void onMediaChanged() { music.refresh(); }

    @Override public void onArtChanged(Bitmap blurred, int accent) {
        this.accent = accent;
        allAppsBtn.setBackground(Ui.button(accent, 28));
        if (blurred == null) {
            backdrop.animate().alpha(0f).setDuration(300).start();
        } else {
            backdrop.setImageBitmap(blurred);
            backdrop.animate().alpha(1f).setDuration(300).start();
        }
    }

    @Override public void onAppsChanged() {
        pager.setApps(apps.all());
        drawerCount.setText(apps.all().size() + " apps");
        buildDock();
    }

    // ---- Layout ----------------------------------------------------------------------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0xD9000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(24), Ui.dp(20), Ui.dp(24), Ui.dp(20));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(buildClock(), new LinearLayout.LayoutParams(0, -1, 0.44f));
        music = new MusicCard(this, this);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -1, 0.56f);
        ml.leftMargin = Ui.dp(24);
        top.addView(music.root, ml);
        page.addView(top, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setBackground(Ui.round(Ui.CARD, 28));
        dock.setPadding(Ui.dp(8), 0, Ui.dp(8), 0);
        dockRow.addView(dock, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout all = Ui.pill(this, R.drawable.ic_apps, "All apps", accent, 0xFF000000, 22);
        all.setOnClickListener(v -> showDrawer());
        allAppsBtn = all;
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(Ui.dp(220), -1);
        al.leftMargin = Ui.dp(16);
        dockRow.addView(all, al);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Ui.dp(100));
        dl.topMargin = Ui.dp(16);
        page.addView(dockRow, dl);

        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildDrawer(), new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildClock() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(Ui.dp(8), 0, 0, Ui.dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        TextClock time = new FitClock(this, 150, android.text.format.DateFormat.is24HourFormat(this) ? 0 : 80);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Ui.TEXT);
        time.setTypeface(Ui.LIGHT);
        time.setIncludeFontPadding(false);
        time.setLetterSpacing(-0.03f);
        time.setSingleLine(true);
        row.addView(time);
        TextClock ampm = new TextClock(this);
        ampm.setFormat12Hour("a");
        ampm.setFormat24Hour("");
        ampm.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
        ampm.setTextColor(Ui.TEXT_2);
        ampm.setTypeface(Ui.REGULAR);
        ampm.setIncludeFontPadding(false);
        LinearLayout.LayoutParams apl = Ui.lp(-2, -2);
        apl.leftMargin = Ui.dp(10);
        apl.bottomMargin = Ui.dp(22);
        row.addView(ampm, apl);
        col.addView(row);

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        date.setSingleLine(true);
        date.setTextColor(Ui.TEXT_2);
        date.setTypeface(Ui.REGULAR);
        LinearLayout.LayoutParams dlp = Ui.lp(-2, -2);
        dlp.topMargin = Ui.dp(10);
        dlp.leftMargin = Ui.dp(6);
        col.addView(date, dlp);
        return col;
    }

    /** Clock that shrinks its font so "00:00" (+ AM/PM room) always fits its column. */
    static final class FitClock extends TextClock {
        private final float max, reserve;
        private final Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);

        FitClock(Context c, float maxDp, float reserveDp) {
            super(c);
            max = Ui.dp(maxDp);
            reserve = Ui.dp(reserveDp);
            setTextSize(TypedValue.COMPLEX_UNIT_PX, max);
        }

        @Override protected void onMeasure(int w, int h) {
            int avail = MeasureSpec.getSize(w);
            if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && avail > 0) {
                probe.setTypeface(getTypeface());
                probe.setLetterSpacing(getLetterSpacing());
                probe.setTextSize(max);
                float need = probe.measureText("00:00") + reserve + getPaddingLeft() + getPaddingRight();
                float size = need > avail ? max * avail / need : max;
                if (Math.abs(size - getTextSize()) > 0.5f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
            super.onMeasure(w, h);
        }
    }

    private void buildDock() {
        dock.removeAllViews();
        int added = 0;
        for (String key : apps.pins()) {
            final Apps.App a = apps.find(key);
            if (a == null) continue;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(Ui.button(0x00000000, 22));
            ImageView i = new ImageView(this);
            i.setImageBitmap(a.icon);
            tile.addView(i, Ui.lp(Ui.dp(54), Ui.dp(54)));
            TextView t = Ui.line(this, 14, Ui.TEXT_2, Ui.REGULAR);
            t.setText(a.label);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tl = Ui.lp(-1, -2);
            tl.topMargin = Ui.dp(4);
            tile.addView(t, tl);
            tile.setOnClickListener(v -> launch(a));
            tile.setOnLongClickListener(v -> { appMenu(a); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(88), 1);
            lp.leftMargin = lp.rightMargin = Ui.dp(4);
            dock.addView(tile, lp);
            added++;
        }
        if (added == 0) {
            TextView hint = Ui.text(this, 18, Ui.TEXT_3, Ui.REGULAR);
            hint.setText("Long-press any app in All apps to pin it here");
            hint.setGravity(Gravity.CENTER);
            dock.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    // ---- Drawer ----------------------------------------------------------------------------

    private View buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xF50B0D12);
        drawer.setClickable(true);
        drawer.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(12), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 28, Ui.TEXT, Ui.MEDIUM);
        title.setText("All apps");
        header.addView(title);
        drawerCount = Ui.text(this, 18, Ui.TEXT_3, Ui.REGULAR);
        LinearLayout.LayoutParams cl = Ui.lp(-2, -2);
        cl.leftMargin = Ui.dp(16);
        header.addView(drawerCount, cl);
        header.addView(Ui.space(this), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout files = Ui.pill(this, R.drawable.ic_folder, "Files", Ui.RAISED, Ui.TEXT, 20);
        files.setOnClickListener(v -> startActivity(new Intent(this, Files.class)));
        header.addView(files, Ui.lp(-2, Ui.dp(62)));
        LinearLayout tools = Ui.pill(this, R.drawable.ic_sweep, "Tools", Ui.RAISED, Ui.TEXT, 20);
        tools.setOnClickListener(v -> toolsMenu());
        LinearLayout.LayoutParams dbl = Ui.lp(-2, Ui.dp(62));
        dbl.leftMargin = Ui.dp(12);
        header.addView(tools, dbl);
        ImageView close = Ui.roundIconButton(this, R.drawable.ic_close, Ui.RAISED, Ui.TEXT, 30, 62);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> hideDrawer(true));
        LinearLayout.LayoutParams xl = Ui.lp(Ui.dp(62), Ui.dp(62));
        xl.leftMargin = Ui.dp(16);
        xl.rightMargin = Ui.dp(12);
        header.addView(close, xl);
        col.addView(header, Ui.lp(-1, Ui.dp(70)));

        pager = new AppPager(this, new AppPager.Host() {
            @Override public void onAppClick(Apps.App a) { launch(a); }
            @Override public void onAppLongClick(Apps.App a) { appMenu(a); }
            @Override public void onSwipeDown() { hideDrawer(true); }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0, 1);
        pl.topMargin = Ui.dp(2);
        pl.rightMargin = Ui.dp(12);
        pl.bottomMargin = Ui.dp(2);
        col.addView(pager, pl);
        drawer.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return drawer;
    }

    private void showDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        pager.reset();
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(Ui.dp(40));
        drawer.animate().alpha(1f).translationY(0).setDuration(170).withLayer().start();
    }

    private void hideDrawer(boolean animate) {
        if (drawer.getVisibility() != View.VISIBLE) return;
        drawer.animate().cancel();
        if (!animate) { drawer.setVisibility(View.GONE); return; }
        drawer.animate().alpha(0f).translationY(Ui.dp(40)).setDuration(140).withLayer()
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    // ---- Swipe up on home opens the drawer ---------------------------------------------------
    private float swipeX, swipeY;
    private boolean swipeOpened;

    /** Watches every touch on home (even ones starting on a button) for a clear upward swipe. */
    private boolean swipeUp(android.view.MotionEvent ev) {
        if (drawer.getVisibility() == View.VISIBLE || (dialog != null && dialog.isShowing())) return false;
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                swipeX = ev.getX();
                swipeY = ev.getY();
                swipeOpened = false;
                return false;
            case android.view.MotionEvent.ACTION_MOVE:
                if (swipeOpened) return true;
                float dy = swipeY - ev.getY(), dx = Math.abs(ev.getX() - swipeX);
                if (dy > Ui.dp(70) && dy > dx * 1.5f) {
                    swipeOpened = true;
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(ev);
                    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL); // the button under the finger lets go
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    showDrawer();
                    return true;
                }
                return false;
            default:
                return swipeOpened;
        }
    }

    // ---- Actions ---------------------------------------------------------------------------

    private void launch(Apps.App a) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void appMenu(final Apps.App a) {
        final boolean pinned = apps.isPinned(a);
        dialog = BigMenu.show(this, a.label, null,
                new String[]{pinned ? "Remove from home" : "Add to home", "App info", "Uninstall", "Cancel"}, idx -> {
                    if (idx == 0) {
                        if (!apps.togglePin(a)) Toast.makeText(this, "Home dock is full (5)", Toast.LENGTH_SHORT).show();
                        buildDock();
                    } else if (idx == 1) {
                        appInfo(this, a.cn.getPackageName());
                    } else if (idx == 2) {
                        uninstall(this, a.cn.getPackageName());
                    }
                });
    }

    private void toolsMenu() {
        dialog = BigMenu.show(this, "Tools", null, new String[]{"Activity & resources", "Network firewall",
                "Debloat", PcLink.running() ? "PC link (on)" : "PC link & remote", "Permissions & setup", "Cancel"}, idx -> {
            if (idx == 0) startActivity(new Intent(this, Monitor.class));
            else if (idx == 1) startActivity(new Intent(this, Network.class));
            else if (idx == 2) startActivity(new Intent(this, Debloat.class));
            else if (idx == 3) {
                if (!PcLink.running()) PcLink.start(this);
                handler.postDelayed(this::pcLinkDialog, 400);
            }
            else if (idx == 4) startActivity(new Intent(this, Setup.class));
        });
    }

    private void pcLinkDialog() {
        if (!PcLink.running()) {
            Toast.makeText(this, "PC link could not start", Toast.LENGTH_LONG).show();
            return;
        }
        boolean touch = RemoteControl.ready();
        String msg = "Address  http://" + PcLink.address() + "\nPIN  " + PcLink.pin()
                + "\n\nOpen the address in a browser on your PC (same Wi-Fi) to see and control this screen."
                + (touch ? "" : "\n\nFor touch control, turn on \"HU Launcher remote & helper\" in Accessibility (one-time).");
        String[] items = touch ? new String[]{"Close", "Stop PC link"}
                : new String[]{"Turn on touch control", "Close", "Stop PC link"};
        dialog = BigMenu.show(this, "PC link is on", msg, items, idx -> {
            String pick = items[idx];
            if (pick.equals("Stop PC link")) PcLink.stop(this);
            else if (pick.equals("Turn on touch control")) {
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                catch (RuntimeException e) { Toast.makeText(this, "Accessibility settings are hidden on this unit", Toast.LENGTH_LONG).show(); }
            }
        });
    }

    static void appInfo(Context c, String pkg) {
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(c, "App info is blocked on this unit", Toast.LENGTH_LONG).show();
        }
    }

    static void uninstall(Context c, String pkg) {
        try {
            c.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            appInfo(c, pkg);
        }
    }

    static boolean isDefaultHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && c.getPackageName().equals(r.activityInfo.packageName);
    }

    /** First runs: the permissions screen once, then become the home app, then offer the bloat scan once. */
    private void onboard() {
        if (onboardShown || isFinishing() || (dialog != null && dialog.isShowing())) return;
        if (!hasWindowFocus()) return; // something is on top (e.g. Setup); try again on next resume
        if (Setup.needed(this)) { // onboardShown stays false: the next steps run when you come back
            prefs.edit().putBoolean(Setup.DONE, true).apply();
            startActivity(new Intent(this, Setup.class));
            return;
        }
        onboardShown = true;
        if (!isDefaultHome(this)) {
            int asked = prefs.getInt("homeAsked", 0);
            if (asked >= 3) return;
            prefs.edit().putInt("homeAsked", asked + 1).apply();
            dialog = BigMenu.show(this, "Make this your home screen?",
                    "Pick \"HU Launcher\" and choose \"Always\" so the Home button comes here.",
                    new String[]{"Choose home app", "Not now"}, idx -> { if (idx == 0) chooseHome(); });
        } else if (!prefs.getBoolean("debloatOffered", false)) {
            prefs.edit().putBoolean("debloatOffered", true).apply();
            dialog = BigMenu.show(this, "Scan for bloat?",
                    "Checks every installed app and walks you through disabling the junk, one by one. "
                            + "You can also let your PC decide. It's always under All apps → Debloat.",
                    new String[]{"Scan now", "Later"}, idx -> {
                        if (idx == 0) startActivity(new Intent(this, Debloat.class));
                    });
        }
    }

    private void chooseHome() { chooseHome(this); }

    static void chooseHome(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            return;
        } catch (RuntimeException ignored) { }
        // Fallback: the system "Complete action using" picker (has an "Always" button).
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(c, "Open Settings → Apps → Default apps → Home", Toast.LENGTH_LONG).show();
        }
    }
}
