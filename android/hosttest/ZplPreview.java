import io.github.vertango.labelprinter.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Off-device preview of a ZPL file (Java 2D). Barcodes are drawn as grey boxes here. */
public class ZplPreview {
    public static void main(String[] a) throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(a[0])), "ISO-8859-1");
        List<Zpl.Label> labels = Zpl.parse(src);
        System.out.println(labels.size() + " label(s)");
        int k = 0;
        for (Zpl.Label l : labels) {
            BufferedImage img = new BufferedImage(l.width, l.length, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, l.width, l.length);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Deque<AffineTransform> stack = new ArrayDeque<>();
            ZplDraw.Surface s = new ZplDraw.Surface() {
                Font font(double size, double sx) { return new Font("SansSerif", Font.BOLD, 1).deriveFont(AffineTransform.getScaleInstance(size * 0.9 * sx, size * 0.9)); }
                public void save() { stack.push(g.getTransform()); }
                public void restore() { g.setTransform(stack.pop()); }
                public void translate(double x, double y) { g.translate(x, y); }
                public void rotate(int d) { g.rotate(Math.toRadians(d)); }
                public void rect(double x, double y, double w, double h, boolean white) { g.setColor(white ? Color.WHITE : Color.BLACK); g.fill(new java.awt.geom.Rectangle2D.Double(x, y, w, h)); }
                public double textWidth(String t, double size, double sx) { return g.getFontMetrics(font(size, sx)).stringWidth(t); }
                public double ascent(double size) { return size * 0.75; }
                public void text(String t, double x, double base, double size, double sx) { g.setColor(Color.BLACK); g.setFont(font(size, sx)); g.drawString(t, (float) x, (float) base); }
            };
            ZplDraw.Modules grey = o -> {
                int n = o.kind == 'P' ? 30 : o.kind == 'C' || o.kind == '3' ? (o.text.length() * 11 + 35) / 2 : 25;
                boolean[][] m = new boolean[o.kind == 'P' ? Math.max(3, o.brows) : o.kind == 'C' || o.kind == '3' ? 1 : n][n * (o.kind == 'P' ? 6 : 1)];
                for (boolean[] r : m) for (int i = 0; i < r.length; i += 2) r[i] = true;
                return m;
            };
            ZplDraw.render(l, s, grey);
            ImageIO.write(img, "png", new java.io.File(a[1] + "_" + (++k) + ".png"));
        }
    }
}
