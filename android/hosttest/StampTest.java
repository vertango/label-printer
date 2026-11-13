import io.github.vertango.labelprinter.*;
import java.util.*;

/** Off-device checks for SKU matching and the blank-spot finder. */
public class StampTest {
    static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError("expected [" + b + "] got [" + a + "]"); }
    public static void main(String[] a) {
        List<SkuMatcher.Listing> L = new ArrayList<>();
        L.add(new SkuMatcher.Listing("69/120 Pin Car Connector Waterproof ABS Pump ECU Plug 34854-1105 For Renault", Arrays.asList(
                new SkuMatcher.Variation("696in", "48.89", "Pins: 69 pins"), new SkuMatcher.Variation("1206in", "58.89", "Pins: 120 pins"))));
        L.add(new SkuMatcher.Listing("2 Pin Car Connector Plug", Arrays.asList(new SkuMatcher.Variation("12in", "9.99", null))));
        L.add(new SkuMatcher.Listing("Car Connector Plug", Arrays.asList(new SkuMatcher.Variation("X", "1.00", null))));
        L.add(new SkuMatcher.Listing("Sealed Relay Box 4 Way", Arrays.asList(
                new SkuMatcher.Variation("RB4a", "12.00", "Colour: Black"), new SkuMatcher.Variation("RB4b", "12.00", "Colour: Grey"))));
        SkuMatcher m = new SkuMatcher(L);
        String slip = "Order: 19-15408-22240\nItem Qty Item price VAT % Item total\n69/120 Pin Car 1 £48.89 0% £48.89\nConnector Waterproof\nABS Pump ECU Plug\n34854-1105 For Renault\nNew\nOrder total £53.90";
        if (!SkuMatcher.isSlip(slip)) throw new AssertionError("slip");
        eq(m.stampFor(slip), "696in");                                   // variation by price, not the shorter title
        eq(m.stampFor("Item Qty Item price\n2 Pin Car Connector Plug 1 £9.99\nOrder total £14.99"), "12in");
        eq(m.stampFor("Item Qty Item price\nSealed Relay Box 4 Way 1 £12.00\nOrder total £17.00"), "RB4a / RB4b"); // same price: both
        eq(m.stampFor("Item Qty Item price\nSealed Relay Box 4 Way\nColour: Grey 1 £12.00\nOrder total £17.00"), "RB4b"); // option on slip
        eq(m.stampFor("Item Qty Item price\nSomething else entirely 1 £3.00\nOrder total"), "");
        eq(m.stampFor("Item Qty Item price\n2 Pin Car Connector Plug 1 £9.99\nSealed Relay Box 4 Way Colour: Black 1 £12.00\nOrder total"), "12in · RB4a");
        if (SkuMatcher.isSlip("")) throw new AssertionError("empty page is not a slip");

        // Blank spot: empty label -> big box; full label -> none.
        byte[] bits = new byte[LabelRaster.BPR * LabelRaster.H];
        for (int y = 0; y < 600; y++) for (int c = 0; c < 100; c++) bits[y * 100 + c] = (byte) 0xff; // top half printed
        int[] spot = SkuStamp.findBlankSpot(bits, 300);
        if (spot == null || spot[1] < 600 || spot[4] != 64) throw new AssertionError("spot " + Arrays.toString(spot));
        Arrays.fill(bits, (byte) 0xff);
        if (SkuStamp.findBlankSpot(bits, 300) != null) throw new AssertionError("full label has no spot");
        System.out.println("stamp tests ok, spot " + Arrays.toString(spot));
    }
}
