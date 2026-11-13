"""Batch sending is paced (about 15 KB/s, never more than one label ahead of the printer),
so a busy printer isn't overrun and labels don't come out striped."""
import asyncio, sys, pathlib, functools, threading, http.server
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from run_test import MOCK, FIX, ROOT, render_jobs
from playwright.async_api import async_playwright
class Q(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
M = "window.__labelPrinterPacing=true;" + MOCK.replace('const serial = {', 'const serial = { addEventListener(){},')
async def main():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 8792), functools.partial(Q, directory=str(ROOT))); threading.Thread(target=srv.serve_forever, daemon=True).start()
    async with async_playwright() as pw:
        b = await pw.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"]); pg = await b.new_page()
        await pg.add_init_script(M); await pg.goto("http://127.0.0.1:8792/index.html")
        await pg.set_input_files("#fileIn", [str(FIX / "ebay_4x6_two.pdf"), str(FIX / "a4_label_only.pdf"), str(FIX / "shopify_6x4_landscape.pdf"), str(FIX / "label_image.png")])
        await pg.wait_for_function("document.querySelectorAll('.item').length===5 && !/Opening/.test(document.getElementById('msg').textContent)", timeout=60000)
        await pg.evaluate("document.getElementById('connectBtn').click()"); await pg.wait_for_timeout(300)
        await pg.evaluate("window.__sent=[]; window.__writes=[]; document.getElementById('printBtn').click()")
        await pg.wait_for_function("/Sent 5 label/.test(document.getElementById('msg').textContent)", timeout=60000)
        w = await pg.evaluate("window.__writes"); raw = bytes(x for ch in await pg.evaluate("window.__sent") for x in ch)
        t0 = w[0][0]; total = sum(n for _, n in w); dur = (w[-1][0] - t0) / 1000
        starts = []; acc = 0; ends = [m.end() for m in __import__('re').finditer(rb"PRINT \d+,\d+\r\n", raw)]
        for t, n in w:
            if not starts or (len(starts) < len(ends) and acc >= ends[len(starts) - 1]): starts.append(round((t - t0) / 1000, 2))
            acc += n
        print(f"labels: {len(render_jobs(raw))}, {total/1024:.0f} KB over {dur:.1f} s = {total/1024/max(dur,0.01):.1f} KB/s, biggest piece {max(n for _, n in w)} bytes")
        print("each label started sending at (s):", starts)
        await b.close()
    srv.shutdown()
asyncio.run(main())
