import {
  canonicalDigest,
  digestReferenceValid,
  stableStringify,
} from "./cross-service-evaluation-digests.mjs";
import { EVALUATION_DIGEST_DOMAINS as DOMAINS } from "./cross-service-evaluation-projector.mjs";
import { deriveToolExecutions } from "./cross-service-evaluation-projection.mjs";

function exactKeys(value, keys) {
  return value
    && typeof value === "object"
    && !Array.isArray(value)
    && JSON.stringify(Object.keys(value).sort()) === JSON.stringify([...keys].sort());
}

function fragmentValid(value, keys, domain) {
  if (!exactKeys(value, [...keys, "fragmentDigest"])) return false;
  const { fragmentDigest, ...fragment } = value;
  return digestReferenceValid(fragmentDigest, "canonical-json", domain)
    && stableStringify(fragmentDigest) === stableStringify(canonicalDigest(fragment, domain));
}

export function evaluationProjectionIntegrityChecks(run) {
  const projection = run.evaluationProjection;
  if (!exactKeys(projection, [
    "schemaVersion", "sourceExport", "scope", "run", "timeline",
    "evidenceRecords", "acceptedAnalyses", "toolReceipts", "canonicalDigest",
  ])) return [false];
  const { canonicalDigest: observedDigest, ...unsigned } = projection;
  const source = projection.sourceExport;
  const scope = projection.scope;
  const snapshot = projection.run;
  const checks = [
    projection.schemaVersion === "opsmind-cross-service-evaluation-projection-v1",
    exactKeys(source, [
      "classification", "byteDigest", "canonicalDigest",
      "queryManifestReference", "queryManifestByteDigest",
    ]),
    ["REGRESSION_SNAPSHOT_NOT_PRODUCTION_PATH", "TRANSIENT_SYNTHETIC_CROSS_SERVICE_EXPORT"]
      .includes(source?.classification),
    digestReferenceValid(source?.byteDigest, "raw-bytes", DOMAINS.exportBytes),
    digestReferenceValid(source?.canonicalDigest, "canonical-json", DOMAINS.export),
    digestReferenceValid(source?.queryManifestByteDigest, "raw-bytes", DOMAINS.manifestBytes),
    digestReferenceValid(observedDigest, "canonical-json", DOMAINS.projection),
    stableStringify(observedDigest)
      === stableStringify(canonicalDigest(unsigned, DOMAINS.projection)),
    exactKeys(scope, ["organizationId", "projectId", "incidentId", "runId", "actorId"]),
    scope?.runId === run.runId,
    scope?.organizationId === run.organizationId,
    scope?.projectId === run.projectId,
    scope?.incidentId === run.incidentId,
    snapshot?.runId === run.runId,
    snapshot?.status === run.status,
    snapshot?.rounds === projection.acceptedAnalyses?.length,
    snapshot?.eventCount === projection.timeline?.length,
    snapshot?.toolCalls === projection.toolReceipts?.length,
    snapshot?.totalTokens === run.operatorProjection?.totalTokens,
    snapshot?.totalTokens === projection.acceptedAnalyses?.reduce(
      (total, accepted) => total + accepted.response.usage.total_tokens,
      0,
    ),
    snapshot?.totalTokens === projection.acceptedAnalyses?.reduce(
      (total, accepted) => total + accepted.actualTokens,
      0,
    ),
    snapshot?.toolCalls === projection.acceptedAnalyses?.reduce(
      (total, accepted) => total + accepted.actualTools,
      0,
    ),
    stableStringify(snapshot?.evidenceIds) === stableStringify([...(run.evidenceIds ?? [])].sort()),
    stableStringify(run.rawAnalysis) === stableStringify(
      projection.acceptedAnalyses?.at(-1)?.response,
    ),
    stableStringify(run.toolExecutions) === stableStringify(
      deriveToolExecutions(projection),
    ),
  ];
  checks.push(...(projection.timeline ?? []).map((value) => fragmentValid(
    value, ["eventId", "sequence", "eventType", "occurredAt"], DOMAINS.event,
  )));
  checks.push(...(projection.acceptedAnalyses ?? []).map((value) => fragmentValid(
    value,
    [
      "eventId", "sequence", "invocationId", "provider", "modelId",
      "promptVersion", "schemaVersion", "actualTokens", "actualTools",
      "actualCostUsd", "response", "startedAt", "finishedAt",
    ],
    DOMAINS.accepted,
  )));
  checks.push(...(projection.evidenceRecords ?? []).map((value) => fragmentValid(
    value,
    [
      "evidenceId", "intentId", "executionId", "investigationEventId",
      "gatewayAuditEventId", "gatewayRequestDigest", "sourceType", "sourceIdentity",
      "targetIdentity", "observedAt", "windowStart", "windowEnd", "connectorVersion",
      "manifestVersion", "policyVersion", "sourceProvenance", "trustClass",
      "contentDigest", "redactedFields", "truncated", "gatewayDuplicate", "createdAt",
    ],
    DOMAINS.evidence,
  )));
  checks.push(...(projection.toolReceipts ?? []).map((value) => fragmentValid(
    value,
    [
      "executionId", "requestDigest", "completedAt", "auditEventId", "auditOutcome",
      "resultDigest", "manifestVersion", "policyVersion", "connector", "operation",
      "riskClass", "connectorId", "connectorProfile",
      "connectorManifestByteDigest", "evidenceDigests",
    ],
    DOMAINS.receipt,
  )));
  return checks;
}
