package com.hu.launcher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Settings for the night screen: on/off, start and end time, software dim. Big targets only. */
public class NightSettings extends Activity {
    private static final int STEP = 30; // minutes per +/- press

    private SharedPreferences prefs;
    private TextView onText, startText, endText, dimText;
    private LinearLayout onBtn;
    private View body;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = NightScreen.prefs(this);
        setContentView(build());
        refresh();
    }

    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);
        page.setPadding(Ui.dp(24), Ui.dp(8), Ui.dp(24), Ui.dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.line(this, 26, Ui.TEXT, Ui.MEDIUM);
        title.setText("Night screen");
        TextView sub = Ui.line(this, 16, Ui.TEXT_2, Ui.REGULAR);
        sub.setText("Black home with just the clock and music, after " + (NightScreen.IDLE_MS / 1000) + " s without a touch");
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2);
        sl.topMargin = Ui.dp(6);
        titles.addView(title);
        titles.addView(sub, sl);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout done = Ui.pill(this, R.drawable.ic_check, "Done", Ui.RAISED, Ui.TEXT, 22);
        done.setMinimumWidth(Ui.dp(160));
        done.setOnClickListener(v -> finish());
        header.addView(done, Ui.lp(-2, Ui.dp(64)));
        page.addView(header, Ui.lp(-1, Ui.dp(72)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(6), 0, 0);

        // Row 1: on/off + try it
        LinearLayout r1 = new LinearLayout(this);
        onBtn = Ui.pill(this, R.drawable.ic_check, "Off", Ui.RAISED, Ui.TEXT, 22);
        onText = (TextView) onBtn.getChildAt(1);
        onBtn.setOnClickListener(v -> {
            prefs.edit().putBoolean("on", !NightScreen.enabled(this)).apply();
            refresh();
        });
        r1.addView(onBtn, new LinearLayout.LayoutParams(0, Ui.dp(76), 1));
        LinearLayout tryBtn = Ui.pill(this, R.drawable.ic_play, "Try it now", Ui.RAISED, Ui.TEXT, 22);
        tryBtn.setOnClickListener(v -> { Home.nightPreview = true; finish(); });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, Ui.dp(76), 1);
        tl.leftMargin = Ui.dp(12);
        r1.addView(tryBtn, tl);
        col.addView(r1, Ui.lp(-1, -2));

        LinearLayout rest = new LinearLayout(this);
        rest.setOrientation(LinearLayout.VERTICAL);
        body = rest;

        // Row 2: start and end times
        LinearLayout r2 = new LinearLayout(this);
        startText = Ui.line(this, 26, Ui.TEXT, Ui.MEDIUM);
        endText = Ui.line(this, 26, Ui.TEXT, Ui.MEDIUM);
        r2.addView(timeCard("Starts", "start", startText), new LinearLayout.LayoutParams(0, Ui.dp(96), 1));
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, Ui.dp(96), 1);
        el.leftMargin = Ui.dp(12);
        r2.addView(timeCard("Ends", "end", endText), el);
        LinearLayout.LayoutParams r2l = Ui.lp(-1, -2);
        r2l.topMargin = Ui.dp(12);
        rest.addView(r2, r2l);

        // Row 3: dim slider
        LinearLayout r3 = new LinearLayout(this);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        r3.setPadding(Ui.dp(20), 0, Ui.dp(12), 0);
        r3.setBackground(Ui.round(Ui.CARD_SOLID, 22));
        LinearLayout lab = new LinearLayout(this);
        lab.setOrientation(LinearLayout.VERTICAL);
        TextView dl = Ui.line(this, 17, Ui.TEXT_2, Ui.REGULAR);
        dl.setText("Dim");
        dimText = Ui.line(this, 26, Ui.TEXT, Ui.MEDIUM);
        lab.addView(dl);
        lab.addView(dimText);
        r3.addView(lab, Ui.lp(Ui.dp(96), -2));
        SeekBar seek = new SeekBar(this);
        seek.setMax(90);
        seek.setProgress(NightScreen.dim(this));
        seek.setThumb(thumb());
        seek.setProgressDrawable(track());
        seek.setSplitTrack(false);
        seek.setPadding(Ui.dp(28), 0, Ui.dp(28), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) prefs.edit().putInt("dim", p).apply();
                dimText.setText(p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        r3.addView(seek, new LinearLayout.LayoutParams(0, Ui.dp(88), 1));
        LinearLayout.LayoutParams r3l = Ui.lp(-1, Ui.dp(96));
        r3l.topMargin = Ui.dp(12);
        rest.addView(r3, r3l);
        col.addView(rest, Ui.lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private View timeCard(String label, String key, TextView value) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
        c.setBackground(Ui.round(Ui.CARD_SOLID, 22));
        ImageView minus = Ui.roundIconButton(this, R.drawable.ic_back, Ui.RAISED, Ui.TEXT, 30, 76);
        minus.setContentDescription(label + " earlier");
        minus.setOnClickListener(v -> step(key, -STEP));
        c.addView(minus, Ui.lp(Ui.dp(76), Ui.dp(76)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        TextView l = Ui.line(this, 17, Ui.TEXT_2, Ui.REGULAR);
        l.setText(label);
        mid.addView(l);
        mid.addView(value);
        c.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView plus = Ui.roundIconButton(this, R.drawable.ic_forward, Ui.RAISED, Ui.TEXT, 30, 76);
        plus.setContentDescription(label + " later");
        plus.setOnClickListener(v -> step(key, STEP));
        c.addView(plus, Ui.lp(Ui.dp(76), Ui.dp(76)));
        return c;
    }

    private void step(String key, int by) {
        int cur = key.equals("start") ? NightScreen.start(this) : NightScreen.end(this);
        int next = ((cur / STEP) * STEP + by + 24 * 60) % (24 * 60);
        prefs.edit().putInt(key, next).apply();
        refresh();
    }

    private void refresh() {
        boolean on = NightScreen.enabled(this);
        onText.setText(on ? "On" : "Off");
        onBtn.setBackground(Ui.button(on ? Ui.SPOTIFY : Ui.RAISED, 40));
        onText.setTextColor(on ? 0xFF000000 : Ui.TEXT);
        ((ImageView) onBtn.getChildAt(0)).setImageResource(on ? R.drawable.ic_check : R.drawable.ic_close);
        ((ImageView) onBtn.getChildAt(0)).setColorFilter(on ? 0xFF000000 : Ui.TEXT);
        body.setAlpha(on ? 1f : 0.5f);
        startText.setText(NightScreen.fmt(this, NightScreen.start(this)));
        endText.setText(NightScreen.fmt(this, NightScreen.end(this)));
        dimText.setText(NightScreen.dim(this) + "%");
    }

    private Drawable thumb() {
        GradientDrawable g = Ui.oval(Ui.TEXT);
        g.setSize(Ui.dp(44), Ui.dp(44));
        return g;
    }

    private Drawable track() {
        GradientDrawable bg = Ui.round(Ui.RAISED, 6);
        GradientDrawable fill = Ui.round(Ui.SPOTIFY, 6);
        LayerDrawable l = new LayerDrawable(new Drawable[]{bg, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        l.setId(0, android.R.id.background);
        l.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) { // a 12dp bar centred in whatever height the SeekBar gives it
            l.setLayerHeight(i, Ui.dp(12));
            l.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        return l;
    }
}
