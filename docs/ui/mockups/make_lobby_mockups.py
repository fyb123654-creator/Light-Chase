# -*- coding: utf-8 -*-
"""Lobby mockups for Light Chase. Draws at 2x then downsamples for anti-aliasing."""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SCALE = 2
W, H = 1280, 720
OUT = os.path.dirname(os.path.abspath(__file__))

F = r"C:\Windows\Fonts"
def font(name, size):
    return ImageFont.truetype(os.path.join(F, name), size * SCALE)

SEGOE_B  = lambda s: font("segoeuib.ttf", s)
SEGOE    = lambda s: font("segoeui.ttf", s)
SEGOE_SB = lambda s: font("seguisb.ttf", s)
MONO     = lambda s: font("consola.ttf", s)
MONO_B   = lambda s: font("consolab.ttf", s)


def canvas(bg):
    im = Image.new("RGB", (W * SCALE, H * SCALE), bg)
    return im, ImageDraw.Draw(im)


def px(v):
    return int(round(v * SCALE))


def text(d, xy, s, f, fill, tracking=0):
    x, y = px(xy[0]), px(xy[1])
    if tracking == 0:
        d.text((x, y), s, font=f, fill=fill)
        return
    for ch in s:
        d.text((x, y), ch, font=f, fill=fill)
        x += d.textlength(ch, font=f) + px(tracking)


def tw(d, s, f, tracking=0):
    if tracking == 0:
        return d.textlength(s, font=f) / SCALE
    return sum(d.textlength(c, font=f) for c in s) / SCALE + tracking * (len(s) - 1)


def chamfer_box(d, x, y, w, h, cut, fill=None, outline=None, width=1):
    p = [(x + cut, y), (x + w, y), (x + w, y + h - cut), (x + w - cut, y + h), (x, y + h), (x, y + cut)]
    p = [(px(a), px(b)) for a, b in p]
    if fill:
        d.polygon(p, fill=fill)
    if outline:
        d.line(p + [p[0]], fill=outline, width=px(width), joint="curve")


def hairline(d, x1, y1, x2, y2, fill, width=1):
    d.line([px(x1), px(y1), px(x2), px(y2)], fill=fill, width=max(1, px(width)))


def vignette(im, strength=90):
    v = Image.new("L", (W * SCALE, H * SCALE), 0)
    dv = ImageDraw.Draw(v)
    dv.ellipse([-px(320), -px(260), px(W + 320), px(H + 260)], fill=strength)
    v = v.filter(ImageFilter.GaussianBlur(px(160)))
    black = Image.new("RGB", im.size, (0, 0, 0))
    return Image.composite(im, black, v.point(lambda p: 130 + p // 3))


# ---------------------------------------------------------------- style A: nocturne amber
def style_a(path):
    BG, PANEL, LINE = (11, 15, 23), (18, 26, 38), (36, 49, 74)
    TXT, DIM, AMBER, AMBER_DK = (232, 237, 245), (138, 151, 171), (245, 165, 36), (90, 61, 14)
    im, d = canvas(BG)
    vignette(im, 120)
    d = ImageDraw.Draw(im)

    # top bar
    d.rectangle([0, 0, px(W), px(64)], fill=(9, 12, 18))
    hairline(d, 0, 64, W, 64, LINE)
    text(d, (40, 22), "LIGHT CHASE", SEGOE_B(20), AMBER, tracking=1.2)
    text(d, (232, 26), "/  LOBBY", SEGOE(16), DIM, tracking=0.6)
    text(d, (W - 340, 26), "ROOM  DEMO", MONO_B(16), TXT, tracking=0.4)
    text(d, (W - 150, 26), "REV 3", MONO(16), DIM)

    # hero
    text(d, (40, 116), "LOBBY", SEGOE_B(64), TXT, tracking=-1)
    text(d, (40, 200), "MAP  test-room      PHASE  LOBBY", MONO(15), DIM, tracking=0.3)
    d.rectangle([px(40), px(236), px(148), px(240)], fill=AMBER)

    # role cards
    cards = [
        ("POLICE", "AMBER SWEEP - 60 deg, 4 tiles", True,  "YOU"),
        ("THIEF",  "THREE DROPS - then the exits open", False, "OPPONENT"),
    ]
    cx, cy, cw, ch, gap = 40, 272, 372, 244, 24
    for i, (name, hint, sel, who) in enumerate(cards):
        x = cx + i * (cw + gap)
        accent = AMBER if sel else LINE
        d.rounded_rectangle([px(x), px(cy), px(x + cw), px(cy + ch)], radius=px(10),
                            fill=PANEL, outline=accent, width=px(2 if sel else 1))
        tag = who
        text(d, (x + 28, cy + 28), tag, SEGOE_SB(13), AMBER if sel else DIM, tracking=1.4)
        text(d, (x + 28, cy + 60), name, SEGOE_B(38), TXT if sel else DIM, tracking=0.4)
        text(d, (x + 28, cy + 118), hint, SEGOE(15), DIM)
        if sel:
            d.rounded_rectangle([px(x + 28), px(cy + ch - 62), px(x + 148), px(cy + ch - 26)],
                                radius=px(4), fill=(38, 30, 12), outline=AMBER_DK, width=px(1))
            text(d, (x + 46, cy + ch - 54), "SELECTED", SEGOE_SB(13), AMBER, tracking=1.2)
        else:
            text(d, (x + 28, cy + ch - 54), "TAP TO PICK", SEGOE_SB(13), DIM, tracking=1.2)

    # right column: players
    rx = cx + 2 * (cw + gap)
    rw = W - 40 - rx
    d.rounded_rectangle([px(rx), px(cy), px(rx + rw), px(cy + ch)], radius=px(10), fill=(14, 20, 30), outline=LINE)
    text(d, (rx + 24, cy + 26), "PLAYERS", SEGOE_SB(13), DIM, tracking=1.4)
    rows = [("YOU", "POLICE", "READY", True), ("OPPONENT", "-", "WAITING", False)]
    for i, (who, role, state, ready) in enumerate(rows):
        y = cy + 62 + i * 78
        dot = AMBER if ready else (60, 74, 96)
        d.ellipse([px(rx + 24), px(y + 6), px(rx + 36), px(y + 18)], fill=dot)
        text(d, (rx + 48, y), who, SEGOE_SB(15), TXT if ready else DIM, tracking=0.6)
        text(d, (rx + 48, y + 26), role + "  /  " + state, MONO(13), DIM)
        if i == 0:
            hairline(d, rx + 24, y + 58, rx + rw - 24, y + 58, (30, 40, 58))
    hy = cy + ch - 74
    hairline(d, rx + 24, hy, rx + rw - 24, hy, (30, 40, 58))
    text(d, (rx + 24, hy + 18), "READY", SEGOE_SB(12), DIM, tracking=1.4)
    text(d, (rx + 24, hy + 36), "1 / 2", MONO_B(20), AMBER)
    text(d, (rx + 118, hy + 40), "countdown starts at 2 / 2", MONO(11), DIM)

    # actions
    by = 556
    d.rounded_rectangle([px(40), px(by), px(268), px(by + 68)], radius=px(8), fill=AMBER)
    text(d, (40 + 74, by + 22), "READY", SEGOE_B(20), (11, 15, 23), tracking=1.6)
    d.rounded_rectangle([px(288), px(by), px(516), px(by + 68)], radius=px(8),
                        fill=None, outline=LINE, width=px(1))
    text(d, (288 + 40, by + 22), "LEAVE ROOM", SEGOE_SB(18), DIM, tracking=0.8)
    text(d, (560, by + 26), "Both players must be ready before the countdown starts.",
         SEGOE(15), DIM)

    hairline(d, 0, 664, W, 664, LINE)
    text(d, (40, 682), "HOLD  ready   /   TAP  role card to switch", MONO(13), (90, 104, 128))
    im.resize((W, H), Image.LANCZOS).save(path)


# ---------------------------------------------------------------- style B: steel neon
def style_b(path):
    BG, PANEL, LINE = (8, 10, 15), (16, 21, 29), (26, 34, 46)
    TXT, DIM, CYAN, MAGENTA = (242, 247, 255), (110, 124, 143), (0, 229, 255), (255, 46, 136)
    im, d = canvas(BG)
    # faint grid
    for gx in range(0, W + 1, 64):
        hairline(d, gx, 0, gx, H, (13, 17, 24))
    for gy in range(0, H + 1, 64):
        hairline(d, 0, gy, W, gy, (13, 17, 24))
    d = ImageDraw.Draw(im)

    chamfer_box(d, 0, 0, W, 54, 0, fill=(10, 13, 19))
    hairline(d, 0, 54, W, 54, LINE)
    text(d, (36, 18), "LIGHT CHASE", SEGOE_B(18), TXT, tracking=2.0)
    text(d, (250, 20), "// LOBBY", MONO_B(15), CYAN, tracking=1.0)
    text(d, (W - 250, 20), "DEMO  3", MONO(15), DIM, tracking=0.6)
    d.rectangle([px(W - 268), px(20), px(W - 258), px(34)], fill=CYAN)

    text(d, (36, 92), "SELECT ROLE", SEGOE_SB(13), DIM, tracking=2.4)
    d.rectangle([px(36), px(114), px(158), px(116)], fill=CYAN)

    # two big vertical cards
    cw, ch, cy = 292, 336, 142
    for i, (name, line1, line2, sel) in enumerate([
        ("POLICE", "FLASHLIGHT 60 deg", "RANGE 4 / MARK 24t", True),
        ("THIEF", "3 DROPS, THEN EXIT", "CARRY 1 / INTERACT 30t", False),
    ]):
        x = 36 + i * (cw + 20)
        accent = CYAN if sel else LINE
        chamfer_box(d, x, cy, cw, ch, 16, fill=PANEL, outline=accent, width=2 if sel else 1)
        if sel:
            chamfer_box(d, x, cy, cw, 4, 0, fill=CYAN)
        text(d, (x + 26, cy + 30), "0" + str(i + 1), MONO_B(13), CYAN if sel else DIM, tracking=1.0)
        text(d, (x + 26, cy + 58), name, SEGOE_B(34), TXT if sel else DIM, tracking=1.6)
        text(d, (x + 26, cy + 116), line1, MONO(12), DIM, tracking=0.2)
        text(d, (x + 26, cy + 138), line2, MONO(12), DIM, tracking=0.2)
        gx, gy = x + 26, cy + 176
        if i == 0:
            d.polygon([(px(gx), px(gy)), (px(gx + 148), px(gy + 26)), (px(gx + 148), px(gy + 62)), (px(gx), px(gy + 88))], fill=(0, 60, 72) if sel else (24, 32, 42))
            d.polygon([(px(gx), px(gy + 22)), (px(gx + 92), px(gy + 34)), (px(gx + 92), px(gy + 54)), (px(gx), px(gy + 66))], fill=(0, 120, 140) if sel else (30, 40, 52))
        else:
            for k in range(3):
                d.rectangle([px(gx + k * 34), px(gy + 22), px(gx + 22 + k * 34), px(gy + 62)], fill=(28, 34, 46))
        tag = "SELECTED" if sel else "AVAILABLE"
        text(d, (x + 26, cy + ch - 44), tag, MONO_B(12), CYAN if sel else DIM, tracking=1.4)

    # right status column
    rx = 36 + 2 * (cw + 20) + 12
    rw = W - 36 - rx
    chamfer_box(d, rx, cy, rw, ch, 16, fill=(10, 13, 19), outline=LINE)
    text(d, (rx + 24, cy + 28), "ROOM", MONO_B(12), DIM, tracking=1.6)
    text(d, (rx + 24, cy + 50), "DEMO", SEGOE_B(30), TXT, tracking=1.0)
    hairline(d, rx + 24, cy + 104, rx + rw - 24, cy + 104, LINE)
    for i, (k, v, hot) in enumerate([("MAP", "test-room", False), ("PHASE", "LOBBY", False),
                                    ("YOU", "POLICE / READY", True), ("OPPONENT", "WAITING", False)]):
        y = cy + 124 + i * 42
        text(d, (rx + 24, y), k, MONO(12), DIM, tracking=1.0)
        text(d, (rx + 24, y + 16), v, MONO_B(14) if hot else MONO(14), CYAN if hot else TXT)

    # actions
    by = 512
    chamfer_box(d, 36, by, 232, 64, 14, fill=CYAN)
    text(d, (36 + 62, by + 20), "READY", SEGOE_B(20), (8, 10, 15), tracking=2.2)
    chamfer_box(d, 288, by, 232, 64, 14, fill=None, outline=MAGENTA, width=1)
    text(d, (288 + 40, by + 22), "LEAVE ROOM", SEGOE_SB(17), MAGENTA, tracking=1.2)
    text(d, (556, by + 24), "BOTH READY -> 3s COUNTDOWN", MONO(13), DIM, tracking=0.8)

    hairline(d, 0, 640, W, 640, LINE)
    text(d, (36, 658), "TAP ROLE CARD  /  READY  /  LEAVE", MONO(12), (80, 92, 110), tracking=0.8)
    im.resize((W, H), Image.LANCZOS).save(path)


# ---------------------------------------------------------------- style C: blackout line
def style_c(path):
    BG, TXT, DIM = (10, 10, 10), (245, 245, 245), (122, 122, 122)
    RED = (224, 58, 43)
    im, d = canvas(BG)

    # big type, generous margin
    text(d, (72, 64), "LIGHT", SEGOE_B(58), TXT, tracking=2)
    text(d, (72, 128), "CHASE", SEGOE_B(58), (70, 70, 70), tracking=2)
    text(d, (74, 214), "LOBBY", MONO_B(14), RED, tracking=4)

    hairline(d, 72, 250, W - 72, 250, (38, 38, 38))

    # two choices as typographic rows
    rows = [("01", "POLICE", "flashlight / mark three times", True),
            ("02", "THIEF", "three drops / then escape", False)]
    y = 292
    for num, name, hint, sel in rows:
        col = TXT if sel else (168, 168, 168)
        text(d, (72, y + 6), num, MONO(14), RED if sel else (70, 70, 70), tracking=2)
        text(d, (120, y), name, SEGOE_B(40), col, tracking=1.6)
        text(d, (120, y + 52), hint, SEGOE(15), (150, 150, 150) if sel else DIM)
        if not sel:
            text(d, (120, y + 82), "TAP TO PICK", MONO(11), (86, 86, 86), tracking=2)
        if sel:
            d.rectangle([px(120), px(y + 82), px(320), px(y + 84)], fill=RED)
        y += 120

    # status block right
    text(d, (W - 380, 292), "ROOM", MONO_B(12), DIM, tracking=3)
    text(d, (W - 380, 312), "DEMO", SEGOE_B(30), TXT, tracking=1.2)
    text(d, (W - 380, 372), "MAP", MONO_B(12), DIM, tracking=3)
    text(d, (W - 380, 392), "test-room", MONO(17), TXT, tracking=0.4)
    text(d, (W - 380, 440), "YOU", MONO_B(12), DIM, tracking=3)
    text(d, (W - 380, 460), "POLICE / READY", MONO_B(15), RED, tracking=0.6)
    text(d, (W - 380, 508), "OPPONENT", MONO_B(12), DIM, tracking=3)
    text(d, (W - 380, 528), "waiting", MONO(15), (90, 90, 90), tracking=0.6)

    hairline(d, 72, 574, W - 72, 574, (38, 38, 38))

    # actions: only the primary is filled
    d.rectangle([px(72), px(604), px(300), px(664)], fill=TXT)
    text(d, (72 + 78, 620), "READY", SEGOE_B(19), (10, 10, 10), tracking=2.4)
    text(d, (340, 624), "LEAVE ROOM", SEGOE_SB(17), DIM, tracking=1.6)
    hairline(d, 340, 650, 340 + tw(d, "LEAVE ROOM", SEGOE_SB(17), 1.6), 650, (60, 60, 60))

    text(d, (72, 686), "BOTH READY -> COUNTDOWN 3s", MONO(12), (86, 86, 86), tracking=1.0)
    im.resize((W, H), Image.LANCZOS).save(path)


if __name__ == "__main__":
    style_a(os.path.join(OUT, "lobby-a-nocturne.png"))
    style_b(os.path.join(OUT, "lobby-b-steel.png"))
    style_c(os.path.join(OUT, "lobby-c-blackout.png"))
    for f in ("lobby-a-nocturne.png", "lobby-b-steel.png", "lobby-c-blackout.png"):
        p = os.path.join(OUT, f)
        print(f, os.path.getsize(p), "bytes")
