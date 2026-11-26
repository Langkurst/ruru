"""
Makes the Sleep tab's bundled recordings: seamless 48 kHz stereo AAC-LC loops (.m4a, 64 kbps) in
app/shared/src/commonMain/composeResources/files/sleep/<id>.m4a, which Android and iPhone both
read, plus loops.txt (one "<id> <frames>" line per loop).

    python tools/sleep-sounds/make_loops.py <folder with the downloaded originals>
    python tools/sleep-sounds/make_loops.py --from-loops <folder of <id>.ogg loops already cut>

For each entry of sources.json that is bundled (wind and heavy rain are synthesised instead): cut
`length_s + crossfade_s` seconds from `start_s` (high-passed at `highpass_hz` to drop rumble and DC,
resampled to 48 kHz stereo by ffmpeg), crossfade the extra seconds at the end into the start with an
equal-power curve so the loop's end runs straight into its beginning, and level it to `target_rms_db`
(peaks kept under -1 dBFS). `--from-loops` skips the cutting and only re-encodes loops that were cut
earlier (the Ogg loops of Ruru 0.2 / Earshot), which is how the shipped files were made.

AAC adds a short start-up and a short tail to every file, and Android and iPhone differ in whether
their decoders remove them. So each loop is encoded with one second of its own end in front and one
second of its own start behind it. The player cuts exactly `frames` frames from inside the file (see
LoopCut in the app), which is a seamless loop whether or not the decoder trimmed anything.

Needs ffmpeg on PATH and numpy. The originals are not kept in the repo; sources.json says where each
came from and under which licence (all public domain).
"""
import json
import pathlib
import subprocess
import sys

import numpy as np

RATE = 48_000
HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = REPO / "app/shared/src/commonMain/composeResources/files/sleep"
BITRATE = "64k"
PAD = RATE  # one second either side


def decode(path: pathlib.Path, start: float, seconds: float, highpass_hz: float) -> np.ndarray:
    # Two poles of high-pass: drops wind rumble, handling thumps and DC that a loop would repeat.
    cmd = [
        "ffmpeg", "-v", "error", "-ss", str(start), "-t", str(seconds), "-i", str(path),
        "-af", f"highpass=f={highpass_hz}:poles=2", "-ac", "2", "-ar", str(RATE), "-f", "f32le", "pipe:1",
    ]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).astype(np.float64)


def seamless(seg: np.ndarray, length: int, fade: int) -> np.ndarray:
    """seg holds length + fade frames; its last `fade` frames are blended into the first ones."""
    loop = seg[:length].copy()
    t = np.linspace(0.0, 1.0, fade, endpoint=False)[:, None]
    fade_in = np.sin(t * np.pi / 2)
    fade_out = np.cos(t * np.pi / 2)
    loop[:fade] = seg[:fade] * fade_in + seg[length:length + fade] * fade_out
    return loop


def seam_score(loop: np.ndarray) -> float:
    """How big the jump at the wrap is, against the recording's own sample-to-sample steps (1 = invisible)."""
    steps = np.abs(np.diff(loop, axis=0)).mean()
    jump = np.abs(loop[0] - loop[-1]).mean()
    return float(jump / max(steps, 1e-12))


def level(loop: np.ndarray, target_rms_db: float) -> np.ndarray:
    rms = np.sqrt(np.mean(loop ** 2))
    gain = 10 ** (target_rms_db / 20) / max(rms, 1e-9)
    peak = np.abs(loop).max() * gain
    ceiling = 10 ** (-1 / 20)
    if peak > ceiling:
        gain *= ceiling / peak
    return loop * gain


def encode(loop: np.ndarray, out: pathlib.Path) -> None:
    padded = np.concatenate([loop[-PAD:], loop, loop[:PAD]])
    cmd = [
        "ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(RATE), "-ac", "2", "-i", "pipe:0",
        "-c:a", "aac", "-b:a", BITRATE, "-map_metadata", "-1", "-movflags", "+faststart", str(out),
    ]
    subprocess.run(cmd, check=True, input=padded.astype(np.float32).tobytes())


def read_loop(path: pathlib.Path) -> np.ndarray:
    cmd = ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "2", "-ar", str(RATE), "-f", "f32le", "pipe:1"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).astype(np.float64)


def main() -> None:
    args = sys.argv[1:]
    from_loops = args[:1] == ["--from-loops"]
    if from_loops:
        args = args[1:]
    if len(args) != 1:
        sys.exit(__doc__)
    folder = pathlib.Path(args[0])
    sources = json.loads((HERE / "sources.json").read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = []
    for s in sources["recordings"]:
        out = OUT / f"{s['id']}.m4a"
        if from_loops:
            loop = read_loop(folder / f"{s['id']}.ogg")
        else:
            length, fade = int(s["length_s"] * RATE), int(s["crossfade_s"] * RATE)
            seg = decode(folder / s["file"], s["start_s"], s["length_s"] + s["crossfade_s"], s.get("highpass_hz", 25))
            if len(seg) < length + fade:
                sys.exit(f"{s['id']}: the original is too short for start {s['start_s']} s + {s['length_s'] + s['crossfade_s']} s")
            loop = level(seamless(seg, length, fade), s["target_rms_db"])
        encode(loop, out)
        manifest.append(f"{s['id']} {len(loop)}")
        print(f"{s['id']:<12} {len(loop) / RATE:5.1f} s  seam {seam_score(loop):4.2f}  peak {20 * np.log10(np.abs(loop).max()):6.1f} dBFS  {out.stat().st_size / 1024:6.0f} KB")
    nl = chr(10)
    (OUT / "loops.txt").write_text(nl.join(manifest) + nl, encoding="utf-8")


if __name__ == "__main__":
    main()
