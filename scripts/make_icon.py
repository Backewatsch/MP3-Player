#!/usr/bin/env python3
"""Erzeugt das App-Logo „ILLEGAL FREEDOM MUSIC“ aus denselben Formen für Android (Vektor) und iPhone (PNG).

Motiv (Cyberpunk): Neon-Note mit Glitch-Versatz vor einer gesprengten Kette, eingerahmt von Neon-Winkeln,
oben ein gelbes Warnschild „ILLEGAL“, unten „FREEDOM“ in Neon-Cyan; dahinter Neon-Gitter und Leiterbahnen.
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
STEEL = "#FF8F96B4"
STEEL_DARK = "#FF4A5070"

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

# ---------- Motiv: Kette + Note ----------

MOTIF_SCALE = 0.80

def motif(points):
    """Kette, Note und Funken sitzen verkleinert in der Mitte, damit oben und unten Platz für die Schrift bleibt"""
    return [((x - 57) * MOTIF_SCALE + 54, (y - 52.5) * MOTIF_SCALE + 54) for x, y in points]

def chain_link(cx, cy, deg, w=26, h=14, t=3.4):
    """Kettenglied als Ring (außen + innen)"""
    return [rounded_rect(cx, cy, w, h, h / 2, deg), rounded_rect(cx, cy, w - 2 * t, h - 2 * t, h / 2 - t, deg)]

def half_ring(cx, cy, deg, side, w=24, h=13, t=3.4, steps=14):
    """Aufgesprengtes Kettenglied: nur die äußere Hälfte (side=+1 rechts, -1 links im Glied-Koordinatensystem)"""
    r = h / 2
    ri = r - t
    outer, inner = [], []
    for i in range(steps + 1):
        a = math.radians(-90 + 180 * i / steps)
        outer.append((w / 2 - r + r * math.cos(a), r * math.sin(a)))
        inner.append((w / 2 - r + ri * math.cos(a), ri * math.sin(a)))
    pts = [(0.5, -r)] + outer + [(0.5, r), (0.5, ri)] + list(reversed(inner)) + [(0.5, -ri)]
    pts = [(side * x + cx, y + cy) for x, y in pts]
    return rot(pts, cx, cy, deg)

CHAIN_ANGLE = -32
CENTER = (54, 55)

def chain_parts():
    """Kette diagonal hinter der Note: Ringe und seitlich gesehene Glieder; in der Mitte gesprengt"""
    rings, solids = [], []
    a = math.radians(CHAIN_ANGLE)
    ux, uy = math.cos(a), math.sin(a)
    for side in (-1, 1):
        d = 10
        cx, cy = CENTER[0] + side * d * ux, CENTER[1] + side * d * uy
        solids.append(motif(half_ring(cx, cy, CHAIN_ANGLE, side)))
        for k, dist in enumerate([22, 36, 50, 64, 78]):
            cx, cy = CENTER[0] + side * dist * ux, CENTER[1] + side * dist * uy
            if k % 2 == 0:   # seitlich gesehen: schmaler Steg
                solids.append(motif(rounded_rect(cx, cy, 22, 5, 2.5, CHAIN_ANGLE)))
            else:            # Ring
                o, i = chain_link(cx, cy, CHAIN_ANGLE)
                rings.append((motif(o), motif(i)))
    return rings, solids

def note_shapes():
    """Doppelte Achtelnote (zwei Köpfe, zwei Hälse, ein schräger Balken)"""
    s = [ellipse(41, 70, 7.6, 5.5, -22), ellipse(65, 63, 7.6, 5.5, -22),
         [(46.2, 68.5), (49.8, 68.0), (49.8, 37), (46.2, 37.6)],
         [(70.2, 61.5), (73.8, 61.0), (73.8, 30), (70.2, 30.6)],
         [(46.2, 37.6), (73.8, 30), (73.8, 38.5), (46.2, 46.1)]]
    return [motif(p) for p in s]

# Glitch: waagerechte Streifen der Note sind seitlich verrutscht (y von, y bis, Versatz)
GLITCH_BANDS = [(0, 43.2, 0.0), (43.2, 45.6, 3.4), (45.6, 52.0, 0.0), (52.0, 53.6, -2.8),
                (53.6, 61.0, 0.0), (61.0, 63.4, 2.6), (63.4, 108, 0.0)]

def glitched_note(dx=0.0):
    out = []
    for shape in note_shapes():
        for y0, y1, off in GLITCH_BANDS:
            piece = clip_band(shape, y0, y1)
            if len(piece) >= 3:
                out.append(shift(piece, off + dx))
    return same_winding(out)

SPARKS = [motif([(a, b), (c, d)]) for a, b, c, d in [
    (54, 53, 51, 43), (55, 53, 63, 45), (56, 55, 66, 56), (53, 56, 44, 59), (55, 57, 59, 65), (53, 54, 45, 49)]]

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

TOP_Y, BOTTOM_Y = 32.5, 75.5
TEXT_K = 1.0

# ---------- Ebenen ----------

def fill(color, polys, evenodd=False):
    return {"kind": "fill", "color": color, "polys": polys, "evenodd": evenodd}

def stroke(color, width, lines, cap="round"):
    return {"kind": "stroke", "color": color, "width": width, "lines": lines, "cap": cap}

def circle(cx, cy, r, steps=40):
    return ellipse(cx, cy, r, r, 0, steps)

def background_layers():
    L = [fill(("radial", 54, 52, 78, "#FF34095A", "#FF04010A"), [rect(0, 0, 108, 108)])]
    # Bildzeilen wie auf einem alten Monitor
    L.append(stroke("#12FFFFFF", 0.45, [[(0, y), (108, y)] for y in [i * 2.7 + 1 for i in range(40)]], "butt"))
    # Neon-Gitter als Boden, Fluchtpunkt in der Mitte
    hy = 57.0
    grid = [[(0, hy + 1.5 * 1.5 ** i), (108, hy + 1.5 * 1.5 ** i)] for i in range(9)]
    grid += [[(54 + k * 2.2, hy + 1.5), (54 + k * 26, 108)] for k in range(-9, 10)]
    L.append(stroke("#30FF2A6D", 1.6, grid, "butt"))
    L.append(stroke("#B0FF2A6D", 0.5, grid, "butt"))
    # Leiterbahnen oben
    traces = [[(0, 12), (14, 12), (22, 20), (22, 30)], [(0, 22), (8, 22), (13, 27)], [(30, 0), (30, 8), (38, 16), (48, 16)],
              [(108, 14), (92, 14), (86, 20), (86, 31)], [(108, 26), (99, 26), (95, 30)], [(76, 0), (76, 9), (69, 16), (60, 16)],
              [(54, 0), (54, 9)]]
    L.append(stroke("#5500F0FF", 0.7, traces))
    L.append(fill("#9900F0FF", [circle(x, y, 1.3, 14) for x, y in [(22, 30), (13, 27), (48, 16), (86, 31), (95, 30), (60, 16), (54, 9)]]))
    # Leuchten am Horizont
    L.append(fill(("radial", 54, 57, 46, "#70FF2A6D", "#00FF2A6D"), [circle(54, 57, 46)]))
    return L

def foreground_layers():
    L = []
    # Neon-Leuchten hinter der Note
    L.append(fill(("radial", 54, 54, 30, "#99FF2A6D", "#00FF2A6D"), [circle(54, 54, 30)]))

    # Neon-Winkel links und rechts (Rahmen), mit Lücke und Markierungen
    def chevron(side, r):
        return [(54 + side * r * 0.5, 54 - r * 0.866), (54 + side * r, 54), (54 + side * r * 0.5, 54 + r * 0.866)]
    outer = [chevron(-1, 32), chevron(1, 32)]
    L.append(stroke("#4000F0FF", 3.4, outer))
    L.append(stroke(CYAN, 1.1, outer))
    L.append(stroke("#CCFF2A6D", 0.6, [chevron(-1, 28.6), chevron(1, 28.6)]))
    L.append(stroke(YELLOW, 1.0, [[(54 - 32 - 2.6, 54), (54 - 32 + 2.6, 54)], [(54 + 32 - 2.6, 54), (54 + 32 + 2.6, 54)]], "butt"))

    # Kette (gesprengt): dunkle Kante, Stahl, heller Rand
    rings, solids = chain_parts()
    for dx, dy, col in [(0.5, 0.7, STEEL_DARK), (0, 0, STEEL)]:
        L.append(fill(col, [shift(p, dx, dy) for p in solids]))
        for o, i in rings:
            L.append(fill(col, [shift(o, dx, dy), shift(i, dx, dy)], evenodd=True))
    for o, i in rings:
        L.append(stroke("#99E8F4FF", 0.35, [o + o[:1]]))

    # Funken an der Bruchstelle
    L.append(stroke("#55FCEE0A", 3.6, SPARKS))
    L.append(stroke(YELLOW, 1.5, SPARKS))
    L.append(stroke("#FFFFFFFF", 0.5, SPARKS))

    # Note: Farbsäume (Cyan/Rot), dann Neon-Verlauf, alles in verrutschten Streifen
    L.append(fill("#E600F0FF", glitched_note(-2.0)))
    L.append(fill("#E6FF003C", glitched_note(2.0)))
    L.append(fill(("linear", 54, 36, 54, 72, "#FFFF2A6D", "#FFFFB300"), glitched_note()))
    L.append(fill("#CCFFFFFF", [rect(40.5, 49.2, 68, 49.7), rect(36, 58.6, 60, 59.0)]))

    # Schild „ILLEGAL“: gelb mit Farbsäumen, schwarze Schrift
    wt = text_width("ILLEGAL", TEXT_K)
    top = plate(54, TOP_Y, wt + 6.4, 11.4)
    L.append(fill("#E600F0FF", [shift(top, -1.3, -0.5)]))
    L.append(fill("#E6FF2A6D", [shift(top, 1.3, 0.6)]))
    L.append(fill(YELLOW, [top]))
    L.append(fill(INK, text("ILLEGAL", 54, TOP_Y, TEXT_K)))
    L.append(fill(INK, [rect(54 - wt / 2 - 1.0, TOP_Y + 4.55, 54 + wt / 2 - 2.0, TOP_Y + 4.95)]))

    # „FREEDOM“: Neon-Cyan auf dunklem Schild
    wb = text_width("FREEDOM", TEXT_K)
    bottom = plate(54, BOTTOM_Y, wb + 5.6, 11.4)
    L.append(fill("#F207030F", [bottom]))
    L.append(stroke("#4000F0FF", 2.2, [bottom + bottom[:1]]))
    L.append(stroke(CYAN, 0.7, [bottom + bottom[:1]]))
    L.append(fill("#F2FF2A6D", text("FREEDOM", 54, BOTTOM_Y, TEXT_K, 0.9, 0.35)))
    L.append(fill(CYAN, text("FREEDOM", 54, BOTTOM_Y, TEXT_K)))
    return L

# ---------- PNG (iPhone + Vorschau) ----------

def rgba(c):
    c = c.lstrip("#")
    return (int(c[2:4], 16), int(c[4:6], 16), int(c[6:8], 16), int(c[0:2], 16))

def render_png(size, path, crop=18):
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

    for layer in background_layers() + foreground_layers():
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
