"""Checks that "Fast sending" (BAR rectangles + cropped BITMAPs) prints exactly the same
dots as sending the whole label as one BITMAP, and how much less data it sends."""
import asyncio, re, sys, pathlib, http.server, functools, threading, time
from PIL import Image, ImageChops
from playwright.async_api import async_playwright
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from run_test import MOCK, ROOT, FIX, OUT, render_jobs

def render_imgs(raw): return [img for img, _ in render_jobs(raw)]

async def run_once(pg, method):
    await pg.evaluate(f"(()=>{{const s=JSON.parse(localStorage.getItem('labelPrinter.settings')||'{{}}');s.method=\'{method}\';localStorage.setItem('labelPrinter.settings',JSON.stringify(s));}})()")
    await pg.goto("http://127.0.0.1:8770/index.html")
    await pg.set_input_files("#fileIn", [str(FIX / f) for f in ["clickdrop_a4.pdf", "a4_label_only.pdf", "ebay_4x6_two.pdf", "shopify_6x4_landscape.pdf", "label_image.png"]])
    await pg.wait_for_function("document.querySelectorAll('.item').length===6 && !/Opening/.test(document.getElementById('msg').textContent)", timeout=60000)
    await pg.select_option('.item[data-id="1"] .crop', "top")
    await pg.evaluate("document.getElementById('sampleBtn').click()"); await pg.wait_for_function("document.querySelectorAll('.item').length===9")
    await pg.wait_for_timeout(800)
    await pg.evaluate("window.__sent=[]")
    t = time.time()
    await pg.click("#printBtn")
    await pg.wait_for_function("/Sent \\d+ label/.test(document.getElementById('msg').textContent)", timeout=60000)
    took = time.time() - t
    raw = bytes(b for ch in await pg.evaluate("window.__sent") for b in ch)
    await pg.evaluate("window.__sent=[]")
    await pg.evaluate("document.getElementById('testBtn').click()"); await pg.wait_for_function("/Sent 1 label/.test(document.getElementById('msg').textContent)", timeout=30000)
    raw_test = bytes(b for ch in await pg.evaluate("window.__sent") for b in ch)
    return raw, raw_test, took

async def main():
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(ROOT))
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a): pass
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 8770), functools.partial(Quiet, directory=str(ROOT)))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    async with async_playwright() as pw:
        br = await pw.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"])
        pg = await br.new_page(); errs = []; pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script(MOCK); await pg.goto("http://127.0.0.1:8770/index.html")
        slow, slow_t, ts = await run_once(pg, "plain")
        rects, rects_t, _ = await run_once(pg, "rects")
        fast, fast_t, tf = await run_once(pg, "zlib")
        ra, rb_ = render_imgs(slow) + render_imgs(slow_t), render_imgs(rects) + render_imgs(rects_t)
        rect_same = all(ImageChops.difference(x.convert("L"), y.convert("L")).getbbox() is None for x, y in zip(ra, rb_))
        print("rectangles method identical to plain:", rect_same, f"({len(rects+rects_t)//1024} KB)")
        a, b = render_imgs(slow) + render_imgs(slow_t), render_imgs(fast) + render_imgs(fast_t)
        names = ["ClickDrop A4", "A4 label", "eBay 1 (x2)", "eBay 2", "Shopify 6x4", "Image", "Sample label", "Sample pack 1", "Sample pack 2", "Test print"]
        ok = len(a) == len(b) == len(names)
        print(f"jobs: full={len(a)} fast={len(b)}")
        # per-job sizes
        def split(raw): return [m.end() for m in re.finditer(rb"PRINT \d+,\d+\r\n", raw)]
        def sizes(raw):
            ends = split(raw); return [e - s for s, e in zip([0] + ends[:-1], ends)]
        for n, ia, ib, sa, sb in zip(names, a, b, sizes(slow) + sizes(slow_t), sizes(fast) + sizes(fast_t)):
            same = ImageChops.difference(ia.convert("L"), ib.convert("L")).getbbox() is None
            ok &= same
            print(f"{n:15s} {sa//1024:4d} KB -> {sb//1024:3d} KB  ({sa/sb:4.1f}x)  identical={same}")
        print(f"total: {len(slow+slow_t)//1024} KB -> {len(fast+fast_t)//1024} KB")
        b[-1].save(OUT / "fast_test_print.png")
        print("page errors:", errs or "none")
        print("ALL IDENTICAL" if ok and rect_same else "MISMATCH")
        await br.close()
    srv.shutdown()

if __name__ == "__main__":
    asyncio.run(main())
