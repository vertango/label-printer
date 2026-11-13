package io.github.vertango.labelprinter;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** The SKU list from Outlet Analytics (label-printer-sku-list.json), kept in the app's own storage. */
public final class SkuList {
    private SkuList() {}
    private static File file(Context c) { return new File(c.getFilesDir(), "sku-list.json"); }

    /** Reads a downloaded list and keeps it. Returns the number of listings, or -1 if it isn't a SKU list. */
    public static int importFrom(Context c, File downloaded) {
        try {
            byte[] raw = read(downloaded);
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            if (!"label-printer/sku-list".equals(o.optString("type"))) return -1;
            int n = parse(o).size();
            try (FileOutputStream out = new FileOutputStream(file(c))) { out.write(raw); }
            return n;
        } catch (Exception e) { return -1; }
    }

    public static SkuMatcher matcher(Context c) {
        try { return new SkuMatcher(parse(new JSONObject(new String(read(file(c)), StandardCharsets.UTF_8)))); }
        catch (Exception e) { return null; }
    }

    /** "312 listings, from 13 Nov" or null. */
    public static String summary(Context c) {
        try {
            JSONObject o = new JSONObject(new String(read(file(c)), StandardCharsets.UTF_8));
            String made = o.optString("made", "");
            return parse(o).size() + " listings" + (made.length() >= 10 ? ", made " + made.substring(0, 10) : "");
        } catch (Exception e) { return null; }
    }

    private static List<SkuMatcher.Listing> parse(JSONObject o) throws Exception {
        List<SkuMatcher.Listing> out = new ArrayList<>();
        JSONArray items = o.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            JSONArray vs = it.getJSONArray("v");
            List<SkuMatcher.Variation> vars = new ArrayList<>();
            for (int j = 0; j < vs.length(); j++) {
                JSONObject v = vs.getJSONObject(j);
                vars.add(new SkuMatcher.Variation(v.getString("s"), v.has("p") ? v.getString("p") : null, v.has("o") ? v.getString("o") : null));
            }
            out.add(new SkuMatcher.Listing(it.getString("t"), vars));
        }
        return out;
    }

    private static byte[] read(File f) throws Exception {
        if (f.length() > 8 * 1024 * 1024) throw new Exception("too big");
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; }
        return b;
    }
}
