#!/usr/bin/env bash
# Rebuilds the bundled Inter subset (SIL OFL 1.1) from the official 4.1 release.
#   Output: app/src/main/res/font/inter_{regular,medium,semibold,bold}.ttf
#           app/src/main/assets/licenses/Inter-OFL.txt
# Needs: bash, curl, unzip, python3 with pip. fonttools is pinned and installed into $WORK/pylib.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${LINE_FONTS_WORK:-${TMPDIR:-/tmp}/line-fonts}"
INTER_VERSION="4.1"
INTER_URL="https://github.com/rsms/inter/releases/download/v${INTER_VERSION}/Inter-${INTER_VERSION}.zip"
INTER_SHA256="9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e"
FONTTOOLS_VERSION="4.66.1"

FONT_OUT="$ROOT/app/src/main/res/font"
LICENSE_OUT="$ROOT/app/src/main/assets/licenses/Inter-OFL.txt"

# Basic Latin, Latin-1, Latin Ext-A (+ U+01F4/5 for Kazakh Latin), combining marks, Cyrillic + Supplement,
# general punctuation, numero/trademark, arrows, minus, euro/tenge/ruble, check marks.
UNICODES="U+0020-007E,U+00A0-00FF,U+0100-017F,U+01F4-01F5,U+0300-036F,U+0400-04FF,U+0500-052F,U+2000-206F,U+2116,U+2122,U+2190-2193,U+2212,U+20AC,U+20B8,U+20BD,U+2713,U+2714"
# Inter has calt/case/ss01/cv11/tnum/zero but no liga; ccmp/locl/mark/mkmk keep shaping and diacritics correct.
FEATURES="kern,liga,calt,tnum,case,ss01,cv11,zero,ccmp,locl,mark,mkmk"

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$WORK" "$FONT_OUT" "$(dirname "$LICENSE_OUT")"

PYLIB="$WORK/pylib-${FONTTOOLS_VERSION}"
if [ ! -d "$PYLIB/fontTools" ]; then
  python3 -m pip install --quiet --target "$PYLIB" "fonttools==${FONTTOOLS_VERSION}"
fi
export PYTHONPATH="$PYLIB${PYTHONPATH:+:$PYTHONPATH}"

ZIP="$WORK/Inter-${INTER_VERSION}.zip"
[ -f "$ZIP" ] || curl -fsSL --retry 3 -o "$ZIP" "$INTER_URL"
ACTUAL="$(sha256 "$ZIP")"
if [ "$ACTUAL" != "$INTER_SHA256" ]; then
  echo "Inter-${INTER_VERSION}.zip checksum mismatch (got $ACTUAL)" >&2
  rm -f "$ZIP"
  exit 1
fi

SRC="$WORK/src"
rm -rf "$SRC"
mkdir -p "$SRC"
unzip -q -o "$ZIP" 'extras/ttf/Inter-Regular.ttf' 'extras/ttf/Inter-Medium.ttf' \
  'extras/ttf/Inter-SemiBold.ttf' 'extras/ttf/Inter-Bold.ttf' LICENSE.txt -d "$SRC"

subset() {
  python3 -m fontTools.subset "$SRC/extras/ttf/Inter-$1.ttf" \
    --unicodes="$UNICODES" \
    --layout-features="$FEATURES" \
    --no-hinting --notdef-outline --recommended-glyphs \
    --name-IDs='*' --name-legacy --name-languages='*' \
    --output-file="$FONT_OUT/$2"
}

subset Regular  inter_regular.ttf
subset Medium   inter_medium.ttf
subset SemiBold inter_semibold.ttf
subset Bold     inter_bold.ttf

cp "$SRC/LICENSE.txt" "$LICENSE_OUT"

python3 "$ROOT/tools/fonts/verify_fonts.py" --source "$SRC/extras/ttf" --unicodes "$UNICODES"

