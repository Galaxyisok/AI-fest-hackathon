// Mirror of app/src/main/java/com/scamguard/judge/ScoreFusion.kt.
// Both are checked against shared/fusion-vectors.json, so the eval measures the same maths the app ships.

export const AMBER = 30;
export const RED = 60;
export const MAX_DISPLAY = 95;

const PRIOR_WEIGHTS = { international: 0.35, unverified: 0.35, hidden: 0.25 };
const PRIOR_CAP = 0.7;
const BOOST = { high: 0.5, medium: 0.25, low: 0, none: 0 };
const RISE_ALPHA = 0.7;
const FALL_ALPHA = 0.4;
const DEEP_WEIGHT = 0.65;
const MAX_DROP_AFTER_RED = 8;

const logit = (p) => Math.log(p / (1 - p));
const sigmoid = (x) => 1 / (1 + Math.exp(-x));
const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));

export function bandFor(display) {
  if (display === null || display === undefined) return 'listening';
  if (display >= RED) return 'red';
  if (display >= AMBER) return 'amber';
  return 'green';
}

export class ScoreFusion {
  constructor(prior = {}) {
    let w = 0;
    for (const k of Object.keys(PRIOR_WEIGHTS)) if (prior[k]) w += PRIOR_WEIGHTS[k];
    this.priorWeight = Math.min(PRIOR_CAP, w);
    this.ema = null;
    this.peak = 0;
    this.lastFast = null;
    this.lastDeep = null;
    this.fastIsNewer = false;
  }

  /** verdict: { tier: 'fast'|'deep', p: 0..100, confidence, profileMatch: 0..3, matchedLevel } */
  update(v) {
    if (v.confidence === 'low') return this.snapshot();   // too little speech: never show a guess

    if (v.tier === 'deep') { this.lastDeep = v; this.fastIsNewer = false; }
    else { this.lastFast = v; this.fastIsNewer = true; }

    let llm;
    if (this.lastDeep && this.lastFast && this.fastIsNewer) {
      llm = DEEP_WEIGHT * this.lastDeep.p + (1 - DEEP_WEIGHT) * this.lastFast.p;
    } else if (this.lastDeep) {
      llm = this.lastDeep.p;
    } else {
      llm = this.lastFast.p;
    }

    const boost = v.profileMatch >= 2 ? (BOOST[v.matchedLevel] ?? 0) : 0;
    const shift = this.priorWeight + boost;
    const adjusted = shift === 0
      ? clamp(llm, 0, 100)
      : 100 * sigmoid(logit(clamp(llm, 2, 98) / 100) + shift);

    if (this.ema === null) {
      this.ema = adjusted;
    } else {
      const alpha = adjusted >= this.ema ? RISE_ALPHA : FALL_ALPHA;
      let next = alpha * adjusted + (1 - alpha) * this.ema;
      if (this.peak >= RED) next = Math.max(next, this.ema - MAX_DROP_AFTER_RED);
      this.ema = next;
    }
    this.peak = Math.max(this.peak, this.ema);
    return this.snapshot();
  }

  snapshot() {
    // Rounded to 5, and never above 95: no detector is certain, and "100%" would say it is.
    const display = this.ema === null ? null : Math.min(MAX_DISPLAY, Math.round(this.ema / 5) * 5);
    return { display, band: bandFor(display), raw: this.ema };
  }
}
