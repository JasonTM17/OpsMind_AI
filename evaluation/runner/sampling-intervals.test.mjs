import assert from "node:assert/strict";
import { test } from "node:test";

import { summarizeSampling, wilsonInterval } from "./sampling-intervals.mjs";

test("matches reference Wilson bounds and stays inside the unit interval", () => {
  // Reference values for the 95 percent Wilson score interval. 50/100 and
  // 97/100 are the commonly published check cases; the small denominators are
  // the ones this project actually reports.
  const expected = [
    [50, 100, 0.403832, 0.596168],
    [97, 100, 0.915481, 0.989745],
    [100, 100, 0.963007, 1],
    [3, 3, 0.438503, 1],
    [0, 3, 0, 0.561497],
    [1, 1, 0.206549, 1],
  ];
  for (const [successes, trials, lower, upper] of expected) {
    const interval = wilsonInterval(successes, trials);
    assert.equal(interval.method, "wilson");
    assert.equal(interval.level, 0.95);
    assert.equal(interval.reason, null);
    assert.equal(interval.lower, lower);
    assert.equal(interval.upper, upper);
    assert.ok(interval.lower >= 0 && interval.upper <= 1);
    assert.ok(interval.lower <= interval.upper);
  }
});

test("keeps width at the extremes instead of implying certainty", () => {
  // A normal approximation collapses to zero width when every trial succeeds,
  // which would report three successes out of three as a certainty.
  const perfect = wilsonInterval(3, 3);
  assert.ok(perfect.upper - perfect.lower > 0.5);
  const none = wilsonInterval(0, 3);
  assert.ok(none.upper - none.lower > 0.5);
});

test("reports a reason instead of a number when counts cannot support one", () => {
  for (const [successes, trials, reason] of [
    [0, 0, "no trials"],
    [2, 1, "invalid counts"],
    [-1, 5, "invalid counts"],
    [1.5, 5, "invalid counts"],
  ]) {
    const interval = wilsonInterval(successes, trials);
    assert.equal(interval.lower, null);
    assert.equal(interval.upper, null);
    assert.equal(interval.reason, reason);
  }
});

test("counts correlated repeats as trials of one case, not as cases", () => {
  // A scenario replayed 100 times is 100 observations of the same case. Counting
  // it as 100 cases would turn a three-case corpus into an apparent population.
  const warm = summarizeSampling(Array.from({ length: 100 }, () => "scenario-a"));
  assert.equal(warm.cases, 1);
  assert.equal(warm.trials, 100);
  assert.equal(warm.trials_per_case, 100);
  assert.equal(warm.independent, false);

  const distinct = summarizeSampling(["scenario-a", "scenario-b", "scenario-c"]);
  assert.equal(distinct.cases, 3);
  assert.equal(distinct.trials, 3);
  assert.equal(distinct.independent, true);

  const empty = summarizeSampling([]);
  assert.deepEqual(empty, { cases: 0, trials: 0, trials_per_case: 0, independent: false });
});
