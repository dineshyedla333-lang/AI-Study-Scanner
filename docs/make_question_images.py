"""Render textbook-style question images to feed the app's OCR for store screenshots.

Plain white background, large high-contrast serif text: ML Kit reads this far more
reliably than a photo of a real page, and the resulting screenshot still looks like a
scanned question because the app shows the extracted text, not the image.
"""
from PIL import Image, ImageDraw, ImageFont
import pathlib

OUT = pathlib.Path(__file__).parent


def load_font(size, bold=False):
    names = (
        ["georgiab.ttf", "timesbd.ttf", "arialbd.ttf"]
        if bold
        else ["georgia.ttf", "times.ttf", "arial.ttf"]
    )
    for n in names:
        try:
            return ImageFont.truetype(f"C:/Windows/Fonts/{n}", size)
        except OSError:
            continue
    return ImageFont.load_default()


def wrap(draw, text, font, max_w):
    words, lines, cur = text.split(), [], ""
    for w in words:
        trial = f"{cur} {w}".strip()
        if draw.textlength(trial, font=font) <= max_w:
            cur = trial
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def render(filename, number, question, marks=None):
    W, H = 1400, 700
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)

    body = load_font(52)
    num_f = load_font(52, bold=True)
    small = load_font(34)

    margin = 70
    x = margin
    y = 150

    # Question number, then the question text indented past it.
    num = f"{number}."
    d.text((x, y), num, font=num_f, fill="black")
    text_x = x + d.textlength(num, font=num_f) + 24

    for line in wrap(d, question, body, W - text_x - margin):
        d.text((text_x, y), line, font=body, fill="black")
        y += 74

    # Marks go on their own line under the question, right-aligned, so they can
    # never collide with the wrapped text.
    if marks:
        d.text((W - margin - d.textlength(marks, font=small), y + 6), marks,
               font=small, fill="#444444")
        y += 56

    # Faint rules top and bottom so it reads as a page crop, not a slide.
    d.line([(margin, 100), (W - margin, 100)], fill="#cccccc", width=3)
    d.line([(margin, y + 40), (W - margin, y + 40)], fill="#cccccc", width=3)

    path = OUT / filename
    img.save(path, "PNG")
    print(f"{path}  ({W}x{H})")


render(
    "question-physics.png",
    17,
    "A ball is thrown vertically upwards with a speed of 20 m/s. "
    "Find the maximum height reached and the total time before it "
    "returns to the thrower's hand. Take g = 10 m/s2.",
    marks="[3 marks]",
)

render(
    "question-maths.png",
    8,
    "Solve for x: 2x2 - 7x + 3 = 0",
    marks="[2 marks]",
)
