#!/usr/bin/env python3
"""Erzeugt das App-Logo „ILLEGAL FREEDOM MUSIC“ aus denselben Formen für Android (Vektor) und iPhone (PNG).

Motiv: eine Gestalt mit Kapuze und Kopfhörern sitzt bei Sonnenuntergang auf einem fahrenden Güterwagen,
dahinter Berge und Oberleitung, auf dem Wagen ein Noten-Tag. Ohne Schrift.
Koordinaten im 108×108-Raster der adaptiven Android-Symbole (sichtbar: Kreis/Quadrat um 54,54).

Aufruf:
    make_icon.py [Projektordner]                Android-Dateien schreiben
    make_icon.py [Projektordner] --preview X    zusätzlich Vorschau-PNG nach X
    make_icon.py [Projektordner] --ios          zusätzlich die iPhone-Bilder schreiben
"""
import math
import shutil
import sys

# ---------- Farben ----------

CYAN = "#FF00F0FF"
MAGENTA = "#FFFF2A6D"
BLACK = "#FF05060A"

# ---------- Formen ----------

def rot(points, cx, cy, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in points]

def rect(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]

def rounded_rect(cx, cy, w, h, r, deg, steps=10):
    pts = []
    corners = [(cx + w / 2 - r, cy - h / 2 + r, -90), (cx + w / 2 - r, cy + h / 2 - r, 0),
               (cx - w / 2 + r, cy + h / 2 - r, 90), (cx - w / 2 + r, cy - h / 2 + r, 180)]
    for ccx, ccy, start in corners:
        for i in range(steps + 1):
            t = math.radians(start + 90 * i / steps)
            pts.append((ccx + r * math.cos(t), ccy + r * math.sin(t)))
    return rot(pts, cx, cy, deg)

def ellipse(cx, cy, rx, ry, deg, steps=36):
    pts = [(cx + rx * math.cos(2 * math.pi * i / steps), cy + ry * math.sin(2 * math.pi * i / steps)) for i in range(steps)]
    return rot(pts, cx, cy, deg)

# ---------- Hilfen fürs Motiv ----------

def arc(cx, cy, r, a0, a1, steps=24):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / steps))) for i in range(steps + 1)]

# ---------- Ebenen ----------

def fill(color, polys, evenodd=False):
    return {"kind": "fill", "color": color, "polys": polys, "evenodd": evenodd}

def stroke(color, width, lines, cap="round"):
    return {"kind": "stroke", "color": color, "width": width, "lines": lines, "cap": cap}

def circle(cx, cy, r, steps=40):
    return ellipse(cx, cy, r, r, 0, steps)

def place(poly, x, y, k=1.0):
    """Setzt eine Form, die um (0,0) gezeichnet ist, an ihre Stelle"""
    return [(x + px * k, y + py * k) for px, py in poly]

# Sitzende Gestalt mit Kapuze und Rucksack, Blick nach rechts; (0,0) = Sitzfläche
SITTING = [(-0.5, 0), (-3.6, -1.5), (-4.2, -8.5), (-2.6, -10.6), (-0.4, -11.4), (-0.9, -13.8), (0.2, -16.6), (2.6, -17.9),
           (5.0, -17.2), (6.2, -15.4), (6.0, -13.2), (5.4, -12.6), (5.0, -11.8), (3.8, -11.2), (5.2, -10.2), (12.4, -7.9),
           (13.4, -6.6), (14.6, -1.3), (17.2, -0.8), (17.2, 0), (13, 0), (12, -4.2), (7.5, -1.6), (6, 0)]
SITTING_GAP = [(5.7, -8.4), (11.0, -6.7), (5.7, -5.0)]  # Lücke zwischen Arm und Bein

# Doppelte Achtelnote als Tag auf dem Wagen
NOTE = [ellipse(-4.2, 5, 3.0, 2.2, -22), ellipse(5.2, 2.4, 3.0, 2.2, -22),
        [(-2.1, 4.6), (-0.9, 4.4), (-0.9, -7.2), (-2.1, -6.9)], [(7.3, 2.0), (8.5, 1.8), (8.5, -9.8), (7.3, -9.5)],
        [(-2.1, -6.9), (8.5, -9.8), (8.5, -6.6), (-2.1, -3.7)]]

ROOF = 63.0  # Höhe des Wagendachs

def background_layers():
    """Nur Himmel und Sterne: der Hintergrund wird in der App anders zugeschnitten als der Vordergrund"""
    L = [fill(("linear", 0, 6, 0, 70, "#FF0A0826", "#FFE8482E"), [rect(0, 0, 108, 108)])]
    stars = [(12, 10), (25, 19), (38, 7), (70, 11), (84, 20), (96, 8), (18, 30), (92, 33), (60, 4)]
    L.append(fill("#CCFFFFFF", [circle(x, y, 0.45, 8) for x, y in stars]))
    return L

def foreground_layers():
    L = []
    # Sonne mit Streifen, Berge am Horizont, Boden
    L.append(fill(("radial", 57, 53, 34, "#99FFB03A", "#00FFB03A"), [circle(57, 53, 34)]))
    L.append(fill(("linear", 0, 32, 0, 74, "#FFFFE14A", "#FFFF2A6D"), [circle(57, 53, 21, 60)]))
    L.append(fill("#FFB8343C", [rect(30, y, 84, y + h) for y, h in [(56.5, 0.9), (60.2, 1.3), (64.2, 1.7)]]))
    L.append(fill("#FF2A1238", [[(0, 70), (0, 60), (10, 55), (20, 61), (31, 52), (43, 62), (60, 66), (74, 58), (86, 63),
                                 (97, 54), (108, 61), (108, 70)]]))
    L.append(fill(BLACK, [rect(0, 68, 108, 108)]))

    # Oberleitung: Masten und Drähte
    for x in (20, 90):
        L.append(stroke(BLACK, 1.1, [[(x, 26), (x, 64)], [(x - 5, 30), (x + 5, 30)], [(x - 5, 30), (x, 26)]], "butt"))
    L.append(stroke(BLACK, 0.45, [[(0, 33), (20, 30), (55, 35), (90, 30), (108, 33)],
                                  [(0, 37), (20, 34.5), (55, 39), (90, 34.5), (108, 37)]]))

    # Güterwagen mit Rippen, Kupplung, Rädern und Schiene
    for x0, x1 in [(-10, 71), (75, 130)]:
        L.append(fill(BLACK, [[(x0, ROOF + 1.2), (x0 + 1.5, ROOF), (x1 - 1.5, ROOF), (x1, ROOF + 1.2), (x1, 80), (x0, 80)]]))
        L.append(stroke("#FF232733", 0.5, [[(x, ROOF + 2.5), (x, 78.5)] for x in range(x0 + 4, x1, 6)], "butt"))
        L.append(stroke("#FF2E3444", 0.6, [[(x0 + 1.5, ROOF + 2.2), (x1 - 1.5, ROOF + 2.2)]], "butt"))
    L.append(fill(BLACK, [rect(70, 75, 76, 76.6)]))
    L.append(fill("#FF2E3444", [circle(cx, 82.5, 1.1, 12) for cx in (8, 20, 52, 64, 82, 94)]))
    L.append(fill("#FF3A4154", [rect(0, 86, 108, 86.8)]))

    # Noten-Tag auf dem Wagen (Pink mit Cyan-Saum)
    L.append(fill("#E600F0FF", [place(p, 31.0, 72, 0.62) for p in NOTE]))
    L.append(fill(MAGENTA, [place(p, 31.8, 72, 0.62) for p in NOTE]))

    # Gestalt auf dem Dach, mit Kopfhörern
    fx, k = 44.0, 1.22
    L.append(fill(BLACK, [place(SITTING, fx, ROOF, k), place(SITTING_GAP, fx, ROOF, k)], evenodd=True))
    L.append(stroke(CYAN, 0.9, [place(arc(2.7, -14.2, 3.9, 215, 330, 12), fx, ROOF, k)]))
    L.append(fill(CYAN, [place(rounded_rect(2.3, -14.0, 1.5, 2.5, 0.7, 0), fx, ROOF, k)]))

    # Fahrtwind
    L.append(stroke("#66FFFFFF", 0.5, [[(6, 45), (22, 45)], [(2, 50), (14, 50)], [(74, 44), (92, 44)], [(80, 49), (100, 49)]], "butt"))
    return L

# ---------- PNG (iPhone + Vorschau) ----------

def rgba(c):
    c = c.lstrip("#")
    return (int(c[2:4], 16), int(c[4:6], 16), int(c[6:8], 16), int(c[0:2], 16))

def render_png(size, path, crop=18, layers=None):
    """crop: Rand im 108er-Raster, der abgeschnitten wird (18 = Ausschnitt eines Android-Symbols)"""
    import numpy as np
    from PIL import Image, ImageChops, ImageDraw

    ss = 4
    N = size * ss
    scale = N / (108 - 2 * crop)
    P = lambda pts: [((x - crop) * scale, (y - crop) * scale) for x, y in pts]
    img = Image.new("RGBA", (N, N), (0, 0, 0, 255))
    yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)
    ux, uy = xx / scale + crop, yy / scale + crop

    def paint(color):
        if isinstance(color, str):
            return Image.new("RGBA", (N, N), rgba(color))
        if color[0] == "linear":
            _, x0, y0, x1, y1, c0, c1 = color
            t = ((ux - x0) * (x1 - x0) + (uy - y0) * (y1 - y0)) / ((x1 - x0) ** 2 + (y1 - y0) ** 2)
        else:
            _, cx, cy, r, c0, c1 = color
            t = np.hypot(ux - cx, uy - cy) / r
        t = np.clip(t, 0, 1)[..., None]
        a, b = np.array(rgba(c0), np.float32), np.array(rgba(c1), np.float32)
        return Image.fromarray((a + (b - a) * t).astype(np.uint8), "RGBA")

    for layer in (layers or background_layers() + foreground_layers()):
        mask = Image.new("L", (N, N), 0)
        if layer["kind"] == "fill":
            for poly in layer["polys"]:
                m = Image.new("L", (N, N), 0)
                ImageDraw.Draw(m).polygon(P(poly), fill=255)
                mask = ImageChops.difference(mask, m) if layer["evenodd"] else ImageChops.lighter(mask, m)
        else:
            d = ImageDraw.Draw(mask)
            w = layer["width"] * scale
            for line in layer["lines"]:
                pts = P(line)
                d.line(pts, fill=255, width=max(1, round(w)))
                joints = pts if layer["cap"] == "round" else pts[1:-1]
                for x, y in joints:
                    d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=255)
        colour = paint(layer["color"])
        alpha = ImageChops.multiply(colour.getchannel("A"), mask)
        colour.putalpha(255)
        img.paste(colour, (0, 0), alpha)

    img.convert("RGB").resize((size, size), Image.LANCZOS).save(path)

# ---------- Android-Vektor ----------

def path_data(polys, closed=True):
    return " ".join("M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in p) + (" Z" if closed else "") for p in polys)

def android_vector(layers):
    parts = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Erzeugt von scripts/make_icon.py – nicht von Hand bearbeiten -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    xmlns:aapt="http://schemas.android.com/aapt"',
             '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    for layer in layers:
        if layer["kind"] == "stroke":
            cap = ' android:strokeLineCap="round" android:strokeLineJoin="round"' if layer["cap"] == "round" else ""
            parts.append(f'    <path android:strokeColor="{layer["color"]}" android:strokeWidth="{layer["width"]}"{cap} '
                         f'android:pathData="{path_data(layer["lines"], closed=False)}" />')
            continue
        rule = ' android:fillType="evenOdd"' if layer["evenodd"] else ""
        data = path_data(layer["polys"])
        color = layer["color"]
        if isinstance(color, str):
            parts.append(f'    <path android:fillColor="{color}"{rule} android:pathData="{data}" />')
            continue
        parts.append(f'    <path{rule} android:pathData="{data}">')
        parts.append('        <aapt:attr name="android:fillColor">')
        if color[0] == "linear":
            _, x0, y0, x1, y1, c0, c1 = color
            parts.append(f'            <gradient android:type="linear" android:startX="{x0}" android:startY="{y0}" '
                         f'android:endX="{x1}" android:endY="{y1}"')
        else:
            _, cx, cy, r, c0, c1 = color
            parts.append(f'            <gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" '
                         f'android:gradientRadius="{r}"')
        parts.append(f'                android:startColor="{c0}" android:endColor="{c1}" />')
        parts.append('        </aapt:attr>')
        parts.append('    </path>')
    parts.append('</vector>')
    return "\n".join(parts) + "\n"

if __name__ == "__main__":
    args = sys.argv[1:]
    root = args[0] if args and not args[0].startswith("--") else "."
    res = f"{root}/android/app/src/main/res/drawable"
    open(f"{res}/ic_launcher_foreground.xml", "w").write(android_vector(foreground_layers()))
    open(f"{res}/ic_launcher_background.xml", "w").write(android_vector(background_layers()))
    if "--preview" in args:
        render_png(1024, args[args.index("--preview") + 1])
    if "--ios" in args:
        icon = f"{root}/MP3Player/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
        render_png(1024, icon)
        # Gleiches Logo als Bild in der App (neben jedem Lied)
        shutil.copyfile(icon, f"{root}/MP3Player/Assets.xcassets/Logo.imageset/Logo.png")
    print("Logo erzeugt")
