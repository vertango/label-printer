package io.github.vertango.labelprinter;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

/** A ZPL label drawn on an Android canvas at its own size (dots), usable as a page for layout. */
public final class ZplPage implements LabelPipeline.Page {
    private final Bitmap img;

    public ZplPage(Zpl.Label label) {
        img = Bitmap.createBitmap(Math.max(1, label.width), Math.max(1, label.length), Bitmap.Config.ARGB_8888);
        final Canvas cv = new Canvas(img);
        cv.drawColor(Color.WHITE);
        final Paint fill = new Paint();
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        text.setColor(Color.BLACK);
        ZplDraw.Surface s = new ZplDraw.Surface() {
            public void save() { cv.save(); }
            public void restore() { cv.restore(); }
            public void translate(double x, double y) { cv.translate((float) x, (float) y); }
            public void rotate(int d) { cv.rotate(d); }
            public void rect(double x, double y, double w, double h, boolean white) {
                fill.setColor(white ? Color.WHITE : Color.BLACK);
                cv.drawRect((float) x, (float) y, (float) (x + w), (float) (y + h), fill);
            }
            private void size(double size, double sx) { text.setTextSize((float) size); text.setTextScaleX((float) sx); }
            public double textWidth(String t, double size, double sx) { size(size, sx); return text.measureText(t); }
            public double ascent(double size) { size(size, 1); return -text.getFontMetrics().ascent * 0.92; }
            public void text(String t, double x, double base, double size, double sx) { size(size, sx); cv.drawText(t, (float) x, (float) base, text); }
        };
        ZplDraw.render(label, s, new ZplBarcodes());
    }

    @Override public double width() { return img.getWidth(); }
    @Override public double height() { return img.getHeight(); }
    @Override public int[] render(double[] r, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(Color.WHITE);
        Rect from = new Rect((int) Math.round(r[0] * img.getWidth()), (int) Math.round(r[1] * img.getHeight()),
                (int) Math.round((r[0] + r[2]) * img.getWidth()), (int) Math.round((r[1] + r[3]) * img.getHeight()));
        cv.drawBitmap(img, from, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        out.recycle();
        return px;
    }
    public void recycle() { img.recycle(); }
}
