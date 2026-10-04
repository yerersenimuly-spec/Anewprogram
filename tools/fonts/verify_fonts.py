#!/usr/bin/env python3
"""Verifies the bundled Inter subset: size budget, glyph coverage, OpenType features, weights.

Usage: verify_fonts.py [--source DIR --unicodes LIST]
With --source (directory holding the upstream Inter-*.ttf) it also proves that subsetting dropped no
requested codepoint the upstream font has, and lists the requested ones upstream lacks.
"""
import argparse
import sys
from pathlib import Path

from fontTools.subset import parse_unicodes
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
FONT_DIR = ROOT / "app/src/main/res/font"
LICENSE = ROOT / "app/src/main/assets/licenses/Inter-OFL.txt"
BUDGET = 130 * 1024
WEIGHTS = {
    "inter_regular.ttf": (400, "Regular"),
    "inter_medium.ttf": (500, "Medium"),
    "inter_semibold.ttf": (600, "SemiBold"),
    "inter_bold.ttf": (700, "Bold"),
}


def span(first, last):
    return [chr(c) for c in range(first, last + 1)]


# Must be present in every file.
GROUPS = {
    "ASCII": span(0x20, 0x7E),
    "Russian": span(0x0410, 0x044F) + ["Ё", "ё"],
    "Kazakh": list("ӘәҒғҚқҢңӨөҰұҮүҺһІі"),
    "Punctuation": [chr(c) for c in (
        0x2022, 0x2026, 0x2013, 0x2014, 0x00B7, 0x2018, 0x2019, 0x201A, 0x201C, 0x201D, 0x201E,
        0x00AB, 0x00BB, 0x2039, 0x203A, 0x2009, 0x200A, 0x202F)],
    "Symbols": [chr(c) for c in (0x2212, 0x20B8, 0x20BD, 0x20AC, 0x2713, 0x2116, 0x2122, *range(0x2190, 0x2194))],
}
GSUB_REQUIRED = ["calt", "case", "ss01", "cv11", "tnum"]
GPOS_REQUIRED = ["kern"]


def features(font, tag):
    table = font[tag].table
    return {record.FeatureTag for record in table.FeatureList.FeatureRecord}


def tnum_widths(font):
    """Advance widths of the digits after applying the single substitutions of 'tnum'."""
    gsub = font["GSUB"].table
    cmap = font.getBestCmap()
    mapping = {}
    for record in gsub.FeatureList.FeatureRecord:
        if record.FeatureTag != "tnum":
            continue
        for index in record.Feature.LookupListIndex:
            for sub in gsub.LookupList.Lookup[index].SubTable:
                sub = getattr(sub, "ExtSubTable", sub)
                mapping.update(getattr(sub, "mapping", {}))
    hmtx = font["hmtx"]
    return [hmtx[mapping.get(cmap[ord(d)], cmap[ord(d)])][0] for d in "0123456789"]


def ranges(codepoints):
    out, start, prev = [], None, None
    for cp in sorted(codepoints):
        if start is None:
            start = prev = cp
        elif cp == prev + 1:
            prev = cp
        else:
            out.append((start, prev))
            start = prev = cp
    if start is not None:
        out.append((start, prev))
    return " ".join(f"{a:04X}" if a == b else f"{a:04X}-{b:04X}" for a, b in out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, help="directory with the upstream Inter-<Weight>.ttf files")
    parser.add_argument("--unicodes", help="the --unicodes list given to pyftsubset")
    args = parser.parse_args()
    wanted = set(parse_unicodes(args.unicodes)) if args.unicodes else None

    failures = []
    print(f"{'file':<22}{'bytes':>8}{'KiB':>8}{'glyphs':>8}{'weight':>8}")
    for name, (weight, upstream) in WEIGHTS.items():
        path = FONT_DIR / name
        if not path.exists():
            failures.append(f"{name}: missing")
            continue
        size = path.stat().st_size
        font = TTFont(path)
        cmap = font.getBestCmap()
        print(f"{name:<22}{size:>8}{size / 1024:>8.1f}{len(font.getGlyphOrder()):>8}{font['OS/2'].usWeightClass:>8}")
        if size > BUDGET:
            failures.append(f"{name}: {size} bytes exceeds the {BUDGET} byte budget")
        if font["OS/2"].usWeightClass != weight:
            failures.append(f"{name}: usWeightClass {font['OS/2'].usWeightClass} != {weight}")
        for group, chars in GROUPS.items():
            missing = [f"U+{ord(c):04X}" for c in chars if ord(c) not in cmap]
            if missing:
                failures.append(f"{name}: {group} missing {', '.join(missing[:12])}{' ...' if len(missing) > 12 else ''}")
        if args.source and wanted is not None:
            source_cmap = TTFont(args.source / f"Inter-{upstream}.ttf").getBestCmap()
            dropped = [cp for cp in wanted if cp in source_cmap and cp not in cmap]
            if dropped:
                failures.append(f"{name}: subsetting dropped upstream codepoints {ranges(dropped)}")
            if name == "inter_regular.ttf":
                kept = sum(1 for cp in wanted if cp in cmap)
                print(f"  requested {len(wanted)} codepoints, kept {kept}; "
                      f"not in upstream Inter: {ranges(cp for cp in wanted if cp not in source_cmap)}")
        gsub, gpos = features(font, "GSUB"), features(font, "GPOS")
        for tag in GSUB_REQUIRED:
            if tag not in gsub:
                failures.append(f"{name}: GSUB feature '{tag}' missing")
        for tag in GPOS_REQUIRED:
            if tag not in gpos:
                failures.append(f"{name}: GPOS feature '{tag}' missing")
        widths = tnum_widths(font)
        if len(set(widths)) != 1:
            failures.append(f"{name}: tnum digits are not tabular {widths}")
        names = {record.nameID: record.toUnicode() for record in font["name"].names if record.platformID == 3}
        for name_id in (0, 1, 2, 4, 6, 13):
            if name_id not in names:
                failures.append(f"{name}: name table lacks nameID {name_id}")
        print(f"  family={names.get(1)!r} style={names.get(2)!r} tnum-digit-advance={widths[0]} "
              f"GSUB={sorted(gsub)} GPOS={sorted(gpos)}")
    print("coverage groups checked:", ", ".join(f"{k} ({len(v)})" for k, v in GROUPS.items()))
    if not LICENSE.exists() or "SIL OPEN FONT LICENSE Version 1.1" not in LICENSE.read_text(encoding="utf-8"):
        failures.append(f"{LICENSE}: missing or not the OFL text")
    if failures:
        print("\nFAILED:")
        for failure in failures:
            print(" -", failure)
        return 1
    print("\nOK: all fonts pass size, coverage, feature and weight checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())

