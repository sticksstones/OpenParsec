package com.example.parsecdemo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Diagnostic: tile a high-density grid of tiny colored squares across the
 * entire viewport. Each square is its own View in the regular window draw
 * layer — anywhere the squares fail to render reveals a compositing dead
 * zone (e.g. a region where the GLSurfaceView is occluding overlay Views).
 */
final class DebugGrid {
    private DebugGrid() {}

    /**
     * Install 4 corner anchor Views — the minimal overlay structure that
     * forces the SurfaceView to be routed through the window compositor on
     * hardware-overlay-capable devices, so the FAB and cursor render on top.
     * Each anchor is 1px and uses alpha=0x01 (effectively invisible).
     */
    static FrameLayout installCornerAnchors(Context ctx, FrameLayout parent) {
        FrameLayout layer = new FrameLayout(ctx);
        layer.setClipChildren(false);
        layer.setClipToPadding(false);
        int[][] corners = {
                { android.view.Gravity.TOP    | android.view.Gravity.START },
                { android.view.Gravity.TOP    | android.view.Gravity.END },
                { android.view.Gravity.BOTTOM | android.view.Gravity.START },
                { android.view.Gravity.BOTTOM | android.view.Gravity.END },
        };
        for (int[] g : corners) {
            View dot = new View(ctx);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setColor(0x01000000);
            dot.setBackground(d);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1, 1, g[0]);
            layer.addView(dot, lp);
        }
        parent.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layer;
    }

    /** Add a grid of tiles to {@code parent}. Returns the container so it can be removed. */
    static FrameLayout install(Context ctx, FrameLayout parent, int tilePx, int gapPx) {
        return install(ctx, parent, tilePx, gapPx, false);
    }

    /**
     * @param stealth when true, tiles are nearly transparent (alpha 0x01) so they
     *                stay in the View hierarchy and force the compositor to mediate
     *                the SurfaceView, but aren't visually intrusive.
     */
    static FrameLayout install(Context ctx, FrameLayout parent, int tilePx, int gapPx, boolean stealth) {
        FrameLayout grid = new FrameLayout(ctx);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);

        int w = parent.getWidth();
        int h = parent.getHeight();
        if (w <= 0 || h <= 0) {
            w = ctx.getResources().getDisplayMetrics().widthPixels;
            h = ctx.getResources().getDisplayMetrics().heightPixels;
        }

        int pitch = tilePx + gapPx;
        int cols = w / pitch;
        int rows = h / pitch;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                View tile = new View(ctx);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.RECTANGLE);
                int color;
                if (stealth) {
                    color = 0x01000000; // alpha=1 (almost invisible) so the
                                        // compositor still treats it as a draw op
                } else {
                    boolean marker = (r % 8 == 0) && (c % 8 == 0);
                    boolean rowMarker = (r % 8 == 0) ^ (c % 8 == 0);
                    if (marker)            color = 0xFFFFFF00;
                    else if (rowMarker)    color = 0xFFFF00FF;
                    else                   color = 0xFF00FF00;
                }
                d.setColor(color);
                tile.setBackground(d);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(tilePx, tilePx);
                lp.leftMargin = c * pitch;
                lp.topMargin = r * pitch;
                grid.addView(tile, lp);
            }
        }

        parent.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return grid;
    }
}
