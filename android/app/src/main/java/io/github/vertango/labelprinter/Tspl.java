package io.github.vertango.labelprinter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Printer commands, exactly as the Jadens app sends one label (from its Bluetooth traffic). */
public final class Tspl {
    private Tspl() {}

    public static byte[] jadensJob(byte[] bits, int density, int speed, int copies, boolean flip) {
        byte[] z = Deflate1K.zlib(bits);
        ByteArrayOutputStream o = new ByteArrayOutputStream(z.length + 256);
        put(o, "SIZE 100 mm,150 mm\r\nDIRECTION " + (flip ? 1 : 0) + ",0\r\nSET GAP ON\r\nCLS\r\nSETCOLOR BLACK\r\n");
        put(o, "BITMAP 0,0," + LabelRaster.BPR + "," + LabelRaster.H + ",3," + z.length + ",");
        o.write(z, 0, z.length);
        put(o, "\r\n");
        put(o, "\r\nSPEED " + clamp(speed, 1, 6) + "\r\nDENSITY " + clamp(density, 0, 15) + "\r\nPRINT 1," + Math.max(1, copies) + "\r\n");
        return o.toByteArray();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static void put(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII); o.write(b, 0, b.length);
    }
}
