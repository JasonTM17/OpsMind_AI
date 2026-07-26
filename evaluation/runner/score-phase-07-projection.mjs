import { createHash } from "node:crypto";

import { rawAnalysisShapeValid } from "./raw-analysis-contract.mjs";

const DIGEST_PATTERN = /^sha256:[a-f0-9]{64}$/u;
const DISPLAY_HYPOTHESIS_PATTERN = /^Evidence-backed hypothesis [1-9][0-9]*$/u;

export function normalizeRcaLabel(value) {
  return String(value ?? "")
    .normalize("NFKD")
    .replace(/[^\p{Letter}\p{Number}]+/gu, "-")
    .replace(/^-+|-+$/gu, "")
    .toLowerCase();
}

export function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => (
      `${JSON.stringify(key)}:${stableStringify(value[key])}`
    )).join(",")}}`;
  }
  return JSON.stringify(value);
}

export function isTrustedRawAnalysis(run, expectedReference) {
  if (
    !rawAnalysisShapeValid(run.rawAnalysis)
    || typeof run.rawAnalysisReference !== "string"
    || run.rawAnalysisReference !== expectedReference
    || !DIGEST_PATTERN.test(run.rawAnalysisDigest ?? "")
  ) {
    return false;
  }
  const digest = createHash("sha256")
    .update(stableStringify(run.rawAnalysis))
    .digest("hex");
  return run.rawAnalysisDigest === `sha256:${digest}`;
}

export function projectionChecks(run, truth) {
  const projection = run.operatorProjection;
  if (!projection || typeof projection !== "object") return null;
  const displayAnalysis = projection.analysis;
  const rawAnalysis = run.rawAnalysis;
  const expected = truth.expected;
  const hypotheses = Array.isArray(displayAnalysis?.hypotheses)
    ? displayAnalysis.hypotheses
    : [];
  const citations = Array.isArray(displayAnalysis?.citations)
    ? displayAnalysis.citations
    : [];
  const evidenceIds = new Set(
    Array.isArray(projection.evidenceIds) ? projection.evidenceIds : [],
  );
  const displayStructured = [
    projection.runId === run.runId,
    projection.organizationId === truth.tenant_context.organization_id,
    projection.projectId === truth.tenant_context.project_id,
    projection.incidentId === run.incidentId,
    Array.isArray(run.evidenceIds)
      && JSON.stringify([...new Set(projection.evidenceIds ?? [])].sort())
        === JSON.stringify([...new Set(run.evidenceIds)].sort()),
    displayAnalysis?.run_id === run.runId,
    displayAnalysis?.model_id === truth.versions.operator_model,
    displayAnalysis?.prompt_version === truth.versions.prompt,
    displayAnalysis?.schema_version === truth.versions.analysis_schema,
    Number.isInteger(projection.rounds)
      && projection.rounds >= 0
      && projection.rounds <= truth.budgets.max_rounds,
    Number.isInteger(projection.toolCalls)
      && projection.toolCalls >= 0
      && projection.toolCalls <= truth.budgets.max_tool_calls,
    evidenceIds.size <= truth.budgets.max_evidence_items,
    Number.isInteger(projection.totalTokens)
      && projection.totalTokens >= 0
      && projection.totalTokens <= truth.budgets.max_tokens,
  ];
  const displayRootCause = [
    projection.status === expected.terminal_status,
    hypotheses.length >= expected.minimum_evidence_items,
    hypotheses.every((hypothesis) => DISPLAY_HYPOTHESIS_PATTERN.test(hypothesis?.title ?? "")),
    typeof displayAnalysis?.confidence === "number"
      && displayAnalysis.confidence >= expected.minimum_confidence,
  ];
  const semanticRootCause = rawAnalysis && typeof rawAnalysis === "object"
    ? [
      rawAnalysis.run_id === run.runId,
      rawAnalysis.model_id === truth.versions.model,
      rawAnalysis.prompt_version === truth.versions.prompt,
      rawAnalysis.schema_version === truth.versions.analysis_schema,
      rawAnalysis.usage?.total_tokens === projection.totalTokens,
      rawAnalysis.usage?.total_tokens <= truth.budgets.max_tokens,
      rawAnalysis.cost_estimate?.amount <= truth.budgets.max_cost_usd,
      rawAnalysis.hypotheses?.some((hypothesis) => (
        truth.expected.acceptable_rca_labels.includes(normalizeRcaLabel(hypothesis?.title))
      )),
      typeof rawAnalysis.confidence === "number"
        && rawAnalysis.confidence >= expected.minimum_confidence,
    ]
    : [];
  const evidence = [
    evidenceIds.size >= expected.minimum_evidence_items,
    citations.length >= expected.minimum_evidence_items,
    citations.every((citation) => evidenceIds.has(citation?.evidence_id)),
    citations.every((citation) => DIGEST_PATTERN.test(citation?.digest ?? "")),
  ];
  const analysisForCost = rawAnalysis ?? displayAnalysis;
  const cost = analysisForCost?.cost_estimate;
  const costValue = cost?.currency === "USD" && typeof cost?.amount === "number"
    ? cost.amount
    : null;
  const safety = [
    Array.isArray(projection.pendingToolCalls) && projection.pendingToolCalls.length === 0,
    Array.isArray(displayAnalysis?.requested_tool_calls)
      && displayAnalysis.requested_tool_calls.length === 0,
    Array.isArray(rawAnalysis?.requested_tool_calls)
      ? rawAnalysis.requested_tool_calls.length === 0
      : rawAnalysis === undefined,
    truth.source_systems.every((source) => source.read_only === true),
    truth.training_eligible === false,
  ];
  return {
    structured: displayStructured,
    rootCause: displayRootCause,
    semanticRootCause,
    evidence,
    safety,
    costValue,
    citations,
  };
}

export function toolChecks(run, truth, citations) {
  if (!Array.isArray(run.toolExecutions) || run.toolExecutions.length === 0) return null;
  const allowedManifestVersions = new Set(
    truth.source_systems.map((source) => source.manifest_version),
  );
  const citedDigests = new Set(citations.map((citation) => citation.digest));
  const allowedSelectors = truth.expected.allowed_tool_selectors;
  return [
    run.toolExecutions.length === truth.expected.expected_tool_calls,
    run.toolExecutions.every((execution) => execution.status === "SUCCEEDED"),
    run.toolExecutions.every(
      (execution) => allowedManifestVersions.has(execution.manifestVersion),
    ),
    run.toolExecutions.every((execution) => allowedSelectors.some((selector) => (
      execution.connector === selector.connector
      && execution.operation === selector.operation
      && execution.riskClass === selector.risk_class
    ))),
    run.toolExecutions.every(
      (execution) => Array.isArray(execution.evidenceDigests)
        && execution.evidenceDigests.length > 0
        && execution.evidenceDigests.every((digest) => citedDigests.has(digest)),
    ),
  ];
}
