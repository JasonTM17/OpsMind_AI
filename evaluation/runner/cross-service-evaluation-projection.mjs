import { createHash } from "node:crypto";

import { stableStringify } from "./cross-service-evaluation-digests.mjs";
import { parseAndValidateEvaluationExport } from "./cross-service-evaluation-export-contract.mjs";
import { createEvaluationProjection } from "./cross-service-evaluation-projector.mjs";
import { contractFailure } from "./evaluation-value-safety.mjs";

function sameSet(left, right) {
  return JSON.stringify([...new Set(left)].sort()) === JSON.stringify([...new Set(right)].sort());
}

function legacyAnalysisDigest(analysis) {
  return `sha256:${createHash("sha256").update(stableStringify(analysis)).digest("hex")}`;
}

export function deriveToolExecutions(projection) {
  const requestedByIntent = new Map();
  for (const accepted of projection.acceptedAnalyses ?? []) {
    for (const call of accepted.response?.requested_tool_calls ?? []) {
      if (requestedByIntent.has(call.intent_id)) {
        contractFailure("INTENT_AMBIGUITY", "A requested tool intent is not unique.");
      }
      requestedByIntent.set(call.intent_id, call);
    }
  }

  const evidenceByExecution = new Map();
  for (const evidence of projection.evidenceRecords ?? []) {
    const records = evidenceByExecution.get(evidence.executionId) ?? [];
    records.push(evidence);
    evidenceByExecution.set(evidence.executionId, records);
  }

  const executions = (projection.toolReceipts ?? []).map((receipt) => {
    const evidenceRecords = evidenceByExecution.get(receipt.executionId) ?? [];
    if (evidenceRecords.length === 0) {
      contractFailure("RECEIPT_BINDING", "A tool receipt has no persisted evidence.");
    }
    const intentIds = new Set(evidenceRecords.map((evidence) => evidence.intentId));
    if (intentIds.size !== 1) {
      contractFailure("INTENT_AMBIGUITY", "A tool execution maps to multiple intents.");
    }
    const requested = requestedByIntent.get([...intentIds][0]);
    if (!requested) {
      contractFailure("ACCEPTANCE_BINDING", "Tool evidence has no accepted tool request.");
    }
    const evidenceBindings = evidenceRecords
      .map((evidence) => ({
        evidenceId: evidence.evidenceId,
        digest: evidence.contentDigest,
      }))
      .sort((left, right) => left.evidenceId.localeCompare(right.evidenceId));
    const projectedDigests = evidenceBindings.map((binding) => binding.digest).sort();
    if (!sameSet(projectedDigests, receipt.evidenceDigests)) {
      contractFailure("RECEIPT_BINDING", "Receipt evidence digests do not match persisted evidence.");
    }
    evidenceByExecution.delete(receipt.executionId);
    return {
      executionId: receipt.executionId,
      status: receipt.auditOutcome,
      connector: receipt.connector,
      operation: receipt.operation,
      riskClass: receipt.riskClass,
      manifestVersion: receipt.manifestVersion,
      connectorId: receipt.connectorId,
      connectorProfile: receipt.connectorProfile,
      connectorManifestByteDigest: receipt.connectorManifestByteDigest,
      argumentsDigest: requested.arguments_digest,
      evidenceDigests: projectedDigests,
      evidenceBindings,
    };
  });
  if (evidenceByExecution.size !== 0) {
    contractFailure("RECEIPT_BINDING", "Persisted tool evidence has no matching receipt.");
  }
  return executions;
}

function validateTraceBinding(run, projection) {
  const identityChecks = [
    run.runId === projection.scope.runId,
    run.organizationId === projection.scope.organizationId,
    run.projectId === projection.scope.projectId,
    run.incidentId === projection.scope.incidentId,
    run.status === projection.run.status,
    sameSet(run.evidenceIds ?? [], projection.run.evidenceIds),
  ];
  const operator = run.operatorProjection;
  if (operator) {
    identityChecks.push(
      operator.runId === projection.run.runId,
      operator.status === projection.run.status,
      operator.rounds === projection.run.rounds,
      operator.toolCalls === projection.run.toolCalls,
      operator.totalTokens === projection.run.totalTokens,
    );
  }
  if (identityChecks.some((value) => !value)) {
    contractFailure("TRACE_BINDING", "Evaluation export does not match its trace run.");
  }
}

export function projectCrossServiceEvaluationExport(rawBytes) {
  return createEvaluationProjection(parseAndValidateEvaluationExport(rawBytes));
}

export function enrichTraceWithEvaluationExports(trace, rawExports, traceReference) {
  if (!trace || typeof trace !== "object" || !Array.isArray(trace.runs)) {
    contractFailure("TRACE_SHAPE", "Cross-service trace is invalid.");
  }
  if (!Array.isArray(rawExports) || rawExports.length !== trace.runs.length) {
    contractFailure("EXPORT_COUNT", "Every trace run requires exactly one export.");
  }
  const projections = rawExports.map(projectCrossServiceEvaluationExport);
  const byRun = new Map(projections.map((projection) => [projection.scope.runId, projection]));
  if (byRun.size !== projections.length) {
    contractFailure("DUPLICATE_ROW", "Multiple exports target the same run.");
  }
  const enriched = structuredClone(trace);
  enriched.runs.forEach((run, index) => {
    const projection = byRun.get(run.runId);
    if (!projection) contractFailure("FOREIGN_SCOPE", "Trace run has no scoped export.");
    validateTraceBinding(run, projection);
    run.evaluationProjection = projection;
    run.toolExecutions = deriveToolExecutions(projection);
    const finalAnalysis = projection.acceptedAnalyses.at(-1)?.response;
    run.rawAnalysis = finalAnalysis;
    run.rawAnalysisReference = `${traceReference}#/runs/${index}/rawAnalysis`;
    run.rawAnalysisDigest = legacyAnalysisDigest(finalAnalysis);
    byRun.delete(run.runId);
  });
  if (byRun.size !== 0) contractFailure("FOREIGN_SCOPE", "Export targets an unknown trace run.");
  return enriched;
}
