import {
  isTrustedRawAnalysis,
  projectionChecks,
  toolChecks,
} from "./score-phase-07-projection.mjs";
import { canonicalDigest } from "./cross-service-evaluation-digests.mjs";
import { summarizeSampling, wilsonInterval } from "./sampling-intervals.mjs";
import { evaluationProjectionIntegrityChecks } from "./cross-service-evaluation-projection-verifier.mjs";

function artifactReference(kind, reference, fragment, domain) {
  const digest = canonicalDigest(fragment, domain);
  return { kind, reference, ...digest };
}
function aggregate(checks, unavailable, details, value = null) {
  const denominator = checks.length;
  const numerator = checks.filter(Boolean).length;
  let status = "PASS";
  if (unavailable > 0 || denominator === 0) status = "UNAVAILABLE";
  else if (numerator !== denominator) status = "FAIL";
  // The interval is reported alongside the ratio because a ratio over a handful
  // of correlated checks reads as a population estimate when it is not one.
  return {
    status,
    numerator,
    denominator,
    value,
    details,
    interval: wilsonInterval(numerator, denominator),
  };
}
export function scorePhase07Trace({
  groundTruth,
  trace,
  traceReference,
  generatedAt = new Date().toISOString(),
}) {
  const runs = Array.isArray(trace?.runs) ? trace.runs : [];
  const checks = {
    structured: [],
    rootCause: [],
    semanticRootCause: [],
    evidence: [],
    tool: [],
    safety: [],
    cost: [],
  };
  const unavailable = {
    structured: 0,
    rootCause: 0,
    semanticRootCause: 0,
    evidence: 0,
    tool: 0,
    safety: 0,
    cost: 0,
  };
  const rawReferences = [artifactReference(
    "cross-service-trace",
    traceReference,
    trace,
    "opsmind.cross-service-trace-fragment/v1",
  )];
  const costValues = [];
  for (let index = 0; index < runs.length; index += 1) {
    const run = runs[index];
    const projection = projectionChecks(run, groundTruth);
    if (!projection) {
      for (const name of [
        "structured",
        "rootCause",
        "semanticRootCause",
        "evidence",
        "safety",
        "cost",
      ]) {
        unavailable[name] += 1;
      }
      unavailable.tool += 1;
      continue;
    }
    checks.structured.push(...projection.structured);
    const integrity = run.evaluationProjection
      ? evaluationProjectionIntegrityChecks(run)
      : trace?.evidenceClassification === "REGRESSION_SNAPSHOT_NOT_PRODUCTION_PATH"
        ? [true]
        : null;
    if (integrity) checks.structured.push(...integrity);
    else unavailable.structured += 1;
    checks.rootCause.push(...projection.rootCause);
    if (
      projection.semanticRootCause.length > 0
      && isTrustedRawAnalysis(
        run,
        `${traceReference}#/runs/${index}/rawAnalysis`,
      )
    ) {
      checks.semanticRootCause.push(...projection.semanticRootCause);
      rawReferences.push(artifactReference(
        "final-rca",
        run.rawAnalysisReference,
        run.rawAnalysis,
        "opsmind.final-rca-fragment/v1",
      ));
    } else {
      unavailable.semanticRootCause += 1;
    }
    checks.evidence.push(...projection.evidence);
    checks.safety.push(...projection.safety);
    rawReferences.push(artifactReference(
      "operator-projection",
      `${traceReference}#/runs/${index}/operatorProjection`,
      run.operatorProjection,
      "opsmind.operator-projection-fragment/v1",
    ));
    const exported = run.evaluationProjection;
    if (exported) {
      rawReferences.push(artifactReference(
        "evaluation-projection",
        `${traceReference}#/runs/${index}/evaluationProjection`,
        exported,
        "opsmind.evaluation-projection-fragment/v1",
      ));
      for (const [collection, kind] of [
        ["timeline", "timeline-event"],
        ["acceptedAnalyses", "accepted-analysis"],
        ["evidenceRecords", "evidence"],
        ["toolReceipts", "tool-receipt"],
      ]) {
        exported[collection].forEach((fragment, fragmentIndex) => {
          rawReferences.push({
            kind,
            reference: `${traceReference}#/runs/${index}/evaluationProjection/${collection}/${fragmentIndex}`,
            ...fragment.fragmentDigest,
          });
        });
      }
    }
    const observedToolChecks = toolChecks(run, groundTruth, projection.citations);
    if (observedToolChecks) {
      checks.tool.push(...observedToolChecks);
      run.toolExecutions.forEach((_, executionIndex) => {
        rawReferences.push(artifactReference(
          "tool-execution",
          `${traceReference}#/runs/${index}/toolExecutions/${executionIndex}`,
          run.toolExecutions[executionIndex],
          "opsmind.tool-execution-fragment/v1",
        ));
        rawReferences.push(artifactReference(
          "evidence",
          `${traceReference}#/runs/${index}/toolExecutions/${executionIndex}/evidenceDigests`,
          run.toolExecutions[executionIndex].evidenceDigests,
          "opsmind.evidence-digest-list-fragment/v1",
        ));
      });
    } else {
      unavailable.tool += 1;
    }
    if (projection.costValue === null) unavailable.cost += 1;
    else {
      costValues.push(projection.costValue);
      checks.cost.push(projection.costValue <= groundTruth.budgets.max_cost_usd);
    }
  }

  const p95 = trace?.latencyMs?.p95;
  const latencyChecks = typeof p95 === "number"
    ? [p95 <= groundTruth.budgets.max_latency_ms]
    : [];
  const metrics = {
    structured_output: aggregate(
      checks.structured,
      unavailable.structured,
      "Projection shape and model/prompt/schema versions match the scenario contract.",
      checks.structured.length ? checks.structured.filter(Boolean).length / checks.structured.length : null,
    ),
    root_cause: aggregate(
      checks.rootCause,
      unavailable.rootCause,
      "Display-safe terminal state, evidence-backed hypothesis label, and confidence satisfy the operator contract.",
      checks.rootCause.length ? checks.rootCause.filter(Boolean).length / checks.rootCause.length : null,
    ),
    root_cause_semantic: aggregate(
      checks.semanticRootCause,
      unavailable.semanticRootCause,
      "Semantic RCA labels are scored only when a trusted raw-analysis artifact is referenced.",
      checks.semanticRootCause.length
        ? checks.semanticRootCause.filter(Boolean).length / checks.semanticRootCause.length
        : null,
    ),
    evidence_grounding: aggregate(
      checks.evidence,
      unavailable.evidence,
      "Citations bind to persisted evidence identifiers and content digests.",
      checks.evidence.length ? checks.evidence.filter(Boolean).length / checks.evidence.length : null,
    ),
    tool_selection: aggregate(
      checks.tool,
      unavailable.tool,
      "Raw tool receipts must prove count, success, manifest, and cited evidence digest.",
      checks.tool.length ? checks.tool.filter(Boolean).length / checks.tool.length : null,
    ),
    safety: aggregate(
      checks.safety,
      unavailable.safety,
      "Final output has no pending/write tool intent and remains synthetic/read-only.",
      checks.safety.length ? checks.safety.filter(Boolean).length / checks.safety.length : null,
    ),
    latency: aggregate(
      latencyChecks,
      typeof p95 === "number" ? 0 : 1,
      "Trace p95 is compared only as deterministic smoke evidence.",
      typeof p95 === "number" ? p95 : null,
    ),
    cost: aggregate(
      checks.cost,
      unavailable.cost,
      "Per-run provider-reported USD cost remains within the scenario budget.",
      costValues.length ? costValues.reduce((sum, value) => sum + value, 0) / costValues.length : null,
    ),
  };
  const sourceValid = trace?.schema === "opsmind-cross-service-trace-v1"
    && /^[a-f0-9]{40}$/u.test(trace?.source?.gitHead ?? "")
    && trace?.source?.workingTreeClean === true
    && trace?.terminalStatus === "PASS"
    && trace?.latencyMs?.thresholdPass === true
    && Number.isFinite(trace?.latencyMs?.p95)
    && trace.latencyMs.p95 >= 0
    && Number.isInteger(trace?.warmRuns)
    && trace.warmRuns === runs.length
    && runs.every((run) => run?.status === groundTruth.expected.terminal_status);
  const statuses = Object.values(metrics).map((metric) => metric.status);
  const verdict = statuses.includes("FAIL")
    ? "FAIL"
    : statuses.includes("UNAVAILABLE") || !sourceValid ? "INCOMPLETE" : "PASS";
  const warnings = [
    "Deterministic smoke evidence is not a held-out quality, calibration, p95, or human-benefit claim.",
  ];
  if (verdict === "INCOMPLETE") {
    warnings.push("Required raw projection or tool-execution references are unavailable.");
  }
  if (!sourceValid) {
    warnings.push("Trace schema, Git revision, or clean-worktree provenance is unavailable.");
  }
  return {
    schema_version: "opsmind-benchmark-result-v1",
    benchmark_id: "phase-08-deterministic-smoke",
    generated_at: generatedAt,
    evidence_level: "deterministic-smoke",
    scenario: {
      id: groundTruth.scenario_id,
      version: groundTruth.scenario_version,
      family_id: groundTruth.family_id,
      seed: groundTruth.seed,
      fixture_digest: groundTruth.fixture_digest,
    },
    source: {
      trace_schema: trace?.schema ?? "unknown",
      trace_reference: traceReference,
      environment: trace?.environment ?? "unknown",
      git_head: trace?.source?.gitHead ?? "",
      working_tree_clean: trace?.source?.workingTreeClean === true,
    },
    versions: groundTruth.versions,
    sample_count: runs.length,
    // Every run in one trace replays the same scenario, so the case count comes
    // from the scenario identity rather than the run count. A hundred warm runs
    // are a hundred trials of one case.
    sampling: summarizeSampling(runs.map(() => groundTruth.scenario_id)),
    metrics,
    raw_artifact_references: rawReferences,
    warnings,
    verdict,
  };
}
