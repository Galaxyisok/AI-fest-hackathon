"""Run the SAME speech model the app ships (sherpa-onnx, streaming Zipformer "Kroko" English) over WAV files on a laptop.

    python eval/stt_transcribe.py <script.json> <wav_dir> <out.jsonl>

For each call in the script it writes the recognised text (one line per recognised utterance,
normalised exactly as the app does before redaction) and the word error rate against the script.
This is how we measure what speech recognition costs the detector.

Needs: pip install sherpa-onnx numpy. Model: app/src/main/assets/model-kroko (fetched by the Gradle build).
"""
import json
import re
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parent.parent
MODEL_DIR = ROOT / "app" / "src" / "main" / "assets" / "model-kroko"


def Model(path: str):
    d = Path(path)
    # Same endpoint rules as SpeechEngine.kt.
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(d / "tokens.txt"), encoder=str(d / "encoder.onnx"), decoder=str(d / "decoder.onnx"),
        joiner=str(d / "joiner.onnx"), num_threads=2, sample_rate=16000, feature_dim=80,
        decoding_method="greedy_search", enable_endpoint_detection=True,
        rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=20)


def SetLogLevel(_):
    pass


def normalise(text: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9' ]+", " ", text.lower())).strip()


def transcribe(model, wav_path: Path) -> list[str]:
    with wave.open(str(wav_path), "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, "need 16 kHz mono 16-bit"
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768
    def text(s):
        r = model.get_result(s)
        return normalise(r if isinstance(r, str) else r.text)
    s = model.create_stream()
    lines = []
    for i in range(0, len(x), 1600):  # 100 ms, as in the app
        s.accept_waveform(16000, x[i:i + 1600])
        while model.is_ready(s):
            model.decode_stream(s)
        if model.is_endpoint(s):
            t = text(s)
            if t:
                lines.append(t)
            model.reset(s)
    s.accept_waveform(16000, np.zeros(12800, dtype=np.float32))
    s.input_finished()
    while model.is_ready(s):
        model.decode_stream(s)
    t = text(s)
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
               "category": call.get("category"), "prior": call.get("prior", {}), "wer": round(wer(ref, hyp), 3), "stt_lines": lines}
        rows.append(row)
        print(f"{call['file']}: WER {row['wer']:.0%}, {len(hyp)} words")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    print(f"mean WER {sum(r['wer'] for r in rows) / len(rows):.0%} over {len(rows)} clips -> {out}")


if __name__ == "__main__":
    main()
