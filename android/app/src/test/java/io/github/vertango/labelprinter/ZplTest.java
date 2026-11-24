package io.github.vertango.labelprinter;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** ZPL reading and its barcodes (synthetic labels only). */
public class ZplTest {
    static final String LABEL = "^FX test\n^XA^MCY^XZ\n^XA\n^LH0,0\n"
            + "^FO51,744^GB414,271,1,B,0^FS\n"
            + "^CI28^FO514,30^A0,27,0^FB271,1,0,C,0^FH^FD_4D_61_72_6B_65_74^FS^CI0\n"           // "Market"
            + "^CI28^FWB^FO57,582^A0,20,0^FB135,1,0,L,0^FH^FD_52_65_74^FS^FWN^CI0\n"           // "Ret", turned
            + "^CI28^FO61,750^A0,17,0^FB406,8,0,L,0^FH^FD_41_42\\&_43_44^FS^CI0\n"             // "AB" / "CD"
            + "^FO575,68^GFA,8,8,2, ,:G0FGF0!^FS\n"                                            // 4 rows x 2 bytes
            + "^BY3,,155^FO98,1029^BCN,155,N,N,N,A^FH^FD_54_30_30_54_4C_41_36_35_32_35^FS\n"   // T00TLA6525
            + "^BY3,,7^FO56,163^B7N,7,4,10,35,^FD[)1.3++T00TLA6525++TEST^FS\n"
            + "^XZ\n";

    @Test public void readsLabel() {
        List<Zpl.Label> labels = Zpl.parse(LABEL);
        assertEquals("the empty ^XA^MCY^XZ isn't a label", 1, labels.size());
        Zpl.Label l = labels.get(0);
        assertEquals(812, l.width); assertEquals(1218, l.length);
        List<Character> kinds = new ArrayList<>();
        for (Zpl.Op o : l.ops) kinds.add(o.kind);
        assertEquals("[B, T, T, T, G, C, P]", kinds.toString());
        Zpl.Op market = l.ops.get(1);
        assertEquals("Market", market.text); assertEquals(27, market.fontH); assertEquals('C', market.just); assertEquals('N', market.rot);
        assertEquals('B', l.ops.get(2).rot);
        assertEquals("[AB, CD]", Zpl.lines(l.ops.get(3), s -> s.length() * 10).toString());
        Zpl.Op g = l.ops.get(4);
        assertEquals(4, g.rows); assertEquals(2, g.bytesPerRow);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0x0f, (byte) 0xf0, (byte) 0xff, (byte) 0xff}, g.bits);
        Zpl.Op bc = l.ops.get(5);
        assertEquals("T00TLA6525", bc.text); assertEquals(155, bc.barH); assertEquals(3, bc.module); assertFalse(bc.line);
        Zpl.Op pdf = l.ops.get(6);
        assertEquals(7, pdf.barH); assertEquals(4, pdf.security); assertEquals(10, pdf.cols);
    }

    @Test public void barcodesScanBack() {
        List<Zpl.Label> labels = Zpl.parse(LABEL);
        ZplBarcodes z = new ZplBarcodes();
        for (Zpl.Op o : labels.get(0).ops) {
            if (o.kind != 'C' && o.kind != 'P') continue;
            boolean[][] m = z.make(o);
            assertNotNull(m);
            int rowH = o.kind == 'C' ? 60 : 9, mod = 3, W = m[0].length * mod + 80, H = (o.kind == 'C' ? 1 : m.length) * rowH + 80;
            int[] px = new int[W * H];
            java.util.Arrays.fill(px, 0xffffffff);
            for (int r = 0; r < (o.kind == 'C' ? 1 : m.length); r++)
                for (int c = 0; c < m[r].length; c++) if (m[r][c])
                    for (int y = 0; y < rowH; y++) for (int x = 0; x < mod; x++) px[(40 + r * rowH + y) * W + 40 + c * mod + x] = 0xff000000;
            List<String> read = Barcodes.read(px, W, H);
            assertTrue(o.kind + " read back: " + read, read.contains(o.text));
        }
    }

    @Test public void compressedPictures() {
        // G=1 ... Y=19, g=20: "gH0" = 22 zeros; ',' fills the row with 0, '!' with 1, ':' repeats the row above
        byte[] b = Zpl.graphic("hF,:!", 20, 4);
        for (int i = 0; i < 20; i++) assertEquals((byte) 0xff, b[i]);       // 40 x F
        for (int i = 20; i < 40; i++) assertEquals(0, b[i]);
        for (int i = 40; i < 60; i++) assertEquals(0, b[i]);
        for (int i = 60; i < 80; i++) assertEquals((byte) 0xff, b[i]);
        assertEquals("Café", Zpl.decode("Caf_C3_A9", true, '_', true));
        assertEquals("A>B", ZplBarcodes.code128Text(">:A>>B"));
    }
}
