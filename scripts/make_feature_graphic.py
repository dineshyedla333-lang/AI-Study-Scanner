"""Generate a 1024x500 Play Store feature graphic for AI Study Scan Agent."""
from PIL import Image, ImageDraw, ImageFont
import os

W, H = 1024, 500
PURPLE = (103, 80, 164)      # brand #6750A4
PURPLE_DK = (74, 56, 122)
WHITE = (255, 255, 255)
LIGHT = (230, 224, 245)

img = Image.new("RGB", (W, H), PURPLE)
draw = ImageDraw.Draw(img)

# Vertical gradient (purple -> darker purple)
for y in range(H):
    t = y / H
    r = int(PURPLE[0] * (1 - t) + PURPLE_DK[0] * t)
    g = int(PURPLE[1] * (1 - t) + PURPLE_DK[1] * t)
    b = int(PURPLE[2] * (1 - t) + PURPLE_DK[2] * t)
    draw.line([(0, y), (W, y)], fill=(r, g, b))


def font(size, bold=True):
    candidates = [
        r"C:\Windows\Fonts\segoeuib.ttf" if bold else r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


# Paste the app icon on the left (rounded)
icon_path = os.path.join(os.path.dirname(__file__), "..", "docs", "app-icon-512.png")
if os.path.exists(icon_path):
    icon = Image.open(icon_path).convert("RGBA").resize((300, 300))
    # rounded mask
    mask = Image.new("L", (300, 300), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 300, 300], radius=60, fill=255)
    img.paste(icon, (70, (H - 300) // 2), mask)

# Title + tagline on the right
tx = 430
draw.text((tx, 150), "AI Study", font=font(78), fill=WHITE)
draw.text((tx, 235), "Scan Agent", font=font(78), fill=WHITE)
draw.text((tx, 340), "Scan • Solve • Practice • UPSC Daily",
          font=font(30, bold=False), fill=LIGHT)

out = os.path.join(os.path.dirname(__file__), "..", "docs", "feature-graphic.png")
img.save(out, "PNG")
print("Saved", os.path.abspath(out), img.size)
