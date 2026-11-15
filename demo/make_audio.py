"""Turn scripted two-person calls into 16 kHz mono WAV files with text-to-speech.

    python demo/make_audio.py demo/scripts/demo_calls.json app/src/main/assets/demo
    python demo/make_audio.py <script.json> <out_dir> --phone     # add telephone band-limiting + light noise

Needs: pip install edge-tts, and ffmpeg on PATH. Uses Microsoft Edge's online neural voices
(free, no key). Every clip is synthetic: no real person's voice is used anywhere.
"""
import asyncio
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import edge_tts

GAP_SECONDS = 0.45


async def speak(text: str, voice: str, out: Path) -> None:
    for attempt in range(4):
        try:
            await edge_tts.Communicate(text, voice).save(str(out))
            if out.stat().st_size > 1000:
                return
        except Exception as e:  # network hiccup: retry
            last = e
        await asyncio.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"TTS failed for: {text[:40]}... ({last})")


def ffmpeg(*args: str) -> None:
    r = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)


async def build(call: dict, voices: dict, out_dir: Path, phone: bool) -> Path:
    out = out_dir / call["file"]
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        parts = []
        for i, (who, text) in enumerate(call["lines"]):
            mp3 = tmp / f"{i:02d}.mp3"
            await speak(text, voices[who], mp3)
            wav = tmp / f"{i:02d}.wav"
            # each line -> 16k mono, with a pause after it
            ffmpeg("-i", str(mp3), "-af", f"apad=pad_dur={GAP_SECONDS}", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav))
            parts.append(wav)
        listing = tmp / "list.txt"
        listing.write_text("".join(f"file '{p.as_posix()}'\n" for p in parts))
        joined = tmp / "joined.wav"
        ffmpeg("-f", "concat", "-safe", "0", "-i", str(listing), "-c:a", "pcm_s16le", str(joined))
        if phone:
            # Roughly what a call on loudspeaker sounds like: narrow band, a little room noise.
            ffmpeg("-i", str(joined), "-f", "lavfi", "-i", "anoisesrc=color=pink:amplitude=0.01",
                   "-filter_complex", "[0:a]highpass=f=300,lowpass=f=3400[v];[v][1:a]amix=inputs=2:duration=first:normalize=0[o]",
                   "-map", "[o]", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(out))
        else:
            ffmpeg("-i", str(joined), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "-map_metadata", "-1", str(out))
    return out


async def main() -> None:
    script, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    phone = "--phone" in sys.argv
    spec = json.loads(script.read_text(encoding="utf-8"))
    out_dir.mkdir(parents=True, exist_ok=True)
    for call in spec["calls"]:
        out = await build(call, spec["voices"], out_dir, phone)
        seconds = (out.stat().st_size - 44) / 32000
        print(f"{out.name}: {seconds:.1f}s")


if __name__ == "__main__":
    asyncio.run(main())
