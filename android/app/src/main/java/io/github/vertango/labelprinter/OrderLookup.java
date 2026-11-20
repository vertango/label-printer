package io.github.vertango.labelprinter;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SKU by eBay order number, from Outlet Analytics. Pure Java (no Android).
 * The label's file name carries the order number ("eBay label 25-15411-62068.pdf").
 * Outlet sends a list of printer keys -> SKU text; a printer key is an HMAC of the
 * order number with the secret in the printer code, so the list holds no order
 * numbers, names or addresses. Must match Outlet's lib/label-lookup.mjs.
 */
public final class OrderLookup {
    private OrderLookup() {}

    public static final String CODE_PREFIX = "OUTLET-LP1.";
    private static final Pattern EBAY_ORDER = Pattern.compile("(?<!\\d)(\\d{2}-\\d{5}-\\d{5})(?!\\d)");

    /** The relay address and secret from a printer code, or null if it isn't one. */
    public static String[] parseCode(String code) {
        try {
            String c = code == null ? "" : code.trim();
            if (!c.startsWith(CODE_PREFIX)) return null;
            String json = new String(b64url(c.substring(CODE_PREFIX.length())), StandardCharsets.UTF_8);
            String u = field(json, "u"), s = field(json, "s");
            if (u == null || s == null || !u.matches("https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]{20,}/exec") || !s.matches("[a-f0-9]{32,128}")) return null;
            return new String[]{u, s};
        } catch (Exception e) { return null; }
    }
    /** Base64url decoding (java.util.Base64 needs a newer Android). */
    static byte[] b64url(String s) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char ch : s.replace("=", "").toCharArray()) {
            int v = a.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("not base64url");
            buf = (buf << 6) | v; bits += 6;
            if (bits >= 8) { bits -= 8; out.write((buf >> bits) & 0xff); }
        }
        return out.toByteArray();
    }
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** The eBay order number in a file name, or null. */
    public static String ebayOrderFrom(String fileName) {
        if (fileName == null) return null;
        Matcher m = EBAY_ORDER.matcher(fileName);
        return m.find() ? m.group(1) : null;
    }

    static String hmacHex(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] b = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Printer key for an order: same as Outlet's labelKey(secret, channel, orderNo). */
    public static String key(String secret, String channel, String orderNo) {
        return hmacHex(secret, "label/" + channel + ":" + orderNo.trim().toUpperCase(Locale.ROOT)).substring(0, 32);
    }
    public static String readToken(String secret) { return hmacHex(secret, "label/relay-read").substring(0, 32); }

    /** SKU text for a label by its tracking numbers (read from the barcode), or "". */
    public static String stampForTracking(String secret, Map<String, String> orders, java.util.List<String> tracking) {
        if (secret == null || orders == null || tracking == null) return "";
        for (String t : tracking) {
            String s = orders.get(key(secret, "ebay-tracking", t.replaceAll("\\s+", "")));
            if (s != null) return s;
        }
        return "";
    }

    /** SKU text for a label file, or "" if the name has no order number or the order isn't listed. */
    public static String stampFor(String fileName, String secret, Map<String, String> orders) {
        String no = ebayOrderFrom(fileName);
        if (no == null || secret == null || orders == null) return "";
        String s = orders.get(key(secret, "ebay", no));
        return s == null ? "" : s;
    }
}
