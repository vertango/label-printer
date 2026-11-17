package io.github.vertango.labelprinter;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;

/**
 * The Outlet link on the device: the printer code (relay + secret) and the latest
 * order list, kept in the app's own storage. Lookups never block printing: if the
 * list can't be fetched, the cached one is used, and a label with no match prints as it is.
 */
public final class OrderList {
    private OrderList() {}
    private static final long FRESH_MS = 2 * 60 * 1000;
    private static SharedPreferences sp(Context c) { return c.getSharedPreferences("outletlink", Context.MODE_PRIVATE); }
    private static File file(Context c) { return new File(c.getFilesDir(), "order-list.json"); }

    /** Saves a pasted printer code. Returns false if it isn't one. */
    public static boolean saveCode(Context c, String code) {
        String[] p = OrderLookup.parseCode(code);
        if (p == null) return false;
        sp(c).edit().putString("relay", p[0]).putString("secret", p[1]).putLong("fetched", 0).apply();
        file(c).delete();
        return true;
    }
    public static boolean linked(Context c) { return sp(c).getString("secret", null) != null; }
    public static void unlink(Context c) { sp(c).edit().clear().apply(); file(c).delete(); }

    /** Fetches the latest list. Network: call off the main thread. Returns the number of orders, or -1. */
    public static synchronized int refresh(Context c) {
        String relay = sp(c).getString("relay", null), secret = sp(c).getString("secret", null);
        if (relay == null || secret == null) return -1;
        HttpURLConnection h = null;
        try {
            URL url = new URL(relay + "?t=" + URLEncoder.encode(OrderLookup.readToken(secret), "UTF-8"));
            for (int hop = 0; hop < 5; hop++) { // Apps Script answers through a redirect to another Google address
                h = (HttpURLConnection) url.openConnection();
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(5000); h.setReadTimeout(8000);
                int code = h.getResponseCode();
                if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                    URL next = new URL(url, h.getHeaderField("Location"));
                    if (!"https".equals(next.getProtocol()) || !(next.getHost().endsWith(".google.com") || next.getHost().endsWith(".googleusercontent.com"))) return -1;
                    h.disconnect(); url = next; continue;
                }
                if (code != 200) return -1;
                byte[] body = read(h.getInputStream(), 4 * 1024 * 1024);
                JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
                if (!o.optBoolean("ok")) { sp(c).edit().putString("error", "denied".equals(o.optString("error")) ? "The code doesn't match the relay. Copy the code again from Outlet." : "The relay gave an error.").apply(); return -1; }
                try (FileOutputStream out = new FileOutputStream(file(c))) { out.write(body); }
                int n = o.optJSONObject("orders") == null ? 0 : o.getJSONObject("orders").length();
                sp(c).edit().putLong("fetched", System.currentTimeMillis()).putString("made", o.optString("generatedAt", "")).putInt("count", n).remove("error").apply();
                return n;
            }
            return -1;
        } catch (Exception e) {
            sp(c).edit().putString("error", "No internet or the relay didn't answer.").apply();
            return -1;
        } finally { if (h != null) h.disconnect(); }
    }

    /** SKU text for a label file name, or "" (no order number in the name, not linked, or not found). */
    public static String stampFor(Context c, String fileName) {
        if (OrderLookup.ebayOrderFrom(fileName) == null || !linked(c)) return "";
        String secret = sp(c).getString("secret", null);
        Map<String, String> orders = cached(c);
        String s = OrderLookup.stampFor(fileName, secret, orders);
        boolean stale = System.currentTimeMillis() - sp(c).getLong("fetched", 0) > FRESH_MS;
        // Not found (a brand-new order) or an old list: fetch once, then look again.
        if ((s.isEmpty() || stale) && Looper.myLooper() != Looper.getMainLooper() && refresh(c) >= 0)
            s = OrderLookup.stampFor(fileName, secret, cached(c));
        return s;
    }

    /** "142 orders, updated 19:05" or a problem, or null when not linked. */
    public static String summary(Context c) {
        if (!linked(c)) return null;
        String err = sp(c).getString("error", null);
        long f = sp(c).getLong("fetched", 0);
        String when = f == 0 ? "not fetched yet" : "updated " + new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK).format(new java.util.Date(f));
        return sp(c).getInt("count", 0) + " recent orders, " + when + (err != null ? " — " + err : "");
    }

    private static Map<String, String> cached(Context c) {
        Map<String, String> out = new HashMap<>();
        try (FileInputStream in = new FileInputStream(file(c))) {
            JSONObject o = new JSONObject(new String(read(in, 4 * 1024 * 1024), StandardCharsets.UTF_8)).getJSONObject("orders");
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); out.put(k, o.getString(k)); }
        } catch (Exception ignored) { }
        return out;
    }
    private static byte[] read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > max) throw new Exception("too big"); }
        return b.toByteArray();
    }
}
