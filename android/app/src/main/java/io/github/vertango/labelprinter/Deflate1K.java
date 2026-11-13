package io.github.vertango.labelprinter;

import java.io.ByteArrayOutputStream;

/**
 * zlib compression with a 1 KB window, which is what the Jadens printer's BITMAP mode 3 expects
 * (java.util.zip.Deflater always uses a 32 KB window, so it can't be used). Greedy LZ77 with
 * fixed Huffman codes: label pictures are mostly long white runs, which this packs very small.
 */
public final class Deflate1K {
    private Deflate1K() {}

    static final int WINDOW = 1024, MAX_MATCH = 258, MIN_MATCH = 3, MAX_CHAIN = 96;
    private static final int[] LEN_BASE = {3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258};
    private static final int[] LEN_EXTRA = {0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0};
    private static final int[] DIST_BASE = {1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769};
    private static final int[] DIST_EXTRA = {0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8};

    private static final class Bits {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        int acc, n;
        void put(int value, int count) {
            acc |= value << n; n += count;
            while (n >= 8) { out.write(acc & 0xff); acc >>>= 8; n -= 8; }
        }
        void huff(int code, int len) { put(Integer.reverse(code) >>> (32 - len), len); }
        void flush() { if (n > 0) { out.write(acc & 0xff); acc = 0; n = 0; } }
    }

    private static void literal(Bits b, int v) {
        if (v < 144) b.huff(0x30 + v, 8); else b.huff(0x190 + (v - 144), 9);
    }
    private static void symbol(Bits b, int sym) { // 256..287
        if (sym < 280) b.huff(sym - 256, 7); else b.huff(0xc0 + (sym - 280), 8);
    }
    private static void match(Bits b, int len, int dist) {
        int c = 0;
        if (len == 258) c = 28; else while (c + 1 < 28 && LEN_BASE[c + 1] <= len) c++;
        symbol(b, 257 + c);
        if (LEN_EXTRA[c] > 0) b.put(len - LEN_BASE[c], LEN_EXTRA[c]);
        int d = 0;
        while (d + 1 < DIST_BASE.length && DIST_BASE[d + 1] <= dist) d++;
        b.huff(d, 5);
        if (DIST_EXTRA[d] > 0) b.put(dist - DIST_BASE[d], DIST_EXTRA[d]);
    }

    public static byte[] zlib(byte[] data) {
        Bits b = new Bits();
        b.out.write(0x28); // CMF: deflate, 1 KB window
        b.out.write(0x91); // FLG: (0x28*256 + 0x91) % 31 == 0
        b.put(1, 1); b.put(1, 2); // final block, fixed Huffman
        int n = data.length;
        final int HB = 1 << 15;
        int[] head = new int[HB]; java.util.Arrays.fill(head, -1);
        int[] prev = new int[Math.max(1, n)];
        int i = 0;
        while (i < n) {
            int bestLen = 0, bestDist = 0;
            if (i + MIN_MATCH <= n) {
                int h = hash(data, i);
                int cand = head[h], chain = 0;
                int maxLen = Math.min(MAX_MATCH, n - i);
                while (cand >= 0 && i - cand <= WINDOW && chain++ < MAX_CHAIN) {
                    int l = 0;
                    while (l < maxLen && data[cand + l] == data[i + l]) l++;
                    if (l > bestLen) { bestLen = l; bestDist = i - cand; if (l == maxLen) break; }
                    cand = prev[cand];
                }
            }
            int step = bestLen >= MIN_MATCH ? bestLen : 1;
            if (bestLen >= MIN_MATCH) match(b, bestLen, bestDist); else literal(b, data[i] & 0xff);
            for (int k = 0; k < step; k++, i++) {
                if (i + MIN_MATCH <= n) { int h = hash(data, i); prev[i] = head[h]; head[h] = i; }
            }
        }
        symbol(b, 256);
        b.flush();
        long a = adler32(data);
        b.out.write((int) (a >>> 24) & 0xff); b.out.write((int) (a >>> 16) & 0xff);
        b.out.write((int) (a >>> 8) & 0xff); b.out.write((int) a & 0xff);
        return b.out.toByteArray();
    }

    private static int hash(byte[] d, int i) {
        int v = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
        return (v * 0x9E3779B1 >>> 17) & 0x7fff;
    }
    private static long adler32(byte[] d) {
        long a = 1, b = 0;
        for (byte x : d) { a = (a + (x & 0xff)) % 65521; b = (b + a) % 65521; }
        return (b << 16) | a;
    }
}
