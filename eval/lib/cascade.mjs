// Mirror of app/src/main/java/com/scamguard/judge/CascadePolicy.kt (same defaults, same rules).
export class CascadePolicy {
  constructor({ amber = 35, intervalMs = 30_000, moveThreshold = 20, settled = 85 } = {}) {
    Object.assign(this, { amber, intervalMs, moveThreshold, settled });
    this.deepSaysScam = false;
    this.lastDeepAt = null;
    this.fastAtLastDeep = 0;
  }

  shouldRunDeep(nowMs, fused, fastP) {
    if (this.deepSaysScam) return false;   // the deep model already called it a scam: another look changes nothing
    if (this.lastDeepAt === null) return (fused ?? 0) >= this.amber || fastP >= this.amber;
    if (Math.abs(fastP - this.fastAtLastDeep) >= this.moveThreshold) return true;
    return (fused ?? 0) >= this.amber && nowMs - this.lastDeepAt >= this.intervalMs;
  }

  onDeepVerdict(deepP) {
    if (deepP >= this.settled) this.deepSaysScam = true;
  }

  onDeepStarted(nowMs, fastP) {
    this.lastDeepAt = nowMs;
    this.fastAtLastDeep = fastP;
  }
}
