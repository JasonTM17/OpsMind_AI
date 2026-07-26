import { createHash } from "node:crypto";

import { rawAnalysisShapeValid } from "./raw-analysis-contract.mjs";
import { canonicalDigest } from "./cross-service-evaluation-digests.mjs";

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
  const abstained = expected.terminal_status === "ABSTAINED";
  const displayStructured = [
    projection.runId === run.runId,
    projection.organizationId === truth.tenant_context.organization_id,
    projection.projectId === truth.tenant_context.project_id,
    projection.incidentId === run.incidentId,
    Array.isArray(run.evidenceIds)
      && JSON.stringify([...new Set(projection.evidenceIds ?? [])].sort())
        === JSON.stringify([...new Set(run.evidenceIds)].sort()),
    abstained ? displayAnalysis === null : displayAnalysis?.run_id === run.runId,
    abstained ? displayAnalysis === null
      : displayAnalysis?.model_id === truth.versions.operator_model,
    abstained ? displayAnalysis === null
      : displayAnalysis?.prompt_version === truth.versions.prompt,
    abstained ? displayAnalysis === null
      : displayAnalysis?.schema_version === truth.versions.analysis_schema,
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
    abstained ? displayAnalysis === null : displayAnalysis?.status === "complete",
    abstained
      ? displayAnalysis === null
        && evidenceIds.size === 0
        && projection.toolCalls === 0
      : hypotheses.length >= 1
        && citations.length >= expected.minimum_evidence_items
        && hypotheses.every((hypothesis) => DISPLAY_HYPOTHESIS_PATTERN.test(hypothesis?.title ?? "")),
    abstained
      ? displayAnalysis === null
      : typeof displayAnalysis?.confidence === "number"
        && displayAnalysis.confidence >= expected.minimum_confidence
        && displayAnalysis.confidence <= expected.maximum_confidence,
  ];
  const counterBindings = expected.counter_evidence_bindings ?? [];
  const rawCounterDigests = new Set((rawAnalysis?.counter_evidence ?? []).map((note) => (
    canonicalDigest(note, "opsmind.counter-evidence-note/v1").digest
  )));
  const rawCitationBindings = new Set((rawAnalysis?.citations ?? []).map((citation) => (
    `${citation.evidence_id}:${citation.digest}`
  )));
  const selectorEvidenceIsCited = (selectorDigest) => (
    (run.toolExecutions ?? []).some((execution) => (
      execution.argumentsDigest === selectorDigest
      && execution.evidenceBindings?.some((binding) => rawCitationBindings.has(
        `${binding.evidenceId}:${binding.digest}`,
      ))
    ))
  );
  const acceptedAnalyses = run.evaluationProjection?.acceptedAnalyses;
  const projectedCostValue = Array.isArray(acceptedAnalyses)
    && acceptedAnalyses.length > 0
    && acceptedAnalyses.every((analysis) => (
      typeof analysis.actualCostUsd === "number"
      && Number.isFinite(analysis.actualCostUsd)
      && analysis.actualCostUsd >= 0
    ))
    ? acceptedAnalyses.reduce((sum, analysis) => sum + analysis.actualCostUsd, 0)
    : null;
  const analysisForCost = rawAnalysis ?? displayAnalysis;
  const finalCost = analysisForCost?.cost_estimate;
  const fallbackCostValue = finalCost?.currency === "USD"
    && typeof finalCost?.amount === "number"
    && Number.isFinite(finalCost.amount)
    && finalCost.amount >= 0
    ? finalCost.amount
    : null;
  const costValue = run.evaluationProjection ? projectedCostValue : fallbackCostValue;
  const semanticRootCause = rawAnalysis && typeof rawAnalysis === "object"
    ? [
      rawAnalysis.run_id === run.runId,
      rawAnalysis.model_id === truth.versions.model,
      rawAnalysis.prompt_version === truth.versions.prompt,
      rawAnalysis.schema_version === truth.versions.analysis_schema,
      rawAnalysis.usage?.total_tokens <= projection.totalTokens,
      rawAnalysis.usage?.total_tokens <= truth.budgets.max_tokens,
      costValue !== null && costValue <= truth.budgets.max_cost_usd,
      expected.terminal_status === "ABSTAINED"
        ? rawAnalysis.status === "abstain"
          && rawAnalysis.hypotheses.length === 0
          && rawAnalysis.citations.length === 0
          && rawAnalysis.missing_evidence.length >= expected.minimum_missing_evidence_items
        : rawAnalysis.status === "complete"
          && rawAnalysis.hypotheses?.some((hypothesis) => (
            truth.expected.acceptable_rca_labels.includes(normalizeRcaLabel(hypothesis?.title))
          )),
      typeof rawAnalysis.confidence === "number"
        && rawAnalysis.confidence >= expected.minimum_confidence
        && rawAnalysis.confidence <= expected.maximum_confidence,
      rawAnalysis.counter_evidence.length >= expected.minimum_counter_evidence_items,
      counterBindings.every((binding) => rawCounterDigests.has(binding.note_digest)),
      counterBindings.every((binding) => (
        selectorEvidenceIsCited(binding.selector_arguments_digest)
      )),
    ]
    : [];
  const evidence = [
    evidenceIds.size >= expected.minimum_evidence_items,
    citations.length >= (expected.terminal_status === "ABSTAINED" ? 0 : expected.minimum_evidence_items),
    citations.every((citation) => evidenceIds.has(citation?.evidence_id)),
    citations.every((citation) => DIGEST_PATTERN.test(citation?.digest ?? "")),
  ];
  const safety = [
    Array.isArray(projection.pendingToolCalls) && projection.pendingToolCalls.length === 0,
    abstained
      ? displayAnalysis === null
      : Array.isArray(displayAnalysis?.requested_tool_calls)
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
  if (!Array.isArray(run.toolExecutions)) return null;
  if (truth.expected.expected_tool_calls === 0) {
    return [run.toolExecutions.length === 0];
  }
  if (run.toolExecutions.length === 0) return null;
  const allowedManifestVersions = new Set(
    truth.source_systems.map((source) => source.manifest_version),
  );
  const citedDigests = new Set(citations.map((citation) => citation.digest));
  const allowedSelectors = truth.expected.allowed_tool_selectors;
  const allowedSources = truth.source_systems;
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
    run.toolExecutions.every((execution) => allowedSources.some((source) => (
      execution.connectorId === source.connector_id
        && execution.connectorProfile === source.connector_profile
        && execution.connectorManifestByteDigest === source.connector_manifest_byte_digest
    ))),
    run.toolExecutions.every(
      (execution) => Array.isArray(execution.evidenceDigests)
        && execution.evidenceDigests.length > 0
        && execution.evidenceDigests.every((digest) => citedDigests.has(digest)),
    ),
    run.toolExecutions.every(
      (execution) => DIGEST_PATTERN.test(execution.argumentsDigest ?? "")
        && Array.isArray(execution.evidenceBindings)
        && execution.evidenceBindings.length === execution.evidenceDigests.length
        && execution.evidenceBindings.every((binding) => (
          DIGEST_PATTERN.test(binding.digest ?? "")
          && citedDigests.has(binding.digest)
        )),
    ),
  ];
}
