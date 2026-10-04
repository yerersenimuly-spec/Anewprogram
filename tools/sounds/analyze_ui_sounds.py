#!/usr/bin/env python3
"""Measure and verify the Line UI sound assets (see generate_ui_sounds.py).

Prints a metrics table, asserts the format/level/spectrum rules and, when matplotlib is
available, renders a waveform + spectrogram PNG per cue plus an overview sheet.

    python tools/sounds/analyze_ui_sounds.py [--raw DIR] [--png-dir DIR] [--no-png]

Exit status is non-zero when any assertion fails.
"""
from __future__ import annotations

import argparse
import re
import sys
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[2]
RAW = REPO / "app" / "src" / "main" / "res" / "raw"
PNG_DIR = REPO / ".hoplite" / "artifacts" / "sounds"
PLAYER = REPO / "app" / "src" / "main" / "java" / "app" / "line" / "ui" / "UiSounds.kt"
SR = 48_000
MAX_BYTES = 40_000
FFT_SIZE = 1 << 16
PRIMARY, SECONDARY = "primary", "secondary"


@dataclass(frozen=True)
class Spec:
    tier: str
    ms: tuple[float, float]
    peak_db: tuple[float, float]


# Duration windows (ms) and peak windows (dBFS) from the sound brief.
LOUD, SOFT = (-9.0, -6.0), (-16.0, -12.0)
SPECS = {
    "ui_tap": Spec(SECONDARY, (5, 35), SOFT),
    "ui_send": Spec(PRIMARY, (110, 170), LOUD),
    "ui_receive": Spec(PRIMARY, (160, 220), LOUD),
    "ui_error": Spec(PRIMARY, (180, 240), LOUD),
    "ui_toggle_on": Spec(SECONDARY, (50, 80), SOFT),
    "ui_toggle_off": Spec(SECONDARY, (50, 80), SOFT),
    "ui_record_start": Spec(SECONDARY, (70, 110), SOFT),
    "ui_record_cancel": Spec(SECONDARY, (90, 140), SOFT),
    "ui_call_connected": Spec(PRIMARY, (250, 350), LOUD),
    "ui_call_ended": Spec(PRIMARY, (220, 300), LOUD),
}
MAX_DC = 1e-4                 # full scale
MAX_EDGE_LSB = 1
MAX_ROLLOFF99_HZ = 8_000
MAX_ABOVE_8K_DB = -40.0       # energy above 8 kHz relative to total
MAX_TIER_RMS_SPREAD_DB = 3.0


def db(v: float) -> float:
    return 20 * np.log10(max(float(v), 1e-12))


def load(path: Path) -> tuple[np.ndarray, dict]:
    with wave.open(str(path), "rb") as w:
        info = {"channels": w.getnchannels(), "width": w.getsampwidth(), "rate": w.getframerate(), "frames": w.getnframes()}
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.float64), info


def measure(pcm: np.ndarray) -> dict:
    x = pcm / 32768.0
    power = np.abs(np.fft.rfft(x, FFT_SIZE)) ** 2
    freqs = np.fft.rfftfreq(FFT_SIZE, 1 / SR)
    total = power.sum()
    return {
        "ms": len(x) / SR * 1000,
        "peak_db": db(np.max(np.abs(x))),
        "rms_db": db(np.sqrt(np.mean(x ** 2))),
        "dc": float(np.mean(x)),
        "first": int(abs(pcm[0])),
        "last": int(abs(pcm[-1])),
        "centroid": float((freqs * power).sum() / total),
        "rolloff99": float(freqs[np.searchsorted(np.cumsum(power), 0.99 * total)]),
        "above8k_db": 10 * np.log10(max(power[freqs > 8000].sum() / total, 1e-12)),
        "clipped": int(np.sum(np.abs(x) > 0.999)),
    }


def stft_db(x: np.ndarray, nfft: int, hop: int) -> np.ndarray:
    win = np.hanning(nfft)
    pad = np.concatenate([np.zeros(nfft // 2), x, np.zeros(nfft // 2)])
    frames = np.lib.stride_tricks.sliding_window_view(pad, nfft)[::hop] * win
    mag = np.abs(np.fft.rfft(frames, axis=1)) / (win.sum() / 2)
    return 20 * np.log10(mag + 1e-9).T


def render_pngs(items: list[tuple[str, np.ndarray, dict]], out: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    out.mkdir(parents=True, exist_ok=True)

    def spectrogram(ax, x, ms, title=None):
        nfft = 256 if len(x) < 0.06 * SR else 512
        s = stft_db(x, nfft, nfft // 16)
        keep = int(8000 / (SR / 2) * (s.shape[0] - 1))
        ax.imshow(s[: keep + 1], origin="lower", aspect="auto", extent=[0, ms, 0, 8000], cmap="magma", vmin=-95, vmax=-15)
        ax.set_ylabel("Hz")
        if title:
            ax.set_title(title, fontsize=9)

    for name, pcm, m in items:
        x = pcm / 32768.0
        t = np.arange(len(x)) / SR * 1000
        fig, (a, b) = plt.subplots(2, 1, figsize=(8, 5), dpi=110, sharex=True, gridspec_kw={"height_ratios": [1, 1.6]})
        a.plot(t, x, lw=0.8, color="#2b6cb0")
        a.fill_between(t, x, color="#2b6cb0", alpha=0.25)
        a.set_ylim(-0.6, 0.6)
        a.set_ylabel("amplitude")
        a.set_title(f"{name}  {m['ms']:.0f} ms  peak {m['peak_db']:.1f} dBFS  rms {m['rms_db']:.1f} dBFS  centroid {m['centroid']:.0f} Hz", fontsize=9)
        spectrogram(b, x, m["ms"])
        b.set_xlabel("ms")
        fig.tight_layout()
        fig.savefig(out / f"{name}.png")
        plt.close(fig)

    cols = 5
    rows = (len(items) + cols - 1) // cols
    fig, axes = plt.subplots(rows, cols, figsize=(3.4 * cols, 2.6 * rows), dpi=100)
    for ax, (name, pcm, m) in zip(axes.ravel(), items):
        spectrogram(ax, pcm / 32768.0, m["ms"], f"{name.removeprefix('ui_')}  {m['ms']:.0f} ms")
        ax.set_xlabel("ms")
    fig.tight_layout()
    fig.savefig(out / "overview.png")
    plt.close(fig)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--raw", type=Path, default=RAW)
    parser.add_argument("--png-dir", type=Path, default=PNG_DIR)
    parser.add_argument("--no-png", action="store_true")
    args = parser.parse_args()

    failures: list[str] = []

    def check(ok: bool, msg: str) -> None:
        if not ok:
            failures.append(msg)

    on_disk = sorted(args.raw.glob("ui_*.wav"))
    check({p.stem for p in on_disk} == set(SPECS), f"asset set mismatch: {sorted(p.stem for p in on_disk)}")
    if PLAYER.exists():
        used = set(re.findall(r"R\.raw\.(ui_[a-z_]+)", PLAYER.read_text()))
        check(used == set(SPECS), f"UiSounds.kt raw references differ from assets: {sorted(used ^ set(SPECS))}")

    rows: list[tuple[str, dict]] = []
    items: list[tuple[str, np.ndarray, dict]] = []
    for path in on_disk:
        name = path.stem
        spec = SPECS.get(name)
        pcm, info = load(path)
        check(re.fullmatch(r"ui_[a-z_]+\.wav", path.name) is not None, f"{path.name}: name must be lowercase")
        check(info["channels"] == 1 and info["width"] == 2 and info["rate"] == SR, f"{name}: not mono/16-bit/48 kHz: {info}")
        check(path.stat().st_size < MAX_BYTES, f"{name}: {path.stat().st_size} bytes >= {MAX_BYTES}")
        m = measure(pcm)
        rows.append((name, m))
        items.append((name, pcm, m))
        if spec is None:
            continue
        check(spec.ms[0] <= m["ms"] <= spec.ms[1], f"{name}: duration {m['ms']:.1f} ms outside {spec.ms}")
        check(spec.peak_db[0] <= m["peak_db"] <= spec.peak_db[1], f"{name}: peak {m['peak_db']:.2f} dBFS outside {spec.peak_db}")
        check(abs(m["dc"]) <= MAX_DC, f"{name}: DC {m['dc']:.2e}")
        check(m["first"] <= MAX_EDGE_LSB and m["last"] <= MAX_EDGE_LSB, f"{name}: edges {m['first']}/{m['last']} LSB")
        check(m["clipped"] == 0, f"{name}: {m['clipped']} samples above 0.999 FS")
        check(m["rolloff99"] <= MAX_ROLLOFF99_HZ, f"{name}: 99% rolloff {m['rolloff99']:.0f} Hz")
        check(m["above8k_db"] <= MAX_ABOVE_8K_DB, f"{name}: {m['above8k_db']:.1f} dB of energy above 8 kHz")

    for tier in (PRIMARY, SECONDARY):
        levels = [m["rms_db"] for n, m in rows if n in SPECS and SPECS[n].tier == tier]
        if levels:
            spread = max(levels) - min(levels)
            check(spread <= MAX_TIER_RMS_SPREAD_DB, f"{tier} tier RMS spread {spread:.2f} dB > {MAX_TIER_RMS_SPREAD_DB}")
            print(f"{tier:9s} tier RMS {min(levels):.1f}..{max(levels):.1f} dBFS (spread {spread:.2f} dB)")

    print(f"{'cue':18s}{'ms':>7s}{'peak':>8s}{'rms':>8s}{'dc':>10s}{'first':>6s}{'last':>6s}{'centroid':>10s}{'roll99':>8s}{'>8k dB':>8s}{'>.999':>6s}")
    for name, m in rows:
        print(f"{name:18s}{m['ms']:7.1f}{m['peak_db']:8.2f}{m['rms_db']:8.2f}{m['dc']:10.1e}{m['first']:6d}{m['last']:6d}"
              f"{m['centroid']:10.0f}{m['rolloff99']:8.0f}{m['above8k_db']:8.1f}{m['clipped']:6d}")

    if not args.no_png and items:
        try:
            render_pngs(items, args.png_dir)
            print(f"PNGs written to {args.png_dir}")
        except ImportError:
            print("matplotlib not installed: skipped PNG rendering")

    if failures:
        print("\nFAILED:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("\nOK: all assertions passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
