"""Checks that "Fast sending" (BAR rectangles + cropped BITMAPs) prints exactly the same
dots as sending the whole label as one BITMAP, and how much less data it sends."""
import asyncio, re, sys, pathlib, http.server, functools, threading, time
from PIL import Image, ImageChops
from playwright.async_api import async_playwright
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from run_test import MOCK, ROOT, FIX, OUT

def render_jobs(raw: bytes, w=816, h=1219):
    """Minimal TSPL renderer: CLS / BITMAP (mode 0) / BAR / PRINT. Returns one image per PRINT."""
    imgs, pos, img = [], 0, None
    cmd = re.compile(rb"(CLS\r\n)|(BITMAP (\d+),(\d+),(\d+),(\d+),0,)|(BAR (\d+),(\d+),(\d+),(\d+)\r\n)|(PRINT \d+,(\d+)\r\n)")
    while True:
        m = cmd.search(raw, pos)
        if not m: break
        if m.group(1): img = Image.new("1", (w, h), 1)
        elif m.group(2):
            x, y, wb, hh = map(int, m.group(3, 4, 5, 6)); start = m.end()
            img.paste(Image.frombytes("1", (wb * 8, hh), raw[start:start + wb * hh]), (x, y))
            m_end = start + wb * hh; pos = m_end; continue
        elif m.group(7):
            x, y, ww, hh = map(int, m.group(8, 9, 10, 11)); img.paste(0, (x, y, x + ww, y + hh))
        elif m.group(12): imgs.append(img)
        pos = m.end()
    return imgs

async def run_once(pg, fast):
    await pg.evaluate(f"(()=>{{const s=JSON.parse(localStorage.getItem('labelPrinter.settings')||'{{}}');s.fast={str(fast).lower()};localStorage.setItem('labelPrinter.settings',JSON.stringify(s));}})()")
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
        slow, slow_t, ts = await run_once(pg, False)
        fast, fast_t, tf = await run_once(pg, True)
        a, b = render_jobs(slow) + render_jobs(slow_t), render_jobs(fast) + render_jobs(fast_t)
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
        print("ALL IDENTICAL" if ok else "MISMATCH")
        await br.close()
    srv.shutdown()

if __name__ == "__main__":
    asyncio.run(main())
