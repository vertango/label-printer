package io.github.vertango.labelprinter;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.oned.Code39Writer;
import com.google.zxing.pdf417.encoder.PDF417;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.EnumMap;
import java.util.Map;

/** Makes the barcodes a ZPL label asks for (ZXing). Pure Java. */
public final class ZplBarcodes implements ZplDraw.Modules {
    @Override public boolean[][] make(Zpl.Op o) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 0);
            switch (o.kind) {
                case 'C': return rows(new Code128Writer().encode(code128Text(o.text), BarcodeFormat.CODE_128, 0, 0, hints));
                case '3': return rows(new Code39Writer().encode(o.text, BarcodeFormat.CODE_39, 0, 0, hints));
                case 'Q': {
                    String t = o.text.matches("^[HQML][AM],.*") ? o.text.substring(3) : o.text;
                    return rows(new QRCodeWriter().encode(t, BarcodeFormat.QR_CODE, 0, 0, hints));
                }
                case 'X': return rows(new DataMatrixWriter().encode(o.text, BarcodeFormat.DATA_MATRIX, 0, 0, hints));
                case 'P': {
                    PDF417 p = new PDF417();
                    if (o.cols > 0) p.setDimensions(o.cols, o.cols, 90, 3);
                    p.generateBarcodeLogic(o.text, o.security > 0 ? Math.min(8, o.security) : 2);
                    byte[][] m = p.getBarcodeMatrix().getScaledMatrix(1, 1);
                    boolean[][] out = new boolean[m.length][];
                    for (int r = 0; r < m.length; r++) {
                        out[r] = new boolean[m[r].length];
                        for (int c = 0; c < m[r].length; c++) out[r][c] = m[r][c] == 1;
                    }
                    return out;
                }
                default: return null;
            }
        } catch (Exception e) { return null; }
    }

    /** ZPL Code 128 data can carry subset codes like ">:"; ZXing picks subsets itself. */
    static String code128Text(String t) { return t.replace(">>", "\u0000").replaceAll(">[0-9:;<=8]", "").replace('\u0000', '>'); }

    private static boolean[][] rows(BitMatrix b) {
        int h = b.getHeight(), w = b.getWidth();
        // 1D writers give one row repeated; keep one.
        if (h > 1 && sameRows(b)) h = 1;
        boolean[][] out = new boolean[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out[y][x] = b.get(x, y);
        return out;
    }
    private static boolean sameRows(BitMatrix b) {
        for (int y = 1; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) if (b.get(x, y) != b.get(x, 0)) return false;
        return true;
    }
}
