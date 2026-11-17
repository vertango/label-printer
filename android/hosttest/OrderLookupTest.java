import io.github.vertango.labelprinter.OrderLookup;
import java.util.HashMap;
import java.util.Map;

/** Same answers as Outlet Analytics (tests/label-lookup.test.mjs shares these values). */
public class OrderLookupTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); System.out.println("ok - " + what); }
    public static void main(String[] a) {
        check(OrderLookup.key("test-secret-abc", "ebay", "25-15411-62068").equals("8b960ad7d0bb561a40e6b82a421a9777"), "printer key matches Outlet");
        check(OrderLookup.readToken("test-secret-abc").equals("a2cf434318135516c81e0e4b3db8015d"), "read token matches Outlet");
        check("25-15411-62068".equals(OrderLookup.ebayOrderFrom("eBay label 25-15411-62068.pdf")), "order number from eBay file name");
        check("25-15411-62068".equals(OrderLookup.ebayOrderFrom("12345-eBay label 25-15411-62068 (1).pdf")), "order number after a copy prefix / duplicate suffix");
        check(OrderLookup.ebayOrderFrom("5ce967e7-1234-4abc-9def-001122334455") == null, "no order number in a random download name");
        check(OrderLookup.ebayOrderFrom("label 125-15411-62068.pdf") == null, "not part of a longer number");
        // Printer code made by Outlet: OUTLET-LP1. + base64url({"u":relay,"s":secret})
        String json = "{\"u\":\"https://script.google.com/macros/s/AKfycbxAbCdEfGhIjKlMnOpQrStUv/exec\",\"s\":\"0123456789abcdef0123456789abcdef0123456789abcdef\"}";
        String code = OrderLookup.CODE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] p = OrderLookup.parseCode("  " + code + "\n");
        check(p != null && p[0].endsWith("/exec") && p[1].startsWith("0123"), "printer code parsed");
        check(OrderLookup.parseCode("OUTLET-LP1.bm9wZQ") == null && OrderLookup.parseCode("hello") == null, "bad codes refused");
        Map<String, String> orders = new HashMap<>();
        orders.put(OrderLookup.key("test-secret-abc", "ebay", "25-15411-62068"), "2× 189in · 81in");
        check("2× 189in · 81in".equals(OrderLookup.stampFor("eBay label 25-15411-62068.pdf", "test-secret-abc", orders)), "SKU text for the label");
        check("".equals(OrderLookup.stampFor("eBay label 11-22222-33333.pdf", "test-secret-abc", orders)), "unknown order: no stamp");
    }
}
