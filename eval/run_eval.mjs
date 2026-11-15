#!/usr/bin/env node
// Replays labelled calls through the same judge, redaction, cascade and score maths the app uses,
// one turn at a time, and reports what a user would have seen.
//
//   node proxy/local.mjs                                   (in another terminal; needs ANTHROPIC_API_KEY)
//   node eval/run_eval.mjs --set dev  --mode cascade
//   node eval/run_eval.mjs --set test --mode haiku|sonnet|cascade
//   node eval/run_eval.mjs --set test --mode cascade --dry-run        (cost estimate, no API calls)
//   node eval/run_eval.mjs --stt eval/.cache/audio_stt.jsonl --mode cascade   (speech-recognised text)
//
// RULE: tune prompts and thresholds on --set dev only. --set test is reported, never tuned on.
import { readFileSync, writeFileSync, appendFileSync, existsSync, mkdirSync } from 'node:fs';
import { redact } from './lib/redactor.mjs';
import { ScoreFusion, RED, AMBER } from './lib/fusion.mjs';
import { CascadePolicy } from './lib/cascade.mjs';

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
  return acc;
}, []));
const MODE = args.mode || 'cascade';
const SET = args.stt ? 'audio' : (args.set || 'dev');
const URL_ = args.url || 'http://localhost:8787/api/judge';
const CONCURRENCY = Number(args.concurrency || 4);
const here = (p) => new URL(p, import.meta.url);

// Prices in USD per million tokens (Anthropic list prices, checked 2026-10). Used for the cost column only.
const PRICE = { 'claude-haiku-5-5': [0.10, 0.50], 'claude-sonnet-5-5': [2.00, 10.00] };
const WORDS_PER_SECOND = 2.5;   // ordinary speech, to turn "words so far" into "seconds into the call"
const MIN_WORDS = 8;            // same as GuardSession.MIN_WORDS

// One fixed profile for every call, so a difference between runs is a difference between models.
const PROFILE = {
  userType: 'student',
  threats: [
    { type: 'digital_arrest', level: 'high' }, { type: 'job_offer', level: 'high' },
    { type: 'courier_customs', level: 'medium' }, { type: 'kyc_bank', level: 'medium' }, { type: 'loan_app', level: 'low' },
  ],
};
const LEVEL = Object.fromEntries(PROFILE.threats.map((t) => [t.type, t.level]));

// What on-device speech recognition hands over: lower case, no punctuation, no speaker names.
const sttStyle = (s) => s.toLowerCase().replace(/[^a-z0-9\s'-]/g, ' ').replace(/\s+/g, ' ').trim();
const wordCount = (s) => s.split(/\s+/).filter(Boolean).length;

function loadItems() {
  if (args.stt) {
    return readFileSync(args.stt, 'utf8').split('\n').filter(Boolean).map(JSON.parse)
      .map((r) => ({ id: r.id, label: r.label, category: r.category, prior: r.prior ?? {}, lines: r.stt_lines }));
  }
  const file = SET === 'test' ? './testset/test.jsonl' : './dev/dev.jsonl';
  return readFileSync(here(file), 'utf8').split('\n').filter(Boolean).map(JSON.parse)
    .map((r) => ({ ...r, lines: r.turns.map(([, text]) => sttStyle(text)) }));
}

const totals = { calls: {}, inTok: {}, outTok: {}, cacheRead: {}, latency: { fast: [], deep: [] }, unavailable: 0 };

async function callJudge(tier, transcript, prior) {
  for (let attempt = 0; ; attempt++) {
    const t0 = Date.now();
    let res;
    try {
      res = await fetch(URL_, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'x-eval': '1' },
        body: JSON.stringify({ tier, transcript, profile: PROFILE, prior }),
      });
    } catch (e) {
      if (attempt >= 3) throw new Error(`cannot reach ${URL_}: start it with "node proxy/local.mjs"`);
      await new Promise((r) => setTimeout(r, 1500 * (attempt + 1)));
      continue;
    }
    if (res.status === 429 || res.status >= 500) {
      if (attempt >= 4) throw new Error(`judge kept failing with HTTP ${res.status}`);
      await new Promise((r) => setTimeout(r, 2000 * (attempt + 1)));
      continue;
    }
    if (!res.ok) throw new Error(`judge returned HTTP ${res.status}: ${await res.text()}`);
    const v = await res.json();
    totals.latency[tier].push(Date.now() - t0);
    const m = v.model || tier;
    totals.calls[m] = (totals.calls[m] || 0) + 1;
    if (v.usage) {
      totals.inTok[m] = (totals.inTok[m] || 0) + (v.usage.input_tokens || 0) + (v.usage.cache_creation_input_tokens || 0);
      totals.cacheRead[m] = (totals.cacheRead[m] || 0) + (v.usage.cache_read_input_tokens || 0);
      totals.outTok[m] = (totals.outTok[m] || 0) + (v.usage.output_tokens || 0);
    }
    if (v.unavailable) { totals.unavailable++; return null; }
    return v;
  }
}

const toFusion = (v, tier) => ({
  tier, p: v.scam_probability, confidence: v.confidence,
  profileMatch: v.signals.profile_match.score, matchedLevel: LEVEL[v.matched_threat] ?? 'none',
});

/** Replay one call turn by turn. Returns the trace of what the meter would have shown. */
async function replay(item) {
  const fusion = new ScoreFusion(item.prior);
  const policy = new CascadePolicy();
  const totalWords = wordCount(item.lines.join(' '));
  const trace = [];
  let last = { display: null, band: 'listening', raw: null };
  let reason = '';

  for (let i = 0; i < item.lines.length; i++) {
    const soFar = redact(item.lines.slice(0, i + 1).join('\n')).text;   // the whole call at once, as the app does
    const words = wordCount(soFar);
    if (words < MIN_WORDS) continue;
    const nowMs = (words / WORDS_PER_SECOND) * 1000;

    if (MODE === 'cascade') {
      const fast = await callJudge('fast', soFar, item.prior);
      if (fast) {
        last = fusion.update(toFusion(fast, 'fast'));
        if (fast.confidence !== 'low') reason = fast.reason;
        if (fast.confidence !== 'low' && policy.shouldRunDeep(nowMs, last.raw, fast.scam_probability)) {
          policy.onDeepStarted(nowMs, fast.scam_probability);
          const deep = await callJudge('deep', soFar, item.prior);
          if (deep) { last = fusion.update(toFusion(deep, 'deep')); if (deep.confidence !== 'low') reason = deep.reason; }
        }
      }
    } else {
      // Single-model modes: that model is the only judge, so its verdicts enter the maths un-blended.
      const v = await callJudge(MODE === 'sonnet' ? 'deep' : 'fast', soFar, item.prior);
      if (v) { last = fusion.update(toFusion(v, 'fast')); if (v.confidence !== 'low') reason = v.reason; }
    }
    trace.push({ turn: i + 1, words, fraction: words / totalWords, seconds: words / WORDS_PER_SECOND, display: last.display, band: last.band });
  }

  const firstRed = trace.find((t) => t.display !== null && t.display >= RED);
  const peak = Math.max(0, ...trace.map((t) => t.display ?? 0));
  return {
    id: item.id, label: item.label, category: item.category, peak,
    alarmed: Boolean(firstRed), warned: peak >= AMBER,
    alarmAt: firstRed ? { turn: firstRed.turn, of: item.lines.length, fraction: firstRed.fraction, seconds: firstRed.seconds } : null,
    final: trace.at(-1)?.display ?? null, reason, trace,
  };
}

function dryRun(items) {
  let fast = 0; let chars = 0;
  for (const it of items) {
    for (let i = 0; i < it.lines.length; i++) {
      const soFar = it.lines.slice(0, i + 1).join('\n');
      if (wordCount(soFar) >= MIN_WORDS) { fast++; chars += soFar.length; }
    }
  }
  const SYSTEM_TOKENS = 1700; const OUT_FAST = 450; const OUT_DEEP = 1200;   // generous
  const inTok = fast * SYSTEM_TOKENS + chars / 3.5;
  const cost = (model, calls, share, out) => (inTok * share * PRICE[model][0] + calls * out * PRICE[model][1]) / 1e6;
  const haiku = cost('claude-haiku-5-5', fast, 1, OUT_FAST);
  const sonnet = cost('claude-sonnet-5-5', fast, 1, OUT_DEEP);
  console.log(`${items.length} calls -> ${fast} checkpoints.`);
  console.log(`Upper-bound cost with no prompt caching: haiku-only $${haiku.toFixed(3)}, sonnet-only $${sonnet.toFixed(2)}, cascade <= $${(haiku + sonnet).toFixed(2)} (if every checkpoint escalated).`);
}

const median = (xs) => { if (!xs.length) return null; const s = [...xs].sort((a, b) => a - b); return s[Math.floor(s.length / 2)]; };
const pct = (n, d) => (d ? `${Math.round((100 * n) / d)}%` : 'n/a');

async function main() {
  const items = loadItems();
  if (args['dry-run']) return dryRun(items);

  const results = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: CONCURRENCY }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await replay(items[i]);
      process.stdout.write(results[i].label === 'scam' ? (results[i].alarmed ? 'S' : 's') : (results[i].alarmed ? 'G' : 'g'));
    }
  }));
  process.stdout.write('\n');

  const scams = results.filter((r) => r.label === 'scam');
  const genuine = results.filter((r) => r.label === 'genuine');
  const tp = scams.filter((r) => r.alarmed).length; const fn = scams.length - tp;
  const fp = genuine.filter((r) => r.alarmed).length; const tn = genuine.length - fp;
  const warnedScams = scams.filter((r) => r.warned).length;
  const warnedGenuine = genuine.filter((r) => r.warned).length;
  const alarms = scams.filter((r) => r.alarmAt).map((r) => r.alarmAt);
  let usd = 0;
  for (const [m, n] of Object.entries(totals.calls)) {
    const [pi, po] = PRICE[m] ?? [0, 0];
    usd += ((totals.inTok[m] || 0) * pi + (totals.cacheRead[m] || 0) * pi * 0.1 + (totals.outTok[m] || 0) * po) / 1e6;
    void n;
  }
  const models = Object.entries(totals.calls).map(([m, n]) => `${m} x${n}`).join(', ');
  const stamp = new Date().toISOString().replace('T', ' ').slice(0, 16);

  const md = `
## ${SET} set, mode: ${MODE} (${stamp} UTC)

${scams.length} scam calls, ${genuine.length} genuine calls. "Alarm" = the meter reached red (>= ${RED}%) at any point in the call.

|                    | Alarm raised | No alarm |
|--------------------|-------------:|---------:|
| **Scam call**      | ${tp} caught | ${fn} missed |
| **Genuine call**   | ${fp} false alarm | ${tn} left alone |

| Measure | Value |
|---|---|
| Scams caught (recall) | ${tp}/${scams.length} = ${pct(tp, scams.length)} |
| Alarms that were right (precision) | ${tp}/${tp + fp} = ${pct(tp, tp + fp)} |
| Genuine calls wrongly alarmed (false-alarm rate) | ${fp}/${genuine.length} = ${pct(fp, genuine.length)} |
| Scams that got at least a "be careful" (amber or red) | ${warnedScams}/${scams.length} = ${pct(warnedScams, scams.length)} |
| Genuine calls that got a "be careful" or worse | ${warnedGenuine}/${genuine.length} = ${pct(warnedGenuine, genuine.length)} |
| Median point of the alarm | turn ${median(alarms.map((a) => a.turn)) ?? 'n/a'}, ${alarms.length ? Math.round(100 * median(alarms.map((a) => a.fraction))) : 'n/a'}% of the way through the call, about ${alarms.length ? Math.round(median(alarms.map((a) => a.seconds))) : 'n/a'} s in |
| Judge calls | ${models} |
| Median judge response time | fast ${median(totals.latency.fast) ?? 'n/a'} ms, deep ${median(totals.latency.deep) ?? 'n/a'} ms |
| Verdicts unavailable (declined or cut off) | ${totals.unavailable} |
| API cost of this run | $${usd.toFixed(3)} |

Missed scams: ${scams.filter((r) => !r.alarmed).map((r) => `${r.id} (${r.category}, peak ${r.peak}%)`).join('; ') || 'none'}
False alarms: ${genuine.filter((r) => r.alarmed).map((r) => `${r.id} (${r.category}, peak ${r.peak}%)`).join('; ') || 'none'}
Genuine calls shown amber: ${genuine.filter((r) => r.warned && !r.alarmed).map((r) => `${r.id} (${r.category}, peak ${r.peak}%)`).join('; ') || 'none'}
`;
  console.log(md);

  const cache = here('./.cache/');
  if (!existsSync(cache)) mkdirSync(cache, { recursive: true });
  writeFileSync(new URL(`run-${SET}-${MODE}.json`, cache), JSON.stringify({ stamp, mode: MODE, set: SET, results, totals }, null, 2));
  // Only held-out and audio runs go in the report. Dev runs are for tuning and stay out of it.
  if (SET !== 'dev' && !args['no-write']) appendFileSync(here('./results.md'), md);
}

main().catch((e) => { console.error(`\neval failed: ${e.message}`); process.exit(1); });
