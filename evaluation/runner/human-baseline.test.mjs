import assert from "node:assert/strict";
import { existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";

import { BASELINE_ROOT_ENVIRONMENT, resolveHumanBaseline } from "./human-baseline.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "../..");
const caseIds = new Set(["case-one", "case-two"]);

function record(overrides = {}) {
  return {
    schema_version: "opsmind-human-baseline-record-v1",
    case_id: "case-one",
    reviewer_id: "reviewer-0000000a",
    minutes_to_conclusion: 12.5,
    abstained: false,
    root_cause_label: "deployment-change",
    later_corrected: false,
    interrupted: false,
    adjudicated: false,
    ...overrides,
  };
}

function withRoot(run) {
  const root = path.join(repositoryRoot, ".opsmind", "baseline-tests", `baseline-${process.pid}`);
  rmSync(root, { recursive: true, force: true });
  mkdirSync(root, { recursive: true });
  try {
    return run(root, (name, body) => writeFileSync(path.join(root, name), JSON.stringify(body)));
  } finally {
    if (existsSync(root)) rmSync(root, { recursive: true, force: true });
  }
}

function rejects(root, code) {
  assert.throws(
    () => resolveHumanBaseline({ baselineRoot: root, knownCaseIds: caseIds }),
    (error) => error?.code === code,
  );
}

test("reports absence when no reviewer has answered", () => {
  // This is the state the gate is expected to ship in. Producing a comparator
  // needs reviewer time, and reporting it as absent is the honest result.
  const unset = resolveHumanBaseline({ baselineRoot: "", knownCaseIds: caseIds });
  assert.equal(unset.status, "UNAVAILABLE");
  assert.match(unset.reason, new RegExp(BASELINE_ROOT_ENVIRONMENT, "u"));

  withRoot((root) => {
    const empty = resolveHumanBaseline({ baselineRoot: root, knownCaseIds: caseIds });
    assert.equal(empty.status, "UNAVAILABLE");
    assert.match(empty.reason, /no reviewer sessions/u);
  });
});

test("requires two independent reviewers before a case counts", () => {
  withRoot((root, write) => {
    write("one.json", record());
    const single = resolveHumanBaseline({ baselineRoot: root, knownCaseIds: caseIds });
    assert.equal(single.status, "UNAVAILABLE");
    assert.match(single.reason, /2 independent reviewers/u);

    write("two.json", record({ reviewer_id: "reviewer-0000000b" }));
    const paired = resolveHumanBaseline({ baselineRoot: root, knownCaseIds: caseIds });
    assert.equal(paired.status, "RESOLVED");
    assert.equal(paired.cases.length, 1);
    assert.equal(paired.cases[0].reviewerCount, 2);
    assert.equal(paired.cases[0].disagreed, false);
    assert.equal(paired.cases[0].medianMinutes, 12.5);
  });
});

test("refuses unadjudicated disagreement rather than picking a side", () => {
  withRoot((root, write) => {
    write("one.json", record());
    write("two.json", record({
      reviewer_id: "reviewer-0000000b",
      root_cause_label: "queue-backlog",
    }));
    rejects(root, "HUMAN_BASELINE_ADJUDICATION");

    write("two.json", record({
      reviewer_id: "reviewer-0000000b",
      root_cause_label: "queue-backlog",
      adjudicated: true,
    }));
    const adjudicated = resolveHumanBaseline({ baselineRoot: root, knownCaseIds: caseIds });
    assert.equal(adjudicated.status, "RESOLVED");
    assert.equal(adjudicated.cases[0].disagreed, true);
    assert.equal(adjudicated.cases[0].adjudicated, true);
  });
});

test("rejects records that contradict themselves or the corpus", () => {
  withRoot((root, write) => {
    write("one.json", record({ case_id: "case-unknown" }));
    rejects(root, "HUMAN_BASELINE_RECORD");

    write("one.json", record({ abstained: true }));
    rejects(root, "HUMAN_BASELINE_RECORD");

    write("one.json", record({ abstained: false, root_cause_label: null }));
    rejects(root, "HUMAN_BASELINE_RECORD");

    write("one.json", record());
    write("two.json", record());
    rejects(root, "HUMAN_BASELINE_RECORD");
  });
});
