import { rawAnalysisShapeValid } from "./raw-analysis-contract.mjs";
import {
  boundedArray,
  boundedInteger,
  boundedString,
  contractFailure,
  exactObject,
  RFC_9562_UUID,
} from "./evaluation-value-safety.mjs";
import { sha256DigestValid } from "./cross-service-evaluation-digests.mjs";

const UUID = RFC_9562_UUID;
const DATE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/u;
const EVENT_TYPES = new Set([
  "RUN_STARTED", "ANALYSIS_ACCEPTED", "TOOL_REQUESTED", "EVIDENCE_APPENDED",
  "COMPLETED", "ABSTAINED", "BUDGET_EXCEEDED", "NO_PROGRESS", "FAILED",
]);
const CONNECTOR_PROFILES = new Map([
  ["fixture-observability", "fixture"],
  ["prometheus-read-only", "prometheus"],
]);

function uuid(value, label) {
  if (!UUID.test(value ?? "")) contractFailure("INVALID_IDENTITY", `${label} is not a UUID.`);
}

function dateTime(value, label, nullable = false) {
  if (nullable && value === null) return;
  if (!DATE_TIME.test(value ?? "") || !Number.isFinite(Date.parse(value))) {
    contractFailure("INVALID_VALUE", `${label} is not a date-time.`);
  }
}

function digest(value, label) {
  if (!sha256DigestValid(value)) contractFailure("INVALID_DIGEST", `${label} is invalid.`);
}

export function validateRun(run) {
  const keys = [
    "run_id", "organization_id", "project_id", "incident_id", "actor_id", "status",
    "rounds", "tool_calls", "total_tokens", "event_count", "evidence_ids",
    "pending_intents", "terminal_reason", "started_at", "deadline_at", "ended_at",
  ];
  exactObject(run, keys, "run");
  for (const key of ["run_id", "organization_id", "project_id", "incident_id", "actor_id"]) {
    uuid(run[key], `run.${key}`);
  }
  if (!["COMPLETED", "ABSTAINED"].includes(run.status)) {
    contractFailure("INVALID_TERMINAL", "run.status is not scoreable.");
  }
  boundedInteger(run.rounds, 20, "run.rounds", 1);
  boundedInteger(run.tool_calls, 20, "run.tool_calls");
  boundedInteger(run.total_tokens, 1_000_000, "run.total_tokens");
  boundedInteger(run.event_count, 500, "run.event_count", 2);
  boundedArray(run.evidence_ids, 200, "run.evidence_ids").forEach((value) => uuid(value, "evidence id"));
  boundedArray(run.pending_intents, 20, "run.pending_intents");
  if (run.pending_intents.length !== 0) contractFailure("UNSAFE_ACTION", "Terminal run has pending intents.");
  if (!(run.terminal_reason === null
    || boundedString(run.terminal_reason, 2000, "run.terminal_reason"))) {
    contractFailure("INVALID_VALUE", "run.terminal_reason is invalid.");
  }
  dateTime(run.started_at, "run.started_at");
  dateTime(run.deadline_at, "run.deadline_at");
  dateTime(run.ended_at, "run.ended_at");
}

export function validateEvent(event, runId) {
  exactObject(event, [
    "event_id", "sequence_no", "event_type", "occurred_at", "accepted_analysis",
  ], "event");
  uuid(event.event_id, "event.event_id");
  boundedInteger(event.sequence_no, 500, "event.sequence_no", 1);
  if (!EVENT_TYPES.has(event.event_type)) contractFailure("INVALID_VALUE", "event type is invalid.");
  dateTime(event.occurred_at, "event.occurred_at");
  const isAccepted = event.event_type === "ANALYSIS_ACCEPTED";
  if (isAccepted !== (event.accepted_analysis !== null)) {
    contractFailure("ACCEPTANCE_BINDING", "Accepted analysis presence does not match event type.");
  }
  if (isAccepted && (!rawAnalysisShapeValid(event.accepted_analysis)
    || event.accepted_analysis.run_id !== runId)) {
    contractFailure("ACCEPTANCE_BINDING", "Accepted analysis is invalid or foreign.");
  }
}

export function validateInvocation(invocation, scope) {
  exactObject(invocation, [
    "invocation_id", "organization_id", "incident_id", "run_id", "state",
    "response_status", "response_payload", "provider", "model_id", "prompt_version",
    "schema_version", "actual_tokens", "actual_tools", "actual_cost_usd",
    "started_at", "finished_at",
  ], "analysis invocation");
  for (const key of ["invocation_id", "organization_id", "incident_id", "run_id"]) {
    uuid(invocation[key], `invocation.${key}`);
  }
  if (invocation.organization_id !== scope.organization_id
    || invocation.incident_id !== scope.incident_id
    || invocation.run_id !== scope.run_id
    || invocation.state !== "succeeded"
    || invocation.response_status !== invocation.response_payload?.status
    || !rawAnalysisShapeValid(invocation.response_payload)
    || invocation.response_payload.run_id !== scope.run_id) {
    contractFailure("INVOCATION_SCOPE", "Analysis invocation is not exactly bound to scope.");
  }
  boundedString(invocation.provider, 64, "invocation.provider");
  boundedString(invocation.model_id, 160, "invocation.model_id");
  boundedString(invocation.prompt_version, 128, "invocation.prompt_version");
  boundedString(invocation.schema_version, 128, "invocation.schema_version");
  boundedInteger(invocation.actual_tokens, 1_000_000, "invocation.actual_tokens");
  boundedInteger(invocation.actual_tools, 20, "invocation.actual_tools");
  if (typeof invocation.actual_cost_usd !== "number"
    || !Number.isFinite(invocation.actual_cost_usd)
    || invocation.actual_cost_usd < 0
    || invocation.actual_cost_usd > 1000) {
    contractFailure("INVALID_VALUE", "invocation.actual_cost_usd is invalid.");
  }
  dateTime(invocation.started_at, "invocation.started_at");
  dateTime(invocation.finished_at, "invocation.finished_at");
  const response = invocation.response_payload;
  if (invocation.model_id !== response.model_id
    || invocation.prompt_version !== response.prompt_version
    || invocation.schema_version !== response.schema_version
    || invocation.actual_tokens !== response.usage.total_tokens
    || invocation.actual_tools !== response.requested_tool_calls.length
    || invocation.actual_cost_usd !== response.cost_estimate.amount
    || Date.parse(invocation.finished_at) < Date.parse(invocation.started_at)) {
    contractFailure(
      "INVOCATION_BINDING",
      "Invocation identity, accounting, or timestamps do not match its accepted response.",
    );
  }
}

export function validateEvidence(record, scope) {
  const keys = [
    "evidence_id", "organization_id", "project_id", "incident_id", "run_id", "actor_id",
    "intent_id", "execution_id", "investigation_event_id", "gateway_audit_event_id",
    "gateway_request_digest", "source_type", "source_identity", "target_identity",
    "observed_at", "window_start", "window_end", "connector_version", "manifest_version",
    "policy_version", "source_provenance", "trust_class", "content_digest",
    "redacted_fields", "truncated", "gateway_duplicate", "created_at",
  ];
  exactObject(record, keys, "evidence record");
  for (const key of [
    "evidence_id", "organization_id", "project_id", "incident_id", "run_id", "actor_id",
    "intent_id", "execution_id", "investigation_event_id", "gateway_audit_event_id",
  ]) uuid(record[key], `evidence.${key}`);
  for (const key of ["organization_id", "project_id", "incident_id", "run_id", "actor_id"]) {
    if (record[key] !== scope[key]) contractFailure("FOREIGN_SCOPE", `evidence.${key} is foreign.`);
  }
  digest(record.gateway_request_digest, "evidence.gateway_request_digest");
  digest(record.content_digest, "evidence.content_digest");
  for (const key of [
    "source_type", "source_identity", "target_identity", "connector_version",
    "manifest_version", "policy_version", "source_provenance", "trust_class",
  ]) boundedString(record[key], 256, `evidence.${key}`);
  for (const key of ["observed_at", "window_start", "window_end", "created_at"]) {
    dateTime(record[key], `evidence.${key}`);
  }
  boundedInteger(record.redacted_fields, 10_000, "evidence.redacted_fields");
  if (typeof record.truncated !== "boolean" || typeof record.gateway_duplicate !== "boolean") {
    contractFailure("INVALID_VALUE", "Evidence flags are invalid.");
  }
}

export function validateReceipt(receipt, scope) {
  const keys = [
    "execution_id", "tenant_id", "project_id", "incident_id", "run_id",
    "request_digest", "status", "completed_at", "audit_event_id", "audit_outcome",
    "audit_request_digest", "result_digest", "manifest_version", "policy_version",
    "denial_code", "connector", "operation", "risk_class",
    "connector_id", "connector_profile",
    "connector_manifest_byte_digest", "evidence_digests",
  ];
  exactObject(receipt, keys, "tool receipt");
  for (const key of ["execution_id", "tenant_id", "project_id", "incident_id", "run_id", "audit_event_id"]) {
    uuid(receipt[key], `receipt.${key}`);
  }
  const aliases = { tenant_id: "organization_id", project_id: "project_id", incident_id: "incident_id", run_id: "run_id" };
  for (const [key, scopeKey] of Object.entries(aliases)) {
    if (receipt[key] !== scope[scopeKey]) contractFailure("FOREIGN_SCOPE", `receipt.${key} is foreign.`);
  }
  for (const key of ["request_digest", "audit_request_digest", "result_digest", "connector_manifest_byte_digest"]) {
    digest(receipt[key], `receipt.${key}`);
  }
  if (receipt.request_digest !== receipt.audit_request_digest
    || receipt.status !== "COMPLETED"
    || !["SUCCEEDED", "DUPLICATE"].includes(receipt.audit_outcome)
    || receipt.denial_code !== null) {
    contractFailure("RECEIPT_BINDING", "Receipt and audit proof do not agree.");
  }
  for (const key of [
    "manifest_version", "policy_version", "connector", "operation", "risk_class",
    "connector_id", "connector_profile",
  ]) {
    boundedString(receipt[key], 128, `receipt.${key}`);
  }
  if (receipt.connector !== "observability"
    || receipt.operation !== "metrics.query"
    || receipt.risk_class !== "read-only") {
    contractFailure("CONNECTOR_SUBSTITUTION", "Tool selector is not allowlisted.");
  }
  if (CONNECTOR_PROFILES.get(receipt.connector_id) !== receipt.connector_profile) {
    contractFailure("CONNECTOR_SUBSTITUTION", "Connector identity/profile is not allowlisted.");
  }
  dateTime(receipt.completed_at, "receipt.completed_at");
  const evidence = boundedArray(receipt.evidence_digests, 200, "receipt.evidence_digests");
  if (evidence.length !== 1) {
    contractFailure("RECEIPT_BINDING", "Receipt must bind exactly one evidence digest.");
  }
  evidence.forEach((value) => digest(value, "receipt.evidence_digest"));
  if (new Set(evidence).size !== evidence.length) {
    contractFailure("RECEIPT_BINDING", "Receipt evidence digests are not unique.");
  }
  if (receipt.result_digest !== evidence[0]) {
    contractFailure(
      "RECEIPT_BINDING",
      "Durable audit result digest does not match receipt evidence.",
    );
  }
}
