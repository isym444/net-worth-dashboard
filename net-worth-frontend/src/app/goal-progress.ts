/**
 * How far along the way to a target you are, in two ways (both on the same constant monthly-compounded return as the
 * milestone projections). Port of GoalProgress.swift / GoalProgress.kt; keep the three in step.
 *  - growthShare: the share of the target that will have come from the money you already hold, once it has grown until
 *    the target is reached: P(1+r)^n / T.
 *  - timeShare: the share of the whole journey from zero (same monthly contribution and return) that is already behind
 *    you: (n0 - n) / n0. Null when undefined (no monthly contribution).
 */
export interface GoalProgress {
  growthShare: number;
  timeShare: number | null;
}

const clamp = (x: number): number => Math.min(Math.max(x, 0), 1);

/** p: current savings, t: target, c: monthly contribution (end of month), annualReturn as a decimal. */
export function goalProgress(p: number, t: number, c: number, annualReturn: number): GoalProgress {
  if (p >= t) {
    return { growthShare: 1, timeShare: 1 };
  }
  const r = Math.pow(1 + annualReturn, 1 / 12) - 1;

  if (r <= 0) {
    if (c <= 0) {
      return { growthShare: clamp(p / t), timeShare: null };
    }
    const share = clamp(p / t);
    return { growthShare: share, timeShare: share };
  }

  const logGrowth = Math.log(1 + r);
  if (c <= 0) {
    return { growthShare: p <= 0 ? 0 : 1, timeShare: null };
  }

  const k = c / r;
  if (p + k <= 0) {
    return { growthShare: 0, timeShare: 0 };
  }
  const n = Math.log((t + k) / (p + k)) / logGrowth;
  const n0 = Math.log(1 + (t * r) / c) / logGrowth;
  const growth = p > 0 ? clamp((p * Math.pow(1 + r, n)) / t) : 0;
  return { growthShare: growth, timeShare: clamp((n0 - n) / n0) };
}
