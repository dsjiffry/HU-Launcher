package com.hu.launcher;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scans every installed package, sorts it into Bloat / Maybe / Keep, and walks you through the
 * bloat one app at a time via its App info page (the only no-root way to disable/uninstall).
 */
public class Debloat extends Activity implements PcLink.Listener {
    private static final String[] TAB_NAMES = {"Bloat", "Maybe", "Keep", "Disabled"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Verdicts verdicts;
    private List<PkgScan.Entry> entries = new ArrayList<>();
    private final List<PkgScan.Entry> shown = new ArrayList<>();
    private int tab = 0;

    private TextView summary, reviewBtnText, pcText, pcStatus;
    private LinearLayout pcPanel, reviewBtn, pcBtn;
    private final TextView[] tabs = new TextView[4];
    private ListView list;
    private RowAdapter adapter;


    // Review mode
    private FrameLayout reviewPage;
    private List<PkgScan.Entry> queue;
    private int index, removedCount;
    private boolean awaitingReturn, autoRunning;
    private LinearLayout autoBtn;
    private String pendingRowCheck;
    private ImageView rIcon;
    private TextView rProgress, rLabel, rPkg, rWhy, rSource, rStatus;
    private LinearLayout rCard, rDone;
    private TextView rDoneText;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        verdicts = new Verdicts(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(buildList(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildReview(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        scan();
    }

    @Override protected void onResume() {
        super.onResume();
        PcLink.listener = this;
        showPc();
        if (awaitingReturn && queue != null && index < queue.size()) {
            awaitingReturn = false;
            PkgScan.Entry e = queue.get(index);
            String st = PkgScan.state(this, e.pkg);
            if (!st.equals("active")) {
                e.enabled = false;
                removedCount++;
                Toast.makeText(this, e.label + (st.equals("removed") ? " uninstalled ✓" : " disabled ✓"), Toast.LENGTH_SHORT).show();
                if (st.equals("removed")) entries.remove(e);
                index++;
                showCurrent();
            } else {
                rStatus.setText("Still enabled. Try again, or tap Keep / Skip.");
                rStatus.setVisibility(View.VISIBLE);
            }
        } else if (pendingRowCheck != null) {
            String pkg = pendingRowCheck;
            pendingRowCheck = null;
            String st = PkgScan.state(this, pkg);
            for (int i = 0; i < entries.size(); i++) {
                PkgScan.Entry e = entries.get(i);
                if (!e.pkg.equals(pkg)) continue;
                if (st.equals("removed")) entries.remove(i); else e.enabled = st.equals("active");
                break;
            }
            refilter();
        }
    }

    @Override protected void onDestroy() {
        if (PcLink.listener == this) PcLink.listener = null;
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (reviewPage.getVisibility() == View.VISIBLE) closeReview(); else super.onBackPressed();
    }

    // ---- Scan --------------------------------------------------------------------------------

    private void scan() {
        summary.setText("Scanning installed apps…");
        Apps.BG.execute(() -> {
            List<PkgScan.Entry> found = PkgScan.scan(this);
            for (PkgScan.Entry e : found) e.verdict = verdicts.resolve(e, getPackageName());
            final Collator col = Collator.getInstance();
            Collections.sort(found, (a, c) -> col.compare(a.label, c.label));
            handler.post(() -> { entries = new ArrayList<>(found); refilter(); });
            // Icons second, so the list appears immediately.
            PackageManager pm = getPackageManager();
            int px = Ui.dp(56), n = 0;
            for (PkgScan.Entry e : found) {
                try { e.icon = render(pm.getApplicationIcon(e.pkg), px); } catch (Exception ignored) { }
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

    private void reresolve() {
        for (PkgScan.Entry e : entries) e.verdict = verdicts.resolve(e, getPackageName());
        refilter();
    }

    private boolean inTab(PkgScan.Entry e, int t) {
        if (t == 3) return !e.enabled;
        return e.enabled && e.verdict != null && e.verdict.level == t;
    }

    private void refilter() {
        int[] counts = new int[4];
        shown.clear();
        for (PkgScan.Entry e : entries) {
            for (int t = 0; t < 4; t++) if (inTab(e, t)) counts[t]++;
            if (inTab(e, tab)) shown.add(e);
        }
        for (int t = 0; t < 4; t++) {
            tabs[t].setText(TAB_NAMES[t] + "  " + counts[t]);
            boolean sel = t == tab;
            tabs[t].setBackground(Ui.button(sel ? Ui.TEXT : Ui.RAISED, 30));
            tabs[t].setTextColor(sel ? 0xFF000000 : Ui.TEXT);
        }
        summary.setText(entries.size() + " apps  ·  " + counts[0] + " bloat  ·  " + counts[3] + " disabled");
        boolean reviewable = (tab == 0 || tab == 1) && !shown.isEmpty();
        reviewBtn.setVisibility(reviewable ? View.VISIBLE : View.GONE);
        reviewBtnText.setText("Review " + shown.size() + " " + (tab == 0 ? "bloat" : "maybe") + " app"
                + (shown.size() == 1 ? "" : "s") + " one by one");
        adapter.notifyDataSetChanged();
    }

    // ---- List page ---------------------------------------------------------------------------

    private View buildList() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = Ui.roundIconButton(this, R.drawable.ic_back, Ui.RAISED, Ui.TEXT, 32, 72);
        back.setOnClickListener(v -> finish());
        header.addView(back, Ui.lp(Ui.dp(72), Ui.dp(72)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(18), 0, 0, 0);
        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.MEDIUM);
        title.setText("Debloat");
        summary = Ui.line(this, 16, Ui.TEXT_3, Ui.REGULAR);
        titles.addView(title);
        LinearLayout.LayoutParams sl = Ui.lp(-2, -2);
        sl.topMargin = Ui.dp(4);
        titles.addView(summary, sl);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        pcBtn = Ui.pill(this, R.drawable.ic_pc, "Let PC decide", Ui.RAISED, Ui.TEXT, 20);
        pcBtn.setOnClickListener(v -> togglePc());
        header.addView(pcBtn, Ui.lp(-2, Ui.dp(72)));
        page.addView(header, Ui.lp(-1, Ui.dp(84)));
        // Everything below the title bar scrolls with the list (short screens).
        LinearLayout topBlock = new LinearLayout(this);
        topBlock.setOrientation(LinearLayout.VERTICAL);

        // PC link panel
        pcPanel = new LinearLayout(this);
        pcPanel.setOrientation(LinearLayout.VERTICAL);
        pcPanel.setBackground(Ui.round(Ui.CARD_SOLID, 22));
        pcPanel.setPadding(Ui.dp(22), Ui.dp(16), Ui.dp(22), Ui.dp(16));
        pcPanel.setVisibility(View.GONE);
        pcText = Ui.text(this, 26, Ui.TEXT, Ui.MEDIUM);
        pcStatus = Ui.text(this, 16, Ui.TEXT_2, Ui.REGULAR);
        pcStatus.setLineSpacing(Ui.dp(3), 1f);
        pcPanel.addView(pcText);
        LinearLayout.LayoutParams psl = Ui.lp(-1, -2);
        psl.topMargin = Ui.dp(8);
        pcPanel.addView(pcStatus, psl);
        LinearLayout.LayoutParams ppl = Ui.lp(-1, -2);
        ppl.topMargin = Ui.dp(8);
        topBlock.addView(pcPanel, ppl);

        // Tabs
        LinearLayout tabRow = new LinearLayout(this);
        for (int t = 0; t < 4; t++) {
            final int idx = t;
            TextView tv = Ui.text(this, 19, Ui.TEXT, Ui.MEDIUM);
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(v -> { tab = idx; refilter(); list.setSelection(0); });
            tabs[t] = tv;
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, Ui.dp(64), 1);
            if (t > 0) tl.leftMargin = Ui.dp(10);
            tabRow.addView(tv, tl);
        }
        LinearLayout.LayoutParams trl = Ui.lp(-1, -2);
        trl.topMargin = Ui.dp(12);
        topBlock.addView(tabRow, trl);

        reviewBtn = Ui.pill(this, R.drawable.ic_sweep, "", Ui.SPOTIFY, 0xFF000000, 21);
        reviewBtnText = (TextView) reviewBtn.getChildAt(1);
        reviewBtn.setOnClickListener(v -> startReview());
        LinearLayout.LayoutParams rbl = Ui.lp(-1, Ui.dp(76));
        rbl.topMargin = Ui.dp(12);
        topBlock.addView(reviewBtn, rbl);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(0));
        list.setDividerHeight(Ui.dp(8));
        list.setSelector(new ColorDrawable(0));
        list.setFastScrollEnabled(true);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(12), 0, Ui.dp(20));
        adapter = new RowAdapter();
        list.addHeaderView(topBlock, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            PkgScan.Entry e = (PkgScan.Entry) p.getItemAtPosition(pos);
            if (e == null) return;
            pendingRowCheck = e.pkg;
            Home.appInfo(this, e.pkg);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> { Object e = p.getItemAtPosition(pos); if (e != null) rowMenu((PkgScan.Entry) e); return e != null; });
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void rowMenu(final PkgScan.Entry e) {
        BigMenu.show(this, e.label, e.pkg + "\n" + e.verdict.why,
                new String[]{"Open App info", "Mark as bloat", "Mark as keep", "Clear my mark", "Cancel"}, idx -> {
                    if (idx == 0) { pendingRowCheck = e.pkg; Home.appInfo(this, e.pkg); }
                    else if (idx == 1) { verdicts.setMine(e.pkg, "bloat"); reresolve(); }
                    else if (idx == 2) { verdicts.setMine(e.pkg, "keep"); reresolve(); }
                    else if (idx == 3) { verdicts.setMine(e.pkg, null); reresolve(); }
                });
    }

    private static String sourceText(BloatRules.Verdict v) {
        return "pc".equals(v.source) ? "PC" : "you".equals(v.source) ? "You" : "Rules";
    }

    private static int levelColor(int level) {
        return level == BloatRules.BLOAT ? Ui.RED : level == BloatRules.MAYBE ? Ui.AMBER : Ui.SPOTIFY;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public PkgScan.Entry getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout row = new LinearLayout(Debloat.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Ui.button(Ui.CARD_SOLID, 20));
                row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
                row.setMinimumHeight(Ui.dp(92));
                ImageView icon = new ImageView(Debloat.this);
                row.addView(icon, Ui.lp(Ui.dp(56), Ui.dp(56)));
                LinearLayout col = new LinearLayout(Debloat.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(18), 0, Ui.dp(12), 0);
                col.addView(Ui.line(Debloat.this, 20, Ui.TEXT, Ui.MEDIUM));
                col.addView(Ui.line(Debloat.this, 14, Ui.TEXT_3, Ui.REGULAR));
                col.addView(Ui.line(Debloat.this, 15, Ui.TEXT_2, Ui.REGULAR));
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                TextView chip = Ui.text(Debloat.this, 15, 0xFF000000, Ui.MEDIUM);
                chip.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
                row.addView(chip);
                v = row;
            }
            PkgScan.Entry e = getItem(p);
            LinearLayout row = (LinearLayout) v;
            ((ImageView) row.getChildAt(0)).setImageBitmap(e.icon);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(e.label);
            ((TextView) col.getChildAt(1)).setText(e.pkg + (e.system ? "  ·  system" : "  ·  user"));
            ((TextView) col.getChildAt(2)).setText(sourceText(e.verdict) + ": " + e.verdict.why);
            TextView chip = (TextView) row.getChildAt(2);
            if (!e.enabled) {
                chip.setText("OFF");
                chip.setBackground(Ui.round(Ui.TEXT_3, 16));
            } else {
                chip.setText(BloatRules.name(e.verdict.level).toUpperCase());
                chip.setBackground(Ui.round(levelColor(e.verdict.level), 16));
            }
            return v;
        }
    }

    // ---- PC link (shared service; keeps running when you leave this screen) -------------------

    private void togglePc() {
        if (PcLink.running()) PcLink.stop(this); else PcLink.start(this);
        handler.postDelayed(this::showPc, 400);
    }

    private void showPc() {
        boolean on = PcLink.running();
        pcPanel.setVisibility(on ? View.VISIBLE : View.GONE);
        ((TextView) pcBtn.getChildAt(1)).setText(on ? "Stop PC link" : "Let PC decide");
        if (!on) return;
        pcText.setText(PcLink.address() + "      PIN  " + PcLink.pin());
        pcStatus.setText("On the PC run:  .\\hu.ps1 pull -Pin " + PcLink.pin()
                + "   ·   or open http://" + PcLink.address() + " in a browser for remote control.");
    }

    @Override public void onPcEvent(final String message, final boolean verdictsChanged) {
        handler.post(() -> {
            if (verdictsChanged) {
                verdicts = new Verdicts(this);
                reresolve();
                pcStatus.setText(message + "  Lists updated. Review the Bloat tab.");
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                showPc();
            }
        });
    }

    // ---- Review mode -------------------------------------------------------------------------

    private View buildReview() {
        reviewPage = new FrameLayout(this);
        reviewPage.setBackgroundColor(Ui.BG);
        reviewPage.setClickable(true);
        reviewPage.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(28), Ui.dp(14), Ui.dp(28), Ui.dp(24));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        rProgress = Ui.text(this, 22, Ui.TEXT_2, Ui.MEDIUM);
        top.addView(rProgress, new LinearLayout.LayoutParams(0, -2, 1));
        autoBtn = Ui.pill(this, R.drawable.ic_forward, "Auto-disable all", Ui.AMBER, 0xFF000000, 20);
        autoBtn.setOnClickListener(v -> toggleAuto());
        LinearLayout.LayoutParams abl = Ui.lp(-2, Ui.dp(72));
        abl.rightMargin = Ui.dp(14);
        top.addView(autoBtn, abl);
        ImageView close = Ui.roundIconButton(this, R.drawable.ic_close, Ui.RAISED, Ui.TEXT, 32, 72);
        close.setOnClickListener(v -> closeReview());
        top.addView(close, Ui.lp(Ui.dp(72), Ui.dp(72)));
        col.addView(top);

        // Current app
        rCard = new LinearLayout(this);
        rCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout who = new LinearLayout(this);
        who.setGravity(Gravity.CENTER_VERTICAL);
        rIcon = new ImageView(this);
        who.addView(rIcon, Ui.lp(Ui.dp(104), Ui.dp(104)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(Ui.dp(24), 0, 0, 0);
        rLabel = Ui.line(this, 34, Ui.TEXT, Ui.MEDIUM);
        rPkg = Ui.line(this, 17, Ui.TEXT_3, Ui.REGULAR);
        names.addView(rLabel);
        LinearLayout.LayoutParams pl = Ui.lp(-1, -2);
        pl.topMargin = Ui.dp(6);
        names.addView(rPkg, pl);
        who.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        rCard.addView(who);
        rWhy = Ui.text(this, 22, Ui.TEXT, Ui.REGULAR);
        rSource = Ui.text(this, 16, Ui.TEXT_3, Ui.REGULAR);
        rStatus = Ui.text(this, 19, Ui.AMBER, Ui.MEDIUM);
        TextView hint = Ui.text(this, 17, Ui.TEXT_2, Ui.REGULAR);
        hint.setText("In App info tap DISABLE (or UNINSTALL), confirm, then press Back - this screen moves on by itself.");
        LinearLayout.LayoutParams wl = Ui.lp(-1, -2);
        wl.topMargin = Ui.dp(20);
        rCard.addView(rWhy, wl);
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2);
        sl.topMargin = Ui.dp(6);
        rCard.addView(rSource, sl);
        LinearLayout.LayoutParams stl = Ui.lp(-1, -2);
        stl.topMargin = Ui.dp(12);
        rCard.addView(rStatus, stl);
        rCard.addView(Ui.space(this), new LinearLayout.LayoutParams(-1, 0, 1));
        rCard.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        LinearLayout open = Ui.pill(this, R.drawable.ic_info, "Open App info", Ui.SPOTIFY, 0xFF000000, 22);
        open.setOnClickListener(v -> {
            awaitingReturn = true;
            Home.appInfo(this, queue.get(index).pkg);
        });
        buttons.addView(open, new LinearLayout.LayoutParams(0, Ui.dp(92), 2));
        LinearLayout keep = Ui.pill(this, R.drawable.ic_check, "Keep it", Ui.RAISED, Ui.TEXT, 22);
        keep.setOnClickListener(v -> {
            PkgScan.Entry e = queue.get(index);
            verdicts.setMine(e.pkg, "keep");
            e.verdict = verdicts.resolve(e, getPackageName());
            index++;
            showCurrent();
        });
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, Ui.dp(92), 1);
        kl.leftMargin = Ui.dp(14);
        buttons.addView(keep, kl);
        LinearLayout skip = Ui.pill(this, R.drawable.ic_forward, "Skip", Ui.RAISED, Ui.TEXT, 22);
        skip.setOnClickListener(v -> { index++; showCurrent(); });
        LinearLayout.LayoutParams skl = new LinearLayout.LayoutParams(0, Ui.dp(92), 1);
        skl.leftMargin = Ui.dp(14);
        buttons.addView(skip, skl);
        LinearLayout.LayoutParams bl = Ui.lp(-1, -2);
        bl.topMargin = Ui.dp(16);
        rCard.addView(buttons, bl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1);
        cl.topMargin = Ui.dp(12);
        col.addView(rCard, cl);

        // Finished
        rDone = new LinearLayout(this);
        rDone.setOrientation(LinearLayout.VERTICAL);
        rDone.setGravity(Gravity.CENTER);
        rDone.setVisibility(View.GONE);
        ImageView tick = Ui.roundIconButton(this, R.drawable.ic_check, Ui.SPOTIFY, 0xFF000000, 64, 120);
        rDone.addView(tick, Ui.lp(Ui.dp(120), Ui.dp(120)));
        rDoneText = Ui.text(this, 28, Ui.TEXT, Ui.MEDIUM);
        rDoneText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dtl = Ui.lp(-2, -2);
        dtl.topMargin = Ui.dp(20);
        rDone.addView(rDoneText, dtl);
        LinearLayout backBtn = Ui.pill(this, R.drawable.ic_back, "Back to list", Ui.RAISED, Ui.TEXT, 22);
        backBtn.setOnClickListener(v -> closeReview());
        LinearLayout.LayoutParams bbl = Ui.lp(Ui.dp(320), Ui.dp(88));
        bbl.topMargin = Ui.dp(28);
        rDone.addView(backBtn, bbl);
        col.addView(rDone, new LinearLayout.LayoutParams(-1, 0, 1));

        reviewPage.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return reviewPage;
    }

    private void startReview() {
        queue = new ArrayList<>(shown);
        index = 0;
        removedCount = 0;
        reviewPage.setVisibility(View.VISIBLE);
        showCurrent();
    }

    private void showCurrent() {
        rStatus.setVisibility(View.GONE);
        if (index >= queue.size()) {
            rCard.setVisibility(View.GONE);
            rDone.setVisibility(View.VISIBLE);
            rProgress.setText("Finished");
            rDoneText.setText("All done — " + removedCount + " of " + queue.size() + " removed or disabled");
            return;
        }
        rCard.setVisibility(View.VISIBLE);
        rDone.setVisibility(View.GONE);
        PkgScan.Entry e = queue.get(index);
        rProgress.setText((index + 1) + " of " + queue.size());
        rIcon.setImageBitmap(e.icon);
        rLabel.setText(e.label);
        rPkg.setText(e.pkg + (e.system ? "  ·  preinstalled" : "  ·  you installed"));
        rWhy.setText(e.verdict.why);
        rWhy.setTextColor(levelColor(e.verdict.level));
        rSource.setText("Verdict from: " + ("pc".equals(e.verdict.source) ? "your PC"
                : "you".equals(e.verdict.source) ? "you" : "built-in rules"));
    }

    // ---- Automatic mode: the accessibility helper presses Disable + confirm for each app ------

    private void toggleAuto() {
        if (autoRunning) { stopAuto("Stopped"); return; }
        if (!AutoClicker.available()) {
            BigMenu.show(this, "Turn on the helper first",
                    "Automatic mode uses the \"HU Launcher remote\" accessibility service to press Disable for you. "
                            + "Turn it on once in Accessibility, then come back.",
                    new String[]{"Open Accessibility", "Cancel"}, idx -> {
                        if (idx == 0) {
                            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                            catch (RuntimeException ignored) { }
                        }
                    });
            return;
        }
        autoRunning = true;
        ((TextView) autoBtn.getChildAt(1)).setText("Stop auto");
        autoStep();
    }

    private void autoStep() {
        if (!autoRunning || queue == null) return;
        if (index >= queue.size()) { stopAuto(null); showCurrent(); return; }
        final PkgScan.Entry e = queue.get(index);
        rStatus.setText("Disabling " + e.label + "…");
        rStatus.setVisibility(View.VISIBLE);
        AutoClicker.run(this, e.pkg, AutoClicker.DISABLE, (ok, msg) -> {
            if (!autoRunning || queue == null) return;
            String st = PkgScan.state(this, e.pkg);
            if (!st.equals("active")) {
                e.enabled = false;
                if (st.equals("removed")) entries.remove(e);
                removedCount++;
                Toast.makeText(this, e.label + " " + (st.equals("removed") ? "uninstalled" : "disabled") + " ✓", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, e.label + ": " + msg + " - skipped", Toast.LENGTH_LONG).show();
            }
            index++;
            showCurrent();
            handler.postDelayed(this::autoStep, 600);
        });
    }

    private void stopAuto(String why) {
        autoRunning = false;
        AutoClicker.cancel();
        ((TextView) autoBtn.getChildAt(1)).setText("Auto-disable all");
        if (why != null) Toast.makeText(this, why, Toast.LENGTH_SHORT).show();
    }

    private void closeReview() {
        stopAuto(null);
        reviewPage.setVisibility(View.GONE);
        awaitingReturn = false;
        queue = null;
        refilter();
    }
}
