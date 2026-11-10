#!/usr/bin/env python3
"""Erzeugt das App-Logo „ILLEGAL FREEDOM MUSIC“ aus denselben Formen für Android (Vektor) und iPhone (PNG).

Motiv (Cyberpunk): kantiger Totenkopf mit gelben Kopfhörern, leuchtenden Augen und Leiterbahnen auf der Stirn,
in verrutschten Glitch-Streifen; darunter das gelbe Schild „ILLEGAL“ und „FREEDOM“; dahinter Datenregen.
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

YELLOW = "#FFFCEE0A"
CYAN = "#FF00F0FF"
MAGENTA = "#FFFF2A6D"
INK = "#FF07030F"

# ---------- Formen ----------

def rot(points, cx, cy, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in points]

def shift(points, dx, dy=0.0):
    return [(x + dx, y + dy) for x, y in points]

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

def area(poly):
    return sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1])) / 2

def same_winding(polys):
    """Überlappende Teilflächen müssen gleich herum laufen, sonst löschen sie sich beim Füllen aus"""
    return [p if area(p) > 0 else list(reversed(p)) for p in polys if abs(area(p)) > 1e-6]

def clip_band(poly, y0, y1):
    """Schneidet ein Vieleck auf den waagerechten Streifen y0..y1 zu"""
    def cut(points, limit, keep_below):
        out = []
        for i, a in enumerate(points):
            b = points[(i + 1) % len(points)]
            ina = a[1] <= limit if keep_below else a[1] >= limit
            inb = b[1] <= limit if keep_below else b[1] >= limit
            if ina:
                out.append(a)
            if ina != inb:
                t = (limit - a[1]) / (b[1] - a[1])
                out.append((a[0] + t * (b[0] - a[0]), limit))
        return out
    p = cut(poly, y0, False)
    return cut(p, y1, True) if len(p) >= 3 else []

# ---------- Hilfen fürs Motiv ----------

def mirror(poly):
    """Spiegelt an der senkrechten Mittelachse"""
    return [(108 - x, y) for x, y in poly]

def glitch(polys, bands, dx=0.0):
    """Zerschneidet Flächen in waagerechte Streifen (y von, y bis, Versatz) und verrutscht sie seitlich"""
    out = []
    for p in polys:
        for y0, y1, off in bands:
            piece = clip_band(p, y0, y1)
            if len(piece) >= 3:
                out.append(shift(piece, off + dx))
    return same_winding(out)

def arc(cx, cy, r, a0, a1, steps=24):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / steps)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / steps))) for i in range(steps + 1)]

# ---------- Schrift (eckige Blockbuchstaben, 7 Einheiten hoch) ----------

S = 1.3  # Strichstärke

GLYPHS = {
    "I": (1.3, [rect(0, 0, S, 7)]),
    "L": (4.6, [rect(0, 0, S, 7), rect(0, 7 - S, 4.6, 7)]),
    "E": (4.8, [rect(0, 0, S, 7), rect(0, 0, 4.8, S), rect(0, 2.85, 3.9, 2.85 + S), rect(0, 7 - S, 4.8, 7)]),
    "F": (4.8, [rect(0, 0, S, 7), rect(0, 0, 4.8, S), rect(0, 2.85, 3.9, 2.85 + S)]),
    "G": (5.0, [[(0, 7), (0, 1.6), (1.3, 0.3), (1.3, 7)], [(0.3, 1.3), (1.6, 0), (5, 0), (5, 1.3)],
                rect(0, 7 - S, 5, 7), rect(5 - S, 3.2, 5, 7), rect(2.6, 3.2, 5, 3.2 + S)]),
    "A": (5.0, [[(0, 7), (0, 1.6), (1.3, 0.3), (1.3, 7)], [(0.3, 1.3), (1.6, 0), (5, 0), (5, 1.3)],
                rect(5 - S, 0, 5, 7), rect(0, 3.7, 5, 3.7 + S)]),
    "R": (5.0, [rect(0, 0, S, 7), rect(0, 0, 5, S), rect(5 - S, 0, 5, 4.15), rect(0, 2.85, 5, 2.85 + S),
                [(2.1, 4.15), (3.7, 4.15), (5, 7), (3.4, 7)]]),
    "D": (5.0, [rect(0, 0, S, 7), [(0, 0), (3.6, 0), (4.9, 1.3), (0, 1.3)], [(3.7, 0.1), (5, 1.4), (5, 5.6), (3.7, 6.9)],
                [(0, 5.7), (4.9, 5.7), (3.6, 7), (0, 7)]]),
    "O": (5.0, [[(0, 7), (0, 1.6), (1.3, 0.3), (1.3, 7)], [(0.3, 1.3), (1.6, 0), (5, 0), (5, 1.3)],
                [(3.7, 0), (5, 0), (5, 5.4), (3.7, 6.7)], [(0, 5.7), (4.7, 5.7), (3.4, 7), (0, 7)]]),
    "M": (5.8, [rect(0, 0, S, 7), rect(5.8 - S, 0, 5.8, 7), [(0, 0), (1.6, 0), (2.9, 2.5), (4.2, 0), (5.8, 0), (3.5, 4.3), (2.3, 4.3)]]),
}
GAP = 1.15
SKEW = 0.16  # Schräglage der Schrift

def text_width(word, k):
    return k * (sum(GLYPHS[c][0] for c in word) + GAP * (len(word) - 1))

def text(word, cx, cy, k, dx=0.0, dy=0.0):
    """Wort als Vielecke, mittig um (cx, cy), k = Größe"""
    out = []
    x = -text_width(word, 1) / 2
    for c in word:
        w, polys = GLYPHS[c]
        for p in polys:
            out.append([(cx + dx + k * (x + px) - SKEW * k * (py - 3.5), cy + dy + k * (py - 3.5)) for px, py in p])
        x += w + GAP
    return same_winding(out)

def plate(cx, cy, w, h, cut=2.2):
    """Schräges Schild mit abgeschnittenen Ecken (oben rechts, unten links)"""
    sk = SKEW * h / 2
    l, r, t, b = cx - w / 2, cx + w / 2, cy - h / 2, cy + h / 2
    return [(l + sk, t), (r + sk - cut, t), (r + sk, t + cut), (r - sk, b), (l - sk + cut, b), (l - sk, b - cut)]

TOP_Y, BOTTOM_Y = 67.6, 77.0
TEXT_K = 0.94

# ---------- Ebenen ----------

def fill(color, polys, evenodd=False):
    return {"kind": "fill", "color": color, "polys": polys, "evenodd": evenodd}

def stroke(color, width, lines, cap="round"):
    return {"kind": "stroke", "color": color, "width": width, "lines": lines, "cap": cap}

def circle(cx, cy, r, steps=40):
    return ellipse(cx, cy, r, r, 0, steps)

def background_layers():
    L = [fill(("radial", 54, 46, 80, "#FF14283A", "#FF020308"), [rect(0, 0, 108, 108)])]
    # Bildzeilen wie auf einem alten Monitor
    L.append(stroke("#10FFFFFF", 0.45, [[(0, y), (108, y)] for y in [i * 2.7 + 1 for i in range(40)]], "butt"))
    # Datenregen
    rain = [(8, 4, 18), (15, 30, 52), (24, 0, 12), (33, 70, 96), (41, 2, 9), (67, 0, 11), (76, 78, 100), (85, 6, 26),
            (93, 40, 66), (100, 10, 22), (12, 70, 88), (97, 80, 98)]
    L.append(stroke("#5500F0FF", 0.7, [[(x, a), (x, b)] for x, a, b in rain], "butt"))
    # Leuchten hinter dem Kopf
    L.append(fill(("radial", 54, 44, 44, "#80FF2A6D", "#00FF2A6D"), [circle(54, 44, 44)]))
    return L

SKULL_TOP, SKULL_K = 27.5, 0.88
# rechte Schädelhälfte: (Abstand von der Mitte, Höhe ab Scheitel)
SKULL_HALF = [(0, 0), (7, 0.3), (13, 2.5), (17.5, 7), (19.5, 13), (19.5, 19), (18, 24), (15, 27.5), (13.5, 31),
              (10.5, 32.5), (10, 38.5), (8, 40), (0, 40)]
SKULL_BANDS = [(0, 33.4, 0), (33.4, 35.4, 3.0), (35.4, 55.6, 0), (55.6, 57.2, -2.6), (57.2, 108, 0)]

def sk(points):
    return [(54 + dx * SKULL_K, SKULL_TOP + y * SKULL_K) for dx, y in points]

def foreground_layers():
    L = []
    right = sk(SKULL_HALF)
    left = mirror(right)
    eye = sk([(3.5, 18.5), (14.5, 14.5), (16.5, 20.5), (13, 26), (5.5, 25.5)])
    glow = sk([(6.5, 20.5), (13, 18.2), (14, 21), (12, 23.8), (7.6, 23.5)])
    nose = sk([(0, 25), (2.9, 31.5), (0, 30), (-2.9, 31.5)])
    cy = SKULL_TOP + 17 * SKULL_K  # Höhe der Ohren

    # Neon-Ring hinter dem Kopf, in vier Stücken
    for a0, a1 in [(-60, -8), (8, 60), (120, 172), (188, 240)]:
        L.append(stroke("#4000F0FF", 3.0, [arc(54, cy + 3, 30, a0, a1)]))
        L.append(stroke(CYAN, 0.9, [arc(54, cy + 3, 30, a0, a1)]))

    # Kopfhörer-Bügel
    L.append(stroke(INK, 5.0, [arc(54, cy, 20.3, 180, 360, 30)]))
    L.append(stroke(YELLOW, 3.2, [arc(54, cy, 20.3, 180, 360, 30)]))
    L.append(stroke("#FF8A8200", 0.6, [arc(54, cy, 20.3, 200, 340, 30)]))

    # Schädel: Farbsäume, dann helle und schattierte Hälfte, in verrutschten Streifen
    L.append(fill("#E600F0FF", glitch([left, right], SKULL_BANDS, -1.8)))
    L.append(fill("#E6FF003C", glitch([left, right], SKULL_BANDS, 1.8)))
    L.append(fill("#FFEAF6FF", glitch([left], SKULL_BANDS)))
    L.append(fill("#FFB4C4DC", glitch([right], SKULL_BANDS)))
    # Leiterbahnen auf der Stirn
    L.append(stroke("#FF00B8D4", 0.6, [sk([(-12, 9), (-7, 9), (-4, 12), (-4, 15)]), sk([(-9, 4.5), (-2, 4.5), (0, 6.5)]),
                                       sk([(9, 6), (5, 10), (5, 14)])]))
    L.append(fill("#FF00B8D4", [circle(*sk([p])[0], 0.9, 12) for p in [(-4, 15), (0, 6.5), (5, 14)]]))
    # Augen (leuchten), Nase, Zähne
    L.append(fill(INK, glitch([eye, mirror(eye), nose], SKULL_BANDS)))
    L.append(fill(CYAN, [mirror(glow)]))
    L.append(fill(MAGENTA, [glow]))
    L.append(stroke(INK, 0.9, [sk([(d, 34.2), (d, 40)]) for d in (-6.8, -3.4, 0, 3.4, 6.8)], "butt"))
    L.append(stroke(INK, 0.8, [sk([(-10.2, 34.2), (10.2, 34.2)])], "butt"))

    # Hörmuscheln
    for side in (-1, 1):
        cx = 54 + side * 20.3
        L.append(fill(INK, [rounded_rect(cx, cy + 4, 9.4, 19, 3.2, 0)]))
        L.append(fill(YELLOW, [rounded_rect(cx, cy + 4, 7.4, 17, 2.4, 0)]))
        L.append(fill(INK, [rounded_rect(cx + side * 0.6, cy + 4, 3.0, 11, 1.4, 0)]))
        L.append(fill(MAGENTA, [rounded_rect(cx + side * 0.6, cy + 4, 1.2, 8, 0.6, 0)]))

    # Schild „ILLEGAL“: gelb mit Farbsäumen, schwarze Schrift
    top = plate(54, TOP_Y, text_width("ILLEGAL", TEXT_K) + 6, 9.2, 1.8)
    L.append(fill("#E600F0FF", [shift(top, -1.1, -0.4)]))
    L.append(fill("#E6FF2A6D", [shift(top, 1.1, 0.5)]))
    L.append(fill(YELLOW, [top]))
    L.append(fill(INK, text("ILLEGAL", 54, TOP_Y, TEXT_K)))
    # „FREEDOM“: weiß mit Pink-Saum
    L.append(fill("#F2FF2A6D", text("FREEDOM", 54, BOTTOM_Y, TEXT_K, 0.9, 0.3)))
    L.append(fill("#FFF4FBFF", text("FREEDOM", 54, BOTTOM_Y, TEXT_K)))
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
