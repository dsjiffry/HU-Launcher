package com.hu.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look: colours, fonts, big touch targets. Views are built in code (no inflation cost). */
final class Ui {
    static final int BG = 0xFF0B0D12;
    static final int CARD = 0xCC1A1F2A;
    static final int CARD_SOLID = 0xFF1A1F2A;
    static final int RAISED = 0xFF262C3A;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_2 = 0xB3FFFFFF;
    static final int TEXT_3 = 0x80FFFFFF;
    static final int SPOTIFY = 0xFF1DB954;
    static final int RED = 0xFFFF5A5F;
    static final int AMBER = 0xFFFFB443;

    static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private static float density = 1f;

    static void init(Context c) { density = c.getResources().getDisplayMetrics().density; }

    static int dp(float v) { return Math.round(v * density); }

    static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Pressable background: colour + ripple clipped to the same rounded shape. */
    static Drawable button(int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), round(color, radiusDp), round(0xFFFFFFFF, radiusDp));
    }

    static Drawable circleButton(int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), oval(color), oval(0xFFFFFFFF));
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp); // ignore system font scale: layout is already big
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView line(Context c, float sp, int color, Typeface face) {
        TextView t = text(c, sp, color, face);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static ImageView icon(Context c, int res, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return v;
    }

    /** Round button holding a centred icon. sizeDp is the touch target, iconDp the glyph. */
    static ImageView roundIconButton(Context c, int res, int bg, int tint, int iconDp, int sizeDp) {
        ImageView v = icon(c, res, tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Math.max(0, dp((sizeDp - iconDp) / 2f));
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(circleButton(bg));
        return v;
    }

    /** Pill button: icon + label, big enough to hit while driving. */
    static LinearLayout pill(Context c, int iconRes, String label, int bg, int fg, float textSp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(24), 0, dp(28), 0);
        l.setBackground(button(bg, 40));
        if (iconRes != 0) {
            ImageView i = icon(c, iconRes, fg);
            l.addView(i, new LinearLayout.LayoutParams(dp(30), dp(30)));
        }
        TextView t = text(c, textSp, fg, MEDIUM);
        t.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        if (iconRes != 0) lp.leftMargin = dp(12);
        l.addView(t, lp);
        return l;
    }

    static void clipRound(View v, final float radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, -1, w); }

    static View space(Context c) { return new View(c); }

    private Ui() { }
}
