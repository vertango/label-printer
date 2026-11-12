# Label Printer

A simple web app for printing 4x6 shipping labels and packing lists on a **Jadens JD-168BT** Bluetooth thermal label printer from an Android tablet, replacing the Jadens app.

**Open it:** https://vertango.github.io/label-printer/ in Chrome on Android, then ⋮ → *Add to home screen* → *Install*.

## What it does
- Connects to the printer over Bluetooth and remembers it.
- Prints PDFs and images (eBay, Amazon, TikTok Shop, Shopify, Royal Mail Click & Drop, …) sized to 4x6: 4x6 pages print as-is; A4 pages are trimmed to the label and scaled up; landscape labels are turned.
- Prints many labels in one go, with copies per label; every PDF page is one label.
- Prints the Outlet Analytics packing list (Print 4x6 → Save as PDF → *Add packing list*).
- Appears in Android's **Share** menu once installed; optional "print as soon as shared".
- Test print, printer status check, darkness/speed/gap and position nudges. Settings stay on the tablet; files never leave it.

## How it talks to the printer
| | |
|---|---|
| Bluetooth | Bluetooth Classic, Serial Port Profile (SPP, UUID `00001101-0000-1000-8000-00805f9b34fb`), pair with PIN `0000`. Not BLE, so Web Bluetooth can't be used. |
| Browser API | **Web Serial** over Bluetooth RFCOMM, available in Chrome for Android 138+. Needs HTTPS. |
| Printer language | **TSPL** (TSC). Same AiYin hardware family as the JD-268BT (FCC ID 2BDL3-JD-268BT). |
| Resolution | 203 dpi = 8 dots/mm; 4x6 = 813 x 1219 dots (bitmap rows padded to 102 bytes = 816 dots). |

Each label is sent as one job:
```
SIZE 101.6 mm,152.4 mm
GAP 3 mm,0 mm
DIRECTION 0,0
REFERENCE 0,0
OFFSET 0 mm
SET TEAR ON
DENSITY 10
SPEED 4
CLS
BITMAP 0,0,102,1219,0,<raw 1-bit rows>
PRINT 1,<copies>
```
**Printer commands** (Settings): **Same as the Jadens app** (default) sends, for every label, exactly what the Jadens app sends (recorded from its Bluetooth traffic):
```
SIZE 100 mm,150 mm
DIRECTION 0,0
SET GAP ON
CLS
SETCOLOR BLACK
BITMAP 0,0,100,1200,3,<length>,<zlib data>

SPEED 4
DENSITY 5
PRINT 1,1
```
**Standard TSPL** uses the job shown above and the options below.

**Sending method** (Standard only):
- **Compressed** (default), the same format the Jadens app uses (found by recording its Bluetooth traffic): `BITMAP x,y,widthBytes,height,3,<length>,<zlib data>`. The zlib stream uses a **1 KB window** (`windowBits` 10, header byte `0x28`), and in mode 3 **1 = black**. A shipping label is about 2-10 KB. Uses pako (MIT/Zlib licence) in `vendor/pako/`.
- **Rectangles**: solid areas as `BAR x,y,w,h`, the rest as small cropped mode-0 `BITMAP`s.
- **Plain picture**: one mode-0 `BITMAP` for the whole label (about 122 KB).

`test/fast_test.py` checks all three print exactly the same dots.

Batches are sent as one stream: setup lines once, `SET TEAR OFF` for every label but the last, so the printer doesn't feed out and pull back between labels ("Print batches without stopping" in Settings).

In TSPL `BITMAP` data a **0 bit burns (black)** and a 1 bit stays white, most significant bit first. Status: send `ESC ! ?` (`1B 21 3F`), the printer replies with one byte (0 = ready; 1 lid open, 2 jam, 4 out of paper, 16 paused, 32 printing).

## Files
- `index.html`: the whole app (UI, PDF/image layout, TSPL encoding, Web Serial).
- `sw.js`: service worker; receives files shared to the app and caches the app for offline use.
- `manifest.webmanifest`: install details and the Android share target.
- `vendor/pdfjs/`: Mozilla pdf.js 6.5.438 (Apache-2.0), used to read PDFs.
- `vendor/pako/`: pako 2.2.0 deflate (MIT/Zlib), used for compressed sending.
- `test/`: `make_fixtures.py` makes sample labels; `run_test.py` loads the app in headless Chrome with a fake printer, prints, and decodes the TSPL back to PNGs in `test/out/` for checking.

Run the tests: `pip install playwright reportlab pillow && python3 test/make_fixtures.py && python3 test/run_test.py`

## Releasing a change
Bump `APP_VERSION` in `index.html` (and the `CACHE` name in `sw.js`) on every change, so the app's **Check for updates** button sees the new version.

## Ideas for later
- Send labels straight from Outlet Analytics (packing list "Print to label printer" button).
- Split A4 sheets with 4 labels per page.
