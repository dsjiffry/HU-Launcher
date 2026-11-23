package com.hu.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A dialog whose choices are 80dp-tall buttons - easy to hit on a bumpy road. */
final class BigMenu {
    interface Pick { void onPick(int index); }

    static Dialog show(Context c, String title, String message, String[] items, Pick pick) {
        final Dialog d = new Dialog(c, android.R.style.Theme_Material_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(28), Ui.dp(26), Ui.dp(28), Ui.dp(22));
        box.setBackground(Ui.round(Ui.CARD_SOLID, 28));

        TextView t = Ui.text(c, 26, Ui.TEXT, Ui.MEDIUM);
        t.setText(title);
        box.addView(t);
        if (message != null) {
            TextView m = Ui.text(c, 19, Ui.TEXT_2, Ui.REGULAR);
            m.setText(message);
            m.setLineSpacing(Ui.dp(4), 1f);
            LinearLayout.LayoutParams ml = Ui.lp(-1, -2);
            ml.topMargin = Ui.dp(12);
            box.addView(m, ml);
        }
        // Long menus go two per row (last item, usually Cancel, full width) so they fit a 600px
        // screen without scrolling.
        boolean twoCol = items.length > 4;
        LinearLayout row = null;
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView b = Ui.text(c, twoCol ? 21 : 22, i == 0 ? 0xFF000000 : Ui.TEXT, Ui.MEDIUM);
            b.setText(items[i]);
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
            b.setBackground(Ui.button(i == 0 ? Ui.SPOTIFY : Ui.RAISED, 20));
            b.setOnClickListener(v -> { d.dismiss(); if (pick != null) pick.onPick(idx); });
            int h = Ui.dp(items.length > 3 ? 72 : 80);
            boolean paired = twoCol && i < items.length - 1;
            if (paired && (i % 2 == 0)) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams rl = Ui.lp(-1, h);
                rl.topMargin = Ui.dp(i == 0 ? 20 : 10);
                box.addView(row, rl);
            }
            if (paired) {
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -1, 1);
                if (i % 2 == 1) cl.leftMargin = Ui.dp(10);
                row.addView(b, cl);
            } else {
                LinearLayout.LayoutParams bl = Ui.lp(-1, h);
                bl.topMargin = Ui.dp(i == 0 ? 20 : 10);
                box.addView(b, bl);
            }
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.addView(box);
        d.setContentView(scroll);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            int screen = c.getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(Ui.dp(twoCol ? 720 : 560), screen - Ui.dp(32)), -2);
        }
        d.show();
        return d;
    }

    private BigMenu() { }
}
