package io.github.vertango.labelprinter;

/**
 * Where to print the SKU on a label: the largest empty area, kept 3 mm clear of anything printed
 * (barcodes need clear space) and of the edges, where the text fits biggest. Same rules as the web app.
 * Pure Java: works on the label's dots; the text width is measured by the caller.
 */
public final class SkuStamp {
    private SkuStamp() {}

    public static final int STRIP = 80;          // dots kept under the label when there's no room on it
    public static final int MIN_FONT = 26, MAX_FONT = 64;

    /** Box {x, y, w, h, font} or null. width100 = width of the text at a 100-dot font. */
    public static int[] findBlankSpot(byte[] bits, double width100) {
        final int CELL = 8, PAD = 3, gw = LabelRaster.W / CELL, gh = LabelRaster.H / CELL, bpr = LabelRaster.BPR;
        boolean[] ink = new boolean[gw * gh];
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            boolean hit = false;
            for (int r = 0; r < CELL && !hit; r++) hit = bits[(gy * CELL + r) * bpr + gx] != 0; // 8 dots = one byte
            ink[gy * gw + gx] = hit;
        }
        boolean[] free = new boolean[gw * gh];
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            boolean ok = gx >= PAD && gy >= PAD && gx < gw - PAD && gy < gh - PAD;
            for (int y = Math.max(0, gy - PAD); ok && y <= Math.min(gh - 1, gy + PAD); y++)
                for (int x = Math.max(0, gx - PAD); x <= Math.min(gw - 1, gx + PAD); x++)
                    if (ink[y * gw + x]) { ok = false; break; }
            free[gy * gw + gx] = ok;
        }
        int[] best = null;
        int[] hgt = new int[gw];
        int[] stS = new int[gw + 1], stH = new int[gw + 1];
        double tw = Math.max(1, width100);
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) hgt[gx] = free[gy * gw + gx] ? hgt[gx] + 1 : 0;
            int sp = 0;
            for (int gx = 0; gx <= gw; gx++) {
                int h = gx < gw ? hgt[gx] : 0, start = gx;
                while (sp > 0 && stH[sp - 1] >= h) {
                    sp--;
                    int s0 = stS[sp], hh = stH[sp];
                    if (hh > 0) {
                        int w = (gx - s0) * CELL, ht = hh * CELL;
                        int font = (int) Math.min(MAX_FONT, Math.min(Math.floor(ht * 0.62), Math.floor(((w - 30) / tw) * 100)));
                        if (font >= MIN_FONT && (best == null || font > best[4] || (font == best[4] && (long) w * ht > (long) best[2] * best[3])))
                            best = new int[]{s0 * CELL, (gy - hh + 1) * CELL, w, ht, font};
                    }
                    start = s0;
                }
                stS[sp] = start; stH[sp] = h; sp++;
            }
        }
        return best;
    }

    /** Box for the strip under a shrunk label. */
    public static int[] stripBox(double width100, LabelPipeline.Options o) {
        int m = (int) Math.round(o.marginMm * LabelRaster.DPMM);
        int nx = (int) Math.round(o.nudgeXmm * LabelRaster.DPMM), ny = (int) Math.round(o.nudgeYmm * LabelRaster.DPMM);
        int w = LabelRaster.W - 2 * m, h = STRIP - 6;
        int font = (int) Math.min(52, Math.min(h - 22, Math.floor(((w - 30) / Math.max(1, width100)) * 100)));
        return new int[]{m + nx, LabelRaster.H - m - STRIP + 6 + ny, w, h, Math.max(12, font)};
    }
}
