package com.hu.launcher;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Cheap album-art effects for a weak GPU/CPU: a tiny box-blurred copy (drawn stretched, the
 * bilinear filter does the rest) and an accent colour. Both cost well under a millisecond.
 */
final class Art {
    private static final int SIZE = 36;

    static Bitmap backdrop(Bitmap art) {
        if (art == null) return null;
        try {
            Bitmap src = art.getConfig() == Bitmap.Config.ARGB_8888 ? art : art.copy(Bitmap.Config.ARGB_8888, false);
            if (src == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(src, SIZE, SIZE, true);
            int[] px = new int[SIZE * SIZE];
            small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
            for (int pass = 0; pass < 3; pass++) {
                blur(px, true);
                blur(px, false);
            }
            Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One horizontal or vertical box-blur pass, radius 2, edge-clamped. */
    private static void blur(int[] px, boolean horizontal) {
        final int r = 2, n = SIZE;
        int[] line = new int[n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) line[b] = horizontal ? px[a * n + b] : px[b * n + a];
            for (int b = 0; b < n; b++) {
                int rs = 0, gs = 0, bs = 0;
                for (int k = -r; k <= r; k++) {
                    int c = line[Math.max(0, Math.min(n - 1, b + k))];
                    rs += (c >> 16) & 0xFF; gs += (c >> 8) & 0xFF; bs += c & 0xFF;
                }
                int d = 2 * r + 1;
                int v = 0xFF000000 | ((rs / d) << 16) | ((gs / d) << 8) | (bs / d);
                if (horizontal) px[a * n + b] = v; else px[b * n + a] = v;
            }
        }
    }

    /** Most colourful tone of the art, lifted so it reads well on a dark background. */
    static int accent(Bitmap small, int fallback) {
        if (small == null) return fallback;
        int w = small.getWidth(), h = small.getHeight();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        float[] hsv = new float[3];
        float bestScore = -1;
        float[] best = null;
        for (int i = 0; i < px.length; i += 3) {
            Color.colorToHSV(px[i], hsv);
            float score = hsv[1] * (0.3f + hsv[2]);
            if (score > bestScore) { bestScore = score; best = hsv.clone(); }
        }
        if (best == null || best[1] < 0.22f) return fallback;
        best[1] = Math.max(best[1], 0.5f);
        best[2] = Math.max(0.8f, Math.min(1f, best[2] + 0.3f));
        return Color.HSVToColor(best);
    }

    private Art() { }
}
