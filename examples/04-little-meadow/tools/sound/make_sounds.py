"""
Synthesises every sound effect and music loop of Little Meadow (nothing sampled, all original).

    cd tools/sound && uv run make_sounds.py

Output: app/src/main/assets/sfx/*.ogg (SoundPool) and assets/music/*.ogg (seamless loops).
Design rules: soft attacks, no harsh highs, quiet; the "not quite" sound is two gentle notes, never a buzzer.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import soundfile  # Vorbis encoder (libsndfile); Homebrew's ffmpeg 9 no longer ships libvorbis

ROOT = Path(__file__).resolve().parent.parent.parent
SFX = ROOT / "app/src/main/assets/sfx"
MUSIC = ROOT / "app/src/main/assets/music"
SR = 44100
rng = np.random.default_rng(7)


def t_axis(sec):
    return np.arange(int(SR * sec)) / SR


def midi_hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def env_exp(n, decay):
    return np.exp(-np.arange(n) / SR / decay)


def attack(x, ms=4):
    n = min(len(x), int(SR * ms / 1000))
    x[:n] *= np.linspace(0, 1, n) ** 2
    return x


def fft_lowpass(x, cutoff, width=400):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    X *= 1 / (1 + np.exp((f - cutoff) / width))
    return np.fft.irfft(X, len(x))


def fft_band(x, lo, hi):
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    X *= (1 / (1 + np.exp((lo - f) / 200))) * (1 / (1 + np.exp((f - hi) / 400)))
    return np.fft.irfft(X, len(x))


# ---------------------------------------------------------------- instruments (modal synthesis)

def marimba(hz, sec=1.2, vel=1.0):
    t = t_axis(sec)
    x = (np.sin(2 * np.pi * hz * t) * np.exp(-t / 0.45)
         + 0.25 * np.sin(2 * np.pi * hz * 4.0 * t) * np.exp(-t / 0.08)
         + 0.06 * np.sin(2 * np.pi * hz * 9.9 * t) * np.exp(-t / 0.02))
    return attack(x * vel, 2)


def bell(hz, sec=2.0, vel=1.0):
    t = t_axis(sec)
    parts = [(1.0, 1.0, 1.2), (2.0, 0.35, 0.8), (2.76, 0.22, 0.5), (5.4, 0.08, 0.25), (8.93, 0.03, 0.12)]
    x = sum(a * np.sin(2 * np.pi * hz * r * t) * np.exp(-t / d) for r, a, d in parts)
    return attack(x * vel * 0.7, 3)


def kalimba(hz, sec=1.6, vel=1.0):
    t = t_axis(sec)
    x = (np.sin(2 * np.pi * hz * t) * np.exp(-t / 0.7)
         + 0.18 * np.sin(2 * np.pi * hz * 6.3 * t) * np.exp(-t / 0.05)
         + 0.08 * np.sin(2 * np.pi * hz * 2.0 * t) * np.exp(-t / 0.3))
    return attack(x * vel, 3)


def musicbox(hz, sec=1.8, vel=1.0):
    t = t_axis(sec)
    x = (np.sin(2 * np.pi * hz * t) * np.exp(-t / 0.9)
         + 0.3 * np.sin(2 * np.pi * hz * 3.0 * t) * np.exp(-t / 0.35)
         + 0.12 * np.sin(2 * np.pi * hz * 5.95 * t) * np.exp(-t / 0.12))
    return attack(x * vel * 0.8, 2)


def pad(midis, sec, vel=1.0):
    """Warm chord: detuned sines + a little second harmonic, slow swell and release."""
    t = t_axis(sec)
    x = np.zeros_like(t)
    for m in midis:
        hz = midi_hz(m)
        for det in (-0.25, 0.0, 0.25):
            f = hz * 2 ** (det / 1200 * 8)
            x += np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) + 0.15 * np.sin(4 * np.pi * f * t)
    x /= len(midis) * 3
    a = np.clip(t / 1.2, 0, 1) ** 2
    r = np.clip((sec - t) / 1.5, 0, 1) ** 2
    trem = 1 + 0.05 * np.sin(2 * np.pi * 0.3 * t)
    return x * a * r * trem * vel


INSTRUMENTS = {"marimba": marimba, "bell": bell, "kalimba": kalimba, "musicbox": musicbox}


# ---------------------------------------------------------------- effects

def mix(*parts):
    n = max(len(p) for _, p in parts)
    out = np.zeros(n)
    for off, p in parts:
        o = int(off * SR)
        out[o:o + len(p)] += p[: max(0, n - o)]
    return out


def sweep(f0, f1, sec, decay):
    t = t_axis(sec)
    f = f0 * (f1 / f0) ** (t / sec)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-t / decay)


def noise(sec):
    return rng.standard_normal(int(SR * sec))


def effects():
    out = {}
    tock = mix((0, marimba(midi_hz(79), 0.25) * 0.5), (0, marimba(midi_hz(91), 0.12) * 0.15))
    out["tap"] = tock * env_exp(len(tock), 0.05) * 0.8
    out["pop"] = mix((0, attack(sweep(320, 1100, 0.09, 0.05)) * 0.8), (0, fft_band(noise(0.09), 1500, 4000) * env_exp(int(SR * 0.09), 0.01) * 0.08))
    out["good"] = mix((0, marimba(midi_hz(72))), (0.11, marimba(midi_hz(76))), (0.22, marimba(midi_hz(79), 1.6))) * 0.55
    out["sparkle"] = mix(*[(i * 0.07, bell(midi_hz(m), 1.4, 0.6)) for i, m in enumerate([79, 84, 88, 91, 96])]) * 0.5
    out["place"] = attack(sweep(240, 120, 0.22, 0.07), 3) * 0.9
    out["hmm"] = mix((0, marimba(midi_hz(64), 0.8, 0.6)), (0.16, marimba(midi_hz(60), 1.0, 0.6))) * 0.45
    w = fft_band(noise(0.5), 400, 2500)
    out["whoosh"] = w * np.sin(np.linspace(0, np.pi, len(w))) ** 2 * 0.25
    out["pick"] = attack(sweep(520, 860, 0.08, 0.05)) * 0.6
    out["drum"] = mix((0, attack(sweep(160, 85, 0.45, 0.16), 2)), (0, fft_lowpass(noise(0.45), 900) * env_exp(int(SR * 0.45), 0.03) * 0.3))
    s = fft_band(noise(0.18), 4000, 9000)
    out["shaker"] = s * np.sin(np.linspace(0, np.pi, len(s))) ** 3 * 0.35
    c = fft_band(noise(0.16), 800, 3500)
    out["clap"] = attack(c * env_exp(len(c), 0.035), 1) * 0.6
    out["ding"] = bell(midi_hz(81), 1.5) * 0.5
    sp = fft_band(noise(0.9), 300, 2500) * np.exp(-t_axis(0.9) / 0.35)
    bubbles = mix(*[(rng.uniform(0, 0.6), attack(sweep(rng.uniform(500, 900), rng.uniform(1200, 2000), 0.05, 0.03)) * 0.25) for _ in range(9)])
    out["splash"] = attack(mix((0, sp * 0.5), (0, bubbles)), 15) * 0.8
    b = fft_band(noise(0.7), 2500, 6000)
    out["brush"] = b * (0.55 + 0.45 * np.sin(2 * np.pi * 7 * t_axis(0.7))) * np.sin(np.linspace(0, np.pi, len(b))) * 0.22
    t = t_axis(1.0)
    f = 520 * (0.5 ** (t / 1.0)) * (1 + 0.01 * np.sin(2 * np.pi * 5 * t))
    y = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.sin(np.pi * t) ** 1.5
    out["yawn"] = y * 0.35
    return out


# ---------------------------------------------------------------- music

def place_note(buf, start, sound, pan=0.0):
    """Adds a mono sound into a stereo loop buffer, wrapping around the end so the loop is seamless."""
    n = len(sound)
    o = int(start * SR) % len(buf)
    l, r = np.cos((pan + 1) * np.pi / 4), np.sin((pan + 1) * np.pi / 4)
    first = min(n, len(buf) - o)
    buf[o:o + first, 0] += sound[:first] * l
    buf[o:o + first, 1] += sound[:first] * r
    if first < n:
        rest = n - first
        buf[:rest, 0] += sound[first:] * l
        buf[:rest, 1] += sound[first:] * r


CHORDS = {  # midi notes
    "C": [48, 55, 60, 64], "Am": [45, 52, 57, 60], "F": [41, 48, 53, 57], "G": [43, 50, 55, 59],
    "Dm": [50, 53, 57, 62], "Bb": [46, 53, 58, 62], "Em": [52, 55, 59, 64],
    "Cmaj7": [48, 55, 59, 64], "Am7": [45, 52, 55, 60], "Fmaj7": [41, 48, 52, 57], "G6": [43, 50, 52, 59],
}


def track_meadow():
    bpm, bars = 72, 16
    beat = 60 / bpm
    buf = np.zeros((int(SR * beat * 4 * bars), 2))
    prog = ["C", "Am", "F", "G"] * 2
    penta = [60, 62, 64, 67, 69, 72, 74, 76]
    for b in range(bars):
        ch = CHORDS[prog[(b // 2) % len(prog)]]
        if b % 2 == 0:
            place_note(buf, b * 4 * beat, pad(ch, beat * 8 + 1.5, 0.35))
        # kalimba: a lazy arpeggio on the chord tones, two octaves up
        pattern = [0, 2, 1, 3, 2, 1] if b % 4 < 2 else [0, 1, 2, 3, 2, 1]
        for i, k in enumerate(pattern):
            m = ch[k % len(ch)] + 12 + (12 if k >= 2 else 0)
            m = min(m, 84)
            place_note(buf, (b * 4 + i * 0.66) * beat, kalimba(midi_hz(m), 1.4, 0.45), pan=0.35 * np.sin(i))
        # a few bell notes like birds far away
        if b % 4 == 3:
            m = penta[rng.integers(3, len(penta))] + 12
            place_note(buf, (b * 4 + 2.5) * beat, bell(midi_hz(m), 1.8, 0.18), pan=-0.5)
        place_note(buf, b * 4 * beat, marimba(midi_hz(ch[0] - 12 + 24), 1.2, 0.25))
    return buf


def track_studio():
    bpm, bars = 66, 16
    beat = 60 / bpm
    buf = np.zeros((int(SR * beat * 4 * bars), 2))
    prog = ["F", "Dm", "Bb", "C"]
    for b in range(bars):
        ch = CHORDS[prog[(b // 2) % 4]]
        if b % 2 == 0:
            place_note(buf, b * 4 * beat, pad(ch, beat * 8 + 1.5, 0.3))
        for i, beat_pos in enumerate([0, 1.5, 3]):
            m = ch[(i + b) % 4] + 24
            place_note(buf, (b * 4 + beat_pos) * beat, marimba(midi_hz(min(m, 84)), 1.2, 0.35), pan=0.3 * (i - 1))
    return buf


TWINKLE = "C C G G A A G- F F E E D D C- G G F F E E D- G G F F E E D- C C G G A A G- F F E E D D C-"


def melody_notes(text, base=72):
    names = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
    out = []
    for tok in text.split():
        long = tok.endswith("-")
        out.append((base + names[tok.rstrip("-")], 2 if long else 1))
    return out


def track_night():
    bpm = 54
    beat = 60 / bpm
    notes = melody_notes(TWINKLE)
    total = sum(d for _, d in notes)
    buf = np.zeros((int(SR * beat * total), 2))
    # gentle chords every two beats under the tune
    harmony = ["C", "C", "F", "C", "F", "C", "G", "C", "C", "F", "C", "G", "C", "F", "C", "G",
               "C", "C", "F", "C", "F", "C", "G", "C"]
    for i in range(int(total // 2)):
        ch = CHORDS[harmony[i % len(harmony)]]
        place_note(buf, i * 2 * beat, pad(ch, beat * 2 + 2.0, 0.22))
        place_note(buf, i * 2 * beat, musicbox(midi_hz(ch[0] + 12), 1.8, 0.2), pan=-0.3)
    t = 0
    for m, d in notes:
        place_note(buf, t * beat, musicbox(midi_hz(m), 2.2, 0.55), pan=0.2)
        t += d
    return buf


def track_calm():
    seconds = 8.0
    prog = ["Cmaj7", "Am7", "Fmaj7", "G6"]
    buf = np.zeros((int(SR * seconds * len(prog)), 2))
    for i, c in enumerate(prog):
        place_note(buf, i * seconds, pad(CHORDS[c], seconds + 3, 0.5))
        place_note(buf, i * seconds + 1, bell(midi_hz(CHORDS[c][-1] + 12), 3.0, 0.08), pan=0.4)
    return buf


# ---------------------------------------------------------------- output

def write_ogg(path: Path, x: np.ndarray, quality=4, peak=10 ** (-3 / 20)):
    """peak=None keeps the level as given (effects are pre-scaled by LEVELS)."""
    x = np.asarray(x, dtype=np.float64)
    if peak is not None:
        x = x / (np.max(np.abs(x)) or 1.0) * peak
    write_vorbis(path, np.clip(x, -1, 1).astype(np.float32), SR, quality)


def write_vorbis(path: Path, x: np.ndarray, rate: int, quality: float):
    """In small blocks: libsndfile's Vorbis writer crashes on one big write."""
    channels = 1 if x.ndim == 1 else x.shape[1]
    with soundfile.SoundFile(str(path), "w", rate, channels, format="OGG", subtype="VORBIS",
                             compression_level=1 - quality / 10) as f:
        for i in range(0, len(x), 4096):
            f.write(x[i:i + 4096])


LEVELS = {  # relative loudness of each effect (applied after peak normalisation)
    "tap": 0.45, "pop": 0.55, "good": 0.7, "sparkle": 0.6, "place": 0.6, "hmm": 0.45, "whoosh": 0.35,
    "pick": 0.4, "drum": 0.8, "shaker": 0.5, "clap": 0.6, "ding": 0.6, "splash": 0.5, "brush": 0.45, "yawn": 0.5,
}


def main():
    SFX.mkdir(parents=True, exist_ok=True)
    MUSIC.mkdir(parents=True, exist_ok=True)
    for name, x in effects().items():
        x = x / (np.max(np.abs(x)) or 1) * LEVELS.get(name, 0.6) * 0.9
        write_ogg(SFX / f"{name}.ogg", np.clip(x, -1, 1), quality=3, peak=None)
    for inst, fn in INSTRUMENTS.items():
        bases = [66, 72, 78, 84, 90] if inst == "musicbox" else [60, 66, 72, 78, 84]
        for b in bases:
            write_ogg(SFX / f"{inst}_{b}.ogg", fn(midi_hz(b)) * 0.8, quality=3)
    for name, fn in [("meadow", track_meadow), ("studio", track_studio), ("night", track_night), ("calm", track_calm)]:
        buf = fn()
        write_ogg(MUSIC / f"{name}.ogg", buf, quality=3)
    for d in (SFX, MUSIC):
        files = sorted(d.glob("*.ogg"))
        print(f"{d.name}: {len(files)} files, {sum(f.stat().st_size for f in files) // 1024} KB")


if __name__ == "__main__":
    main()
