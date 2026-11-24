package io.github.vertango.labelprinter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Reads ZPL (the Zebra label printer language some couriers' labels come in) into a list of
 * things to draw: boxes, text, pictures and barcodes. Covers the commands shipping labels use.
 * Pure Java; drawing is done by ZplRender (Android) and tested off the device.
 */
public final class Zpl {
    private Zpl() {}

    public static final class Op {
        /** 'B' box, 'T' text, 'G' picture, barcodes: 'C' Code 128, '3' Code 39, 'P' PDF417, 'Q' QR, 'X' Data Matrix. */
        public char kind;
        public int x, y;            // field origin in dots (top-left; baseline/bottom when ft)
        public boolean ft;          // placed with ^FT
        public int w, h, t = 1;     // box size and line thickness
        public boolean white;       // white box (erases)
        public String text = "";    // text or barcode data
        public int fontH = 30, fontW;
        public int blockW, lines = 1, lineGap; public char just = 'L'; public boolean block;
        public char rot = 'N';      // N, R (90° clockwise), I (180°), B (270°)
        public byte[] bits; public int bytesPerRow, rows;   // picture, 1 = black
        public int module = 2, barH = 10, security, cols, brows; public boolean line;
    }

    public static final class Label {
        public int width = 812, length = 1218;   // 4x6 at 203 dpi unless ^PW / ^LL say otherwise
        public boolean inverted;
        public final List<Op> ops = new ArrayList<>();
    }

    public static boolean looksLikeZpl(String head) { return head != null && head.contains("^XA"); }

    private static int nextCmd(String s, int from) {
        for (int i = from; i < s.length(); i++) { char c = s.charAt(i); if (c == '^' || c == '~') return i; }
        return s.length();
    }
    private static int num(String[] p, int i, int def) {
        if (i >= p.length) return def;
        try { return (int) Math.round(Double.parseDouble(p[i].trim())); } catch (Exception e) { return def; }
    }
    private static char ch(String[] p, int i, char def) {
        if (i >= p.length || p[i].trim().isEmpty()) return def;
        return Character.toUpperCase(p[i].trim().charAt(0));
    }

    public static List<Label> parse(String src) {
        List<Label> out = new ArrayList<>();
        Label cur = null;
        int lhX = 0, lhY = 0, fx = 0, fy = 0, cfH = 30, cfW = 0, byW = 2, byH = 10;
        boolean ft = false, utf8 = false, fh = false; char fw = 'N', esc = '_';
        int fontH = cfH, fontW = cfW; char fontRot = 0;
        int fbW = 0, fbLines = 1, fbGap = 0; char fbJust = 'L'; boolean fb = false;
        Op bar = null;
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if ((c != '^' && c != '~') || i + 1 >= n) { i++; continue; }
            String cmd = src.substring(i + 1, Math.min(n, i + 3)).toUpperCase(Locale.ROOT);
            if (cmd.equals("FD") || cmd.equals("FV")) {
                int e = src.indexOf("^FS", i + 3);
                if (e < 0) e = n;
                String data = decode(src.substring(i + 3, e), fh, esc, utf8);
                if (cur != null) {
                    Op o = bar != null ? bar : new Op();
                    if (bar == null) {
                        o.kind = 'T'; o.fontH = fontH; o.fontW = fontW; o.rot = fontRot != 0 ? fontRot : fw;
                        o.block = fb; o.blockW = fbW; o.lines = Math.max(1, fbLines); o.lineGap = fbGap; o.just = fbJust;
                    }
                    o.x = fx; o.y = fy; o.ft = ft; o.text = data;
                    if (!data.isEmpty()) cur.ops.add(o);
                }
                // end of field
                bar = null; fb = false; fh = false; fontH = cfH; fontW = cfW; fontRot = 0;
                i = e; continue;
            }
            int e = nextCmd(src, i + 1);
            if (cmd.equals("FX")) { i = e; continue; }
            if (cmd.equals("GF")) { // ^GFa,b,c,d,data — data may contain commas
                String all = src.substring(i + 3, e);
                String[] p = all.split(",", 5);
                if (cur != null && p.length == 5) {
                    Op o = new Op(); o.kind = 'G'; o.x = fx; o.y = fy; o.ft = ft;
                    o.bytesPerRow = Math.max(1, num(p, 3, 1));
                    int total = num(p, 2, 0);
                    o.rows = Math.max(1, (total + o.bytesPerRow - 1) / o.bytesPerRow);
                    o.bits = graphic(p[4], o.bytesPerRow, o.rows);
                    if (o.bits != null) cur.ops.add(o);
                }
                i = e; continue;
            }
            String params;
            if (cmd.length() < 2) { i = e; continue; }
            if (cmd.charAt(0) == 'A' && cmd.charAt(1) != '@') { // ^A<font><orientation>,h,w
                String p0 = src.substring(i + 3, e);
                String[] p = p0.split(",", -1);
                if (p.length > 0 && !p[0].isEmpty()) fontRot = Character.toUpperCase(p[0].charAt(0));
                fontH = num(p, 1, fontH); fontW = num(p, 2, 0);
                i = e; continue;
            }
            params = src.substring(Math.min(i + 3, e), e);
            String[] p = params.split(",", -1);
            switch (cmd) {
                case "XA": cur = new Label(); break;
                case "XZ":
                    if (cur != null && !cur.ops.isEmpty()) out.add(cur);
                    cur = null; break;
                case "LH": lhX = num(p, 0, 0); lhY = num(p, 1, 0); break;
                case "FO": fx = lhX + num(p, 0, 0); fy = lhY + num(p, 1, 0); ft = false; break;
                case "FT": fx = lhX + num(p, 0, 0); fy = lhY + num(p, 1, 0); ft = true; break;
                case "FW": fw = ch(p, 0, 'N'); break;
                case "CF": cfH = num(p, 1, cfH); cfW = num(p, 2, 0); fontH = cfH; fontW = cfW; break;
                case "CI": utf8 = num(p, 0, 0) == 28; break;
                case "FH": fh = true; esc = params.isEmpty() ? '_' : params.charAt(0); break;
                case "FB": fb = true; fbW = num(p, 0, 0); fbLines = num(p, 1, 1); fbGap = num(p, 2, 0); fbJust = ch(p, 3, 'L'); break;
                case "BY": byW = Math.max(1, num(p, 0, byW)); byH = num(p, 2, byH); break;
                case "PW": if (cur != null) cur.width = num(p, 0, cur.width); break;
                case "LL": if (cur != null) cur.length = num(p, 0, cur.length); break;
                case "PO": if (cur != null) cur.inverted = ch(p, 0, 'N') == 'I'; break;
                case "GB": if (cur != null) {
                    Op o = new Op(); o.kind = 'B'; o.x = fx; o.y = fy; o.ft = ft;
                    o.t = Math.max(1, num(p, 2, 1)); o.w = Math.max(o.t, num(p, 0, o.t)); o.h = Math.max(o.t, num(p, 1, o.t));
                    o.white = ch(p, 3, 'B') == 'W';
                    cur.ops.add(o);
                } break;
                case "BC": bar = barcode('C', p, fw, byW, byH); bar.barH = num(p, 1, byH); bar.line = ch(p, 2, 'Y') == 'Y'; break;
                case "B3": bar = barcode('3', p, fw, byW, byH); bar.barH = num(p, 2, byH); bar.line = ch(p, 3, 'Y') == 'Y'; break;
                case "B7": bar = barcode('P', p, fw, byW, byH); bar.barH = Math.max(1, num(p, 1, byW * 3)); bar.security = num(p, 2, 0);
                    bar.cols = num(p, 3, 0); bar.brows = num(p, 4, 0); break;
                case "BQ": bar = barcode('Q', p, fw, byW, byH); bar.module = Math.max(1, num(p, 2, 3)); break;
                case "BX": bar = barcode('X', p, fw, byW, byH); bar.module = Math.max(1, num(p, 1, byW)); break;
                default: break;
            }
            i = e;
        }
        if (cur != null && !cur.ops.isEmpty()) out.add(cur);
        return out;
    }

    private static Op barcode(char kind, String[] p, char fw, int byW, int byH) {
        Op o = new Op(); o.kind = kind; o.rot = ch(p, 0, fw); o.module = byW; o.barH = byH;
        return o;
    }

    /** Field data with ^FH escapes (_41 = "A") turned into text, as UTF-8 (^CI28) or Latin-1. */
    static String decode(String raw, boolean fh, char esc, boolean utf8) {
        if (!fh) return raw;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int k = 0; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (c == esc && k + 2 < raw.length()) {
                int hi = Character.digit(raw.charAt(k + 1), 16), lo = Character.digit(raw.charAt(k + 2), 16);
                if (hi >= 0 && lo >= 0) { b.write(hi * 16 + lo); k += 2; continue; }
            }
            byte[] cb = String.valueOf(c).getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            b.write(cb, 0, cb.length);
        }
        return new String(b.toByteArray(), utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
    }

    /** ^GF picture data: plain or Zebra-compressed hex, or :Z64:/:B64: base64. Returns rows x bytesPerRow, or null. */
    static byte[] graphic(String data, int bpr, int rows) {
        data = data.trim();
        try {
            if (data.startsWith(":Z64:") || data.startsWith(":B64:")) {
                String b64 = data.substring(5);
                int colon = b64.indexOf(':');
                if (colon >= 0) b64 = b64.substring(0, colon);
                byte[] raw = Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
                if (data.startsWith(":Z64:")) {
                    java.util.zip.Inflater inf = new java.util.zip.Inflater();
                    inf.setInput(raw);
                    byte[] buf = new byte[bpr * rows];
                    int got = 0;
                    while (!inf.finished() && got < buf.length) { int k = inf.inflate(buf, got, buf.length - got); if (k == 0 && inf.needsInput()) break; got += k; }
                    inf.end();
                    return buf;
                }
                byte[] buf = new byte[bpr * rows];
                System.arraycopy(raw, 0, buf, 0, Math.min(raw.length, buf.length));
                return buf;
            }
        } catch (Exception ex) { return null; }
        byte[] out = new byte[bpr * rows];
        StringBuilder row = new StringBuilder();
        String prev = null;
        int r = 0, count = 0, need = bpr * 2;
        for (int k = 0; k < data.length() && r < rows; k++) {
            char c = data.charAt(k);
            if (Character.isWhitespace(c)) continue;
            if (c >= 'G' && c <= 'Y') { count += c - 'F'; continue; }
            if (c >= 'g' && c <= 'z') { count += (c - 'f') * 20; continue; }
            boolean done = false;
            if (c == ',') { while (row.length() < need) row.append('0'); done = true; }
            else if (c == '!') { while (row.length() < need) row.append('F'); done = true; }
            else if (c == ':') { row.setLength(0); row.append(prev != null ? prev : zeros(need)); done = true; }
            else if (Character.digit(c, 16) >= 0) {
                int times = count == 0 ? 1 : count;
                for (int t = 0; t < times && row.length() < need; t++) row.append(c);
                count = 0;
                done = row.length() >= need;
            }
            if (done) {
                String hex = row.toString();
                for (int b = 0; b < bpr; b++) out[r * bpr + b] = (byte) Integer.parseInt(hex.substring(b * 2, b * 2 + 2), 16);
                prev = hex; row.setLength(0); r++; count = 0;
            }
        }
        return out;
    }
    private static String zeros(int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append('0'); return b.toString(); }

    /** Lines of a text field: ^FB wraps to its width (measure gives text width in dots); "\&" breaks a line. */
    public interface Measure { double width(String s); }
    public static List<String> lines(Op o, Measure m) {
        List<String> out = new ArrayList<>();
        if (!o.block) { out.add(o.text); return out; }
        for (String para : o.text.split("\\\\&", -1)) {
            String[] words = para.split(" ", -1);
            StringBuilder line = new StringBuilder();
            for (String w : words) {
                String tryLine = line.length() == 0 ? w : line + " " + w;
                if (o.blockW > 0 && line.length() > 0 && m.width(tryLine) > o.blockW) { out.add(line.toString()); line = new StringBuilder(w); }
                else line = new StringBuilder(tryLine);
            }
            out.add(line.toString());
        }
        while (out.size() > o.lines) {
            // ZPL overwrites extra lines onto the last one; keep the first ones.
            out.remove(out.size() - 1);
        }
        return out;
    }
}
