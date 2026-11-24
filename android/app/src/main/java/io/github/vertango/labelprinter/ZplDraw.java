package io.github.vertango.labelprinter;

import java.util.List;

/** Draws a ZPL label onto any surface (Android canvas on the device, Java 2D in tests). Pure Java. */
public final class ZplDraw {
    private ZplDraw() {}

    public interface Surface {
        void save(); void restore(); void translate(double x, double y); void rotate(int degreesClockwise);
        void rect(double x, double y, double w, double h, boolean white);
        double textWidth(String s, double size, double scaleX);
        double ascent(double size);
        void text(String s, double x, double baseline, double size, double scaleX);
    }
    /** Barcode modules (rows x columns, true = black), or null if it can't be made. */
    public interface Modules { boolean[][] make(Zpl.Op o); }

    public static void render(Zpl.Label label, Surface s, Modules modules) {
        s.save();
        if (label.inverted) { s.translate(label.width, label.length); s.rotate(180); }
        for (Zpl.Op o : label.ops) {
            switch (o.kind) {
                case 'B': box(o, s); break;
                case 'G': picture(o, s); break;
                case 'T': text(o, s); break;
                default: barcode(o, s, modules); break;
            }
        }
        s.restore();
    }

    private static void box(Zpl.Op o, Surface s) {
        int t = o.t, w = o.w, h = o.h, x = o.x, y = o.ft ? o.y - h : o.y;
        if (t * 2 >= w || t * 2 >= h) { s.rect(x, y, w, h, o.white); return; }
        s.rect(x, y, w, t, o.white); s.rect(x, y + h - t, w, t, o.white);
        s.rect(x, y, t, h, o.white); s.rect(x + w - t, y, t, h, o.white);
    }

    private static void picture(Zpl.Op o, Surface s) {
        int y0 = o.ft ? o.y - o.rows : o.y;
        for (int r = 0; r < o.rows; r++) {
            int run = -1;
            for (int b = 0; b <= o.bytesPerRow * 8; b++) {
                boolean on = b < o.bytesPerRow * 8 && (o.bits[r * o.bytesPerRow + (b >> 3)] & (0x80 >> (b & 7))) != 0;
                if (on && run < 0) run = b;
                if (!on && run >= 0) { s.rect(o.x + run, y0 + r, b - run, 1, false); run = -1; }
            }
        }
    }

    /** Places a W x H field (as drawn upright) at its origin with its rotation, then runs draw at 0,0. */
    private static void placed(Zpl.Op o, Surface s, double W, double H, Runnable draw) {
        double x = o.x, y = o.y;
        s.save();
        switch (o.rot) {
            case 'R': s.translate(x + H, y); s.rotate(90); break;
            case 'I': s.translate(x + W, y + H); s.rotate(180); break;
            case 'B': s.translate(x, y + W); s.rotate(270); break;
            default: s.translate(x, y); break;
        }
        draw.run();
        s.restore();
    }

    private static void text(Zpl.Op o, Surface s) {
        double size = o.fontH, sx = o.fontW > 0 ? Math.max(0.3, Math.min(3, o.fontW / (double) o.fontH)) : 1;
        List<String> lines = Zpl.lines(o, t -> s.textWidth(t, size, sx));
        double widest = 0;
        for (String l : lines) widest = Math.max(widest, s.textWidth(l, size, sx));
        double W = o.block && o.blockW > 0 ? o.blockW : widest, lineH = o.fontH + o.lineGap, H = lines.size() * lineH;
        double asc = s.ascent(size);
        Zpl.Op at = o;
        if (o.ft) { at = copyAt(o, o.x, (int) Math.round(o.y - asc - (lines.size() - 1) * lineH)); }
        placed(at, s, W, H, () -> {
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                double lw = s.textWidth(l, size, sx), dx = o.just == 'C' ? (W - lw) / 2 : o.just == 'R' ? W - lw : 0;
                s.text(l, dx, i * lineH + asc, size, sx);
            }
        });
    }

    private static void barcode(Zpl.Op o, Surface s, Modules modules) {
        boolean[][] m = modules == null ? null : modules.make(o);
        if (m == null || m.length == 0) return;
        boolean oneD = o.kind == 'C' || o.kind == '3';
        int cols = m[0].length, rows = m.length;
        double mw = o.module, rh = oneD ? o.barH : (o.kind == 'P' ? o.barH : o.module);
        double W = cols * mw, barsH = oneD ? o.barH : rows * rh;
        double lineSize = Math.max(14, o.module * 9.0);
        double H = barsH + (oneD && o.line ? lineSize + 4 : 0);
        Zpl.Op at = o.ft ? copyAt(o, o.x, (int) Math.round(o.y - barsH)) : o;
        placed(at, s, W, H, () -> {
            for (int r = 0; r < (oneD ? 1 : rows); r++) {
                int run = -1;
                for (int c = 0; c <= cols; c++) {
                    boolean on = c < cols && m[r][c];
                    if (on && run < 0) run = c;
                    if (!on && run >= 0) { s.rect(run * mw, r * rh, (c - run) * mw, oneD ? barsH : rh, false); run = -1; }
                }
            }
            if (oneD && o.line) {
                double tw = s.textWidth(o.text, lineSize, 1);
                s.text(o.text, (W - tw) / 2, barsH + 2 + s.ascent(lineSize), lineSize, 1);
            }
        });
    }

    private static Zpl.Op copyAt(Zpl.Op o, int x, int y) {
        Zpl.Op c = new Zpl.Op();
        c.kind = o.kind; c.x = x; c.y = y; c.rot = o.rot;
        return c;
    }
}
