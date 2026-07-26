import {
  canonicalDigest,
  rawByteDigest,
} from "./cross-service-evaluation-digests.mjs";

const DOMAINS = {
  accepted: "opsmind.accepted-analysis-fragment/v1",
  evidence: "opsmind.evidence-metadata-fragment/v1",
  event: "opsmind.investigation-event-fragment/v1",
  export: "opsmind.cross-service-evaluation-export/v1",
  exportBytes: "opsmind.cross-service-evaluation-export.raw-bytes/v1",
  manifestBytes: "opsmind.cross-service-evaluation-query-manifest.raw-bytes/v1",
  projection: "opsmind.cross-service-evaluation-projection/v1",
  receipt: "opsmind.tool-receipt-fragment/v1",
};

function withDigest(fragment, domain) {
  return { ...fragment, fragmentDigest: canonicalDigest(fragment, domain) };
}

function projectRun(run) {
  return {
    runId: run.run_id,
    organizationId: run.organization_id,
    projectId: run.project_id,
    incidentId: run.incident_id,
    actorId: run.actor_id,
    status: run.status,
    rounds: run.rounds,
    toolCalls: run.tool_calls,
    totalTokens: run.total_tokens,
    eventCount: run.event_count,
    evidenceIds: [...run.evidence_ids].sort(),
    pendingIntents: run.pending_intents,
    terminalReason: run.terminal_reason,
    startedAt: run.started_at,
    deadlineAt: run.deadline_at,
    endedAt: run.ended_at,
  };
}

function projectTimeline(events) {
  return events.map((event) => withDigest({
    eventId: event.event_id,
    sequence: event.sequence_no,
    eventType: event.event_type,
    occurredAt: event.occurred_at,
  }, DOMAINS.event));
}

function projectAnalyses(bindings) {
  return bindings.map(({ event, invocation }) => withDigest({
    eventId: event.event_id,
    sequence: event.sequence_no,
    invocationId: invocation.invocation_id,
    provider: invocation.provider,
    modelId: invocation.model_id,
    promptVersion: invocation.prompt_version,
    schemaVersion: invocation.schema_version,
    actualTokens: invocation.actual_tokens,
    actualTools: invocation.actual_tools,
    actualCostUsd: invocation.actual_cost_usd,
    response: event.accepted_analysis,
    startedAt: invocation.started_at,
    finishedAt: invocation.finished_at,
  }, DOMAINS.accepted));
}

function projectEvidence(records) {
  return records.map((record) => withDigest({
    evidenceId: record.evidence_id,
    intentId: record.intent_id,
    executionId: record.execution_id,
    investigationEventId: record.investigation_event_id,
    gatewayAuditEventId: record.gateway_audit_event_id,
    gatewayRequestDigest: record.gateway_request_digest,
    sourceType: record.source_type,
    sourceIdentity: record.source_identity,
    targetIdentity: record.target_identity,
    observedAt: record.observed_at,
    windowStart: record.window_start,
    windowEnd: record.window_end,
    connectorVersion: record.connector_version,
    manifestVersion: record.manifest_version,
    policyVersion: record.policy_version,
    sourceProvenance: record.source_provenance,
    trustClass: record.trust_class,
    contentDigest: record.content_digest,
    redactedFields: record.redacted_fields,
    truncated: record.truncated,
    gatewayDuplicate: record.gateway_duplicate,
    createdAt: record.created_at,
  }, DOMAINS.evidence));
}

function projectReceipts(receipts) {
  return receipts.map((receipt) => withDigest({
    executionId: receipt.execution_id,
    requestDigest: receipt.request_digest,
    completedAt: receipt.completed_at,
    auditEventId: receipt.audit_event_id,
    auditOutcome: receipt.audit_outcome,
    resultDigest: receipt.result_digest,
    manifestVersion: receipt.manifest_version,
    policyVersion: receipt.policy_version,
    connector: receipt.connector,
    operation: receipt.operation,
    riskClass: receipt.risk_class,
    connectorId: receipt.connector_id,
    connectorProfile: receipt.connector_profile,
    connectorManifestByteDigest: receipt.connector_manifest_byte_digest,
    evidenceDigests: [...receipt.evidence_digests].sort(),
  }, DOMAINS.receipt));
}

export function createEvaluationProjection(validated) {
  const sourceExport = {
    classification: validated.document.evidence_classification,
    byteDigest: rawByteDigest(validated.bytes, DOMAINS.exportBytes),
    canonicalDigest: canonicalDigest(validated.document, DOMAINS.export),
    queryManifestReference: validated.document.query_manifest.reference,
    queryManifestByteDigest: {
      digest_type: "raw-bytes",
      digest_domain: DOMAINS.manifestBytes,
      digest: validated.document.query_manifest.byte_digest,
    },
  };
  const projection = {
    schemaVersion: "opsmind-cross-service-evaluation-projection-v1",
    sourceExport,
    scope: {
      organizationId: validated.document.scope.organization_id,
      projectId: validated.document.scope.project_id,
      incidentId: validated.document.scope.incident_id,
      runId: validated.document.scope.run_id,
      actorId: validated.document.scope.actor_id,
    },
    run: projectRun(validated.document.run),
    timeline: projectTimeline(validated.timeline),
    evidenceRecords: projectEvidence(validated.evidence),
    acceptedAnalyses: projectAnalyses(validated.analysisBindings),
    toolReceipts: projectReceipts(validated.receipts),
  };
  return {
    ...projection,
    canonicalDigest: canonicalDigest(projection, DOMAINS.projection),
  };
}

export { DOMAINS as EVALUATION_DIGEST_DOMAINS };
