"""Compare Vosk models on REAL Indian-English speech (Svarah, AI4Bharat; public mirror).

Ground truth is the dataset's human transcript, not anything we wrote. Each clip is tested clean and
through a phone-call simulation (300-3400 Hz band, 8 kHz, plus noise), streamed in 100 ms chunks as the app does.
"""
import io, json, re, sys, urllib.request, urllib.parse
from pathlib import Path
import numpy as np, soundfile as sf
from scipy.signal import butter, sosfilt, resample_poly
from vosk import KaldiRecognizer, Model, SetLogLevel

HERE = Path(__file__).parent
CLIPS = HERE / "svarah"; CLIPS.mkdir(exist_ok=True)
DS = "amrithanandini/svarah"

def fetch(n_pages=6, page=25, step=260):
    mp = HERE / "svarah_meta.json"
    if mp.exists():
        return json.loads(mp.read_text(encoding="utf-8"))
    import time
    def get(off, length):
        url = "https://datasets-server.huggingface.co/rows?" + urllib.parse.urlencode(
            dict(dataset=DS, config="default", split="test", offset=off, length=length))
        for attempt in range(4):
            try:
                return json.load(urllib.request.urlopen(url, timeout=90))
            except Exception as e:
                print("retry", off, e, flush=True); time.sleep(3)
        return {"rows": []}
    total = get(0, 1).get("num_rows_total", 1000)
    step = max(page, (total - page) // n_pages)
    meta = []
    for i in range(n_pages):
        off = i * step
        rows = get(off, page)["rows"]
        for r in rows:
            idx = r["row_idx"]; p = CLIPS / f"{idx}.wav"
            if not p.exists():
                p.write_bytes(urllib.request.urlopen(r["row"]["audio"][0]["src"], timeout=60).read())
            meta.append({"id": idx, "text": r["row"]["sentence"], "wav": str(p)})
    (HERE / "svarah_meta.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
    return meta

def load16k(path):
    x, sr = sf.read(path, dtype="float32", always_2d=True); x = x.mean(axis=1)
    if sr != 16000: x = resample_poly(x, 16000, sr)
    return x.astype(np.float32)

def phone(x, rng, snr_db=15, gain=1.0):
    sos = butter(4, [300, 3400], btype="band", fs=16000, output="sos")
    y = sosfilt(sos, x)
    y = resample_poly(resample_poly(y, 1, 2), 2, 1)           # 8 kHz and back
    p = np.mean(y ** 2) + 1e-12
    y = y + rng.normal(0, np.sqrt(p / 10 ** (snr_db / 10)), len(y))
    return (y * gain).astype(np.float32)

NUM = {"0":"zero","1":"one","2":"two","3":"three","4":"four","5":"five","6":"six","7":"seven","8":"eight","9":"nine"}
def words(s):
    s = s.lower().replace("-", " ")
    s = re.sub(r"[^a-z0-9' ]+", " ", s)
    return [w for w in s.split() if w]

def wer_counts(ref, hyp):
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1], len(ref)

def transcribe(model, x, agc=False):
    if agc:
        x = app_gain(x)
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
    rec = KaldiRecognizer(model, 16000); out = []
    for i in range(0, len(pcm), 3200):
        if rec.AcceptWaveform(pcm[i:i + 3200]):
            t = json.loads(rec.Result()).get("text", "").strip()
            if t: out.append(t)
    t = json.loads(rec.FinalResult()).get("text", "").strip()
    if t: out.append(t)
    return " ".join(out)

def app_gain(x, target=0.25, max_gain=12.0, chunk=1600):
    """Mirror of the app's streaming level control (GainControl.kt): per-100ms chunk, slow-follow peak."""
    y = np.empty_like(x); env = 0.0; g = 1.0
    for i in range(0, len(x), chunk):
        c = x[i:i + chunk]; peak = float(np.max(np.abs(c))) if len(c) else 0.0
        env = max(peak, env * 0.97)
        want = min(max_gain, target / env) if env > 0.004 else g
        want = max(1.0, want)
        g = g + (want - g) * (0.5 if want < g else 0.15)
        y[i:i + chunk] = np.clip(c * g, -1, 1)
    return y

def main():
    SetLogLevel(-1)
    meta = fetch()
    # skip clips whose reference has digits (written "11th" vs spoken "eleventh" would count as errors for both models)
    meta = [m for m in meta if not re.search(r"\d", m["text"])]
    models = {"en-us small 0.15 (shipped)": sys.argv[1], "en-in small 0.4": sys.argv[2]}
    rng = np.random.default_rng(7)
    conds = {
        "clean": lambda x: x,
        "phone band + noise": lambda x: phone(x, rng),
        "phone, quiet (-20 dB)": lambda x: phone(x, rng, gain=0.1),
    }
    audio = {m["id"]: load16k(m["wav"]) for m in meta}
    degraded = {c: {i: f(a) for i, a in audio.items()} for c, f in conds.items()}
    print(f"{len(meta)} real clips, {sum(len(a) for a in audio.values())/16000/60:.1f} min")
    res = {}
    for name, path in models.items():
        model = Model(path)
        for c in conds:
            for agc in (False, True):
                if agc and c != "phone, quiet (-20 dB)" and c != "phone band + noise": continue
                e = n = 0; samples = []
                for m in meta:
                    hyp = transcribe(model, degraded[c][m["id"]], agc)
                    a, b = wer_counts(words(m["text"]), words(hyp)); e += a; n += b
                    if len(samples) < 3: samples.append((m["text"], hyp))
                key = f"{name} | {c}{' + level control' if agc else ''}"
                res[key] = round(100 * e / n, 1)
                print(f"{key:75s} WER {100*e/n:5.1f}%  ({n} words)", flush=True)
                if c == "phone band + noise" and not agc:
                    for r, h in samples: print("     ref:", r[:110], "\n     hyp:", h[:110])
    (HERE / "bench_result.json").write_text(json.dumps(res, indent=1))

main()
