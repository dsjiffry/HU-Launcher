package com.hu.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Minimal read-only FileProvider (no AndroidX). Android 7+ forbids handing file:// URIs to
 * other apps, so APKs/media go out as content:// URIs with a one-off read grant.
 * Only files under shared storage are served.
 */
public class FileShare extends ContentProvider {
    static final String AUTHORITY = "com.hu.launcher.files";

    static Uri uri(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getAbsolutePath()).build();
    }

    static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        if (ext.equals("apk")) return "application/vnd.android.package-archive";
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    }

    private static File resolve(Uri uri) throws FileNotFoundException {
        String p = uri.getPath();
        if (p == null) throw new FileNotFoundException("no path");
        File f = new File(p);
        try {
            String c = f.getCanonicalPath();
            if (!(c.startsWith("/storage/") || c.startsWith("/mnt/") || c.startsWith("/sdcard/"))) {
                throw new FileNotFoundException("not shared storage");
            }
            return new File(c);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String m = mime(uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment());
        return m == null ? "application/octet-stream" : m;
    }

    @Override public Cursor query(Uri uri, String[] proj, String sel, String[] args, String sort) {
        File f;
        try { f = resolve(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = proj == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : proj;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
