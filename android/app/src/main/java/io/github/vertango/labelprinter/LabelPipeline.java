package io.github.vertango.labelprinter;

/** Turns one page into label dots, the same way the web app does. Pure Java (the page drawing is supplied). */
public final class LabelPipeline {
    private LabelPipeline() {}

    /** A page that can draw a region of itself (fractions of the page) into a w x h picture on white. */
    public interface Page {
        double width();   // points
        double height();
        int[] render(double[] region, int w, int h) throws Exception; // ARGB pixels
    }

    public static final class Options {
        public double nudgeXmm = -2, nudgeYmm = 0, marginMm = 1;
        public int threshold = 160;
    }

    public static byte[] layout(Page page, Options o) throws Exception { return layout(page, o, 0); }

    public static byte[] layout(Page page, Options o, int reserve) throws Exception {
        double W = page.width(), H = page.height();
        double[] region = {0, 0, 1, 1};
        if (!LabelRaster.looksLikeLabel(W, H)) {
            // Trim white edges, but only if what's left is a real label (not a line of text).
            double s = 700 / Math.max(W, H);
            int tw = Math.max(1, (int) Math.round(W * s)), th = Math.max(1, (int) Math.round(H * s));
            double[] b = LabelRaster.contentBox(page.render(region, tw, th), tw, th);
            if (b[2] * b[3] >= 0.1) region = b;
        }
        double rw = region[2] * W, rh = region[3] * H;
        LabelRaster.Plan p = LabelRaster.plan(rw, rh, o.nudgeXmm, o.nudgeYmm, o.marginMm, reserve);
        int[] piece = page.render(region, p.cw, p.ch);
        return LabelRaster.compose(piece, p, o.threshold);
    }
}
