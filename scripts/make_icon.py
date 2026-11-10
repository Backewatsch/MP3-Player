#!/usr/bin/env python3
"""Erzeugt das App-Logo „ILLEGAL FREEDOM MUSIC“ aus denselben Formen für Android (Vektor) und iPhone (PNG).

Motiv: Neon-Musiknote mit Glitch-Versatz vor einer zerrissenen Kette mit Funken auf dunklem Grund.
Koordinaten im 108×108-Raster der adaptiven Android-Symbole (sichtbar: Kreis/Quadrat um 54,54).
"""
import math
import shutil
import sys
from PIL import Image, ImageDraw, ImageFilter

# ---------- Formen ----------

def rot(points, cx, cy, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in points]

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
        # aufgesprengtes Glied an der Bruchstelle
        d = 10
        cx, cy = CENTER[0] + side * d * ux, CENTER[1] + side * d * uy
        solids.append(half_ring(cx, cy, CHAIN_ANGLE, side))
        # weitere Glieder nach außen
        for k, dist in enumerate([22, 36, 50, 64, 78]):
            cx, cy = CENTER[0] + side * dist * ux, CENTER[1] + side * dist * uy
            if k % 2 == 0:   # seitlich gesehen: schmaler Steg
                solids.append(rounded_rect(cx, cy, 22, 5, 2.5, CHAIN_ANGLE))
            else:            # Ring
                rings.append(chain_link(cx, cy, CHAIN_ANGLE))
    return rings, solids

def note_shapes(dx=0.0, dy=0.0):
    """Doppelte Achtelnote (zwei Köpfe, zwei Hälse, ein schräger Balken)"""
    s = []
    s.append(ellipse(41 + dx, 70 + dy, 7.2, 5.2, -22))
    s.append(ellipse(65 + dx, 63 + dy, 7.2, 5.2, -22))
    s.append([(46.6 + dx, 68.5 + dy), (49.6 + dx, 68.0 + dy), (49.6 + dx, 37 + dy), (46.6 + dx, 37.6 + dy)])
    s.append([(70.6 + dx, 61.5 + dy), (73.6 + dx, 61.0 + dy), (73.6 + dx, 30 + dy), (70.6 + dx, 30.6 + dy)])
    s.append([(46.6 + dx, 37.6 + dy), (73.6 + dx, 30 + dy), (73.6 + dx, 37.5 + dy), (46.6 + dx, 45.1 + dy)])
    return s

SPARKS = [  # (x1, y1, x2, y2) – strahlen von der Bruchstelle aus
    (54, 53, 52, 45), (55, 53, 61, 46), (56, 55, 64, 56), (53, 56, 46, 58), (55, 57, 58, 63), (53, 54, 47, 50),
]

# ---------- PNG (iPhone + Vorschau) ----------

def render_png(size, path, crop=18):
    """crop: Rand im 108er-Raster, der beim iPhone-Symbol abgeschnitten wird (Vollbild wie Android sichtbar)"""
    ss = 4
    S = size * ss
    scale = S / (108 - 2 * crop)
    P = lambda pts: [((x - crop) * scale, (y - crop) * scale) for x, y in pts]

    img = Image.new("RGB", (S, S))
    # Hintergrund: dunkler Radialverlauf (Weinrot → Schwarz)
    bg = Image.new("RGB", (S, S))
    px = bg.load()
    for y in range(0, S, 4):
        for x in range(0, S, 4):
            d = math.hypot(x - S * 0.5, y - S * 0.45) / (S * 0.75)
            k = max(0.0, 1 - d)
            col = (int(70 * k ** 1.6 + 6), int(4 * k), int(30 * k ** 1.4 + 8))
            for yy in range(y, min(y + 4, S)):
                for xx in range(x, min(x + 4, S)):
                    px[xx, yy] = col
    img.paste(bg)
    d = ImageDraw.Draw(img)

    # Kette (gesprengt)
    steel = (128, 132, 150)
    rings, solids = chain_parts()
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    for poly in solids:
        ld.polygon(P(poly), fill=steel + (255,))
    for o, i in rings:
        ld.polygon(P(o), fill=steel + (255,))
        ld.polygon(P(i), fill=(0, 0, 0, 0))
    img.paste(layer, (0, 0), layer)

    # Funken (Glow)
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    for x1, y1, x2, y2 in SPARKS:
        g.line(P([(x1, y1), (x2, y2)]), fill=(255, 200, 40, 255), width=int(2.2 * scale))
    glow_blur = glow.filter(ImageFilter.GaussianBlur(3 * scale))
    img.paste(glow_blur, (0, 0), glow_blur)
    img.paste(glow, (0, 0), glow)

    # Note: Glitch-Kopien (Cyan/Rot) + Neon-Verlauf (Pink → Orange) + Leuchten
    def note_mask(dx, dy):
        m = Image.new("L", (S, S), 0)
        md = ImageDraw.Draw(m)
        for shp in note_shapes(dx, dy):
            md.polygon(P(shp), fill=255)
        return m

    for dx, col in [(-2.2, (0, 229, 255)), (2.2, (255, 0, 64))]:
        m = note_mask(dx, 0)
        layer = Image.new("RGB", (S, S), col)
        img.paste(layer, (0, 0), m.point(lambda v: int(v * 0.85)))

    m = note_mask(0, 0)
    halo = m.filter(ImageFilter.GaussianBlur(5 * scale)).point(lambda v: int(v * 0.8))
    img.paste(Image.new("RGB", (S, S), (255, 46, 99)), (0, 0), halo)
    grad = Image.new("RGB", (S, S))
    gd = ImageDraw.Draw(grad)
    for y in range(S):
        t = y / S
        gd.line([(0, y), (S, y)], fill=(255, int(46 + 110 * t), int(99 - 99 * t)))
    img.paste(grad, (0, 0), m)

    # Glitch-Streifen durch die Note
    for yy, h, off in [(48, 1.6, 3), (58, 1.2, -3)]:
        y0 = int((yy - crop) * scale)
        band = img.crop((0, y0, S, y0 + int(h * scale)))
        img.paste(band, (int(off * scale), y0))

    img = img.resize((size, size), Image.LANCZOS)
    img.save(path)

# ---------- Android-Vektor ----------

def path_data(polys):
    out = []
    for poly in polys:
        out.append("M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in poly) + " Z")
    return " ".join(out)

def android_foreground():
    parts = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Erzeugt von scripts/make_icon.py – nicht von Hand bearbeiten -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    xmlns:aapt="http://schemas.android.com/aapt"',
             '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    # Neon-Leuchten hinter der Note
    parts.append('    <path android:pathData="M57,52 m-38,0 a38,38 0 1,0 76,0 a38,38 0 1,0 -76,0">')
    parts.append('        <aapt:attr name="android:fillColor">')
    parts.append('            <gradient android:type="radial" android:centerX="57" android:centerY="52" android:gradientRadius="38"')
    parts.append('                android:startColor="#88FF2E63" android:endColor="#00FF2E63" />')
    parts.append('        </aapt:attr>')
    parts.append('    </path>')
    rings, solids = chain_parts()
    parts.append(f'    <path android:fillColor="#FF80849A" android:pathData="{path_data(solids)}" />')
    for o, i in rings:
        parts.append(f'    <path android:fillColor="#FF80849A" android:fillType="evenOdd" android:pathData="{path_data([o, i])}" />')
    spark = " ".join(f"M{x1},{y1} L{x2},{y2}" for x1, y1, x2, y2 in SPARKS)
    parts.append(f'    <path android:strokeColor="#66FFC828" android:strokeWidth="4.5" android:strokeLineCap="round" android:pathData="{spark}" />')
    parts.append(f'    <path android:strokeColor="#FFFFD34D" android:strokeWidth="1.8" android:strokeLineCap="round" android:pathData="{spark}" />')
    parts.append(f'    <path android:fillColor="#D900E5FF" android:pathData="{path_data(note_shapes(-2.2))}" />')
    parts.append(f'    <path android:fillColor="#D9FF0040" android:pathData="{path_data(note_shapes(2.2))}" />')
    parts.append(f'    <path android:pathData="{path_data(note_shapes())}">')
    parts.append('        <aapt:attr name="android:fillColor">')
    parts.append('            <gradient android:type="linear" android:startX="54" android:startY="28" android:endX="54" android:endY="78"')
    parts.append('                android:startColor="#FFFF2E63" android:endColor="#FFFF9A00" />')
    parts.append('        </aapt:attr>')
    parts.append('    </path>')
    # Glitch-Streifen quer durch die Note
    parts.append('    <path android:fillColor="#B300E5FF" android:pathData="M44,47.6 L77,47.6 L77,48.5 L44,48.5 Z" />')
    parts.append('    <path android:fillColor="#B3FF0040" android:pathData="M40,58.4 L70,58.4 L70,59.1 L40,59.1 Z" />')
    parts.append('</vector>')
    return "\n".join(parts) + "\n"

ANDROID_BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<!-- Erzeugt von scripts/make_icon.py – nicht von Hand bearbeiten -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0 L108,0 L108,108 L0,108 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="48" android:gradientRadius="80"
                android:startColor="#FF4C0422" android:endColor="#FF060008" />
        </aapt:attr>
    </path>
</vector>
"""

if __name__ == "__main__":
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    render_png(1024, f"{root}/MP3Player/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
    # Gleiches Logo als Bild in der App (neben jedem Lied)
    shutil.copyfile(f"{root}/MP3Player/Assets.xcassets/AppIcon.appiconset/AppIcon.png", f"{root}/MP3Player/Assets.xcassets/Logo.imageset/Logo.png")
    res = f"{root}/android/app/src/main/res/drawable"
    open(f"{res}/ic_launcher_foreground.xml", "w").write(android_foreground())
    open(f"{res}/ic_launcher_background.xml", "w").write(ANDROID_BACKGROUND)
    print("Logo erzeugt")
