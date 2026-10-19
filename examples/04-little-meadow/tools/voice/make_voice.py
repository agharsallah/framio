#!/usr/bin/env python3
"""
Records the narration. The frame has no text-to-speech engine, so every sentence the friends say is
rendered on the Mac with `say`, cleaned up with ffmpeg and shipped as a small OGG file.

    cd tools/voice && uv run make_voice.py          # all languages, only lines whose text changed
    cd tools/voice && uv run make_voice.py en --force

Sources:  voice/<lang>/*.tsv    one line per clip:  id <TAB> text      (# comments, blank lines ok)
Output:   app/src/main/assets/voice/<lang>/<id>.ogg  +  manifest.json (id → {"h": text hash, "ms": duration, "t": text})

The app reads manifest.json to know each clip's length (for the talking animation), to show what is
said as a caption ("t"), and to fall back to English when a translation is missing. Ids must be
unique per language. Older manifests without "t" still load; the text is filled in on the next run
without re-recording anything.
"""
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import soundfile  # Vorbis encoder (libsndfile); Homebrew's ffmpeg 9 no longer ships libvorbis

ROOT = Path(__file__).resolve().parent.parent.parent
SRC = ROOT / "voice"
OUT = ROOT / "app/src/main/assets/voice"

# Warm, unhurried, toddler-directed speech: slower than the default 175 wpm.
VOICES = {
    "en": {"voice": "Samantha", "rate": 150},
    "de": {"voice": "Anna", "rate": 145},
}
ID = re.compile(r"^[a-z0-9_]+$")


def load(lang):
    lines = {}
    for tsv in sorted((SRC / lang).glob("*.tsv")):
        for n, raw in enumerate(tsv.read_text(encoding="utf-8").splitlines(), 1):
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            if "\t" not in raw:
                sys.exit(f"{tsv.name}:{n}: expected 'id<TAB>text'")
            cid, text = (s.strip() for s in raw.split("\t", 1))
            if not ID.match(cid):
                sys.exit(f"{tsv.name}:{n}: bad id {cid!r} (a-z 0-9 _)")
            if cid in lines:
                sys.exit(f"{tsv.name}:{n}: duplicate id {cid!r}")
            lines[cid] = text
    return lines


def render(lang, cid, text, cfg, dest):
    with tempfile.TemporaryDirectory() as tmp:
        aiff = Path(tmp) / "a.aiff"
        subprocess.run(["say", "-v", cfg["voice"], "-r", str(cfg["rate"]), "-o", str(aiff), "--", text], check=True)
        # trim silence at both ends, gentle loudness normalisation, a short tail so nothing is clipped
        af = ("silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.03,"
              "areverse,silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.05,areverse,"
              "loudnorm=I=-18:TP=-2:LRA=11,apad=pad_dur=0.08")
        wav = Path(tmp) / "a.wav"
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(aiff), "-af", af, "-ac", "1", "-ar", "22050", str(wav)], check=True)
        data, rate = soundfile.read(str(wav), dtype="float32")
        with soundfile.SoundFile(str(dest), "w", rate, 1, format="OGG", subtype="VORBIS") as f:
            for i in range(0, len(data), 4096):  # small blocks: one big write can crash libsndfile's Vorbis encoder
                f.write(data[i:i + 4096])
    return int(len(data) * 1000 / rate)


def build(lang, force):
    cfg = VOICES[lang]
    lines = load(lang)
    out = OUT / lang
    out.mkdir(parents=True, exist_ok=True)
    manifest_path = out / "manifest.json"
    old = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() and not force else {}
    todo, manifest = [], {}
    for cid, text in lines.items():
        h = hashlib.sha1(f"{cfg['voice']}|{cfg['rate']}|{text}".encode()).hexdigest()[:10]
        prev = old.get(cid)
        if prev and prev["h"] == h and (out / f"{cid}.ogg").exists():
            manifest[cid] = {"h": h, "ms": prev["ms"], "t": text}  # the hash covers the text: same words
        else:
            todo.append((cid, text, h))
    with ThreadPoolExecutor(max_workers=6) as pool:
        for (cid, text, h), ms in zip(todo, pool.map(lambda t: render(lang, t[0], t[1], cfg, out / f"{t[0]}.ogg"), todo)):
            manifest[cid] = {"h": h, "ms": ms, "t": text}
    for stale in out.glob("*.ogg"):
        if stale.stem not in lines:
            stale.unlink()
    missing = [cid for cid, m in manifest.items() if not m.get("t")]
    if missing:
        sys.exit(f"{lang}: clips without caption text: {', '.join(missing[:5])}")
    manifest_path.write_text(json.dumps(dict(sorted(manifest.items())), separators=(",", ":"), ensure_ascii=False), encoding="utf-8")
    size = sum(p.stat().st_size for p in out.glob("*.ogg"))
    print(f"{lang}: {len(lines)} clips ({len(todo)} new), {size // 1024} KB")


def main(argv):
    force = "--force" in argv
    langs = [a for a in argv if not a.startswith("--")] or list(VOICES)
    for lang in langs:
        if (SRC / lang).exists():
            build(lang, force)


if __name__ == "__main__":
    main(sys.argv[1:])
