// Local stand-in for the Vercel function, for development and the eval:
//   ANTHROPIC_API_KEY=... node proxy/local.mjs            (or put the key in proxy/.env, which is git-ignored)
//   JUDGE_BACKEND=mock node proxy/local.mjs               (no key: fixed fake verdicts, for wiring tests ONLY)
// Listens on 0.0.0.0:8787 so the Android emulator can reach it as http://10.0.2.2:8787/api/judge
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { validate, judge, statusFor, allow } from './lib/judge-core.mjs';

// proxy/.env, or the one .env at the repo root that scripts/dev-servers.sh also uses.
for (const envFile of [new URL('./.env', import.meta.url), new URL('../.env', import.meta.url)]) {
  if (!existsSync(envFile)) continue;
  for (const line of readFileSync(envFile, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)\s*$/);
    if (m && !process.env[m[1]]) process.env[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
}

const BACKEND = process.env.JUDGE_BACKEND || 'anthropic';
const PORT = Number(process.env.PORT || 8787);

// NOT a detector. A crude stand-in so the app's plumbing can be exercised without a key.
// It is never deployed and no reported number comes from it.
function mockJudge(input) {
  const t = input.transcript.toLowerCase();
  const words = t.split(/\s+/).length;
  const hits = ['arrest', 'otp', 'any desk', 'anydesk', 'transfer', 'do not tell', 'safe account', 'digit number']
    .filter((k) => t.includes(k)).length;
  const p = words < 25 ? 10 : Math.min(95, 10 + hits * 25);
  const sig = (score) => ({ score, evidence: 'mock' });
  return {
    signals: Object.fromEntries(['authority', 'urgency_threat', 'secrecy_isolation', 'payment_request',
      'credential_or_app_request', 'script_stage', 'profile_match'].map((k) => [k, sig(Math.min(3, hits))])),
    matched_threat: hits ? 'digital_arrest' : 'none',
    confidence: words < 25 ? 'low' : 'high',
    scam_probability: p,
    reason: 'MOCK JUDGE (no AI): for wiring tests only.',
    tier: input.tier, model: 'mock',
  };
}

if (BACKEND === 'anthropic' && !process.env.ANTHROPIC_API_KEY) {
  console.error('ANTHROPIC_API_KEY is not set. Put it in .env at the repo root or the environment, or run with JUDGE_BACKEND=mock.');
  process.exit(1);
}

http.createServer(async (req, res) => {
  const send = (status, obj) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(obj));
  };
  if (req.url === '/health') return send(200, { ok: true, backend: BACKEND });
  if (req.method !== 'POST' || req.url !== '/api/judge') return send(404, { error: 'not found' });
  // The eval sends its own header so a full run is not throttled by the per-client limit.
  if (req.headers['x-eval'] !== '1' && !allow(req.socket.remoteAddress ?? 'local')) return send(429, { error: 'too many requests' });

  let raw = '';
  for await (const chunk of req) { raw += chunk; if (raw.length > 200_000) return send(413, { error: 'too large' }); }
  try {
    const input = validate(JSON.parse(raw));
    const out = BACKEND === 'mock' ? mockJudge(input) : await judge(input);
    // usage is returned locally (the eval totals cost from it); the deployed function strips it.
    send(200, out);
  } catch (err) {
    const [status, message] = err instanceof SyntaxError ? [400, 'invalid JSON'] : statusFor(err);
    if (status >= 500) console.error(`judge failed: ${err?.constructor?.name}: ${err?.message}`);
    send(status, { error: message });
  }
}).listen(PORT, '0.0.0.0', () => console.log(`ready: judge proxy (${BACKEND}) on http://localhost:${PORT}/api/judge`));
