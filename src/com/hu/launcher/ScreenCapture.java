package com.hu.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Mirrors the screen into a small ImageReader (the GPU does the downscale). Cheap by design:
 *  - frames arrive only when something on screen changes (static screen = zero work),
 *  - each frame is just a memcpy into a reused bitmap,
 *  - JPEG encoding happens only when a PC actually asks for a frame.
 */
final class ScreenCapture {
    private static final int WIDTH = 800;
    private static final Object LOCK = new Object();

    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static HandlerThread thread;
    private static Bitmap frame;
    private static int frameW, frameH;
    private static long version, encodedVersion = -1;
    private static byte[] jpeg;

    static boolean running() { synchronized (LOCK) { return projection != null; } }

    static void start(Context c, int resultCode, Intent data) {
        stop();
        MediaProjectionManager mpm = (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection mp = mpm.getMediaProjection(resultCode, data);
        if (mp == null) return;
        Point real = new Point();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(real);
        int w = Math.min(WIDTH, real.x);
        int h = Math.round(real.y * (w / (float) real.x));
        HandlerThread t = new HandlerThread("capture", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        r.setOnImageAvailableListener(ScreenCapture::onImage, new Handler(t.getLooper()));
        int dpi = Math.max(80, c.getResources().getDisplayMetrics().densityDpi * w / real.x);
        VirtualDisplay vd = mp.createVirtualDisplay("hu-remote", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.getSurface(), null, null);
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stop(); }
        }, new Handler(t.getLooper()));
        synchronized (LOCK) {
            projection = mp;
            display = vd;
            reader = r;
            thread = t;
            frameW = w;
            frameH = h;
        }
    }

    static void stop() {
        MediaProjection mp;
        VirtualDisplay vd;
        ImageReader r;
        HandlerThread t;
        synchronized (LOCK) {
            mp = projection; vd = display; r = reader; t = thread;
            projection = null; display = null; reader = null; thread = null;
            jpeg = null;
            encodedVersion = -1;
            LOCK.notifyAll();
        }
        if (vd != null) vd.release();
        if (mp != null) { try { mp.stop(); } catch (RuntimeException ignored) { } }
        if (r != null) r.close();
        if (t != null) t.quitSafely();
    }

    private static void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            Image.Plane p = img.getPlanes()[0];
            int stride = p.getRowStride() / p.getPixelStride();
            ByteBuffer buf = p.getBuffer();
            synchronized (LOCK) {
                if (frame == null || frame.getWidth() != stride || frame.getHeight() != img.getHeight()) {
                    frame = Bitmap.createBitmap(stride, img.getHeight(), Bitmap.Config.ARGB_8888);
                }
                buf.rewind();
                frame.copyPixelsFromBuffer(buf);
                version++;
                LOCK.notifyAll();
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (img != null) img.close();
        }
    }

    /** Latest frame as JPEG (encoded lazily, shared by all viewers), or null if not capturing. */
    static byte[] jpeg() {
        synchronized (LOCK) {
            if (projection == null || frame == null) return null;
            if (encodedVersion != version) {
                Bitmap src = frame.getWidth() == frameW ? frame : Bitmap.createBitmap(frame, 0, 0, frameW, frameH);
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                src.compress(Bitmap.CompressFormat.JPEG, 60, out);
                if (src != frame) src.recycle();
                jpeg = out.toByteArray();
                encodedVersion = version;
            }
            return jpeg;
        }
    }

    /** Blocks until a frame newer than {@code seen} exists (or timeout); returns the newest version. */
    static long awaitFrame(long seen, long timeoutMs) throws InterruptedException {
        synchronized (LOCK) {
            if (version == seen && projection != null) LOCK.wait(timeoutMs);
            return version;
        }
    }

    private ScreenCapture() { }
}
