package io.github.vertango.labelprinter;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the barcodes on a label picture and finds the tracking number in them
 * (Royal Mail / eBay labels: two letters, nine digits, two letters, e.g. VU960074844GB).
 * Pure Java (ZXing), so it can be tested off the device.
 */
public final class Barcodes {
    private Barcodes() {}

    private static final Pattern S10 = Pattern.compile("[A-Z]{2}\\d{9}[A-Z]{2}");
    private static final Pattern PLAIN = Pattern.compile("[A-Z0-9]{8,30}");

    /** Text of every barcode found in the picture (ARGB pixels). */
    public static List<String> read(int[] argb, int w, int h) {
        List<String> out = new ArrayList<>();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                BarcodeFormat.DATA_MATRIX, BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417));
        try {
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, argb)));
            Result[] found = new GenericMultipleBarcodeReader(new MultiFormatReader()).decodeMultiple(bmp, hints);
            for (Result r : found) if (r.getText() != null) out.add(r.getText());
        } catch (Exception ignored) { /* no barcode found */ }
        return out;
    }

    /** Tracking numbers in the barcode texts, most likely first. */
    public static List<String> trackingNumbers(List<String> texts) {
        Set<String> s10 = new LinkedHashSet<>(), plain = new LinkedHashSet<>();
        for (String t : texts) {
            String u = t.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            Matcher m = S10.matcher(u);
            while (m.find()) s10.add(m.group());
            if (PLAIN.matcher(u).matches()) plain.add(u);
        }
        List<String> out = new ArrayList<>(s10);
        for (String p : plain) if (!out.contains(p)) out.add(p);
        return out;
    }
}
