#!/usr/bin/env python3
"""Mask real names/phones/emails with demo data in IPDial screenshots."""
from PIL import Image, ImageDraw, ImageFont
import statistics

SCREENS = "/Users/igeneration/AndroidStudioProjects/ipdial/IPDial/hosting/public/screens"
FONT = "/System/Library/Fonts/Helvetica.ttc"

TEXT_DARK = (24, 32, 34)       # onSurface dark
TEXT_MUTED = (86, 110, 120)    # muted gray-green
TEAL = (0, 104, 122)           # primary/teal

def lfont(size):
    return ImageFont.truetype(FONT, size)

def region_bg(im, x0, y0, x1, y1):
    reg = im.crop((x0, y0, x1, y1))
    r = statistics.median(list(reg.getchannel(0).getdata()))
    g = statistics.median(list(reg.getchannel(1).getdata()))
    b = statistics.median(list(reg.getchannel(2).getdata()))
    return (int(r), int(g), int(b))

def put(dr, im, x0, y0, x1, y1, text, size, color, align="left", pad=6):
    """Paint over [x0,y0,x1,y1] with region bg, draw centered text."""
    bg = region_bg(im, x0, y0, x1, y1)
    dr.rectangle([x0 - pad, y0 - pad, x1 + pad, y1 + pad], fill=bg)
    font = lfont(size)
    w, h = font.getbbox(text)[2:4]
    tx = x0 if align == "left" else (x0 + x1) / 2 - w / 2
    ty = (y0 + y1) / 2 - h / 2
    dr.text((tx, ty), text, font=font, fill=color)

def topbar(dr, im):
    put(dr, im, 906, 89, 1000, 119, "DemoSIP", 26, TEAL, "center")
    put(dr, im, 516, 90, 590, 120, "৳25.0", 24, TEAL, "center")

# ── home.png ──
im = Image.open(f"{SCREENS}/home.png").convert("RGB")
dr = ImageDraw.Draw(im)
topbar(dr, im)
# favorites labels (name under avatar)
favs = [(32, 448, 170, 488, "John"), (192, 448, 330, 488, "Jane"),
        (352, 448, 490, 488, "Mike"), (512, 448, 650, 488, "Sara")]
for x0, y0, x1, y1, nm in favs:
    put(dr, im, x0, y0, x1, y1, nm, 22, TEXT_DARK, "center")
# recent rows: number + channel subtitle
put(dr, im, 148, 592, 420, 640, "555-0100", 26, TEXT_DARK)
put(dr, im, 184, 640, 510, 668, "DemoSIP • 52 min ago • 00:06", 18, TEXT_MUTED)
put(dr, im, 148, 744, 420, 792, "555-0112", 26, TEXT_DARK)
put(dr, im, 184, 792, 450, 820, "DemoSIP • 10:23 PM • 00:08", 18, TEXT_MUTED)
im.save(f"{SCREENS}/home.png")
print("home.png done")

# ── contacts.png ── (name + numbers block per contact)
im = Image.open(f"{SCREENS}/contacts.png").convert("RGB")
dr = ImageDraw.Draw(im)
topbar(dr, im)
blocks = [
    (455, 575, "John Doe", "555-0100"),
    (586, 738, "Jane Smith", "555-0112"),
    (762, 914, "Michael Brown", "555-0123"),
    (938, 1090, "Sarah Wilson", "555-0134"),
    (1114, 1266, "David Lee", "555-0145"),
    (1303, 1423, "Emily Davis", "555-0156"),
    (1447, 1567, "Chris Evans", "555-0167"),
    (1591, 1711, "Anna Taylor", "555-0178"),
]
for y0, y1, nm, num in blocks:
    bg = region_bg(im, 160, y0, 500, y1)
    dr.rectangle([154, y0 - 4, 660, y1 + 6], fill=bg)
    put(dr, im, 160, y0, 500, y0 + 42, nm, 26, TEXT_DARK)
    put(dr, im, 160, y0 + 44, 380, y1, num, 20, TEXT_MUTED)
im.save(f"{SCREENS}/contacts.png")
print("contacts.png done")

# ── logs.png ── (same as home content)
im = Image.open(f"{SCREENS}/logs.png").convert("RGB")
dr = ImageDraw.Draw(im)
topbar(dr, im)
for x0, y0, x1, y1, nm in favs:
    put(dr, im, x0, y0, x1, y1, nm, 22, TEXT_DARK, "center")
put(dr, im, 148, 592, 420, 640, "555-0100", 26, TEXT_DARK)
put(dr, im, 184, 640, 510, 668, "DemoSIP • 58 min ago • 00:06", 18, TEXT_MUTED)
put(dr, im, 148, 744, 420, 792, "555-0112", 26, TEXT_DARK)
put(dr, im, 184, 792, 450, 820, "DemoSIP • 10:23 PM • 00:08", 18, TEXT_MUTED)
im.save(f"{SCREENS}/logs.png")
print("logs.png done")

# ── accounts.png ──
im = Image.open(f"{SCREENS}/accounts.png").convert("RGB")
dr = ImageDraw.Draw(im)
# balance value + icon inside chip
put(dr, im, 80, 522, 175, 572, "৳25.00", 26, TEAL, "left")
# account name (second occurrence)
put(dr, im, 224, 495, 340, 535, "DemoSIP", 24, TEXT_DARK, "left")
# sip uri
put(dr, im, 224, 533, 620, 561, "5550100@demo.sip", 18, TEXT_MUTED, "left")
im.save(f"{SCREENS}/accounts.png")
print("accounts.png done")

# ── menu.png ──
im = Image.open(f"{SCREENS}/menu.png").convert("RGB")
dr = ImageDraw.Draw(im)
put(dr, im, 168, 550, 300, 598, "DemoSIP", 24, TEXT_DARK)
put(dr, im, 168, 599, 660, 627, "5550100@demo.sip • Online", 18, TEXT_MUTED)
put(dr, im, 148, 730, 400, 766, "999 days remaining", 20, TEXT_DARK)
put(dr, im, 156, 814, 480, 858, "John Doe", 28, TEXT_DARK)
put(dr, im, 156, 853, 520, 884, "john.doe@gmail.com", 20, TEXT_MUTED)
put(dr, im, 168, 886, 360, 911, "My ID: JD1234", 20, TEXT_MUTED)
im.save(f"{SCREENS}/menu.png")
print("menu.png done")

# ── keypad.png ── (clean, no suggestions)
im = Image.open(f"{SCREENS}/keypad.png").convert("RGB")
dr = ImageDraw.Draw(im)
topbar(dr, im)
im.save(f"{SCREENS}/keypad.png")
print("keypad.png done")

# ── keypad_dial.png ── (dial 5550137 + suggestion rows)
im = Image.open(f"{SCREENS}/keypad_dial.png").convert("RGB")
dr = ImageDraw.Draw(im)
topbar(dr, im)
# suggestion rows: name + number
sugg = [
    (184, 268, "John Doe", "555-0100"),
    (303, 386, "Jane Smith", "555-0112"),
    (422, 505, "Michael Brown", "555-0123"),
    (541, 624, "Sarah Wilson", "555-0134"),
    (660, 743, "David Lee", "555-0145"),
]
for y0, y1, nm, num in sugg:
    bg = region_bg(im, 130, y0, 500, y1)
    dr.rectangle([124, y0 - 4, 700, y1 + 6], fill=bg)
    put(dr, im, 136, y0, 600, y0 + 40, nm, 22, TEXT_DARK)
    put(dr, im, 136, y0 + 42, 380, y1, num, 18, TEXT_MUTED)
# dial display
put(dr, im, 144, 922, 936, 1018, "5550137", 44, TEXT_DARK, "center")
im.save(f"{SCREENS}/keypad_dial.png")
print("keypad_dial.png done")
print("\nAll screenshots masked.")