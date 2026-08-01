"""Assemble the Play Store screenshot set from raw device captures.

Everything here is a real capture from a Pixel 7. The only edit is on the answer
screenshot: the debug build shows "Free today: 2/2", but production allows 10/day
(UsageRepository.limitPerDay = if (BuildConfig.DEBUG) 2 else 10). Publishing the
debug number would understate the free tier, so that band is removed and the app
bar re-joined to it. Both cut points sit on plain background, so the seam is
invisible.
"""
from PIL import Image
import pathlib
import shutil

SP = pathlib.Path(__file__).parent
OUT = pathlib.Path(r"c:/Projects/AI Study Scanner/docs/store-screenshots")
OUT.mkdir(parents=True, exist_ok=True)

# Straight copies — none of these show the debug quota line.
COPIES = [
    ("step2-app.png", "01-home.png"),
    ("shot-boards.png", "02-exam-boards.png"),
    ("shot-planner-form.png", "04-planner-setup.png"),
    ("shot-plan-top.png", "05-study-plan.png"),
    ("shot-plan-months.png", "06-plan-milestones.png"),
]

for src, dst in COPIES:
    shutil.copyfile(SP / src, OUT / dst)
    with Image.open(OUT / dst) as im:
        print(f"{dst:26} {im.size[0]}x{im.size[1]}")

# The answer shot, with the debug quota band and its buttons removed.
KEEP_TOP = 260        # status bar + "AI Agent Solution" app bar
RESUME_AT = 600       # just above the "Detected" chips
with Image.open(SP / "step6-answer-scrolled.png") as im:
    w, h = im.size
    top = im.crop((0, 0, w, KEEP_TOP))
    body = im.crop((0, RESUME_AT, w, h))
    out = Image.new("RGB", (w, KEEP_TOP + body.size[1]))
    out.paste(top, (0, 0))
    out.paste(body, (0, KEEP_TOP))
    path = OUT / "03-solved-answer.png"
    out.save(path, "PNG")
    ratio = out.size[1] / out.size[0]
    print(f"{path.name:26} {out.size[0]}x{out.size[1]}  ratio 1:{ratio:.2f}"
          f"  {'OK' if ratio <= 2 else 'TOO TALL for Play'}")
