#!/usr/bin/env python3
"""Procedural, deterministic UI sound generator for the Line Android app.

Every cue is synthesised from sine partials with optional light FM: no samples, no noise,
no RNG, so the result is original (CC0) and reproducible byte for byte. All pitches are
D-major pentatonic (D E F# A B). Output: mono, 16-bit PCM, 48 kHz WAVs in
app/src/main/res/raw/.

    python3 -m venv /tmp/soundvenv && /tmp/soundvenv/bin/pip install -r tools/sounds/requirements.txt
    /tmp/soundvenv/bin/python tools/sounds/generate_ui_sounds.py
    /tmp/soundvenv/bin/python tools/sounds/analyze_ui_sounds.py
"""
from __future__ import annotations

import argparse
import hashlib
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np

SR = 48_000
LOWPASS_HZ = 7_000.0
FADE_OUT_MS = 2.0
PENTATONIC = {"D", "E", "F#", "A", "B"}
_SEMITONE = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}
REPO = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO / "app" / "src" / "main" / "res" / "raw"


def hz(name: str) -> float:
    pitch, octave = name[:-1], int(name[-1])
    if pitch not in PENTATONIC:
        raise ValueError(f"{name} is outside D-major pentatonic")
    midi = 12 * (octave + 1) + _SEMITONE[pitch]
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


@dataclass(frozen=True)
class Note:
    at_ms: float
    pitch: str
    tau_ms: float                                   # amplitude decay time constant
    glide_from: str | None = None                   # pentatonic pitch the voice glides up/down from
    glide_tau_ms: float = 20.0
    scoop_st: float = 0.0                           # transient pitch scoop (semitones), not heard as a pitch
    scoop_tau_ms: float = 8.0
    attack_ms: float = 3.0                          # linear fade-in
    gain: float = 1.0
    partials: tuple[tuple[float, float], ...] = ((1.0, 1.0),)   # (frequency ratio, amplitude)
    damping: float = 0.0                            # higher partials decay faster: tau / (1 + damping * (ratio - 1))
    fm: tuple[float, float, float] | None = None    # on the first partial: (modulator ratio, index, index decay ms)


@dataclass(frozen=True)
class Cue:
    name: str
    length_ms: float
    peak_db: float
    fade_in_ms: float
    notes: tuple[Note, ...]


def _decay(t: np.ndarray, tau: float, end: float) -> np.ndarray:
    """Exponential decay shifted and scaled so it reaches exactly zero at t == end."""
    floor = np.exp(-end / tau)
    return (np.exp(-t / tau) - floor) / (1.0 - floor)


def render(note: Note, total: int) -> np.ndarray:
    start = int(round(note.at_ms * SR / 1000))
    n = total - start
    t = np.arange(n) / SR
    end = (n - 1) / SR
    f_end = hz(note.pitch)
    semis = np.zeros(n)
    if note.glide_from:
        semis += 12.0 * np.log2(hz(note.glide_from) / f_end) * np.exp(-t / (note.glide_tau_ms / 1000))
    if note.scoop_st:
        semis += note.scoop_st * np.exp(-t / (note.scoop_tau_ms / 1000))
    inst = f_end * 2.0 ** (semis / 12.0)
    phase = 2 * np.pi * np.concatenate(([0.0], np.cumsum(inst[:-1]))) / SR
    tau = note.tau_ms / 1000
    y = np.zeros(n)
    for k, (ratio, amp) in enumerate(note.partials):
        arg = ratio * phase
        if k == 0 and note.fm:
            mod, index, index_ms = note.fm
            arg = arg + index * np.exp(-t / (index_ms / 1000)) * np.sin(mod * phase)
        y += amp * np.sin(arg) * _decay(t, tau / (1.0 + note.damping * (ratio - 1.0)), end)
    y *= np.clip(t / (note.attack_ms / 1000), 0.0, 1.0)
    out = np.zeros(total)
    out[start:] = note.gain * y
    return out


def _lowpass(x: np.ndarray, taps: int = 97, beta: float = 7.0) -> np.ndarray:
    m = np.arange(taps) - (taps - 1) / 2
    h = np.sinc(2 * LOWPASS_HZ / SR * m) * np.kaiser(taps, beta)
    return np.convolve(x, h / h.sum(), mode="same")


def finalize(x: np.ndarray, peak_db: float, fade_in_ms: float) -> np.ndarray:
    x = _lowpass(x)
    idx = np.arange(len(x))
    x = x * np.clip(idx / (SR * fade_in_ms / 1000), 0.0, 1.0)
    fo = int(SR * FADE_OUT_MS / 1000)
    x[-fo:] *= 0.5 * (1 + np.cos(np.pi * np.arange(1, fo + 1) / fo))
    bump = np.sin(np.pi * idx / (len(x) - 1)) ** 2          # zero at both ends: removes DC, keeps edges at 0
    x = x - bump * (x.mean() / bump.mean())
    return x * (10 ** (peak_db / 20) / np.max(np.abs(x)))


def synthesize(cue: Cue) -> np.ndarray:
    total = int(round(cue.length_ms * SR / 1000))
    mix = sum(render(n, total) for n in cue.notes)
    return finalize(mix, cue.peak_db, cue.fade_in_ms)


def write_wav(path: Path, x: np.ndarray) -> bytes:
    pcm = np.round(np.clip(x, -1.0, 1.0) * 32767).astype("<i2").tobytes()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm)
    return pcm


BELL = ((1.0, 1.0), (2.0, 0.28), (3.0, 0.07), (0.5, 0.16))
THUD = ((1.0, 1.0), (2.0, 0.5), (3.0, 0.14))
ROUND = ((1.0, 1.0), (2.0, 0.08))

CUES: tuple[Cue, ...] = (
    # Dry, soft tick: D5 body with a tiny pitch scoop and upper partials that die within ~3 ms.
    Cue("ui_tap", 32, -12.5, 2.0, (
        Note(0, "D5", 5.5, scoop_st=5, scoop_tau_ms=3.0, attack_ms=2.0, partials=((1.0, 1.0), (2.0, 0.38), (3.0, 0.12)), damping=0.8),
    )),
    # One rising pop: D5 glides up to A5 with a faint octave partial.
    Cue("ui_send", 150, -6.2, 3.0, (
        Note(0, "A5", 32, glide_from="D5", glide_tau_ms=22, attack_ms=3.0, partials=((1.0, 1.0), (2.0, 0.18)), damping=0.4),
    )),
    # Soft falling "ding-dong": F#5 then D5, rounder and lower than send (barely any FM attack).
    Cue("ui_receive", 200, -8.5, 4.0, (
        Note(0, "F#5", 34, attack_ms=4.0, partials=ROUND, fm=(2.0, 0.15, 10)),
        Note(78, "D5", 46, attack_ms=4.0, gain=0.92, partials=ROUND, fm=(2.0, 0.15, 10)),
    )),
    # Two muted low thuds, D4 then B3 (descending minor third). Upper partials die fast, leaving a
    # round body; the 2nd/3rd partials carry the cue on phone speakers that cannot reproduce ~250 Hz.
    Cue("ui_error", 220, -7.0, 3.0, (
        Note(0, "D4", 40, scoop_st=4, scoop_tau_ms=10, attack_ms=3.0, partials=THUD, damping=1.2),
        Note(92, "B3", 52, scoop_st=3, scoop_tau_ms=10, attack_ms=3.0, gain=0.9, partials=THUD, damping=1.2),
    )),
    Cue("ui_toggle_on", 70, -13.5, 2.0, (
        Note(0, "A5", 9, attack_ms=2.0, partials=((1.0, 1.0), (2.0, 0.10))),
        Note(30, "E6", 11, attack_ms=2.0, gain=0.95, partials=((1.0, 1.0), (2.0, 0.08))),
    )),
    Cue("ui_toggle_off", 70, -14.5, 2.0, (
        Note(0, "E6", 9, attack_ms=2.0, partials=((1.0, 1.0), (2.0, 0.06))),
        Note(30, "A5", 11, attack_ms=2.0, gain=0.95, partials=((1.0, 1.0), (2.0, 0.06))),
    )),
    Cue("ui_record_start", 95, -14.0, 2.5, (
        Note(0, "D5", 24, glide_from="A4", glide_tau_ms=14, attack_ms=2.5, partials=((1.0, 1.0), (2.0, 0.12))),
    )),
    Cue("ui_record_cancel", 120, -14.0, 2.5, (
        Note(0, "A4", 32, glide_from="E5", glide_tau_ms=30, attack_ms=2.5, partials=((1.0, 1.0), (2.0, 0.08))),
    )),
    # Warm rising chime: D5 then A5 (perfect fifth), sub-octave body and a short FM mallet transient.
    Cue("ui_call_connected", 330, -8.5, 4.0, (
        Note(0, "D5", 62, attack_ms=4.0, partials=BELL, damping=0.5, fm=(3.5, 0.25, 9)),
        Note(105, "A5", 85, attack_ms=4.0, gain=0.95, partials=BELL, damping=0.5, fm=(3.5, 0.25, 9)),
    )),
    # Descending counterpart: A5 then D5, darker (weaker transient) and a little shorter.
    Cue("ui_call_ended", 280, -8.5, 4.0, (
        Note(0, "A5", 52, attack_ms=4.0, partials=BELL, damping=0.5, fm=(3.5, 0.14, 8)),
        Note(95, "D5", 74, attack_ms=4.0, gain=0.95, partials=BELL, damping=0.5, fm=(3.5, 0.14, 8)),
    )),
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="output directory (default: app raw resources)")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for cue in CUES:
        path = args.out / f"{cue.name}.wav"
        pcm = write_wav(path, synthesize(cue))
        print(f"{path.name:22s} {len(pcm) / 2 / SR * 1000:6.1f} ms {path.stat().st_size:6d} B sha256={hashlib.sha256(path.read_bytes()).hexdigest()[:12]}")


if __name__ == "__main__":
    main()
