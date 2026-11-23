package com.hu.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * App drawer as horizontal pages: as many big cells as fit per page, swipe left/right with
 * snapping and fling, page dots at the bottom. A clear downward swipe asks to close the drawer.
 * One cell view per app, created once per app-list change; off-screen pages are skipped by the
 * normal clip, so scrolling costs no more than the old grid.
 */
final class AppPager extends ViewGroup {
    interface Host {
        void onAppClick(Apps.App a);
        void onAppLongClick(Apps.App a);
        void onSwipeDown();
    }

    // Still big touch targets (>=116dp), sized so the unit's ~430dp-tall screen gets 2 rows.
    private static final float CELL_W = 132, CELL_H = 116, DOTS_H = 24;

    private final Host host;
    private final OverScroller scroller;
    private final int slop, minFling;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Apps.App> apps = new ArrayList<>();
    private VelocityTracker vt;
    private float downX, downY, lastX;
    private boolean dragging, closing;
    private int cols = 1, rows = 1, page, pages = 1;

    AppPager(Context c, Host host) {
        super(c);
        this.host = host;
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = Math.max(vc.getScaledMinimumFlingVelocity(), Ui.dp(400));
        setWillNotDraw(false);
        setClipChildren(true);
    }

    /**
     * Icon size the 5 x 2 cells will draw on this screen (landscape), so icon bitmaps are rendered
     * at exactly that size: no blurry upscaling, no wasted memory. Mirrors onMeasure's formula with
     * the drawer's fixed chrome (status bar, header, margins, dots ~ 140dp).
     */
    static int iconPx(Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels) - Ui.dp(36), h = Math.min(m.widthPixels, m.heightPixels) - Ui.dp(140);
        return Math.max(Ui.dp(48), Math.min(w / 5 - Ui.dp(24), h / 2 - Ui.dp(40)));
    }

    void setApps(List<Apps.App> list) {
        apps.clear();
        apps.addAll(list);
        removeAllViews();
        for (Apps.App a : apps) addView(cell(a));
        requestLayout();
    }

    /** Back to the first page (when the drawer opens). */
    void reset() {
        scroller.forceFinished(true);
        page = 0;
        scrollTo(0, 0);
    }

    private View cell(final Apps.App a) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(Ui.button(0x00000000, 22));
        ImageView i = new ImageView(getContext());
        i.setImageBitmap(a.icon);
        cell.addView(i, Ui.lp(Ui.dp(84), Ui.dp(84)));
        TextView t = Ui.line(getContext(), 18, Ui.TEXT, Ui.REGULAR);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(6), 0, Ui.dp(6), 0);
        t.setText(a.label);
        LinearLayout.LayoutParams tl = Ui.lp(-1, -2);
        tl.topMargin = Ui.dp(4);
        cell.addView(t, tl);
        cell.setOnClickListener(v -> host.onAppClick(a));
        cell.setOnLongClickListener(v -> { host.onAppLongClick(a); return true; });
        return cell;
    }

    // ---- layout --------------------------------------------------------------------------------

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int gridH = h - Ui.dp(DOTS_H);
        // Fixed 5 x 2 pages: the biggest possible targets on a car screen.
        cols = 5;
        rows = 2;
        int perPage = cols * rows;
        pages = Math.max(1, (apps.size() + perPage - 1) / perPage);
        if (page >= pages) page = pages - 1;
        int cw = w / cols, ch = gridH / rows;
        // Icon fills the cell, leaving room for the label (~26dp) and a little padding.
        int icon = Math.max(Ui.dp(48), Math.min(cw - Ui.dp(24), ch - Ui.dp(40)));
        for (int i = 0; i < getChildCount(); i++) {
            View iv = ((ViewGroup) getChildAt(i)).getChildAt(0);
            ViewGroup.LayoutParams lp = iv.getLayoutParams();
            if (lp.width != icon) { lp.width = lp.height = icon; iv.setLayoutParams(lp); }
        }
        int cs = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), hs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cs, hs);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, gridH = (b - t) - Ui.dp(DOTS_H);
        int cw = w / cols, ch = gridH / rows, perPage = cols * rows;
        int padX = (w - cw * cols) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            int p = i / perPage, k = i % perPage, row = k / cols, col = k % cols;
            int x = p * w + padX + col * cw, y = row * ch;
            getChildAt(i).layout(x, y, x + cw, y + ch);
        }
        // Any re-layout (size/config change, app list change) realigns to the current page.
        if (!dragging && scroller.isFinished() && getScrollX() != page * w) scrollTo(page * w, 0);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (pages < 2) return;
        // Page dots, drawn in screen space (follow the scroll).
        float r = Ui.dp(5), gap = Ui.dp(22);
        float cx0 = getScrollX() + getWidth() / 2f - (pages - 1) * gap / 2f;
        float cy = getHeight() - Ui.dp(DOTS_H) / 2f;
        float pos = getWidth() == 0 ? 0 : getScrollX() / (float) getWidth();
        for (int i = 0; i < pages; i++) {
            float near = Math.max(0f, 1f - Math.abs(pos - i));
            dot.setColor(near > 0.5f ? Ui.TEXT : Ui.TEXT_3);
            c.drawCircle(cx0 + i * gap, cy, r + near * Ui.dp(2), dot);
        }
    }

    // ---- touch ---------------------------------------------------------------------------------

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = closing = false;
                track(e);
                if (!scroller.isFinished()) { scroller.forceFinished(true); dragging = true; } // catch a moving page
                return dragging;
            case MotionEvent.ACTION_MOVE: {
                track(e);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) { dragging = true; lastX = e.getX(); }
                else if (dy > slop * 2 && dy > Math.abs(dx) * 1.5f) closing = true;
                return dragging || closing;
            }
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        track(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (closing) return true;
                if (!dragging && Math.abs(e.getX() - downX) > slop) dragging = true;
                if (dragging) {
                    float dx = lastX - e.getX();
                    int max = (pages - 1) * getWidth(), x = getScrollX();
                    if (x + dx < 0 || x + dx > max) dx *= 0.35f; // resist at the ends
                    scrollBy((int) dx, 0);
                    invalidate();
                }
                lastX = e.getX();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (closing) {
                    closing = false;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getY() - downY > Ui.dp(70)) host.onSwipeDown();
                    recycle();
                    return true;
                }
                vt.computeCurrentVelocity(1000);
                float v = vt.getXVelocity();
                int target = page;
                float pos = getScrollX() / (float) Math.max(1, getWidth());
                if (Math.abs(v) > minFling) target = v < 0 ? (int) Math.floor(pos) + 1 : (int) Math.ceil(pos) - 1;
                else target = Math.round(pos);
                snapTo(target);
                dragging = false;
                recycle();
                return true;
            }
        }
        return true;
    }

    private void snapTo(int p) {
        page = Math.max(0, Math.min(pages - 1, p));
        int dx = page * getWidth() - getScrollX();
        scroller.startScroll(getScrollX(), 0, dx, 0, Math.min(320, 120 + Math.abs(dx) / 3));
        postInvalidateOnAnimation();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }

    private void track(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
    }

    private void recycle() {
        if (vt != null) { vt.recycle(); vt = null; }
    }
}
