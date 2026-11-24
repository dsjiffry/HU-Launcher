package com.hu.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Night screen: a mostly black home with just a clock and a small music widget, for head units
 * that stay too bright even at minimum backlight. Optional; shown on home after 5 s without a
 * touch, between two times you pick (default 6 PM - 4 AM). Dimming is done in software, by fading
 * the few visible things - the black stays black. One TextClock tick a minute; nothing else runs.
 */
final class NightScreen {
    static final long IDLE_MS = 5000;
    private static final String PREFS = "night";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("on", false); }
    /** Minutes after midnight. */
    static int start(Context c) { return prefs(c).getInt("start", 18 * 60); }
    static int end(Context c) { return prefs(c).getInt("end", 4 * 60); }
    /** Software dim, 0 (none) to 90 percent. */
    static int dim(Context c) { return prefs(c).getInt("dim", 50); }

    static int nowMinutes() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE);
    }

    static boolean inWindow(Context c) {
        int s = start(c), e = end(c), n = nowMinutes();
        if (s == e) return true; // same time twice = all day
        return s < e ? (n >= s && n < e) : (n >= s || n < e);
    }

    /** Milliseconds until the clock next reads {@code minute} (at least 1 s). */
    static long msUntil(int minute) {
        Calendar k = Calendar.getInstance();
        long now = k.getTimeInMillis();
        k.set(Calendar.HOUR_OF_DAY, minute / 60);
        k.set(Calendar.MINUTE, minute % 60);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        if (k.getTimeInMillis() <= now) k.add(Calendar.DAY_OF_YEAR, 1);
        return Math.max(1000, k.getTimeInMillis() - now);
    }

    static String fmt(Context c, int minute) {
        int h = minute / 60, m = minute % 60;
        String mm = (m < 10 ? "0" : "") + m;
        if (android.text.format.DateFormat.is24HourFormat(c)) return (h < 10 ? "0" : "") + h + ":" + mm;
        return (h % 12 == 0 ? 12 : h % 12) + ":" + mm + (h < 12 ? " AM" : " PM");
    }

    // ---- view ----------------------------------------------------------------------------------

    final FrameLayout root;
    private final Context ctx;
    private final LinearLayout content, musicRow;
    private final ImageView art, playBtn;
    private final TextView title, artist;
    private String artKey = "";

    NightScreen(Context c, Runnable onWake) {
        ctx = c;
        root = new FrameLayout(c);
        root.setBackgroundColor(0xFF000000);
        root.setClickable(true);
        root.setOnClickListener(v -> onWake.run()); // tap anywhere on the black: back to normal home
        root.setVisibility(View.GONE);

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setLayerType(View.LAYER_TYPE_HARDWARE, null); // fading a layer is cheap

        boolean is24 = android.text.format.DateFormat.is24HourFormat(c);
        TextClock time = new Home.FitClock(c, 140, 0);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(0xFFD8D8D8);
        time.setTypeface(Ui.THIN);
        time.setIncludeFontPadding(false);
        time.setSingleLine(true);
        time.setGravity(Gravity.CENTER);
        content.addView(time, Ui.lp(-2, -2));

        TextClock date = new TextClock(c);
        date.setFormat12Hour(is24 ? "EEEE, d MMMM" : "EEEE, d MMMM  ·  a");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        date.setTextColor(0xFF8C8C8C);
        date.setTypeface(Ui.REGULAR);
        date.setIncludeFontPadding(false);
        date.setSingleLine(true);
        LinearLayout.LayoutParams dl = Ui.lp(-2, -2);
        dl.topMargin = Ui.dp(4);
        content.addView(date, dl);

        // Music: small art, title/artist, and three big but quiet buttons (no filled circles).
        musicRow = new LinearLayout(c);
        musicRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout artBox = new FrameLayout(c);
        Ui.clipRound(artBox, 14);
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBox.addView(art, new FrameLayout.LayoutParams(-1, -1));
        musicRow.addView(artBox, Ui.lp(Ui.dp(88), Ui.dp(88)));

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(18), 0, Ui.dp(12), 0);
        title = Ui.line(c, 22, 0xFFD0D0D0, Ui.MEDIUM);
        artist = Ui.line(c, 18, 0xFF8C8C8C, Ui.REGULAR);
        info.addView(title);
        LinearLayout.LayoutParams al = Ui.lp(-1, -2);
        al.topMargin = Ui.dp(6);
        info.addView(artist, al);
        musicRow.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView prev = Ui.roundIconButton(c, R.drawable.ic_prev, 0x00000000, 0xFFA0A0A0, 38, 84);
        playBtn = Ui.roundIconButton(c, R.drawable.ic_play, 0x00000000, 0xFFD0D0D0, 46, 92);
        ImageView next = Ui.roundIconButton(c, R.drawable.ic_next, 0x00000000, 0xFFA0A0A0, 38, 84);
        prev.setContentDescription("Previous");
        playBtn.setContentDescription("Play or pause");
        next.setContentDescription("Next");
        prev.setOnClickListener(v -> { Player.prev(ctx); soon(); });
        playBtn.setOnClickListener(v -> { Player.playPause(ctx); soon(); });
        next.setOnClickListener(v -> { Player.next(ctx); soon(); });
        musicRow.addView(prev, Ui.lp(Ui.dp(84), Ui.dp(84)));
        LinearLayout.LayoutParams pl = Ui.lp(Ui.dp(92), Ui.dp(92));
        pl.leftMargin = pl.rightMargin = Ui.dp(12);
        musicRow.addView(playBtn, pl);
        musicRow.addView(next, Ui.lp(Ui.dp(84), Ui.dp(84)));
        LinearLayout.LayoutParams ml = Ui.lp(Math.min(Ui.dp(680), c.getResources().getDisplayMetrics().widthPixels - Ui.dp(48)), Ui.dp(96));
        ml.topMargin = Ui.dp(28);
        content.addView(musicRow, ml);

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(content, cl);
    }

    boolean showing() { return root.getVisibility() == View.VISIBLE; }

    void applyDim() { content.setAlpha(1f - Math.max(0, Math.min(90, dim(ctx))) / 100f); }

    private void soon() { root.postDelayed(this::refresh, 400); }

    void refresh() {
        MediaController mc = MediaListener.controller(ctx);
        MediaMetadata md = mc == null ? null : mc.getMetadata();
        String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.isEmpty()) {
            musicRow.setVisibility(View.INVISIBLE); // nothing playing: just the clock
            return;
        }
        musicRow.setVisibility(View.VISIBLE);
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        title.setText(t);
        artist.setText(a == null ? "" : a);
        playBtn.setImageResource(MediaListener.isPlaying(mc.getPlaybackState()) ? R.drawable.ic_pause : R.drawable.ic_play);
        Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        String key = t + "|" + a + "|" + (bmp == null ? 0 : bmp.getWidth());
        if (!key.equals(artKey)) {
            artKey = key;
            art.setImageBitmap(bmp);
            ((View) art.getParent()).setVisibility(bmp == null ? View.GONE : View.VISIBLE);
        }
    }
}
