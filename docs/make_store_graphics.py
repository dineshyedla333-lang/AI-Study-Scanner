"""Generate the Play Store graphics at Google's exact required sizes.

Play's asset sizes are fixed and small — there is no 4K option, and
anything other than the exact dimensions is rejected:

  app icon        512 x 512   (32-bit PNG)
  feature graphic 1024 x 500
  screenshots     up to 3840 px (see build_store_screenshots.py)

The icon is downsampled from a 1024 master with LANCZOS, which is the sharpest
result available at 512. The feature graphic deliberately carries no app name:
the title is still being decided, Play renders the name alongside the graphic
anyway, and a benefit headline with exam keywords does more work than a logotype.
"""
from PIL import Image, ImageDraw, ImageFont
import pathlib

DOCS = pathlib.Path(__file__).parent
BRAND = (103, 80, 164)      # #6750A4, the app's primary purple
BRAND_DEEP = (58, 41, 104)  # darker end of the gradient


def load_font(size, bold=True):
    names = (
        ["segoeuib.ttf", "arialbd.ttf", "calibrib.ttf"]
        if bold
        else ["segoeui.ttf", "arial.ttf", "calibri.ttf"]
    )
    for n in names:
        try:
            return ImageFont.truetype(f"C:/Windows/Fonts/{n}", size)
        except OSError:
            continue
    return ImageFont.load_default()


def build_icon():
    """512 x 512 exactly — the only size Play accepts for an app icon."""
    master = DOCS / "app-icon-1024-master.png"
    if not master.exists():
        # First run: promote the existing 1024 file to be the master.
        legacy = DOCS / "app-icon-512.png"
        with Image.open(legacy) as im:
            im.convert("RGBA").save(master, "PNG")

    with Image.open(master) as im:
        icon = im.convert("RGBA").resize((512, 512), Image.LANCZOS)
    out = DOCS / "app-icon-512.png"
    icon.save(out, "PNG")
    print(f"{out.name:26} {icon.size[0]}x{icon.size[1]}"
          "  (Play requires exactly 512x512)")
    return master


def build_feature_graphic(master):
    """1024 x 500 exactly. Text stays well inside the edges: Play crops
    this graphic per surface and edge text is the first casualty."""
    W, H = 1024, 500
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)

    # Diagonal-ish gradient, brand purple into a deeper shade.
    for y in range(H):
        t = y / (H - 1)
        d.line(
            [(0, y), (W, y)],
            fill=(
                int(BRAND[0] + (BRAND_DEEP[0] - BRAND[0]) * t),
                int(BRAND[1] + (BRAND_DEEP[1] - BRAND[1]) * t),
                int(BRAND[2] + (BRAND_DEEP[2] - BRAND[2]) * t),
            ),
        )

    # App icon on the left, comfortably inside the safe area.
    icon_px = 260
    with Image.open(master) as im:
        icon = im.convert("RGBA").resize((icon_px, icon_px), Image.LANCZOS)
    img.paste(icon, (72, (H - icon_px) // 2), icon)

    text_x = 72 + icon_px + 56
    # Sized so the longest line keeps a right margin matching the left inset.
    # Play crops this graphic differently per surface and edge text is lost first.
    headline = load_font(58, bold=True)
    sub = load_font(36, bold=False)

    # Benefit first, in the student's words — not the product's architecture.
    lines = ["Scan any question.", "Get the full steps."]
    total = len(lines) * 78
    y = (H - total) // 2 - 26
    for line in lines:
        d.text((text_x, y), line, font=headline, fill="white")
        y += 78

    d.text((text_x, y + 14), "JEE  ·  NEET  ·  CBSE  ·  UPSC",
           font=sub, fill=(233, 221, 255))

    out = DOCS / "feature-graphic.png"
    img.save(out, "PNG")
    print(f"{out.name:26} {W}x{H}"
          "  (Play requires exactly 1024x500)")


if __name__ == "__main__":
    master = build_icon()
    build_feature_graphic(master)
