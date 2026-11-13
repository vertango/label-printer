package io.github.vertango.labelprinter;

/**
 * Lays a page out on a 100x150 mm label at 203 dpi, the same way the web app does:
 * trim white edges (unless the page is already label-shaped), turn it to fit, centre it,
 * apply the left/right and up/down nudges, then turn it into black and white dots.
 * Pure Java so it can be tested off the device.
 */
public final class LabelRaster {
    private LabelRaster() {}

    public static final int DPMM = 8;
    public static final int W = 800, H = 1200, BPR = W / 8; // 100 x 150 mm

    public static double lum(int argb) {
        if ((argb >>> 24) < 128) return 255;
        int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000.0;
    }

    /** A page that's already label-shaped (4x6 / 100x150, either way round), sizes in points. */
    public static boolean looksLikeLabel(double w, double h) {
        double r = w / h;
        if (Math.abs(r - 2.0 / 3) < 0.015 || Math.abs(r - 1.5) < 0.03) return true;
        double lo = Math.min(w, h), hi = Math.max(w, h);
        return lo > 250 && lo < 330 && hi > 380 && hi < 485;
    }

    /** Box around the printed content as fractions {x, y, w, h} of the picture. */
    public static double[] contentBox(int[] px, int w, int h) {
        int[] rowInk = new int[h], colInk = new int[w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            if (lum(px[y * w + x]) < 200) { rowInk[y]++; colInk[x]++; }
        int noise = 1, x0 = 0, x1 = w - 1, y0 = 0, y1 = h - 1;
        while (y0 < h && rowInk[y0] <= noise) y0++;
        while (y1 > y0 && rowInk[y1] <= noise) y1--;
        while (x0 < w && colInk[x0] <= noise) x0++;
        while (x1 > x0 && colInk[x1] <= noise) x1--;
        if (y0 >= h || x0 >= w) return new double[]{0, 0, 1.0 / w, 1.0 / h};
        int pad = (int) Math.round(Math.max(w, h) * 0.006);
        x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad);
        x1 = Math.min(w - 1, x1 + pad); y1 = Math.min(h - 1, y1 + pad);
        return new double[]{(double) x0 / w, (double) y0 / h, (double) (x1 - x0 + 1) / w, (double) (y1 - y0 + 1) / h};
    }

    /** Where and how big the picture goes on the label. */
    public static final class Plan {
        public int rot, cw, ch, ox, oy;
        public double scale; // label dots per source unit
    }

    public static Plan plan(double rw, double rh, double nudgeXmm, double nudgeYmm, double marginMm) {
        return plan(rw, rh, nudgeXmm, nudgeYmm, marginMm, 0);
    }

    /** reserve: dots kept free at the bottom (for the SKU strip when there's no blank space). */
    public static Plan plan(double rw, double rh, double nudgeXmm, double nudgeYmm, double marginMm, int reserve) {
        Plan p = new Plan();
        p.rot = (rw > rh) != (W > H) ? 90 : 0;
        boolean turned = p.rot == 90;
        int m = (int) Math.round(marginMm * DPMM);
        double boxW = W - 2 * m, boxH = H - 2 * m - reserve;
        double fw = turned ? rh : rw, fh = turned ? rw : rh;
        p.scale = Math.min(boxW / fw, boxH / fh);
        p.cw = Math.max(1, (int) Math.round(rw * p.scale));
        p.ch = Math.max(1, (int) Math.round(rh * p.scale));
        int pw = turned ? p.ch : p.cw, ph = turned ? p.cw : p.ch;
        p.ox = (int) Math.round((W - pw) / 2.0 + nudgeXmm * DPMM);
        p.oy = (int) Math.round((H - reserve - ph) / 2.0 + nudgeYmm * DPMM);
        return p;
    }

    /** Places the drawn piece (cw x ch pixels) on the label; returns packed dots, 1 = black. */
    public static byte[] compose(int[] piece, Plan p, int threshold) {
        byte[] bits = new byte[BPR * H];
        boolean turned = p.rot == 90;
        int pw = turned ? p.ch : p.cw, ph = turned ? p.cw : p.ch;
        for (int y = 0; y < ph; y++) {
            int ly = p.oy + y;
            if (ly < 0 || ly >= H) continue;
            for (int x = 0; x < pw; x++) {
                int lx = p.ox + x;
                if (lx < 0 || lx >= W) continue;
                // Turned right (clockwise): label (x, y) shows piece (y, ch-1-x).
                int c = turned ? piece[(p.ch - 1 - x) * p.cw + y] : piece[y * p.cw + x];
                if (lum(c) < threshold) bits[ly * BPR + (lx >> 3)] |= (byte) (0x80 >> (lx & 7));
            }
        }
        return bits;
    }

    /** Plain picture (e.g. a test label drawn on the device) the size of the label. */
    public static byte[] fromLabelPixels(int[] px, int threshold) {
        byte[] bits = new byte[BPR * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
            if (lum(px[y * W + x]) < threshold) bits[y * BPR + (x >> 3)] |= (byte) (0x80 >> (x & 7));
        return bits;
    }
}
