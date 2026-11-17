// Shared by the Vercel function (api/judge.js) and the local dev server (local.mjs).
// Stateless: nothing here writes a transcript, a verdict or an IP address anywhere.
import Anthropic from '@anthropic-ai/sdk';
import { SYSTEM_PROMPT, VERDICT_SCHEMA, SYSTEM_PROMPT_DEEP_SLIM, VERDICT_SCHEMA_SLIM, THREAT_TYPES, SIGNAL_KEYS, buildUserMessage } from './prompt.mjs';
import { redact } from './redactor.mjs';

export const MAX_TRANSCRIPT_CHARS = 8000;   // about 8 minutes of speech; older text is dropped from the front
const LEVELS = ['low', 'medium', 'high'];
const USER_TYPES = ['student', 'parent', 'senior', 'professional'];

// The model for each tier is configuration, so the eval can compare them without a code change.
export function modelFor(tier, env = process.env) {
  return tier === 'deep'
    ? (env.JUDGE_DEEP_MODEL || 'claude-sonnet-5-5')
    : (env.JUDGE_FAST_MODEL || 'claude-haiku-5-5');
}

export class BadRequest extends Error {}

/** Accept only what the app sends. Anything else is rejected, so this cannot be used as a general LLM endpoint. */
export function validate(body) {
  if (!body || typeof body !== 'object') throw new BadRequest('body must be a JSON object');
  const tier = body.tier === 'deep' ? 'deep' : 'fast';
  if (typeof body.transcript !== 'string' || body.transcript.trim().length === 0) {
    throw new BadRequest('transcript must be a non-empty string');
  }
  if (body.transcript.length > 4 * MAX_TRANSCRIPT_CHARS) throw new BadRequest('transcript too long');

  const p = body.profile && typeof body.profile === 'object' ? body.profile : {};
  const profile = {
    userType: USER_TYPES.includes(p.userType) ? p.userType : 'unknown',
    // Only type + level reach the model. The free-text "why" stays on the phone.
    threats: (Array.isArray(p.threats) ? p.threats : [])
      .filter((t) => t && THREAT_TYPES.includes(t.type) && LEVELS.includes(t.level))
      .slice(0, THREAT_TYPES.length)
      .map((t) => ({ type: t.type, level: t.level })),
  };
  const pr = body.prior && typeof body.prior === 'object' ? body.prior : {};
  const prior = { international: pr.international === true, unverified: pr.unverified === true, hidden: pr.hidden === true };

  // The phone redacts before sending. Redact again here in case a modified client did not.
  let transcript = redact(body.transcript).text;
  if (transcript.length > MAX_TRANSCRIPT_CHARS) transcript = transcript.slice(-MAX_TRANSCRIPT_CHARS);
  return { tier, transcript, profile, prior };
}

/** True when the deep tier is asked for its verdict only (see prompt.mjs). Off by default. */
export const deepIsSlim = (tier, env = process.env) => tier === 'deep' && env.JUDGE_DEEP_OUTPUT === 'slim';

export function requestFor(tier, input, env = process.env) {
  const slim = deepIsSlim(tier, env);
  const req = {
    model: modelFor(tier, env),
    max_tokens: tier === 'deep' ? 4000 : 1200,
    system: [{ type: 'text', text: slim ? SYSTEM_PROMPT_DEEP_SLIM : SYSTEM_PROMPT, cache_control: { type: 'ephemeral' } }],
    messages: [{ role: 'user', content: buildUserMessage(input) }],
    output_config: {
      format: { type: 'json_schema', schema: slim ? VERDICT_SCHEMA_SLIM : VERDICT_SCHEMA },
      effort: tier === 'deep' ? (env.JUDGE_DEEP_EFFORT || 'low') : (env.JUDGE_FAST_EFFORT || 'low'),
    },
  };
  // The fast tier has to answer in about a second: no thinking. The deep tier keeps adaptive thinking.
  if (tier === 'fast' && (env.JUDGE_FAST_THINKING || 'off') === 'off') req.thinking = { type: 'disabled' };
  return req;
}

/** Clamp and sanity-check the model output so the app never has to defend against it. */
export function normalise(raw) {
  const v = typeof raw === 'string' ? JSON.parse(raw) : raw;
  const signals = {};
  for (const k of SIGNAL_KEYS) {
    // A slim deep reply carries no signal list, only profile_match as a bare number.
    const s = v.signals?.[k] ?? (k === 'profile_match' && Number.isInteger(v.profile_match) ? { score: v.profile_match } : {});
    signals[k] = {
      score: Math.min(3, Math.max(0, Number.isInteger(s.score) ? s.score : 0)),
      evidence: String(s.evidence ?? 'none').slice(0, 200),
    };
  }
  const p = Number(v.scam_probability);
  if (!Number.isFinite(p)) throw new Error('model returned no probability');
  return {
    signals,
    matched_threat: [...THREAT_TYPES, 'other_scam', 'none'].includes(v.matched_threat) ? v.matched_threat : 'none',
    confidence: ['low', 'medium', 'high'].includes(v.confidence) ? v.confidence : 'low',
    scam_probability: Math.min(100, Math.max(0, Math.round(p))),
    reason: String(v.reason ?? '').replace(/[\s{}\[\]"]+$/, '').slice(0, 240),   // seen live: "Hang up now.}"
  };
}

let client;
export async function judge(input, env = process.env) {
  client ??= new Anthropic({ apiKey: env.ANTHROPIC_API_KEY, maxRetries: 1, timeout: 25_000 });
  const response = await client.messages.create(requestFor(input.tier, input, env));

  if (response.stop_reason === 'refusal') {
    // A safety classifier declined. The app keeps its last score; the cascade never depends on one call.
    return { unavailable: 'refused', tier: input.tier, model: response.model, usage: response.usage };
  }
  if (response.stop_reason === 'max_tokens') {
    return { unavailable: 'truncated', tier: input.tier, model: response.model, usage: response.usage };
  }
  const text = response.content.find((b) => b.type === 'text')?.text;
  if (!text) return { unavailable: 'empty', tier: input.tier, model: response.model, usage: response.usage };
  return { ...normalise(text), tier: input.tier, model: response.model, usage: response.usage };
}

/** Map SDK errors to an HTTP status without leaking details to the caller. */
export function statusFor(err) {
  if (err instanceof BadRequest) return [400, err.message];
  if (err instanceof Anthropic.RateLimitError) return [429, 'busy, try again shortly'];
  if (err instanceof Anthropic.AuthenticationError) return [500, 'proxy is not configured'];
  if (err instanceof Anthropic.BadRequestError) return [502, 'judge rejected the request'];
  if (err instanceof Anthropic.APIConnectionError) return [504, 'judge unreachable'];
  if (err instanceof Anthropic.APIError) return [502, 'judge error'];
  return [500, 'internal error'];
}

// ---- rate limiting -------------------------------------------------------------------------
// Per-IP sliding window held in memory. On serverless this is per warm instance, so it is a
// speed bump, not a wall: it stops one client hammering the judge to tune a scam script, and
// the platform firewall is the place for a hard cap. Only a salted counter is kept, in RAM.
const WINDOW_MS = 60_000;
const hits = new Map();
export function allow(ip, limit = Number(process.env.JUDGE_RATE_PER_MIN || 40), now = Date.now()) {
  const recent = (hits.get(ip) ?? []).filter((t) => now - t < WINDOW_MS);
  if (recent.length >= limit) { hits.set(ip, recent); return false; }
  recent.push(now);
  hits.set(ip, recent);
  if (hits.size > 5000) for (const [k, v] of hits) if (v.every((t) => now - t >= WINDOW_MS)) hits.delete(k);
  return true;
}
