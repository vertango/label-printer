import asyncio, sys, pathlib, mimetypes
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from run_test import MOCK, render_jobs
from playwright.async_api import async_playwright
LP = pathlib.Path(__file__).resolve().parent.parent; OA = 'https://outlet-analytics-jac.diamondphil3.chatgpt.site'
MOCK2 = MOCK.replace('const serial = {', 'const serial = { addEventListener(){},').replace('let granted = sessionStorage.getItem("fakeGranted") ? [null] : [];', 'let granted = [null];')
async def main():
  async with async_playwright() as pw:
    b = await pw.chromium.launch(executable_path="/usr/bin/google-chrome", args=["--no-sandbox"])
    ctx = await b.new_context(service_workers="block", viewport={"width":1000,"height":900})
    await ctx.add_init_script(MOCK2)
    async def lp(route):
        path = route.request.url.split('/label-printer/')[1].split('#')[0].split('?')[0] or 'index.html'
        f = LP / path; await route.fulfill(path=str(f), content_type=mimetypes.guess_type(str(f))[0] or 'text/javascript')
    async def oa(route):
        u = route.request.url
        if u.endswith('/fixture.pdf'): await route.fulfill(path=str(LP/'test/fixtures/ebay_4x6_two.pdf'), content_type='application/pdf')
        else: await route.fulfill(path=''+str(LP/'test/outlet_fake.html')+'', content_type='text/html')
    await ctx.route("https://vertango.github.io/label-printer/**", lp)
    await ctx.route(OA + "/**", oa)
    pg = await ctx.new_page(); errs=[]
    pg.on("pageerror", lambda e: errs.append("OA: "+str(e)))
    await pg.goto(OA + "/packing")
    for which in ("pack", "labels"):
        async with ctx.expect_page() as pi: await pg.click("#"+which)
        p2 = await pi.value; p2.on("pageerror", lambda e: errs.append("LP: "+str(e)))
        await p2.wait_for_load_state()
        try:
            await p2.wait_for_function("(/Printed/.test(document.getElementById('qTitle').textContent))", timeout=20000)
            print(which, "-> LP card:", await p2.inner_text("#qTitle"), "|", await p2.inner_text("#qText"))
            raw = bytes(x for ch in await p2.evaluate("window.__sent") for x in ch)
            for i,(img,_) in enumerate(render_jobs(raw),1): img.save(str(LP)+f"/test/out/outlet_{which}_{i}.png")
            print("   printed pages:", len(render_jobs(raw)), "| tab closed itself:", p2.is_closed())
        except Exception as e:
            print(which, "-> FAILED:", e, "| msg:", (await p2.inner_text("#msg")) if not p2.is_closed() else "closed")
        if which == "labels":
            await pg.wait_for_function("document.getElementById('out').textContent.length>0", timeout=20000)
            print("   OA says:", await pg.inner_text("#out"))
    print("errors:", errs or "none")
    await b.close()
asyncio.run(main())
