#!/usr/bin/env python3
"""Generates app/src/main/java/app/line/ui/IconPaths.kt from tools/icons/*.svg (the source of truth).

Usage: build_icon_paths.py [--check]   (--check fails when the generated file is out of date)

SVG conventions: viewBox 0 0 24 24; children are path/circle/ellipse/rect/line/polyline/polygon.
Default painting is the 2-unit round-capped outline; fill="currentColor" stroke="none" marks a solid shape.
Every shape is normalised to path data limited to M L H V C S Q T A Z (absolute or relative).
"""
import argparse
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / "app/src/main/java/app/line/ui/IconPaths.kt"

ALIASES = {"chevron_left": "back", "chevron_right": "forward", "volume": "speaker"}
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
# Live area: stroked centre lines stay 3..21 (ink 2..22); solid shapes may use the full 2..22.
STROKE_BOX = (3.0, 21.0)
FILL_BOX = (2.0, 22.0)
TOLERANCE = 0.05

NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def fmt(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    if text in ("", "-0"):
        return "0"
    if text.startswith("0."):
        return text[1:]
    if text.startswith("-0."):
        return "-" + text[2:]
    return text


def parse_path(data):
    """Returns [(command, [numbers])] with implicit repeats expanded; arc flags may be compact."""
    commands, i, n = [], 0, len(data)
    while i < n:
        ch = data[i]
        if ch in " \t\r\n,":
            i += 1
        elif ch.isalpha():
            cmd = ch
            if cmd.upper() not in ARITY:
                raise ValueError(f"unsupported path command '{cmd}'")
            i += 1
            arity = ARITY[cmd.upper()]
            if arity == 0:
                commands.append((cmd, []))
                continue
            first = True
            while True:
                while i < n and data[i] in " \t\r\n,":
                    i += 1
                if i >= n or data[i].isalpha():
                    if first:
                        raise ValueError(f"'{cmd}' has no parameters")
                    break
                params = []
                for k in range(arity):
                    while i < n and data[i] in " \t\r\n,":
                        i += 1
                    if cmd.upper() == "A" and k in (3, 4):
                        if i >= n or data[i] not in "01":
                            raise ValueError("bad arc flag")
                        params.append(float(data[i]))
                        i += 1
                        continue
                    match = NUMBER.match(data, i)
                    if not match:
                        raise ValueError(f"bad number near '{data[i:i + 12]}'")
                    params.append(float(match.group()))
                    i += match.end() - match.start()
                use = cmd
                if not first and cmd in "Mm":
                    use = "L" if cmd == "M" else "l"
                commands.append((use, params))
                first = False
        else:
            raise ValueError(f"unexpected character '{ch}'")
    return commands


def normalise(data):
    """Re-serialises path data compactly: one letter per run, minimal separators, flags always spaced."""
    out, last = [], None
    for cmd, params in parse_path(data):
        text = ""
        if cmd != last or cmd in "Zz":
            text += cmd
        tokens = [fmt(p) if not (cmd.upper() == "A" and k in (3, 4)) else str(int(p)) for k, p in enumerate(params)]
        for k, token in enumerate(tokens):
            if k or (cmd == last and cmd not in "Mm"):
                if not token.startswith("-") or (cmd.upper() == "A" and k in (3, 4)):
                    text += " "
            text += token
        out.append(text)
        last = cmd if cmd not in "Zz" else None
    return "".join(out)


def flatten(data):
    """Sampled centre-line points of the path in absolute coordinates."""
    pts, cur, start, prev_ctrl = [], (0.0, 0.0), (0.0, 0.0), None
    for cmd, p in parse_path(data):
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = cur if rel else (0.0, 0.0)
        if c == "M":
            cur = start = (p[0] + ox, p[1] + oy); pts.append(cur); prev_ctrl = None
        elif c == "L":
            cur = (p[0] + ox, p[1] + oy); pts.append(cur); prev_ctrl = None
        elif c == "H":
            cur = (p[0] + (cur[0] if rel else 0.0), cur[1]); pts.append(cur); prev_ctrl = None
        elif c == "V":
            cur = (cur[0], p[0] + (cur[1] if rel else 0.0)); pts.append(cur); prev_ctrl = None
        elif c in "CS":
            if c == "C":
                c1, c2, end = (p[0] + ox, p[1] + oy), (p[2] + ox, p[3] + oy), (p[4] + ox, p[5] + oy)
            else:
                c1 = (2 * cur[0] - prev_ctrl[0], 2 * cur[1] - prev_ctrl[1]) if prev_ctrl else cur
                c2, end = (p[0] + ox, p[1] + oy), (p[2] + ox, p[3] + oy)
            for s in range(1, 17):
                t = s / 16
                u = 1 - t
                pts.append((u**3 * cur[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t**3 * end[0],
                            u**3 * cur[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t**3 * end[1]))
            prev_ctrl, cur = c2, end
        elif c in "QT":
            if c == "Q":
                c1, end = (p[0] + ox, p[1] + oy), (p[2] + ox, p[3] + oy)
            else:
                c1 = (2 * cur[0] - prev_ctrl[0], 2 * cur[1] - prev_ctrl[1]) if prev_ctrl else cur
                end = (p[0] + ox, p[1] + oy)
            for s in range(1, 17):
                t = s / 16
                u = 1 - t
                pts.append((u * u * cur[0] + 2 * u * t * c1[0] + t * t * end[0],
                            u * u * cur[1] + 2 * u * t * c1[1] + t * t * end[1]))
            prev_ctrl, cur = c1, end
        elif c == "A":
            end = (p[5] + ox, p[6] + oy)
            pts.extend(arc_points(cur, p[0], p[1], p[2], int(p[3]), int(p[4]), end))
            cur, prev_ctrl = end, None
        elif c == "Z":
            cur = start; prev_ctrl = None
    return pts


def arc_points(p0, rx, ry, rot, large, sweep, p1):
    """SVG endpoint-to-centre arc conversion (spec F.6.5), sampled."""
    if p0 == p1 or rx == 0 or ry == 0:
        return [p1]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(rot)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (p0[0] - p1[0]) / 2, (p0[1] - p1[1]) / 2
    x1, y1 = cp * dx + sp * dy, -sp * dx + cp * dy
    lam = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    den = rx * rx * y1 * y1 + ry * ry * x1 * x1
    coef = math.sqrt(max(0.0, num / den)) * (-1 if large == sweep else 1)
    cx1, cy1 = coef * rx * y1 / ry, -coef * ry * x1 / rx
    cx = cp * cx1 - sp * cy1 + (p0[0] + p1[0]) / 2
    cy = sp * cx1 + cp * cy1 + (p0[1] + p1[1]) / 2
    a0 = math.atan2((y1 - cy1) / ry, (x1 - cx1) / rx)
    a1 = math.atan2((-y1 - cy1) / ry, (-x1 - cx1) / rx)
    da = a1 - a0
    if sweep and da < 0:
        da += 2 * math.pi
    elif not sweep and da > 0:
        da -= 2 * math.pi
    steps = max(12, int(abs(da) / (math.pi / 36)))
    return [(cx + rx * math.cos(a0 + da * s / steps) * cp - ry * math.sin(a0 + da * s / steps) * sp,
             cy + rx * math.cos(a0 + da * s / steps) * sp + ry * math.sin(a0 + da * s / steps) * cp)
            for s in range(1, steps + 1)]


def num(element, name, default=0.0):
    return float(element.get(name, default))


def element_to_path(tag, el):
    if tag == "path":
        return el.get("d", "")
    if tag == "circle":
        cx, cy, r = num(el, "cx"), num(el, "cy"), num(el, "r")
        return f"M{fmt(cx - r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0z"
    if tag == "ellipse":
        cx, cy, rx, ry = num(el, "cx"), num(el, "cy"), num(el, "rx"), num(el, "ry")
        return f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0z"
    if tag == "rect":
        x, y, w, h = num(el, "x"), num(el, "y"), num(el, "width"), num(el, "height")
        rx = num(el, "rx", el.get("ry", 0)) if el.get("rx") or el.get("ry") else 0.0
        ry = num(el, "ry", rx)
        if rx == 0 or ry == 0:
            return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
        return (f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(ry)}"
                f"v{fmt(h - 2 * ry)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(ry)}h{fmt(-(w - 2 * rx))}"
                f"a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(-ry)}v{fmt(-(h - 2 * ry))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(-ry)}z")
    if tag == "line":
        return f"M{fmt(num(el, 'x1'))} {fmt(num(el, 'y1'))}L{fmt(num(el, 'x2'))} {fmt(num(el, 'y2'))}"
    if tag in ("polyline", "polygon"):
        values = [float(v) for v in NUMBER.findall(el.get("points", ""))]
        pairs = [(values[i], values[i + 1]) for i in range(0, len(values) - 1, 2)]
        body = "M" + "L".join(f"{fmt(x)} {fmt(y)}" for x, y in pairs)
        return body + ("z" if tag == "polygon" else "")
    raise ValueError(f"unsupported element <{tag}>")


def load_icon(path, check_bounds=True):
    root = ET.parse(path).getroot()
    if root.get("viewBox") != "0 0 24 24":
        raise ValueError("viewBox must be '0 0 24 24'")
    if root.get("stroke-width") != "2":
        raise ValueError("root stroke-width must be 2")
    shapes = []
    for el in root:
        tag = el.tag.replace("{http://www.w3.org/2000/svg}", "")
        if tag in ("title", "desc"):
            continue
        if el.get("transform"):
            raise ValueError("transform attributes are not supported; bake them into the path data")
        fill = el.get("fill", root.get("fill", "none")) != "none"
        if fill and el.get("stroke", root.get("stroke", "none")) != "none":
            raise ValueError("a shape is either filled (stroke=\"none\") or stroked, not both")
        data = normalise(element_to_path(tag, el))
        low, high = FILL_BOX if fill else STROKE_BOX
        for x, y in flatten(data) if check_bounds else ():
            if not (low - TOLERANCE <= x <= high + TOLERANCE and low - TOLERANCE <= y <= high + TOLERANCE):
                raise ValueError(f"point ({x:.2f}, {y:.2f}) outside the {low:g}..{high:g} {'fill' if fill else 'stroke'} box")
        shapes.append((data, fill))
    if not shapes:
        raise ValueError("no shapes")
    return shapes


def load_all(directory=HERE, check_bounds=True):
    icons, errors = {}, []
    for path in sorted(directory.glob("*.svg")):
        try:
            icons[path.stem] = load_icon(path, check_bounds)
        except Exception as error:
            errors.append(f"{path.name}: {error}")
    if errors:
        raise SystemExit("\n".join(errors))
    for alias, target in ALIASES.items():
        if target not in icons:
            raise SystemExit(f"alias {alias} points to missing icon {target}")
        icons[alias] = icons[target]
    return dict(sorted(icons.items()))


def render_kotlin(icons):
    lines = [
        "package app.line.ui",
        "",
        "/** One piece of an icon on a 24x24 grid: an outline (2-unit round-capped stroke), or a solid shape when [fill]. */",
        "data class IconShape(val d: String, val fill: Boolean = false)",
        "",
        "/** Generated by tools/icons/build_icon_paths.py from tools/icons/*.svg; edit the SVGs, not this file. */",
        "object IconPaths {",
        "    val all: Map<String, List<IconShape>> = mapOf(",
    ]
    for name, shapes in icons.items():
        lines.append(f'        "{name}" to listOf(')
        for data, fill in shapes:
            lines.append(f'            IconShape("{data}"{", true" if fill else ""}),')
        lines.append("        ),")
    lines += ["    )", "", "    fun get(name: String): List<IconShape>? = all[name]", "}", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="exit 1 when IconPaths.kt is not up to date")
    args = parser.parse_args()
    icons = load_all()
    text = render_kotlin(icons)
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT} is out of date; run tools/icons/build_icon_paths.py", file=sys.stderr)
            return 1
        print(f"{OUT.name} is up to date ({len(icons)} icons)")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    shapes = sum(len(v) for v in icons.values())
    print(f"wrote {OUT.relative_to(ROOT)}: {len(icons)} icons, {shapes} shapes, {len(text)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())

