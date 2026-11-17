"""Stronger on-device recognisers vs the shipped Vosk model, on REAL Indian-English speech (Svarah).

Conditions include a simulation of the caller's voice as the phone hears it on speaker:
band-limited, 26 dB quieter, room echo and noise. Streamed in 100 ms chunks. Also reports speed.
"""
import json, re, sys, time
from pathlib import Path
import numpy as np, soundfile as sf
from scipy.signal import butter, sosfilt, resample_poly, fftconvolve
import sherpa_onnx
from vosk import KaldiRecognizer, Model, SetLogLevel

HERE = Path(__file__).parent
N = int(sys.argv[1]) if len(sys.argv) > 1 else 60

def load16k(path):
    x, sr = sf.read(path, dtype="float32", always_2d=True); x = x.mean(axis=1)
    if sr != 16000: x = resample_poly(x, 16000, sr)
    return x.astype(np.float32)

def band(x):
    y = sosfilt(butter(4, [300, 3400], btype="band", fs=16000, output="sos"), x)
    return resample_poly(resample_poly(y, 1, 2), 2, 1)

def phone(x, rng, snr_db=15):
    y = band(x); p = np.mean(y ** 2) + 1e-12
    return (y + rng.normal(0, np.sqrt(p / 10 ** (snr_db / 10)), len(y))).astype(np.float32)

def far_end(x, rng, snr_db=8, level_db=-26):
    """Caller's voice out of a small loudspeaker, across a room, into the mic."""
    y = band(x)
    t = np.arange(int(0.25 * 16000)) / 16000
    ir = rng.normal(0, 1, len(t)) * np.exp(-t / 0.06); ir[0] = 4.0; ir /= np.sqrt(np.sum(ir ** 2))
    y = fftconvolve(y, ir)[:len(y)]
    p = np.mean(y ** 2) + 1e-12
    y = y + rng.normal(0, np.sqrt(p / 10 ** (snr_db / 10)), len(y))
    return (y * 10 ** (level_db / 20)).astype(np.float32)

def app_gain(x, target=0.25, max_gain=12.0, chunk=1600):
    y = np.empty_like(x); env = 0.0; g = 1.0
    for i in range(0, len(x), chunk):
        c = x[i:i + chunk]; peak = float(np.max(np.abs(c))) if len(c) else 0.0
        env = max(peak, env * 0.97)
        want = min(max_gain, target / env) if env > 0.004 else g
        want = max(1.0, want)
        g = g + (want - g) * (0.5 if want < g else 0.15)
        y[i:i + chunk] = np.clip(c * g, -1, 1)
    return y

def words(s):
    s = s.lower().replace("-", " ")
    return [w for w in re.sub(r"[^a-z0-9' ]+", " ", s).split() if w]

def errs(ref, hyp):
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1): cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1]

class Vosk:
    def __init__(self, path): self.m = Model(path)
    def run(self, x):
        pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
        rec = KaldiRecognizer(self.m, 16000); out = []
        for i in range(0, len(pcm), 3200):
            if rec.AcceptWaveform(pcm[i:i + 3200]):
                out.append(json.loads(rec.Result()).get("text", ""))
        out.append(json.loads(rec.FinalResult()).get("text", ""))
        return " ".join(out)

class Sherpa:
    def __init__(self, rec): self.r = rec
    def run(self, x):
        s = self.r.create_stream()
        for i in range(0, len(x), 1600):
            s.accept_waveform(16000, x[i:i + 1600])
            while self.r.is_ready(s): self.r.decode_stream(s)
        s.accept_waveform(16000, np.zeros(int(0.8 * 16000), dtype=np.float32)); s.input_finished()
        while self.r.is_ready(s): self.r.decode_stream(s)
        res = self.r.get_result(s)
        return res if isinstance(res, str) else res.text

def transducer(d, enc, dec, joi):
    d = HERE / d
    return Sherpa(sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(d / "tokens.txt"), encoder=str(d / enc), decoder=str(d / dec), joiner=str(d / joi),
        num_threads=2, sample_rate=16000, feature_dim=80, decoding_method="greedy_search"))

def main():
    SetLogLevel(-1)
    meta = [m for m in json.loads((HERE / "svarah_meta.json").read_text(encoding="utf-8")) if not re.search(r"\d", m["text"])]
    meta = meta[::max(1, len(meta) // N)][:N]
    rng = np.random.default_rng(7)
    audio = {m["id"]: load16k(m["wav"]) for m in meta}
    conds = {
        "clean": {i: a for i, a in audio.items()},
        "phone band + noise": {i: phone(a, rng) for i, a in audio.items()},
        "caller on speaker (sim) + level control": {i: app_gain(far_end(a, rng)) for i, a in audio.items()},
    }
    mins = sum(len(a) for a in audio.values()) / 16000 / 60
    print(f"{len(meta)} real clips, {mins:.1f} min", flush=True)
    models = {
        "vosk small en-in 0.4 (shipped)": lambda: Vosk(str(HERE / "vosk-model-small-en-in-0.4")),
        "zipformer kroko 2025-08 (70 MB)": lambda: transducer("sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06", "encoder.onnx", "decoder.onnx", "joiner.onnx"),
        "zipformer 20M int8 (35 MB)": lambda: transducer("sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx"),
        "zipformer 2023-06-26 int8 (71 MB)": lambda: transducer("sherpa-onnx-streaming-zipformer-en-2023-06-26", "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
        "nemo fast-conformer ctc 480ms int8 (132 MB)": lambda: Sherpa(sherpa_onnx.OnlineRecognizer.from_nemo_ctc(
            tokens=str(HERE / "sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-480ms-int8/tokens.txt"),
            model=str(HERE / "sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-480ms-int8/model.int8.onnx"), num_threads=2)),
    }
    res = {}
    for name, make in models.items():
        try:
            m = make()
        except Exception as e:
            print(name, "LOAD FAILED", repr(e)[:200], flush=True); continue
        for c, clips in conds.items():
            e = n = 0; t0 = time.time(); sample = None
            for mt in meta:
                hyp = m.run(clips[mt["id"]])
                ref = words(mt["text"]); e += errs(ref, words(hyp)); n += len(ref)
                if sample is None: sample = (mt["text"], hyp)
            rtf = (time.time() - t0) / (mins * 60)
            res[f"{name} | {c}"] = {"wer": round(100 * e / n, 1), "rtf": round(rtf, 3)}
            print(f"{name:46s} | {c:40s} WER {100*e/n:5.1f}%  speed {rtf:.2f}x real time", flush=True)
            if c.startswith("caller"): print("     ref:", sample[0][:100], "\n     hyp:", sample[1][:100], flush=True)
    (HERE / "bench2_result.json").write_text(json.dumps(res, indent=1))

main()
