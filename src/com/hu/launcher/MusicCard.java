package com.hu.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The big now-playing card: art, title, progress and three large transport buttons. */
final class MusicCard {
    interface Host { void onArtChanged(Bitmap blurred, int accent); }

    final LinearLayout root;
    private final Context ctx;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView art, playBtn, artFallback;
    private final TextView source, title, artist, elapsed, total;
    private final Bar bar;
    private String artKey = "";
    private int accent = Ui.SPOTIFY;
    private boolean ticking;
    private PlaybackState state;
    private long duration;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (ticking) handler.postDelayed(this, 1000);
        }
    };

    MusicCard(Context c, Host host) {
        this.ctx = c;
        this.host = host;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.round(Ui.CARD, 28));
        root.setPadding(Ui.dp(22), Ui.dp(20), Ui.dp(22), Ui.dp(16));

        // --- art + text ---
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackground(Ui.button(0x00000000, 20));
        top.setOnClickListener(v -> onInfoTap());

        FrameLayout artBox = new SquareBox(c, Ui.dp(150));
        artBox.setBackground(Ui.round(Ui.RAISED, 20));
        Ui.clipRound(artBox, 20);
        artFallback = Ui.icon(c, R.drawable.ic_music, Ui.TEXT_3);
        artFallback.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        artFallback.setPadding(Ui.dp(36), Ui.dp(36), Ui.dp(36), Ui.dp(36));
        artBox.addView(artFallback, new FrameLayout.LayoutParams(-1, -1));
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artBox.addView(art, new FrameLayout.LayoutParams(-1, -1));
        top.addView(artBox, Ui.lp(-2, -1));

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(22), 0, 0, 0);
        source = Ui.line(c, 15, Ui.SPOTIFY, Ui.MEDIUM);
        source.setLetterSpacing(0.12f);
        title = Ui.text(c, 28, Ui.TEXT, Ui.MEDIUM);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(Ui.dp(2), 1f);
        artist = Ui.line(c, 21, Ui.TEXT_2, Ui.REGULAR);
        info.addView(source);
        LinearLayout.LayoutParams tl = Ui.lp(-1, -2);
        tl.topMargin = Ui.dp(10);
        info.addView(title, tl);
        LinearLayout.LayoutParams al = Ui.lp(-1, -2);
        al.topMargin = Ui.dp(8);
        info.addView(artist, al);
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        // Text/art take whatever height is left; the buttons below never shrink.
        root.addView(top, new LinearLayout.LayoutParams(-1, 0, 1));

        // --- progress ---
        bar = new Bar(c);
        LinearLayout.LayoutParams bl = Ui.lp(-1, Ui.dp(6));
        bl.topMargin = Ui.dp(20);
        root.addView(bar, bl);
        LinearLayout times = new LinearLayout(c);
        elapsed = Ui.line(c, 14, Ui.TEXT_3, Ui.REGULAR);
        total = Ui.line(c, 14, Ui.TEXT_3, Ui.REGULAR);
        total.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(total, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams tml = Ui.lp(-1, -2);
        tml.topMargin = Ui.dp(6);
        root.addView(times, tml);

        // --- controls: big, well spaced ---
        LinearLayout controls = new LinearLayout(c);
        controls.setGravity(Gravity.CENTER);
        ImageView prev = Ui.roundIconButton(c, R.drawable.ic_prev, Ui.RAISED, Ui.TEXT, 44, 96);
        playBtn = Ui.roundIconButton(c, R.drawable.ic_play, accent, 0xFF000000, 56, 112);
        ImageView next = Ui.roundIconButton(c, R.drawable.ic_next, Ui.RAISED, Ui.TEXT, 44, 96);
        prev.setOnClickListener(v -> { Player.prev(ctx); soon(); });
        playBtn.setOnClickListener(v -> { Player.playPause(ctx); soon(); });
        next.setOnClickListener(v -> { Player.next(ctx); soon(); });
        prev.setContentDescription("Previous");
        playBtn.setContentDescription("Play or pause");
        next.setContentDescription("Next");
        controls.addView(prev, Ui.lp(Ui.dp(96), Ui.dp(96)));
        LinearLayout.LayoutParams pl = Ui.lp(Ui.dp(112), Ui.dp(112));
        pl.leftMargin = pl.rightMargin = Ui.dp(40);
        controls.addView(playBtn, pl);
        controls.addView(next, Ui.lp(Ui.dp(96), Ui.dp(96)));
        LinearLayout.LayoutParams cl = Ui.lp(-1, Ui.dp(112));
        cl.topMargin = Ui.dp(10);
        root.addView(controls, cl);
    }

    /** Square as tall as its row allows (capped), so the art adapts to the screen height. */
    static final class SquareBox extends FrameLayout {
        private final int cap;
        SquareBox(Context c, int cap) { super(c); this.cap = cap; }
        @Override protected void onMeasure(int w, int h) {
            int s = MeasureSpec.getSize(h);
            if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED || s > cap) s = cap;
            int spec = MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY);
            super.onMeasure(spec, spec);
        }
    }

    private void onInfoTap() {
        if (!MediaListener.hasAccess(ctx)) {
            try {
                ctx.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(ctx, "Turn on \"HU Launcher\" to show track info", Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) {
                Toast.makeText(ctx, "This head unit hides that setting - buttons still work", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Player.open(ctx);
    }

    /** Players sometimes skip the callback after a key press; re-read shortly after. */
    private void soon() {
        handler.postDelayed(this::refresh, 400);
    }

    void start() { ticking = true; refresh(); }

    void stop() { ticking = false; handler.removeCallbacks(tick); }

    void refresh() {
        MediaController mc = MediaListener.controller(ctx);
        boolean access = MediaListener.hasAccess(ctx);
        MediaMetadata md = mc == null ? null : mc.getMetadata();
        state = mc == null ? null : mc.getPlaybackState();
        boolean playing = MediaListener.isPlaying(state);
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        String t = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = md == null ? null : first(md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), md.getString(MediaMetadata.METADATA_KEY_ALBUM));
        duration = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);

        if (!access) {
            source.setText("MUSIC");
            title.setText("Play / pause / skip work now");
            artist.setText("Tap here to show song & art");
        } else if (mc == null || t == null) {
            source.setText("SPOTIFY");
            title.setText("Nothing playing");
            artist.setText("Tap play to resume");
        } else {
            source.setText(appName(mc.getPackageName()).toUpperCase());
            title.setText(t);
            artist.setText(a == null ? "" : a);
        }

        Bitmap bmp = md == null ? null : md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null && md != null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        String key = (t == null ? "" : t) + "|" + (a == null ? "" : a) + "|" + (bmp == null ? 0 : bmp.getWidth());
        if (!key.equals(artKey)) {
            artKey = key;
            art.setImageBitmap(bmp);
            artFallback.setVisibility(bmp == null ? View.VISIBLE : View.INVISIBLE);
            Bitmap blurred = Art.backdrop(bmp);
            accent = Art.accent(blurred, Ui.SPOTIFY);
            playBtn.setBackground(Ui.circleButton(accent));
            source.setTextColor(accent);
            bar.setColor(accent);
            host.onArtChanged(blurred, accent);
        }

        boolean hasTime = duration > 0 && state != null;
        bar.setVisibility(hasTime ? View.VISIBLE : View.INVISIBLE);
        elapsed.setVisibility(hasTime ? View.VISIBLE : View.INVISIBLE);
        total.setVisibility(hasTime ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(tick);
        updateProgress();
        if (ticking && playing && hasTime) handler.postDelayed(tick, 1000);
    }

    private void updateProgress() {
        if (state == null || duration <= 0) return;
        long pos = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(duration, pos));
        bar.setFraction(pos / (float) duration);
        elapsed.setText(fmt(pos));
        total.setText(fmt(duration));
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "Music";
        }
    }

    private static String first(String... s) {
        for (String x : s) if (x != null && !x.isEmpty()) return x;
        return null;
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Rounded progress bar; two rect draws, no ProgressBar machinery. */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;

        Bar(Context c) {
            super(c);
            track.setColor(0x33FFFFFF);
            fill.setColor(Ui.SPOTIFY);
        }

        void setColor(int c) { fill.setColor(c); invalidate(); }

        void setFraction(float f) {
            if (Math.abs(f - fraction) < 0.0005f) return;
            fraction = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), rad = h / 2f;
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, rad, rad, track);
            r.right = Math.max(h, getWidth() * fraction);
            c.drawRoundRect(r, rad, rad, fill);
        }
    }
}
