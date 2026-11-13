package io.github.vertango.labelprinter;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.Closeable;
import java.io.File;

/** Opens a PDF with Android's built-in renderer and draws regions of its pages. */
public final class PdfPages implements Closeable {
    private final ParcelFileDescriptor fd;
    private final PdfRenderer renderer;

    public PdfPages(File f) throws Exception {
        fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        renderer = new PdfRenderer(fd);
    }
    public int count() { return renderer.getPageCount(); }

    /** Page size in points. */
    public double[] size(int n) {
        try (PdfRenderer.Page pg = renderer.openPage(n)) { return new double[]{pg.getWidth(), pg.getHeight()}; }
    }

    public LabelPipeline.Page page(int n) {
        final double[] sz = size(n);
        return new LabelPipeline.Page() {
            public double width() { return sz[0]; }
            public double height() { return sz[1]; }
            public int[] render(double[] r, int w, int h) {
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.postTranslate((float) (-r[0] * sz[0]), (float) (-r[1] * sz[1]));
                m.postScale((float) (w / (r[2] * sz[0])), (float) (h / (r[3] * sz[1])));
                try (PdfRenderer.Page pg = renderer.openPage(n)) {
                    pg.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                }
                int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                bmp.recycle();
                // The renderer leaves see-through areas; treat them as white paper.
                for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) < 128) px[i] = 0xffffffff;
                return px;
            }
        };
    }

    @Override public void close() {
        try { renderer.close(); } catch (Exception ignored) { }
        try { fd.close(); } catch (Exception ignored) { }
    }
}
