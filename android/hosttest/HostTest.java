import io.github.vertango.labelprinter.*;
import java.util.zip.Inflater;
import java.util.Random;
import java.nio.file.*;

/** Off-device checks for the pure-Java parts. Run: see android/hosttest/run.sh */
public class HostTest {
    static byte[] inflate(byte[] z, int n) throws Exception {
        Inflater inf = new Inflater(); inf.setInput(z); byte[] out = new byte[n + 10];
        int got = inf.inflate(out); if (!inf.finished()) throw new AssertionError("not finished");
        return java.util.Arrays.copyOf(out, got);
    }
    public static void main(String[] a) throws Exception {
        Random r = new Random(1);
        // 1. Round trips: random, empty, all-white, label-like
        byte[][] cases = new byte[5][];
        cases[0] = new byte[0];
        cases[1] = new byte[120000];
        cases[2] = new byte[5000]; r.nextBytes(cases[2]);
        cases[3] = new byte[120000];
        for (int y = 0; y < 1200; y++) for (int c = 0; c < 100; c++) if ((y / 40 + c / 7) % 3 == 0 && r.nextInt(5) > 0) cases[3][y * 100 + c] = (byte) r.nextInt(256);
        cases[4] = new byte[3000]; for (int i = 0; i < 3000; i++) cases[4][i] = (byte) (i % 1100 < 600 ? 0xff : i);
        for (byte[] c : cases) {
            byte[] z = Deflate1K.zlib(c);
            if ((z[0] & 0xff) != 0x28) throw new AssertionError("CMF");
            if (((z[0] & 0xff) * 256 + (z[1] & 0xff)) % 31 != 0) throw new AssertionError("FCHECK");
            byte[] back = inflate(z, c.length);
            if (!java.util.Arrays.equals(back, c)) throw new AssertionError("round trip len " + c.length);
            System.out.println("ok " + c.length + " -> " + z.length);
        }
        // 2. Layout: A4 portrait page with a centred block; landscape 6x4 turns.
        LabelRaster.Plan p = LabelRaster.plan(432, 288, -2, 0, 1);
        if (p.rot != 90) throw new AssertionError("landscape should turn");
        LabelRaster.Plan q = LabelRaster.plan(288, 432, -2, 0, 1);
        System.out.println("6x4 plan rot=" + p.rot + " " + p.cw + "x" + p.ch + " at " + p.ox + "," + p.oy + "; 4x6 " + q.cw + "x" + q.ch + " at " + q.ox + "," + q.oy);
        if (!LabelRaster.looksLikeLabel(288, 432) || LabelRaster.looksLikeLabel(595, 842) || LabelRaster.looksLikeLabel(612, 792)) throw new AssertionError("looksLikeLabel");
        if (!LabelRaster.looksLikeLabel(420, 595) || !LabelRaster.looksLikeLabel(298, 420) || !LabelRaster.looksLikeLabel(595, 420)) throw new AssertionError("A5/A6 labels");
        // 3. A test label from a picture: arrow pointing up on a landscape piece, check turned right.
        int cw = p.cw, ch = p.ch; int[] piece = new int[cw * ch]; java.util.Arrays.fill(piece, 0xffffffff);
        for (int y = 0; y < ch / 4; y++) for (int x = 0; x < cw; x++) piece[y * cw + x] = 0xff000000; // black top band
        byte[] bits = LabelRaster.compose(piece, p, 160);
        // turned clockwise: top band of the piece ends up on the label's right side
        int right = 0, left = 0;
        for (int y = 300; y < 900; y++) { if ((bits[y * 100 + 90] & 0xff) != 0) right++; if ((bits[y * 100 + 5] & 0xff) != 0) left++; }
        System.out.println("turned: right " + right + " left " + left);
        if (right < 500 || left > 0) throw new AssertionError("rotation direction");
        byte[] job = Tspl.jadensJob(bits, 5, 4, 1, false);
        Files.write(Paths.get(a.length > 0 ? a[0] : "/tmp/native_job.bin"), job);
        System.out.println("job " + job.length + " bytes");
    }
}
