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
    private NightScreen night;
    private View page, scrim;
    private boolean resumed;
    private long lastTouchAt;
    private static final long DRAWER_IDLE_MS = 20_000;
    /** Set by the Night screen settings "Try it now" button: show it as soon as home is back. */
    static boolean nightPreview;

    private final Runnable reloadApps = () -> apps.reload();
    private final Runnable nightTimer = this::nightTick;
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
        SpotifyKeeper.ensure(this);
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
        Updater.maybeCheck(this); // once a day at most, background thread
        if (!onboardShown) {
            handler.removeCallbacks(onboardStep);
            handler.postDelayed(onboardStep, 600);
        }
        resumed = true;
        if (nightPreview) {
            nightPreview = false;
            handler.postDelayed(this::showNight, 300);
        } else {
            scheduleNight();
        }
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        Keeper.touched();
        lastTouchAt = android.os.SystemClock.uptimeMillis();
        int a = ev.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_UP) scheduleNight();
        if (night.showing()) return super.dispatchTouchEvent(ev); // its buttons work, a tap elsewhere wakes
        if (swipeUp(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(nightTimer);
        hideNight();
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

    @Override public void onMediaChanged() {
        if (night.showing()) night.refresh();
        else music.refresh();
    }

    // ---- Night screen ------------------------------------------------------------------------

    /** Re-arms the one timer: 5 s of no touches inside the night window, else wake at its start. */
    private void scheduleNight() {
        handler.removeCallbacks(nightTimer);
        if (!resumed || !NightScreen.enabled(this)) return;
        long delay;
        if (night.showing()) delay = NightScreen.msUntil(NightScreen.end(this));
        else if (NightScreen.inWindow(this)) delay = NightScreen.IDLE_MS;
        else delay = NightScreen.msUntil(NightScreen.start(this));
        handler.postDelayed(nightTimer, Math.min(delay, 30 * 60_000L)); // re-check at least every 30 min (clock changes)
    }

    private void nightTick() {
        if (!resumed || !NightScreen.enabled(this)) { hideNight(); return; }
        boolean in = NightScreen.inWindow(this);
        if (night.showing()) {
            if (!in) hideNight();
        } else if (in) {
            if (dialog != null && dialog.isShowing()) { handler.postDelayed(nightTimer, NightScreen.IDLE_MS); return; }
            // Browsing the drawer: give it longer before going dark.
            long idle = android.os.SystemClock.uptimeMillis() - lastTouchAt;
            if (drawer.getVisibility() == View.VISIBLE && idle < DRAWER_IDLE_MS) {
                handler.postDelayed(nightTimer, DRAWER_IDLE_MS - idle);
                return;
            }
            showNight();
            return;
        }
        scheduleNight();
    }

    private void showNight() {
        if (night.showing() || !resumed) return;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        hideDrawer(false);
        music.stop(); // no per-second progress redraws under the black
        night.applyDim();
        night.refresh();
        night.root.setAlpha(0f);
        night.root.setVisibility(View.VISIBLE);
        // Hide the status bar and dim the nav bar icons (hiding the nav bar would eat the wake tap).
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LOW_PROFILE);
        night.root.animate().alpha(1f).setDuration(700).withEndAction(() -> {
            if (!night.showing()) return;
            page.setVisibility(View.INVISIBLE);
            backdrop.setVisibility(View.INVISIBLE);
            scrim.setVisibility(View.INVISIBLE);
        }).start();
        Keeper.setQuiet(true); // no App-info force stops flashing up in the dark
        scheduleNight();
    }

    private void hideNight() {
        if (!night.showing()) return;
        night.root.animate().cancel();
        night.root.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(0);
        page.setVisibility(View.VISIBLE);
        backdrop.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        Keeper.setQuiet(false);
        if (resumed) music.start();
        scheduleNight();
    }

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
        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0xD9000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // Sizes follow the screen: head units range from 800×480 to 1920×720 and portrait 10".
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float wDp = dm.widthPixels / dm.density, hDp = dm.heightPixels / dm.density;
        boolean portrait = hDp > wDp * 1.1f;
        boolean shortScreen = hDp < 480;
        int padV = shortScreen ? 12 : 20, dockDp = shortScreen ? 84 : 100, gap = shortScreen ? 12 : 16;

        LinearLayout page = new LinearLayout(this);
        this.page = page;
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(24), Ui.dp(padV), Ui.dp(24), Ui.dp(padV));

        // Space the music card gets (status bar ~24dp), used to scale its buttons and art.
        float topH = hDp - 24 - 2 * padV - dockDp - gap;
        float cardW, cardH;
        LinearLayout top = new LinearLayout(this);
        if (portrait) {
            top.setOrientation(LinearLayout.VERTICAL);
            cardW = wDp - 48;
            cardH = topH * 0.62f;
        } else {
            top.setOrientation(LinearLayout.HORIZONTAL);
            cardW = (wDp - 48 - 24) * 0.56f;
            cardH = topH;
        }
        // 1.0 = full size; never below 0.72 so buttons stay large enough to hit while driving.
        float k = Math.max(0.72f, Math.min(1f, Math.min((cardW - 44) / 384f, (cardH - 36) / 330f)));
        music = new MusicCard(this, this, k);
        if (portrait) {
            top.addView(buildClock(), new LinearLayout.LayoutParams(-1, 0, 0.38f));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, 0, 0.62f);
            ml.topMargin = Ui.dp(16);
            top.addView(music.root, ml);
        } else {
            top.addView(buildClock(), new LinearLayout.LayoutParams(0, -1, 0.44f));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -1, 0.56f);
            ml.leftMargin = Ui.dp(24);
            top.addView(music.root, ml);
        }
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
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Ui.dp(dockDp));
        dl.topMargin = Ui.dp(gap);
        page.addView(dockRow, dl);

        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildDrawer(), new FrameLayout.LayoutParams(-1, -1));
        night = new NightScreen(this, this::hideNight);
        root.addView(night.root, new FrameLayout.LayoutParams(-1, -1));
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
        TextClock time = new FitClock(this, 200, android.text.format.DateFormat.is24HourFormat(this) ? 0 : 80);
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

        TextClock date = new FitClock(this, 28, 12, null, 0); // shrinks rather than cut "Wednesday, 24 September"
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setSingleLine(true);
        date.setTextColor(Ui.TEXT_2);
        date.setTypeface(Ui.REGULAR);
        LinearLayout.LayoutParams dlp = Ui.lp(-2, -2);
        dlp.topMargin = Ui.dp(10);
        dlp.leftMargin = Ui.dp(6);
        col.addView(date, dlp);
        return col;
    }

    /**
     * TextClock that shrinks its font to fit: the width (for "00:00" plus AM/PM room, or for its
     * own text when probe is null, e.g. the date) and a share of the height it is given.
     */
    static final class FitClock extends TextClock {
        private final float max, reserve, heightShare;
        private final String probeText;
        private final Paint probe = new Paint(Paint.ANTI_ALIAS_FLAG);

        FitClock(Context c, float maxDp, float reserveDp) { this(c, maxDp, reserveDp, "00:00", 0.62f); }

        FitClock(Context c, float maxDp, float reserveDp, String probeText, float heightShare) {
            super(c);
            max = Ui.dp(maxDp);
            reserve = Ui.dp(reserveDp);
            this.probeText = probeText;
            this.heightShare = heightShare;
            setTextSize(TypedValue.COMPLEX_UNIT_PX, max);
        }

        @Override protected void onMeasure(int w, int h) {
            int avail = MeasureSpec.getSize(w);
            float size = max;
            if (MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED && avail > 0) {
                probe.setTypeface(getTypeface());
                probe.setLetterSpacing(getLetterSpacing());
                probe.setTextSize(max);
                String text = probeText != null ? probeText : String.valueOf(getText());
                float need = probe.measureText(text) + reserve + getPaddingLeft() + getPaddingRight();
                if (need > avail) size = max * avail / need;
            }
            int hAvail = MeasureSpec.getSize(h);
            if (heightShare > 0 && MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED && hAvail > 0) {
                size = Math.min(size, hAvail * heightShare / 1.18f); // ~1.18 = line height / font size
            }
            if (Math.abs(size - getTextSize()) > 0.5f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
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
        dialog = BigMenu.show(this, "Tools", null, new String[]{"Check for updates", "Activity", "Firewall",
                "Debloat", PcLink.running() ? "PC link (on)" : "PC link", "Night screen", "Spotify ready", "Setup", "Cancel"}, idx -> {
            if (idx == 0) checkUpdates();
            else if (idx == 1) startActivity(new Intent(this, Monitor.class));
            else if (idx == 2) startActivity(new Intent(this, Network.class));
            else if (idx == 3) startActivity(new Intent(this, Debloat.class));
            else if (idx == 4) {
                if (!PcLink.running()) PcLink.start(this);
                handler.postDelayed(this::pcLinkDialog, 400);
            }
            else if (idx == 5) startActivity(new Intent(this, NightSettings.class));
            else if (idx == 6) spotifyMenu();
            else if (idx == 7) startActivity(new Intent(this, Setup.class));
        });
    }

    private void spotifyMenu() {
        final boolean on = SpotifyKeeper.enabled(this);
        String msg = "Keeps Spotify loaded in the background so it starts playing instantly and isn't closed when "
                + "memory runs low. If it gets closed anyway, Android restarts it. No extra CPU use; Spotify keeps "
                + "its usual memory (about 150-250 MB) all the time."
                + (SpotifyKeeper.mediaService(this) == null ? "\n\nSpotify isn't installed (or is disabled)."
                        : on ? "\n\nNow: " + SpotifyKeeper.state() : "");
        dialog = BigMenu.show(this, "Keep Spotify ready", msg,
                new String[]{on ? "Turn off" : "Turn on", "Close"}, idx -> {
                    if (idx != 0) return;
                    SpotifyKeeper.setEnabled(this, !on);
                    Toast.makeText(this, on ? "Spotify is no longer kept ready" : "Keeping Spotify ready", Toast.LENGTH_SHORT).show();
                });
    }

    /** Manual check: asks GitHub right away, then offers Update now / automatic checks. */
    private void checkUpdates() {
        dialog = BigMenu.show(this, "Checking for updates…", "Asking GitHub for the latest release.",
                new String[]{"Close"}, null);
        Updater.checkNow(this, (ver, err) -> {
            if (dialog != null) dialog.dismiss();
            if (isFinishing() || !resumed) return;
            String version;
            try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (PackageManager.NameNotFoundException e) { version = "?"; }
            final boolean auto = Updater.autoEnabled(this);
            String autoLabel = auto ? "Daily checks: On" : "Daily checks: Off";
            String msg = (err != null ? "Couldn't check: " + err + "."
                    : ver != null ? "Version " + ver + " is available. You have " + version + "."
                    : "You have the latest version (" + version + ").")
                    + "\n\nUpdates come from this app's GitHub releases. The check sends no data about you or this unit.";
            String[] items = ver != null ? new String[]{"Update now", autoLabel, "Later"}
                    : new String[]{"Close", autoLabel};
            dialog = BigMenu.show(this, ver != null ? "Update available" : "Updates", msg, items, idx -> {
                String pick = items[idx];
                if (pick.equals("Update now")) {
                    Toast.makeText(this, "Downloading " + ver + "…", Toast.LENGTH_SHORT).show();
                    Updater.install(getApplicationContext());
                } else if (pick.equals(autoLabel)) {
                    Updater.setAuto(this, !auto);
                    Toast.makeText(this, auto ? "Daily update checks off" : "Will check once a day", Toast.LENGTH_SHORT).show();
                }
            });
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
