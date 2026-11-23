package com.hu.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only text viewer. Lines go into a recycled ListView instead of one giant TextView, so a
 * multi-megabyte log scrolls as smoothly as a short note on the Mali-T720.
 */
public class TextViewer extends Activity {
    private static final long MAX_CHARS = 4L * 1024 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> lines = new ArrayList<>();
    private TextView info;
    private BaseAdapter adapter;
    private volatile boolean closed;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        final File f = new File(getIntent().getStringExtra("path"));

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
        TextView name = Ui.line(this, 26, Ui.TEXT, Ui.MEDIUM);
        name.setText(f.getName());
        info = Ui.line(this, 15, Ui.TEXT_3, Ui.REGULAR);
        info.setText(Files.size(f.length()) + "   ·   loading…");
        titles.addView(name);
        LinearLayout.LayoutParams il = Ui.lp(-1, -2);
        il.topMargin = Ui.dp(4);
        titles.addView(info, il);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout with = Ui.pill(this, 0, "Open with…", Ui.RAISED, Ui.TEXT, 19);
        with.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(FileShare.uri(f), "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(i, "Open with")); }
            catch (ActivityNotFoundException e) { Toast.makeText(this, "No other text app", Toast.LENGTH_SHORT).show(); }
        });
        header.addView(with, Ui.lp(-2, Ui.dp(72)));
        page.addView(header, Ui.lp(-1, Ui.dp(84)));

        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(0));
        list.setSelector(new ColorDrawable(0));
        list.setFastScrollEnabled(true);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(10), Ui.dp(12), Ui.dp(24));
        list.setBackground(Ui.round(Ui.CARD_SOLID, 20));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return lines.size(); }
            @Override public Object getItem(int p) { return lines.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                TextView t = (TextView) v;
                if (t == null) {
                    t = Ui.text(TextViewer.this, 17, 0xE6FFFFFF, Typeface.MONOSPACE);
                    t.setPadding(Ui.dp(18), Ui.dp(2), Ui.dp(8), Ui.dp(2));
                    t.setLineSpacing(Ui.dp(3), 1f);
                }
                t.setText(lines.get(p));
                return t;
            }
        };
        list.setAdapter(adapter);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1);
        ll.topMargin = Ui.dp(8);
        ll.bottomMargin = Ui.dp(16);
        page.addView(list, ll);
        setContentView(page);

        Apps.BG.execute(() -> load(f));
    }

    @Override protected void onDestroy() {
        closed = true;
        super.onDestroy();
    }

    private void load(File f) {
        final List<String> out = new ArrayList<>();
        String note = null;
        long chars = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 64 * 1024)) {
            for (String line; (line = r.readLine()) != null; ) {
                if (closed) return;
                if (line.length() > 4000) line = line.substring(0, 4000) + " …";
                out.add(line.indexOf('\0') >= 0 ? line.replace('\0', '·') : line);
                chars += line.length();
                if (chars > MAX_CHARS) { note = "showing the first 4 MB"; break; }
            }
        } catch (IOException e) {
            note = "can't read: " + e.getMessage();
        }
        final String n = note;
        handler.post(() -> {
            if (closed) return;
            lines.clear();
            lines.addAll(out);
            adapter.notifyDataSetChanged();
            info.setText(Files.size(f.length()) + "   ·   " + out.size() + " lines" + (n == null ? "" : "   ·   " + n));
        });
    }
}
