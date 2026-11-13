package io.github.vertango.labelprinter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the SKU(s) for an eBay packing slip: the listing whose title is on the slip, then the
 * variation by its option name (if the slip shows it) or by price. Pure Java.
 */
public final class SkuMatcher {
    public static final class Variation {
        public final String sku, price, option;
        public Variation(String sku, String price, String option) { this.sku = sku; this.price = price; this.option = option; }
    }
    public static final class Listing {
        public final String title; public final List<Variation> vars;
        final Set<String> words;
        public Listing(String title, List<Variation> vars) { this.title = title; this.vars = vars; this.words = words(title); }
    }

    private final List<Listing> listings;
    public SkuMatcher(List<Listing> listings) { this.listings = listings; }

    static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.UK).replaceAll("[^a-z0-9]+", " ").trim().split(" ")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static final Pattern MONEY = Pattern.compile("£\\s?(\\d{1,6}(?:,\\d{3})*\\.\\d{2})");

    /** Is this page an eBay packing slip / invoice? */
    public static boolean isSlip(String text) {
        String t = text.toLowerCase(Locale.UK);
        return t.contains("order total") || (t.contains("qty") && t.contains("item price"));
    }

    /** The text to print on the label, or "" if nothing was found. */
    public String stampFor(String slipText) {
        Set<String> slip = words(slipText);
        Set<String> prices = new HashSet<>();
        Matcher m = MONEY.matcher(slipText);
        while (m.find()) prices.add(m.group(1).replace(",", ""));
        String low = " " + String.join(" ", slip) + " ";

        // Listings whose title is (almost) all on the slip; longest titles first.
        List<Listing> found = new ArrayList<>();
        for (Listing l : listings) {
            if (l.words.size() < 2) continue;
            int hit = 0;
            for (String w : l.words) if (slip.contains(w)) hit++;
            if (hit >= Math.ceil(l.words.size() * 0.9)) found.add(l);
        }
        found.sort((a, b) -> b.words.size() - a.words.size());
        List<Listing> chosen = new ArrayList<>();
        for (Listing l : found) {
            boolean inside = false;
            for (Listing c : chosen) if (c.words.containsAll(l.words)) { inside = true; break; }
            if (!inside) chosen.add(l);
        }

        List<String> parts = new ArrayList<>();
        for (Listing l : chosen) {
            List<Variation> pick = l.vars;
            if (pick.size() > 1) {
                List<Variation> byOption = new ArrayList<>();
                for (Variation v : pick) {
                    if (v.option == null) continue;
                    String val = v.option.contains(":") ? v.option.substring(v.option.lastIndexOf(':') + 1) : v.option;
                    String key = " " + String.join(" ", words(val)) + " ";
                    if (key.trim().length() > 0 && low.contains(key)) byOption.add(v);
                }
                if (!byOption.isEmpty() && byOption.size() < pick.size()) pick = byOption;
            }
            if (pick.size() > 1) {
                List<Variation> byPrice = new ArrayList<>();
                for (Variation v : pick) if (v.price != null && prices.contains(v.price)) byPrice.add(v);
                if (!byPrice.isEmpty()) pick = byPrice;
            }
            if (pick.isEmpty() || pick.size() > 3) continue; // can't tell which option: print nothing for it
            List<String> skus = new ArrayList<>();
            for (Variation v : pick) if (!skus.contains(v.sku)) skus.add(v.sku);
            parts.add(String.join(" / ", skus));
        }
        return String.join(" · ", parts);
    }
}
