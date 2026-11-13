"""Share labels + an Outlet Analytics SKU note to the app; check the SKU is printed in
a blank space (not over barcodes), or in a strip below when the label has no room."""
import asyncio, base64, json, sys, pathlib, functools, threading, http.server
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from run_test import MOCK, FIX, OUT, ROOT, render_jobs
from reportlab.pdfgen import canvas as rl
from reportlab.lib.units import inch
from playwright.async_api import async_playwright
class Q(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
MOCK2 = MOCK.replace('const serial = {', 'const serial = { addEventListener(){},')
# A label with no blank space at all: solid stripes everywhere.
full = OUT / "full_label.pdf"; c = rl.Canvas(str(full), pagesize=(4 * inch, 6 * inch))
for i in range(0, 432, 6): c.rect(0, i, 288, 3, fill=1, stroke=0)
c.showPage(); c.save()
FILES = {"eBay label 24-15408-19804.pdf": FIX / "ebay_4x6_two.pdf", "202-1234567-1234567.pdf": FIX / "clickdrop_a4.pdf", "eBay label 11-11111-11111.pdf": full}
NOTE = {"type": "label-printer/skus", "v": 1, "labels": {"eBay label 24-15408-19804.pdf": "No.41 / 17in ×2 · 116in", "202-1234567-1234567.pdf": "No.41 / 17in", "eBay label 11-11111-11111.pdf": "1162in"}}
async def main():
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 8781), functools.partial(Q, directory=str(ROOT))); threading.Thread(target=srv.serve_forever, daemon=True).start()
    async with async_playwright() as pw:
        b = await pw.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"]); pg = await b.new_page(viewport={"width": 1100, "height": 1300})
        errs = []; pg.on("pageerror", lambda e: errs.append(str(e)))
        await pg.add_init_script(MOCK2); await pg.goto("http://127.0.0.1:8781/index.html")
        await pg.click("#connectBtn"); await pg.evaluate("navigator.serviceWorker.ready"); await pg.reload(); await pg.evaluate("navigator.serviceWorker.ready")
        await pg.evaluate("localStorage.setItem('labelPrinter.settings', JSON.stringify({v:5, autoPrint:true, quickClose:false}))")
        files = [[n, base64.b64encode(p.read_bytes()).decode(), "application/pdf"] for n, p in FILES.items()] + [["label-skus.txt", base64.b64encode(json.dumps(NOTE).encode()).decode(), "text/plain"]]
        url = await pg.evaluate('''async (files) => { const fd=new FormData();
          for (const [n,b64,t] of files){const bin=atob(b64),u=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)u[i]=bin.charCodeAt(i);fd.append("labels",new File([u],n,{type:t}));}
          return (await fetch("share-target",{method:"POST",body:fd})).url; }''', files)
        await pg.evaluate("window.__sent=[]"); await pg.goto(url)
        await pg.wait_for_function("/Sent \\d+ label/.test(document.getElementById('msg').textContent)", timeout=30000)
        print("message:", await pg.inner_text("#msg"))
        print("SKU boxes:", await pg.evaluate("[...document.querySelectorAll('.item')].map(e=>[e.querySelector('.name').textContent, e.querySelector('.stamp').value])"))
        raw = bytes(x for ch in await pg.evaluate("window.__sent") for x in ch)
        imgs = render_jobs(raw)
        for i, (img, _) in enumerate(imgs, 1): img.save(OUT / f"stamped_{i}.png")
        print("labels printed:", len(imgs)); await pg.screenshot(path=str(OUT / "screen_stamped.png"), full_page=True)
        print("errors:", errs or "none"); await b.close()
    srv.shutdown()
asyncio.run(main())
