import asyncio, sys, pathlib, mimetypes, base64, json
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from run_test import MOCK
from playwright.async_api import async_playwright
LP = pathlib.Path(__file__).resolve().parent.parent
MOCK2 = MOCK.replace('const serial = {', 'const serial = { addEventListener(){},')
enc = lambda o: base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip('=')
async def main():
  async with async_playwright() as pw:
    b = await pw.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"])
    ctx = await b.new_context(service_workers="block"); await ctx.add_init_script(MOCK2)
    async def lp(route):
        path = route.request.url.split('/label-printer/')[1].split('#')[0].split('?')[0] or 'index.html'
        f = LP / path; await route.fulfill(path=str(f), content_type=mimetypes.guess_type(str(f))[0] or 'text/javascript')
    await ctx.route("https://vertango.github.io/label-printer/**", lp)
    pg = await ctx.new_page(); errs=[]; pg.on("pageerror", lambda e: errs.append(str(e)))
    await pg.goto("https://vertango.github.io/label-printer/#outlet=" + enc({"k":"label","s":{"eBay label 24-15408-19804.pdf":"48in"}}))
    await pg.wait_for_timeout(800); print("labels, no tab link:", await pg.inner_text("#msg"))
    await pg.goto("https://vertango.github.io/label-printer/#outlet=" + enc({"k":"pack","g":"x","u":1,"r":[["22in",1,"Test","eBay 1"]]})); await pg.reload()
    await pg.wait_for_timeout(1200); print("pack, not from Outlet:", await pg.inner_text("#msg"), "| sent to printer:", await pg.evaluate("window.__sent.length"))
    print("errors:", errs or "none"); await b.close()
asyncio.run(main())
