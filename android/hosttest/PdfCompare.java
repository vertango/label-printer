import io.github.vertango.labelprinter.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Lays out every page of a PDF with LabelPipeline (pages drawn by pdftoppm) and writes the printer job. */
public class PdfCompare {
    public static void main(String[] a) throws Exception {
        String pdf = a[0]; String out = a[1];
        // page sizes from pdfinfo
        Process pi = new ProcessBuilder("pdfinfo", "-f", "1", "-l", "99", pdf).redirectErrorStream(true).start();
        java.util.List<double[]> sizes = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(pi.getInputStream()))) {
            String line; java.util.regex.Pattern pat = java.util.regex.Pattern.compile("Page\\s+\\d+ size:\\s+([\\d.]+) x ([\\d.]+)");
            while ((line = r.readLine()) != null) { var m = pat.matcher(line); if (m.find()) sizes.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))}); }
        }
        ByteArrayOutputStream job = new ByteArrayOutputStream();
        for (int n = 0; n < sizes.size(); n++) {
            final int pageNo = n + 1; final double[] sz = sizes.get(n);
            LabelPipeline.Page page = new LabelPipeline.Page() {
                public double width() { return sz[0]; }
                public double height() { return sz[1]; }
                public int[] render(double[] reg, int w, int h) throws Exception {
                    // draw the whole page big enough, then crop + scale (like the Android version)
                    double sx = w / (reg[2] * sz[0]), sy = h / (reg[3] * sz[1]);
                    int dpi = (int) Math.ceil(Math.max(sx, sy) * 72);
                    Path tmp = Files.createTempFile("pg", "");
                    new ProcessBuilder("pdftoppm", "-r", String.valueOf(dpi), "-f", "" + pageNo, "-l", "" + pageNo, "-png", "-singlefile", pdf, tmp.toString()).inheritIO().start().waitFor();
                    BufferedImage full = ImageIO.read(new File(tmp + ".png"));
                    BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    var g = o.createGraphics(); g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, w, h);
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    int x0 = (int) Math.round(reg[0] * full.getWidth()), y0 = (int) Math.round(reg[1] * full.getHeight());
                    int x1 = (int) Math.round((reg[0] + reg[2]) * full.getWidth()), y1 = (int) Math.round((reg[1] + reg[3]) * full.getHeight());
                    g.drawImage(full, 0, 0, w, h, x0, y0, x1, y1, null); g.dispose();
                    return o.getRGB(0, 0, w, h, null, 0, w);
                }
            };
            byte[] bits = LabelPipeline.layout(page, new LabelPipeline.Options());
            job.write(Tspl.jadensJob(bits, 5, 4, 1, false));
        }
        Files.write(Paths.get(out), job.toByteArray());
        System.out.println(pdf + ": " + sizes.size() + " page(s), " + job.size() + " bytes");
    }
}
