import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { stableStringify } from "./score-phase-07-projection.mjs";
import { scorePhase07Trace } from "./score-phase-07-trace-core.mjs";

const truth = JSON.parse(readFileSync(
  new URL("../scenarios/deployment-latency-regression/ground-truth.json", import.meta.url),
  "utf8",
));
const passingTrace = JSON.parse(readFileSync(
  new URL("../fixtures/phase-07-trace.scenario-a.valid.json", import.meta.url),
  "utf8",
));
const fixedTime = "2030-01-01T00:07:00Z";
const traceReference = "repository://evaluation/fixtures/phase-07-trace.scenario-a.valid.json";

function score(trace = passingTrace) {
  return scorePhase07Trace({
    groundTruth: truth,
    trace,
    traceReference,
    generatedAt: fixedTime,
  });
}

function clonedTrace() {
  return structuredClone(passingTrace);
}

function refreshRawDigest(trace) {
  trace.runs[0].rawAnalysisDigest = `sha256:${createHash("sha256")
    .update(stableStringify(trace.runs[0].rawAnalysis))
    .digest("hex")}`;
  return trace;
}

test("passes a complete deterministic smoke artifact", () => {
  const result = score();
  assert.equal(result.verdict, "PASS");
  assert.deepEqual(
    Object.values(result.metrics).map((metric) => metric.status),
    ["PASS", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS", "PASS"],
  );
  assert.equal(result.sample_count, 1);
  assert.equal(result.metrics.cost.value, 0);
});

test("is deterministic for fixed input and clock", () => {
  assert.deepEqual(score(), score());
});

test("fails closed when raw projection and receipt references are absent", () => {
  const trace = clonedTrace();
  delete trace.runs[0].operatorProjection;
  delete trace.runs[0].toolExecutions;
  const result = score(trace);
  assert.equal(result.verdict, "INCOMPLETE");
  assert.equal(result.metrics.structured_output.status, "UNAVAILABLE");
  assert.equal(result.metrics.tool_selection.status, "UNAVAILABLE");
});

test("fails an unsupported root cause", () => {
  const trace = clonedTrace();
  trace.runs[0].operatorProjection.analysis.hypotheses[0].title = "Database corruption";
  const result = score(trace);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.root_cause.status, "FAIL");
});

test("fails citation drift from persisted evidence", () => {
  const trace = clonedTrace();
  trace.runs[0].operatorProjection.analysis.citations[0].evidence_id =
    "10000000-0000-4000-8000-000000000899";
  const result = score(trace);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.evidence_grounding.status, "FAIL");
});

test("fails tool evidence digest drift", () => {
  const trace = clonedTrace();
  trace.runs[0].toolExecutions[0].evidenceDigests[0] =
    "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  const result = score(trace);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.tool_selection.status, "FAIL");
});

test("fails a selector substitution even when the receipt succeeds", () => {
  const trace = clonedTrace();
  trace.runs[0].toolExecutions[0].operation = "write";
  const result = score(trace);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.tool_selection.status, "FAIL");
});

test("fails tenant or run identity drift", () => {
  const trace = clonedTrace();
  trace.runs[0].operatorProjection.organizationId =
    "10000000-0000-4000-8000-000000000899";
  const result = score(trace);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.structured_output.status, "FAIL");
});

test("fails evidence identity drift between run and projection", () => {
  const trace = clonedTrace();
  trace.runs[0].evidenceIds = [];
  const result = score(trace);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.structured_output.status, "FAIL");
});

test("fails an over-budget projection", () => {
  const trace = clonedTrace();
  trace.runs[0].operatorProjection.rounds = 6;
  const result = score(trace);
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.structured_output.status, "FAIL");
});

test("fails an untrusted trace-level terminal attestation", () => {
  const trace = clonedTrace();
  trace.terminalStatus = "FAIL";
  trace.latencyMs.thresholdPass = false;
  const result = score(trace);
  assert.equal(result.verdict, "INCOMPLETE");
  assert.ok(result.warnings.some((warning) => warning.includes("provenance")));
});

test("keeps semantic RCA unavailable without a trusted raw artifact", () => {
  const trace = clonedTrace();
  delete trace.runs[0].rawAnalysis;
  delete trace.runs[0].rawAnalysisReference;
  const result = score(trace);
  assert.equal(result.verdict, "INCOMPLETE");
  assert.equal(result.metrics.root_cause.status, "PASS");
  assert.equal(result.metrics.root_cause_semantic.status, "UNAVAILABLE");
});

test("fails closed for out-of-range raw confidence", () => {
  const trace = clonedTrace();
  trace.runs[0].rawAnalysis.confidence = 2;
  const result = score(trace);
  assert.equal(result.verdict, "INCOMPLETE");
  assert.equal(result.metrics.root_cause_semantic.status, "UNAVAILABLE");
});

test("fails closed for incoherent raw token usage", () => {
  const trace = clonedTrace();
  trace.runs[0].rawAnalysis.usage.total_tokens = 65;
  const result = score(trace);
  assert.equal(result.verdict, "INCOMPLETE");
  assert.equal(result.metrics.root_cause_semantic.status, "UNAVAILABLE");
});

test("fails closed for malformed raw citations", () => {
  const trace = clonedTrace();
  trace.runs[0].rawAnalysis.citations[0].claim = "";
  const result = score(trace);
  assert.equal(result.verdict, "INCOMPLETE");
  assert.equal(result.metrics.root_cause_semantic.status, "UNAVAILABLE");
});

test("fails when trusted raw usage exceeds the scenario token budget", () => {
  const trace = clonedTrace();
  trace.runs[0].rawAnalysis.usage = {
    prompt_tokens: 100_000,
    completion_tokens: 100_000,
    total_tokens: 200_000,
  };
  const result = score(refreshRawDigest(trace));
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.root_cause_semantic.status, "FAIL");
});

test("fails when trusted raw cost exceeds the scenario cost budget", () => {
  const trace = clonedTrace();
  trace.runs[0].rawAnalysis.cost_estimate.amount = 0.01;
  const result = score(refreshRawDigest(trace));
  assert.equal(result.verdict, "FAIL");
  assert.equal(result.metrics.cost.status, "FAIL");
});
