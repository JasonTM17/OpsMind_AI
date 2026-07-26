// Two-sided z for a 95 percent interval. Fixed rather than derived so the
// reported level cannot drift with a library change.
const Z_95 = 1.959963984540054;

/**
 * Wilson score interval for a binomial proportion.
 *
 * The normal approximation is unusable here: at the denominators this project
 * reports, it produces bounds outside [0, 1] and a zero-width interval when the
 * observed proportion is 0 or 1, which would read as certainty from three
 * observations. Wilson stays inside [0, 1] and keeps width at the extremes.
 */
export function wilsonInterval(successes, trials, z = Z_95) {
  if (!Number.isInteger(successes) || !Number.isInteger(trials)
    || successes < 0 || trials < 0 || successes > trials) {
    return { method: "wilson", level: 0.95, lower: null, upper: null, reason: "invalid counts" };
  }
  if (trials === 0) {
    return { method: "wilson", level: 0.95, lower: null, upper: null, reason: "no trials" };
  }
  const proportion = successes / trials;
  const zSquared = z * z;
  const denominator = 1 + zSquared / trials;
  const centre = proportion + zSquared / (2 * trials);
  const spread = z * Math.sqrt(
    (proportion * (1 - proportion) + zSquared / (4 * trials)) / trials,
  );
  const lower = Math.max(0, (centre - spread) / denominator);
  const upper = Math.min(1, (centre + spread) / denominator);
  return {
    method: "wilson",
    level: 0.95,
    lower: Number(lower.toFixed(6)),
    upper: Number(upper.toFixed(6)),
    reason: null,
  };
}

/**
 * Account for cases and trials separately.
 *
 * A scenario replayed 100 times supplies 100 correlated trials of one case. It
 * does not supply 100 independent observations, and reporting it as such would
 * make a three-case corpus look like a population. `independent` is true only
 * when every case contributed exactly one trial.
 */
export function summarizeSampling(caseIds) {
  const trials = Array.isArray(caseIds) ? caseIds.length : 0;
  const cases = new Set(Array.isArray(caseIds) ? caseIds : []).size;
  return {
    cases,
    trials,
    trials_per_case: cases === 0 ? 0 : Number((trials / cases).toFixed(4)),
    independent: cases > 0 && cases === trials,
  };
}
