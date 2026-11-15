"""Run the SAME speech model the app ships (Vosk small en-US 0.15) over WAV files on a laptop.

    python eval/stt_transcribe.py <script.json> <wav_dir> <out.jsonl>

For each call in the script it writes the recognised text (one line per recognised utterance,
exactly what the app would send to the judge before redaction) and the word error rate against
the script. This is how we measure what speech recognition costs the detector.

Needs: pip install vosk. Model: app/src/main/assets/model-en-us (fetched by the Gradle build).
"""
import json
import re
import sys
import wave
from pathlib import Path

from vosk import KaldiRecognizer, Model, SetLogLevel

ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = ROOT / "app" / "src" / "main" / "assets" / "model-en-us"


def transcribe(model: Model, wav_path: Path) -> list[str]:
    with wave.open(str(wav_path), "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, "need 16 kHz mono 16-bit"
        rec = KaldiRecognizer(model, 16000)
        lines = []
        while True:
            data = w.readframes(1600)  # 100 ms, as in the app
            if not data:
                break
            if rec.AcceptWaveform(data):
                t = json.loads(rec.Result()).get("text", "").strip()
                if t:
                    lines.append(t)
        t = json.loads(rec.FinalResult()).get("text", "").strip()
        if t:
            lines.append(t)
    return lines


def words(s: str) -> list[str]:
    return re.sub(r"[^a-z0-9' ]+", " ", s.lower()).split()


def wer(ref: list[str], hyp: list[str]) -> float:
    # word-level edit distance / reference length
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1] / max(1, len(ref))


def main() -> None:
    script, wav_dir, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    SetLogLevel(-1)
    model = Model(str(MODEL_DIR))
    spec = json.loads(script.read_text(encoding="utf-8"))
    rows = []
    for call in spec["calls"]:
        lines = transcribe(model, wav_dir / call["file"])
        ref = words(" ".join(text for _, text in call["lines"]))
        hyp = words(" ".join(lines))
        row = {"id": call.get("id", call["file"]), "file": call["file"], "label": call["label"],
               "category": call.get("category"), "wer": round(wer(ref, hyp), 3), "stt_lines": lines}
        rows.append(row)
        print(f"{call['file']}: WER {row['wer']:.0%}, {len(hyp)} words")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    print(f"mean WER {sum(r['wer'] for r in rows) / len(rows):.0%} over {len(rows)} clips -> {out}")


if __name__ == "__main__":
    main()
