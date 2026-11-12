from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm, inch
from reportlab.pdfgen import canvas
from reportlab.graphics.barcode import code128, qr
from reportlab.graphics.shapes import Drawing
from reportlab.graphics import renderPDF
from PIL import Image, ImageDraw, ImageFont
import pathlib
out = pathlib.Path(__file__).parent / "fixtures"; out.mkdir(exist_ok=True)

def label(c, x, y, w, h, title, track):
    c.setLineWidth(1.5); c.rect(x, y, w, h)
    c.setFont("Helvetica-Bold", 16); c.drawString(x + 8, y + h - 22, title)
    c.setFont("Helvetica", 10)
    for i, t in enumerate(["DELIVER TO:", "J Smith", "12 Example Road", "Leicester", "LE1 1AA"]):
        c.drawString(x + 8, y + h - 44 - i * 13, t)
    bc = code128.Code128(track, barHeight=18 * mm, barWidth=0.9)
    bc.drawOn(c, x + 8, y + 30)
    c.setFont("Helvetica", 9); c.drawString(x + 12, y + 16, track)
    d = Drawing(60, 60); d.add(qr.QrCodeWidget(track, barWidth=60, barHeight=60)); renderPDF.draw(d, c, x + w - 70, y + h - 75)

# 1. Royal Mail Click & Drop style: A4 portrait, landscape 6x4 label top-left, packing slip below.
c = canvas.Canvas(str(out / "clickdrop_a4.pdf"), pagesize=A4)
label(c, 15 * mm, A4[1] - 15 * mm - 4 * inch, 6 * inch, 4 * inch, "Royal Mail Tracked 48", "RM123456785GB")
c.setFont("Helvetica", 11); c.drawString(15 * mm, 100 * mm, "PACKING SLIP  Order #1001  1 x Wiring connector")
c.showPage(); c.save()
# 1b. A4 with only the label (auto trim should find it).
c = canvas.Canvas(str(out / "a4_label_only.pdf"), pagesize=A4)
label(c, 30 * mm, 60 * mm, 4 * inch, 6 * inch, "Evri Parcel", "H01ABC0012345678")
c.showPage(); c.save()
# 2. eBay 4x6 portrait, 2 labels in one PDF.
c = canvas.Canvas(str(out / "ebay_4x6_two.pdf"), pagesize=(4 * inch, 6 * inch))
for t in ["EBAY-0001", "EBAY-0002"]:
    label(c, 6, 6, 4 * inch - 12, 6 * inch - 12, "eBay Delivery " + t, t); c.showPage()
c.save()
# 3. Shopify 6x4 landscape.
c = canvas.Canvas(str(out / "shopify_6x4_landscape.pdf"), pagesize=(6 * inch, 4 * inch))
label(c, 6, 6, 6 * inch - 12, 4 * inch - 12, "Shopify Shipping", "SHOP-998877"); c.showPage(); c.save()
# 4. Image (like a screenshot), with grey margins.
im = Image.new("RGB", (1200, 1800), "white"); d = ImageDraw.Draw(im)
d.rectangle([100, 100, 1100, 1700], outline="black", width=6)
d.text((150, 150), "IMAGE LABEL", fill="black", font=ImageFont.load_default(size=80))
for i in range(40): d.rectangle([200 + i * 20, 400, 200 + i * 20 + (6 if i % 3 else 12), 700], fill="black")
im.save(out / "label_image.png")
# 5. Packing list 4x6, 2 pages (like Outlet Analytics' Save as PDF).
c = canvas.Canvas(str(out / "packing_list.pdf"), pagesize=(4 * inch, 6 * inch))
n = 0
for page in range(2):
    c.setFont("Helvetica-Bold", 13); c.drawString(9, 6 * inch - 20, f"Packing list  (page {page+1})")
    c.setFont("Helvetica", 9)
    for r in range(22):
        n += 1; y = 6 * inch - 40 - r * 18
        c.rect(9, y - 2, 9, 9); c.drawString(24, y, f"T{n:03d}-SKU   x{(n % 3) + 1}   Auto connector {n} pin wiring loom")
    c.showPage()
c.save()
print(sorted(p.name for p in out.iterdir()))
