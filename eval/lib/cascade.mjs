// Mirror of app/src/main/java/com/scamguard/judge/CascadePolicy.kt (same defaults, same rules).
export class CascadePolicy {
  constructor({ amber = 35, intervalMs = 30_000, moveThreshold = 20 } = {}) {
    Object.assign(this, { amber, intervalMs, moveThreshold });
    this.lastDeepAt = null;
    this.fastAtLastDeep = 0;
  }

  shouldRunDeep(nowMs, fused, fastP) {
    if (this.lastDeepAt === null) return (fused ?? 0) >= this.amber || fastP >= this.amber;
    if (Math.abs(fastP - this.fastAtLastDeep) >= this.moveThreshold) return true;
    return (fused ?? 0) >= this.amber && nowMs - this.lastDeepAt >= this.intervalMs;
  }

  onDeepStarted(nowMs, fastP) {
    this.lastDeepAt = nowMs;
    this.fastAtLastDeep = fastP;
  }
}
