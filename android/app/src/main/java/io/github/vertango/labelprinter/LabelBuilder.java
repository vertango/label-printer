package io.github.vertango.labelprinter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a downloaded PDF into printer jobs. When the download has both postage labels and
 * eBay packing slips, only the labels print, each with its slip's SKU in a blank space.
 */
public final class LabelBuilder {
    public static final class Result {
        public final List<byte[]> jobs = new ArrayList<>();
        public final List<String> stamps = new ArrayList<>();
        public int slipsSkipped;
    }

    private static boolean pdfboxReady;

    /** Text of each page ("" for pages that are pictures, or if it can't be read). */
    static String[] pageTexts(Context c, File f, int pages) {
        String[] out = new String[pages];
        java.util.Arrays.fill(out, "");
        try {
            if (!pdfboxReady) { PDFBoxResourceLoader.init(c.getApplicationContext()); pdfboxReady = true; }
            try (PDDocument doc = PDDocument.load(f)) {
                PDFTextStripper st = new PDFTextStripper();
                for (int i = 0; i < Math.min(pages, doc.getNumberOfPages()); i++) {
                    st.setStartPage(i + 1); st.setEndPage(i + 1);
                    out[i] = st.getText(doc);
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** decide(n, labelSized) says whether page n would print under the user's rule. */
    public interface Rule { boolean prints(int page, boolean labelSized); }

    public static Result build(Context c, File f, PdfPages pdf, Prefs prefs, Rule rule) throws Exception {
        Result r = new Result();
        int pages = pdf.count();
        String[] text = pageTexts(c, f, pages);
        List<Integer> labels = new ArrayList<>(), slips = new ArrayList<>();
        for (int n = 0; n < pages; n++) {
            if (SkuMatcher.isSlip(text[n])) slips.add(n); else labels.add(n);
        }
        // Labels + slips in one download: print just the labels. Slips on their own still print.
        boolean pair = !labels.isEmpty() && !slips.isEmpty();
        SkuMatcher matcher = pair && prefs.skuStamp() ? SkuList.matcher(c) : null;
        boolean onlyLabels = pair && prefs.labelsOnly();
        List<Integer> toPrint = onlyLabels ? labels : allPages(pages);
        if (onlyLabels) r.slipsSkipped = slips.size();
        java.util.Map<Integer, Integer> labelIndex = new java.util.HashMap<>();
        for (int i = 0; i < labels.size(); i++) labelIndex.put(labels.get(i), i);
        LabelPipeline.Options o = prefs.options();
        for (int k = 0; k < toPrint.size(); k++) {
            int n = toPrint.get(k);
            double[] sz = pdf.size(n);
            if (!rule.prints(n, LabelRaster.looksLikeLabel(sz[0], sz[1]))) continue;
            String stamp = "";
            Integer li = labelIndex.get(n);
            if (matcher != null && li != null) {
                int s = slips.size() == 1 ? slips.get(0) : (li < slips.size() ? slips.get(li) : -1);
                if (s >= 0) stamp = matcher.stampFor(text[s]);
            }
            byte[] bits = LabelPipeline.layout(pdf.page(n), o);
            if (!stamp.isEmpty()) bits = stamp(pdf.page(n), o, bits, stamp);
            r.jobs.add(Tspl.jadensJob(bits, prefs.density(), prefs.speed(), 1, false));
            if (pair && li != null) r.stamps.add(stamp);
        }
        return r;
    }

    private static List<Integer> allPages(int n) { List<Integer> l = new ArrayList<>(); for (int i = 0; i < n; i++) l.add(i); return l; }

    private static Paint paint(float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(size); p.setColor(Color.BLACK);
        return p;
    }

    /** Prints the SKU in a blank space, or in a strip under a slightly smaller label. */
    static byte[] stamp(LabelPipeline.Page page, LabelPipeline.Options o, byte[] bits, String text) throws Exception {
        double w100 = paint(100).measureText(text);
        int[] box = SkuStamp.findBlankSpot(bits, w100);
        if (box == null) {
            bits = LabelPipeline.layout(page, o, SkuStamp.STRIP);
            box = SkuStamp.stripBox(w100, o);
        }
        int font = box[4];
        Paint p = paint(font);
        int tw = (int) Math.ceil(p.measureText(text)), bw = tw + 24, bh = Math.round(font * 1.3f);
        int bx = Math.round(box[0] + (box[2] - bw) / 2f), by = Math.round(box[1] + (box[3] - bh) / 2f);
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.WHITE);
        Paint frame = new Paint(); frame.setStyle(Paint.Style.STROKE); frame.setStrokeWidth(3); frame.setColor(Color.BLACK);
        cv.drawRect(1.5f, 1.5f, bw - 1.5f, bh - 1.5f, frame);
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(text, 12, bh / 2f - (fm.ascent + fm.descent) / 2f, p);
        int[] px = new int[bw * bh];
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
        bmp.recycle();
        int bpr = LabelRaster.BPR;
        for (int y = 0; y < bh; y++) {
            int ly = by + y; if (ly < 0 || ly >= LabelRaster.H) continue;
            for (int x = 0; x < bw; x++) {
                int lx = bx + x; if (lx < 0 || lx >= LabelRaster.W) continue;
                int i = ly * bpr + (lx >> 3), bit = 0x80 >> (lx & 7);
                if (LabelRaster.lum(px[y * bw + x]) < 160) bits[i] |= (byte) bit; else bits[i] &= (byte) ~bit;
            }
        }
        return bits;
    }
}
