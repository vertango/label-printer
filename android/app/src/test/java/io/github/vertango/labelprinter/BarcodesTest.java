package io.github.vertango.labelprinter;

import static org.junit.Assert.*;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.datamatrix.DataMatrixWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Reads tracking numbers from a synthetic label picture, then matches them like the app does. */
public class BarcodesTest {
    private static void draw(int[] px, int W, BitMatrix m, int x0, int y0, int scale) {
        for (int y = 0; y < m.getHeight(); y++) for (int x = 0; x < m.getWidth(); x++)
            if (m.get(x, y)) for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++)
                px[(y0 + y * scale + dy) * W + x0 + x * scale + dx] = 0xff000000;
    }

    @Test public void readsTrackingFromLabelBarcodes() throws Exception {
        int W = 1600, H = 2400;
        int[] px = new int[W * H];
        Arrays.fill(px, 0xffffffff);
        for (int y = 100; y < 160; y++) for (int x = 100; x < 1500; x++) px[y * W + x] = 0xff000000; // a heading bar
        draw(px, W, new Code128Writer().encode("VU960074844GB", BarcodeFormat.CODE_128, 0, 0), 600, 500, 4);
        // Barcode height: stretch the 1-row matrix down
        for (int y = 501; y < 700; y++) System.arraycopy(px, 500 * W, px, y * W, W);
        draw(px, W, new DataMatrixWriter().encode("JGB 62000000123VU960074844GB0000", BarcodeFormat.DATA_MATRIX, 0, 0), 150, 900, 8);
        List<String> texts = Barcodes.read(px, W, H);
        assertTrue("barcodes read: " + texts, texts.size() >= 1);
        List<String> t = Barcodes.trackingNumbers(texts);
        assertEquals("VU960074844GB", t.get(0));
    }

    @Test public void noBarcodeNoTracking() {
        int[] px = new int[400 * 600];
        Arrays.fill(px, 0xffffffff);
        assertTrue(Barcodes.trackingNumbers(Barcodes.read(px, 400, 600)).isEmpty());
        assertEquals(Arrays.asList("VU960074844GB"), Barcodes.trackingNumbers(Collections.singletonList("vu 960 074 844 gb")));
    }

    @Test public void matchesOutletKeys() {
        String secret = "test-secret-abc";
        Map<String, String> orders = new HashMap<>();
        orders.put(OrderLookup.key(secret, "ebay-tracking", "VU960074844GB"), "696in");
        assertEquals("696in", OrderLookup.stampForTracking(secret, orders, Arrays.asList("XX000000000GB", "VU960074844GB")));
        assertEquals("", OrderLookup.stampForTracking(secret, orders, Collections.singletonList("AB123456789GB")));
    }
}
