"""Load the page in headless Chrome with a fake Bluetooth serial printer, add the
fixture files, print, then decode the TSPL the printer received back into PNGs."""
import asyncio, base64, http.server, pathlib, re, sys, threading, functools
from PIL import Image
from playwright.async_api import async_playwright

ROOT = pathlib.Path(__file__).parent.parent
FIXSRC = ROOT / "test"
FIX = ROOT / "test/fixtures"; OUT = ROOT / "test/out"; OUT.mkdir(exist_ok=True)

MOCK = r"""
(() => {
  window.__sent = [];
  let granted = sessionStorage.getItem("fakeGranted") ? [null] : [];
  class FakePort {
    constructor(){ this.readable=null; this.writable=null; }
    getInfo(){ return {bluetoothServiceClassId:"00001101-0000-1000-8000-00805f9b34fb"}; }
    async open(o){ if(this.writable) throw new DOMException("already open","InvalidStateError");
      this.writable = new WritableStream({ write(chunk){ window.__sent.push(Array.from(chunk)); } });
      this.readable = new ReadableStream({ pull(ctrl){ return new Promise(r=>setTimeout(()=>{ try{ctrl.enqueue(new Uint8Array([0]));}catch{} r(); }, 50)); } });
    }
    async close(){ this.writable=null; this.readable=null; }
    async forget(){ granted=[]; }
  }
  const serial = { async getPorts(){ if (granted.length && !granted[0]) granted=[new FakePort()]; return granted; },
    async requestPort(o){ window.__requestOpts=o; const p=new FakePort(); granted=[p]; sessionStorage.setItem("fakeGranted","1"); return p; } };
  Object.defineProperty(navigator, "serial", { value: serial });
})();
"""

def serve():
    h = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(ROOT))
    s = http.server.ThreadingHTTPServer(("127.0.0.1", 8765), h)
    threading.Thread(target=s.serve_forever, daemon=True).start()
    return s

def decode(raw: bytes, prefix: str):
    jobs, pos, n = [], 0, 0
    while True:
        m = re.compile(rb"BITMAP (\d+),(\d+),(\d+),(\d+),(\d+),").search(raw, pos)
        if not m: break
        x, y, wb, h, mode = map(int, m.groups())
        start = m.end(); data = raw[start:start + wb * h]
        header = raw[pos:m.start()].decode(errors="replace")
        tail_end = raw.index(b"\r\n", start + wb * h + 2) if raw[start + wb*h:start + wb*h+2] == b"\r\n" else start + wb*h
        tail = raw[start + wb * h: raw.index(b"\r\n", start + wb * h + 2) + 2].decode()
        img = Image.frombytes("1", (wb * 8, h), bytes(data))  # PIL '1': bit 1 = white, matches TSPL (0 = burn)
        n += 1; p = OUT / f"{prefix}_{n:02d}.png"; img.save(p)
        jobs.append({"header": header.strip(), "tail": tail.strip(), "size": (wb * 8, h), "png": p.name,
                     "black_pct": round(100 * sum(bin(~b & 255).count("1") for b in data) / (len(data) * 8), 1)})
        pos = start + wb * h
    return jobs

async def main():
    srv = serve()
    async with async_playwright() as pw:
        br = await pw.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"])
        pg = await br.new_page(viewport={"width": 1100, "height": 1400})
        logs = []; pg.on("console", lambda m: logs.append(f"{m.type}: {m.text}")); pg.on("pageerror", lambda e: logs.append(f"PAGEERROR: {e}"))
        await pg.add_init_script(MOCK)
        await pg.goto("http://127.0.0.1:8765/index.html")
        await pg.set_input_files("#fileIn", [str(FIX / f) for f in ["clickdrop_a4.pdf", "a4_label_only.pdf", "ebay_4x6_two.pdf", "shopify_6x4_landscape.pdf", "label_image.png"]])
        await pg.wait_for_function("document.querySelectorAll('.item').length===6 && !/Opening/.test(document.getElementById('msg').textContent)", timeout=60000)
        await pg.wait_for_timeout(500)
        # Click & Drop page has a packing slip under the label: choose "Top half" for it.
        await pg.select_option('.item[data-id="1"] .crop', "top")
        await pg.click('.item[data-id="3"] .plus')  # 2 copies of first eBay label
        await pg.set_input_files("#packIn", [str(FIX / "packing_list.pdf")])
        await pg.wait_for_function("document.querySelectorAll('.item').length===8", timeout=60000)
        await pg.wait_for_timeout(800)
        print("Message:", await pg.inner_text("#msg"))
        print("Print button:", await pg.inner_text("#printBtn"))
        await pg.screenshot(path=str(OUT / "screen_queue.png"), full_page=True)
        await pg.click("#printBtn")
        await pg.wait_for_function("!document.getElementById('printBtn').textContent.includes('Printing')", timeout=60000)
        print("After print:", await pg.inner_text("#msg"))
        print("requestPort options:", await pg.evaluate("JSON.stringify(window.__requestOpts)"))
        raw = bytes(b for ch in await pg.evaluate("window.__sent") for b in ch)
        await pg.evaluate("window.__sent=[]")
        # Test print + status check
        await pg.click("#testBtn"); await pg.wait_for_timeout(800)
        raw2 = bytes(b for ch in await pg.evaluate("window.__sent") for b in ch)
        await pg.click("#statusBtn"); await pg.wait_for_timeout(800)
        print("Status:", await pg.inner_text("#msg"))
        await pg.screenshot(path=str(OUT / "screen_after.png"), full_page=False)
        # Narrow tablet-portrait screenshot
        await pg.set_viewport_size({"width": 800, "height": 1280}); await pg.wait_for_timeout(300)
        await pg.screenshot(path=str(OUT / "screen_tablet.png"))
        for j in decode(raw, "job"): print(j)
        for j in decode(raw2, "test"): print(j)
        print("bytes sent:", len(raw))
        print("\n".join(l for l in logs if "error" in l.lower() or "PAGEERROR" in l) or "no console errors")
        # ---- Share-to-app flow with auto-print ----
        await pg.evaluate("navigator.serviceWorker.ready")
        await pg.evaluate("(()=>{const s=JSON.parse(localStorage.getItem('labelPrinter.settings')||'{}');s.autoPrint=true;localStorage.setItem('labelPrinter.settings',JSON.stringify(s));})()")
        await pg.goto("http://127.0.0.1:8765/index.html"); await pg.evaluate("navigator.serviceWorker.ready")
        print("SW controlling:", await pg.evaluate("!!navigator.serviceWorker.controller"))
        pdf = base64.b64encode((FIX / "shopify_6x4_landscape.pdf").read_bytes()).decode()
        final = await pg.evaluate('''async (b64) => {
          const bin = atob(b64), u = new Uint8Array(bin.length); for (let i=0;i<bin.length;i++) u[i]=bin.charCodeAt(i);
          const fd = new FormData(); fd.append("labels", new File([u], "shared-shopify.pdf", {type:"application/pdf"}));
          const r = await fetch("share-target", {method:"POST", body: fd}); return r.url; }''', pdf)
        print("Share redirected to:", final)
        await pg.evaluate("window.__sent=[]")
        await pg.goto(final)
        await pg.wait_for_function("/Sent 1 label/.test(document.getElementById('msg').textContent)", timeout=30000)
        print("After share:", await pg.inner_text("#msg"), "| URL now:", pg.url)
        raw3 = bytes(b for ch in await pg.evaluate("window.__sent") for b in ch)
        for j in decode(raw3, "shared"): print(j)
        await pg.screenshot(path=str(OUT / "screen_shared.png"))
        print("\n".join(l for l in logs if "error" in l.lower() or "PAGEERROR" in l) or "no console errors")
        await br.close()
    srv.shutdown()

asyncio.run(main())
