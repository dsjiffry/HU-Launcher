package com.hu.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Big-target file manager: browse, rename, install APKs, view text, extract zips. */
public class Files extends Activity {
    static final int DIR = 0, APK = 1, TEXT = 2, ZIP = 3, IMAGE = 4, AUDIO = 5, VIDEO = 6, OTHER = 7;
    private static final int[] ICONS = {R.drawable.ic_folder, R.drawable.ic_android, R.drawable.ic_text, R.drawable.ic_zip,
            R.drawable.ic_image, R.drawable.ic_music, R.drawable.ic_movie, R.drawable.ic_file};
    private static final int[] TINTS = {0xFFFFC857, 0xFF3DDC84, 0xFF8AB4F8, 0xFFFF9E64, 0xFFF48FB1, 0xFF1DB954, 0xFFB39DDB, 0xFFB0BEC5};
    private static final String TEXT_EXT = " txt log md json xml csv tsv ini conf cfg prop properties sh bat yml yaml java kt js css srt lrc m3u m3u8 nfo ";

    static final class Item {
        final File f;
        final String name;
        final boolean dir;
        final long size, modified;
        final int kind;
        Item(File f) {
            this.f = f;
            name = f.getName();
            dir = f.isDirectory();
            size = dir ? 0 : f.length();
            modified = f.lastModified();
            kind = dir ? DIR : kindOf(name);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Item> items = new ArrayList<>();
    private final DateFormat dateFmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
    private File dir;
    private int generation;
    private SharedPreferences prefs;

    private TextView title, subtitle, empty;
    private ImageView upBtn;
    private LinearLayout roots;
    private ListView list;
    private RowAdapter adapter;
    private FrameLayout busy;
    private TextView busyText;
    private volatile boolean cancelled;
    private boolean working;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = getSharedPreferences("files", MODE_PRIVATE);
        setContentView(build());
        if (hasStorage()) start();
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override protected void onResume() {
        super.onResume();
        buildRoots(); // a USB stick may have been plugged in meanwhile
        if (dir != null && hasStorage()) load(dir, false);
    }

    @Override protected void onDestroy() {
        cancelled = true;
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (hasStorage()) { buildRoots(); start(); return; }
        empty.setText("Storage permission is needed to browse files.\nTap here to allow it.");
        empty.setVisibility(View.VISIBLE);
        empty.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException ignored) { }
        });
    }

    private boolean hasStorage() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        File last = new File(prefs.getString("last", Environment.getExternalStorageDirectory().getAbsolutePath()));
        load(last.isDirectory() && last.canRead() ? last : Environment.getExternalStorageDirectory(), true);
    }

    @Override public void onBackPressed() {
        if (working) return;
        if (canGoUp()) load(dir.getParentFile(), true); else super.onBackPressed();
    }

    // ---- Layout ------------------------------------------------------------------------------

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(20), Ui.dp(12), Ui.dp(20), 0);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView close = Ui.roundIconButton(this, R.drawable.ic_back, Ui.RAISED, Ui.TEXT, 32, 72);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> finish());
        header.addView(close, Ui.lp(Ui.dp(72), Ui.dp(72)));
        upBtn = Ui.roundIconButton(this, R.drawable.ic_up, Ui.RAISED, Ui.TEXT, 32, 72);
        upBtn.setContentDescription("Up one folder");
        upBtn.setOnClickListener(v -> { if (canGoUp()) load(dir.getParentFile(), true); });
        LinearLayout.LayoutParams ul = Ui.lp(Ui.dp(72), Ui.dp(72));
        ul.leftMargin = Ui.dp(12);
        header.addView(upBtn, ul);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(18), 0, 0, 0);
        title = Ui.line(this, 28, Ui.TEXT, Ui.MEDIUM);
        subtitle = Ui.line(this, 15, Ui.TEXT_3, Ui.REGULAR);
        subtitle.setEllipsize(TextUtils.TruncateAt.START);
        titles.addView(title);
        LinearLayout.LayoutParams sl = Ui.lp(-1, -2);
        sl.topMargin = Ui.dp(4);
        titles.addView(subtitle, sl);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header, Ui.lp(-1, Ui.dp(84)));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        roots = new LinearLayout(this);
        hs.addView(roots);
        LinearLayout.LayoutParams rl = Ui.lp(-1, Ui.dp(64));
        rl.topMargin = Ui.dp(8);
        page.addView(hs, rl);

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(0));
        list.setDividerHeight(Ui.dp(8));
        list.setSelector(new ColorDrawable(0));
        list.setFastScrollEnabled(true);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(12), 0, Ui.dp(20));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> open(items.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { menu(items.get(pos)); return true; });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(this, 20, Ui.TEXT_3, Ui.REGULAR);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Unzip progress overlay
        busy = new FrameLayout(this);
        busy.setBackgroundColor(0xE6000000);
        busy.setClickable(true);
        busy.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        busyText = Ui.text(this, 24, Ui.TEXT, Ui.MEDIUM);
        busyText.setGravity(Gravity.CENTER);
        box.addView(busyText);
        LinearLayout stop = Ui.pill(this, R.drawable.ic_close, "Cancel", Ui.RAISED, Ui.TEXT, 22);
        stop.setOnClickListener(v -> { cancelled = true; busyText.setText("Stopping…"); });
        LinearLayout.LayoutParams stl = Ui.lp(Ui.dp(260), Ui.dp(84));
        stl.topMargin = Ui.dp(28);
        box.addView(stop, stl);
        busy.addView(box, new FrameLayout.LayoutParams(-1, -1));
        root.addView(busy, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    /** Internal storage, Downloads, and every readable removable volume (USB / SD). */
    private void buildRoots() {
        roots.removeAllViews();
        File internal = Environment.getExternalStorageDirectory();
        addRoot(R.drawable.ic_phone, "Internal", internal);
        addRoot(R.drawable.ic_download, "Downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        List<String> seen = new ArrayList<>();
        List<File> candidates = new ArrayList<>();
        File[] st = new File("/storage").listFiles();
        if (st != null) Collections.addAll(candidates, st);
        for (String p : new String[]{"/mnt/usbotg", "/mnt/udisk", "/mnt/usb_storage", "/mnt/media_rw", "/mnt/external_sd"}) {
            candidates.add(new File(p));
        }
        for (File f : candidates) {
            String n = f.getName();
            if (n.equals("emulated") || n.equals("self") || n.equals("sdcard0")) continue;
            if (!f.isDirectory() || f.list() == null) continue;
            String canon = canon(f);
            if (seen.contains(canon) || canon.equals(canon(internal))) continue;
            seen.add(canon);
            addRoot(R.drawable.ic_usb, "USB / SD  " + n, f);
        }
    }

    private void addRoot(int icon, String label, final File f) {
        LinearLayout chip = Ui.pill(this, icon, label, Ui.RAISED, Ui.TEXT, 19);
        chip.setOnClickListener(v -> load(f, true));
        LinearLayout.LayoutParams lp = Ui.lp(-2, -1);
        lp.rightMargin = Ui.dp(10);
        roots.addView(chip, lp);
    }

    private static String canon(File f) {
        try { return f.getCanonicalPath(); } catch (IOException e) { return f.getAbsolutePath(); }
    }

    private boolean canGoUp() {
        if (dir == null) return false;
        File p = dir.getParentFile();
        if (p == null) return false;
        // Stop at volume roots: /storage/emulated (above internal) can't be listed.
        return p.list() != null && !p.getAbsolutePath().equals("/") && !p.getAbsolutePath().equals("/storage");
    }

    // ---- Listing -----------------------------------------------------------------------------

    private void load(final File target, final boolean scrollTop) {
        final int gen = ++generation;
        io.execute(() -> {
            File[] files = target.listFiles();
            final List<Item> out = new ArrayList<>();
            if (files != null) {
                for (File f : files) if (!f.getName().startsWith(".")) out.add(new Item(f));
                final Collator col = Collator.getInstance();
                col.setStrength(Collator.PRIMARY);
                Collections.sort(out, (a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : col.compare(a.name, b.name));
            }
            final boolean readable = files != null;
            handler.post(() -> {
                if (gen != generation) return;
                dir = target;
                prefs.edit().putString("last", target.getAbsolutePath()).apply();
                items.clear();
                items.addAll(out);
                adapter.notifyDataSetChanged();
                if (scrollTop) list.setSelection(0);
                title.setText(displayName(target));
                subtitle.setText(target.getAbsolutePath() + "   ·   " + out.size() + " items");
                upBtn.setAlpha(canGoUp() ? 1f : 0.35f);
                empty.setOnClickListener(null);
                empty.setText(readable ? "This folder is empty" : "Can't read this folder");
                empty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private static String displayName(File f) {
        if (f.equals(Environment.getExternalStorageDirectory())) return "Internal storage";
        return f.getName().isEmpty() ? f.getAbsolutePath() : f.getName();
    }

    static int kindOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
        if (ext.equals("apk")) return APK;
        if (ext.equals("zip")) return ZIP;
        if (!ext.isEmpty() && TEXT_EXT.contains(" " + ext + " ")) return TEXT;
        String m = FileShare.mime(name);
        if (m != null) {
            if (m.startsWith("image/")) return IMAGE;
            if (m.startsWith("audio/")) return AUDIO;
            if (m.startsWith("video/")) return VIDEO;
            if (m.startsWith("text/")) return TEXT;
        }
        return OTHER;
    }

    static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024f);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576f);
        return String.format(Locale.US, "%.2f GB", b / 1073741824f);
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Item getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int p, View v, ViewGroup parent) {
            if (v == null) {
                LinearLayout row = new LinearLayout(Files.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Ui.button(Ui.CARD_SOLID, 20));
                row.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(18), Ui.dp(12));
                row.setMinimumHeight(Ui.dp(88));
                ImageView icon = new ImageView(Files.this);
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                icon.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
                row.addView(icon, Ui.lp(Ui.dp(60), Ui.dp(60)));
                LinearLayout col = new LinearLayout(Files.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(Ui.dp(18), 0, 0, 0);
                TextView name = Ui.line(Files.this, 20, Ui.TEXT, Ui.MEDIUM);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                col.addView(name);
                TextView sub = Ui.line(Files.this, 15, Ui.TEXT_3, Ui.REGULAR);
                LinearLayout.LayoutParams sl = Ui.lp(-1, -2);
                sl.topMargin = Ui.dp(6);
                col.addView(sub, sl);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                v = row;
            }
            Item it = getItem(p);
            LinearLayout row = (LinearLayout) v;
            ImageView icon = (ImageView) row.getChildAt(0);
            icon.setImageResource(ICONS[it.kind]);
            icon.setColorFilter(TINTS[it.kind]);
            icon.setBackground(Ui.round((TINTS[it.kind] & 0x00FFFFFF) | 0x26000000, 16));
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(it.name);
            String when = dateFmt.format(new Date(it.modified));
            ((TextView) col.getChildAt(1)).setText(it.dir ? "Folder   ·   " + when : size(it.size) + "   ·   " + when);
            return v;
        }
    }

    // ---- Actions -----------------------------------------------------------------------------

    private void open(Item it) {
        switch (it.kind) {
            case DIR: load(it.f, true); break;
            case APK: install(it.f); break;
            case TEXT: openText(it.f); break;
            case ZIP:
                BigMenu.show(this, it.name, size(it.size),
                        new String[]{"Extract here", "Open with another app", "Cancel"}, idx -> {
                            if (idx == 0) unzip(it.f);
                            else if (idx == 1) openWith(it.f, true);
                        });
                break;
            default: openWith(it.f, false);
        }
    }

    private void menu(final Item it) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        if (!it.dir) {
            labels.add("Open"); acts.add(() -> open(it));
        }
        labels.add("Rename"); acts.add(() -> rename(it));
        if (!it.dir && it.kind != TEXT) { labels.add("View as text"); acts.add(() -> openText(it.f)); }
        if (!it.dir) { labels.add("Open with another app"); acts.add(() -> openWith(it.f, true)); }
        if (it.kind == ZIP) { labels.add("Extract here"); acts.add(() -> unzip(it.f)); }
        labels.add("Delete"); acts.add(() -> confirmDelete(it));
        labels.add("Cancel"); acts.add(null);
        BigMenu.show(this, it.name, it.dir ? null : size(it.size), labels.toArray(new String[0]), idx -> {
            Runnable r = acts.get(idx);
            if (r != null) r.run();
        });
    }

    private void openText(File f) {
        startActivity(new Intent(this, TextViewer.class).putExtra("path", f.getAbsolutePath()));
    }

    private void openWith(File f, boolean chooser) {
        String m = FileShare.mime(f.getName());
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(FileShare.uri(f), m == null ? "*/*" : m)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(chooser ? Intent.createChooser(i, "Open with") : i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app on this unit can open " + f.getName(), Toast.LENGTH_LONG).show();
        }
    }

    private void install(File apk) {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "Allow \"HU Launcher\" to install apps, then press Back and tap the APK again",
                        Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                return;
            }
        } catch (RuntimeException | LinkageError ignored) { }
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileShare.uri(apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "The package installer is missing", Toast.LENGTH_LONG).show();
        }
    }

    /** Folders whose loss breaks other apps or the storage itself: never deleted from here. */
    private static boolean protectedPath(File f) {
        String p = f.getAbsolutePath();
        File parent = f.getParentFile();
        if (parent == null || p.equals("/") || p.matches("/storage(/[^/]+)?/?") || p.matches("/storage/emulated/\\d+/?")
                || p.startsWith("/system") || p.startsWith("/data") || p.startsWith("/proc")) return true;
        // <storage root>/Android holds other apps' data and OBB files.
        String name = f.getName();
        return name.equals("Android") && (parent.getAbsolutePath().matches("/storage/emulated/\\d+")
                || parent.getParentFile() != null && parent.getParentFile().getAbsolutePath().equals("/storage"));
    }

    /** Delete asks first: the safe choice is the big green default, Delete is the second button. */
    private void confirmDelete(final Item it) {
        if (protectedPath(it.f)) {
            Toast.makeText(this, it.name + " is a system folder - other apps need it", Toast.LENGTH_LONG).show();
            return;
        }
        String msg = (it.dir ? "This folder and everything inside it" : "This file") + " will be deleted for good. There is no recycle bin.";
        BigMenu.show(this, "Delete " + it.name + "?", msg, new String[]{"Cancel", "Delete permanently"}, idx -> {
            if (idx == 1) delete(it);
        });
    }

    private void delete(final Item it) {
        Toast.makeText(this, "Deleting " + it.name + "…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            int[] failed = {0};
            deleteTree(it.f, failed, 0);
            boolean gone = !it.f.exists();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                Toast.makeText(this, gone ? it.name + " deleted"
                        : writeHint(failed[0] + " item(s) couldn't be deleted"), Toast.LENGTH_LONG).show();
                load(dir, false);
            });
        });
    }

    /** Depth-first delete; never follows symlinks out of the folder (deletes the link itself). */
    private static void deleteTree(File f, int[] failed, int depth) {
        if (depth > 64) { failed[0]++; return; }
        boolean link;
        try { link = !f.getCanonicalPath().equals(f.getAbsoluteFile().getParentFile().getCanonicalPath() + "/" + f.getName()); }
        catch (IOException e) { link = true; }
        if (f.isDirectory() && !link) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k, failed, depth + 1);
        }
        if (!f.delete() && f.exists()) failed[0]++;
    }

    /** Rename dialog: the whole name is editable (extension too); the base name is pre-selected. */
    private void rename(final Item it) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(28), Ui.dp(24), Ui.dp(28), Ui.dp(22));
        box.setBackground(Ui.round(Ui.CARD_SOLID, 28));
        // Buttons sit on the title row: on a 600px screen the keyboard would cover anything below.
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.line(this, 26, Ui.TEXT, Ui.MEDIUM);
        t.setText(it.dir ? "Rename folder" : "Rename file");
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row, Ui.lp(-1, -2));
        final EditText edit = new EditText(this);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        edit.setTextColor(Ui.TEXT);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        edit.setBackground(Ui.round(Ui.RAISED, 16));
        edit.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14));
        edit.setText(it.name);
        int dot = it.name.lastIndexOf('.');
        edit.setSelection(0, it.dir || dot <= 0 ? it.name.length() : dot);
        LinearLayout.LayoutParams el = Ui.lp(-1, -2);
        el.topMargin = Ui.dp(18);
        box.addView(edit, el);

        TextView cancel = Ui.text(this, 22, Ui.TEXT, Ui.MEDIUM);
        cancel.setText("Cancel");
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Ui.button(Ui.RAISED, 20));
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams cl = Ui.lp(Ui.dp(150), Ui.dp(72));
        cl.leftMargin = Ui.dp(14);
        row.addView(cancel, cl);
        TextView ok = Ui.text(this, 22, 0xFF000000, Ui.MEDIUM);
        ok.setText("Rename");
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(Ui.button(Ui.SPOTIFY, 20));
        LinearLayout.LayoutParams okl = Ui.lp(Ui.dp(170), Ui.dp(72));
        okl.leftMargin = Ui.dp(12);
        row.addView(ok, okl);

        Runnable apply = () -> {
            String name = edit.getText().toString().trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/")) {
                Toast.makeText(this, "That name isn't allowed", Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.equals(it.name)) { d.dismiss(); return; }
            File target = new File(it.f.getParentFile(), name);
            if (target.exists() && !name.equalsIgnoreCase(it.name)) {
                Toast.makeText(this, name + " already exists", Toast.LENGTH_SHORT).show();
                return;
            }
            if (it.f.renameTo(target)) {
                d.dismiss();
                load(dir, false);
            } else {
                Toast.makeText(this, writeHint("Rename failed"), Toast.LENGTH_LONG).show();
            }
        };
        ok.setOnClickListener(v -> apply.run());
        edit.setOnEditorActionListener((v, action, ev) -> { apply.run(); return true; });

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(Math.min(Ui.dp(640), getResources().getDisplayMetrics().widthPixels - Ui.dp(32)), -2);
            w.setGravity(Gravity.TOP);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        edit.requestFocus();
    }

    private String writeHint(String what) {
        String p = dir == null ? "" : dir.getAbsolutePath();
        if (!p.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            return what + ": Android 9 lets apps change files only on internal storage, not on USB / SD.";
        }
        return what;
    }

    // ---- Unzip -------------------------------------------------------------------------------

    /**
     * Runs on a low-priority background thread in the :files process, so the launcher and any
     * music/navigation keep their CPU share. Guards against "zip slip" paths.
     */
    private void unzip(final File zip) {
        String base = zip.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File dest = new File(zip.getParentFile(), base);
        for (int n = 2; dest.exists(); n++) dest = new File(zip.getParentFile(), base + " (" + n + ")");
        final File out = dest;
        if (!out.mkdirs()) {
            Toast.makeText(this, writeHint("Can't create a folder here"), Toast.LENGTH_LONG).show();
            return;
        }
        cancelled = false;
        working = true;
        busyText.setText("Extracting " + zip.getName() + "…");
        busy.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        io.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            String error = null;
            int[] count = new int[1];
            try {
                try {
                    extract(zip, out, StandardCharsets.UTF_8, count);
                } catch (IllegalArgumentException badNames) {
                    // Names not in UTF-8 (old Windows zips): retry with the DOS code page.
                    deleteTree(out);
                    out.mkdirs();
                    count[0] = 0;
                    extract(zip, out, Charset.isSupported("IBM437") ? Charset.forName("IBM437") : StandardCharsets.ISO_8859_1, count);
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            if (cancelled || error != null) deleteTree(out);
            final String err = error;
            handler.post(() -> {
                working = false;
                busy.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (cancelled) Toast.makeText(this, "Extraction cancelled", Toast.LENGTH_SHORT).show();
                else if (err != null) Toast.makeText(this, "Extract failed: " + err, Toast.LENGTH_LONG).show();
                else Toast.makeText(this, count[0] + " files extracted to " + out.getName(), Toast.LENGTH_LONG).show();
                if (!isDestroyed()) load(dir, false);
            });
        });
    }

    private void extract(File zip, File dest, Charset cs, int[] count) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        byte[] buf = new byte[64 * 1024];
        long lastUi = 0;
        try (ZipFile z = new ZipFile(zip, cs)) {
            final int total = z.size();
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                if (cancelled) return;
                ZipEntry e = en.nextElement();
                File target = new File(dest, e.getName());
                String c = target.getCanonicalPath();
                if (!c.startsWith(root) && !(c + File.separator).equals(root)) {
                    throw new IOException("unsafe path in zip: " + e.getName());
                }
                if (e.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("can't create " + e.getName());
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("can't create " + parent.getName());
                try (InputStream in = new BufferedInputStream(z.getInputStream(e));
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(target), 64 * 1024)) {
                    for (int r; (r = in.read(buf)) > 0; ) {
                        if (cancelled) return;
                        os.write(buf, 0, r);
                    }
                }
                count[0]++;
                long now = SystemClock.uptimeMillis();
                if (now - lastUi > 200) {
                    lastUi = now;
                    final int done = count[0];
                    handler.post(() -> { if (!cancelled) busyText.setText("Extracting…  " + done + " / " + total); });
                }
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
