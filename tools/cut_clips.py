#!/usr/bin/env python3
"""Cut one recording of the wake word said N times into the N clips udkgen wants.

Record ONE take on the phone: the word N times (I used 10), about 2 s apart, from where you normally are,
with the phone where it normally lives. A close-up, quiet take made a model that only caught me 2 times
in 5 at the bar I needed; the take from my normal spot caught 5/5.

What this does (each step mattered, see the README):
  1. ffmpeg -> 16 kHz mono int16.
  2. RMS in 20 ms frames; "loud" = above the 10th-percentile frame + 12 dB; loud runs closer than 300 ms merge.
  3. Keep the N loudest runs, in time order; centre = the loudest frame of each run.
  4. Cut +-1.05 s around each centre. NOT noise-gated: raw cuts and gated cuts both made the Qualcomm
     library refuse the clips (status 14, "no signal").
  5. Peak-normalise each cut to 0.5 full scale and pad 0.5 s of digital silence on both sides.
  6. Write <out>/d01.raw ... (raw int16 little-endian, 16 kHz, mono) = udkgen's input format.

Listen to the clips (or run them through a speech-to-text tool) before you build a model from them.

usage: cut_clips.py <recording.(m4a|wav|...)> <outdir> [--count 10]
needs: ffmpeg on PATH, numpy
"""
import argparse
import os
import subprocess
import sys

import numpy as np

RATE = 16000
FRAME = RATE // 50                 # 20 ms
HALF = int(1.05 * RATE)            # +-1.05 s around the centre
PAD = int(0.5 * RATE)              # 0.5 s of zeros each side
PEAK = 0.5 * 32767


def load(path):
    pcm = subprocess.run(["ffmpeg", "-v", "error", "-i", path, "-ac", "1", "-ar", str(RATE), "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    return np.frombuffer(pcm, dtype=np.int16).astype(np.float64)


def find_centres(x, count):
    n = len(x) // FRAME
    rms = np.sqrt(np.mean(x[:n * FRAME].reshape(n, FRAME) ** 2, axis=1)) + 1e-9
    db = 20 * np.log10(rms)
    loud = db > np.percentile(db, 10) + 12
    runs, start, gap_max = [], None, 300 // 20
    last = -10 ** 9
    for i, on in enumerate(loud):
        if not on:
            continue
        if start is not None and i - last <= gap_max:
            last = i
            continue
        if start is not None:
            runs.append((start, last))
        start = last = i
    if start is not None:
        runs.append((start, last))
    # strongest runs first, keep `count`, then back to time order
    scored = sorted(runs, key=lambda r: -float(np.sum(rms[r[0]:r[1] + 1] ** 2)))[:count]
    scored.sort()
    return [(a + int(np.argmax(rms[a:b + 1]))) * FRAME + FRAME // 2 for a, b in scored]


def cut(x, c):
    seg = np.zeros(2 * HALF)
    lo, hi = c - HALF, c + HALF
    src = x[max(lo, 0):min(hi, len(x))]
    seg[max(0, -lo):max(0, -lo) + len(src)] = src
    peak = np.max(np.abs(seg)) or 1.0
    seg = seg * (PEAK / peak)
    return np.concatenate([np.zeros(PAD), seg, np.zeros(PAD)]).round().astype("<i2")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("recording")
    ap.add_argument("outdir")
    ap.add_argument("--count", type=int, default=10)
    a = ap.parse_args()
    x = load(a.recording)
    centres = find_centres(x, a.count)
    if len(centres) < a.count:
        print(f"only found {len(centres)} of {a.count} words; re-record with clearer gaps", file=sys.stderr)
    os.makedirs(a.outdir, exist_ok=True)
    for i, c in enumerate(centres, 1):
        p = os.path.join(a.outdir, f"d{i:02d}.raw")
        cut(x, c).tofile(p)
        print(f"{p}  centre {c / RATE:6.2f} s")
    return 0 if len(centres) == a.count else 1


if __name__ == "__main__":
    sys.exit(main())
