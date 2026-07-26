import { stableStringify, sha256DigestValid } from "./cross-service-evaluation-digests.mjs";
import {
  validateEvent,
  validateEvidence,
  validateInvocation,
  validateReceipt,
  validateRun,
} from "./cross-service-evaluation-export-rows.mjs";
import {
  boundedArray,
  boundedString,
  contractFailure,
  exactObject,
  parseUntrustedJsonExport,
  RFC_9562_UUID,
} from "./evaluation-value-safety.mjs";

const UUID = RFC_9562_UUID;

function uniqueBy(items, key, label) {
  const values = items.map((item) => item[key]);
  if (new Set(values).size !== values.length) {
    contractFailure("DUPLICATE_ROW", `${label} contains duplicate ${key}.`);
  }
}

function sorted(values) {
  return [...values].sort();
}

function validateEnvelope(document) {
  exactObject(document, [
    "schema_version", "evidence_classification", "query_manifest", "scope", "run", "events",
    "evidence_records", "analysis_invocations", "tool_receipts",
  ], "evaluation export");
  if (document.schema_version !== "opsmind-cross-service-evaluation-export-v1") {
    contractFailure("SCHEMA_VERSION", "Evaluation export schema version is unsupported.");
  }
  if (![
    "REGRESSION_SNAPSHOT_NOT_PRODUCTION_PATH",
    "TRANSIENT_SYNTHETIC_CROSS_SERVICE_EXPORT",
  ].includes(document.evidence_classification)) {
    contractFailure("EVIDENCE_CLASSIFICATION", "Evaluation export classification is invalid.");
  }
  exactObject(document.query_manifest, ["reference", "byte_digest"], "query_manifest");
  boundedString(document.query_manifest.reference, 1024, "query_manifest.reference");
  if (!document.query_manifest.reference.startsWith("repository://scripts/validation/cross-service/")
    || !sha256DigestValid(document.query_manifest.byte_digest)) {
    contractFailure("QUERY_MANIFEST", "Query manifest binding is invalid.");
  }
  exactObject(document.scope, [
    "organization_id", "project_id", "incident_id", "run_id", "actor_id",
  ], "scope");
  for (const [key, value] of Object.entries(document.scope)) {
    if (!UUID.test(value ?? "")) contractFailure("INVALID_IDENTITY", `scope.${key} is invalid.`);
  }
}

function validateScope(run, scope) {
  for (const key of ["organization_id", "project_id", "incident_id", "run_id", "actor_id"]) {
    if (run[key] !== scope[key]) contractFailure("FOREIGN_SCOPE", `run.${key} is foreign.`);
  }
}

function validateTimeline(document) {
  const events = boundedArray(document.events, 500, "events");
  if (events.length !== document.run.event_count) {
    contractFailure("EVENT_GAP", "Event count does not match the run snapshot.");
  }
  events.forEach((event) => validateEvent(event, document.scope.run_id));
  uniqueBy(events, "event_id", "events");
  const ordered = [...events].sort((left, right) => left.sequence_no - right.sequence_no);
  if (ordered.some((event, index) => event.sequence_no !== index + 1)) {
    contractFailure("EVENT_GAP", "Event sequence is not contiguous.");
  }
  const accepted = ordered.filter((event) => event.event_type === "ANALYSIS_ACCEPTED");
  if (accepted.length !== document.run.rounds) {
    contractFailure("ACCEPTANCE_BINDING", "Accepted analysis count does not match rounds.");
  }
  const expectedTerminal = document.run.status === "COMPLETED" ? "COMPLETED" : "ABSTAINED";
  if (ordered.at(-1)?.event_type !== expectedTerminal) {
    contractFailure("INVALID_TERMINAL", "Terminal event does not match run status.");
  }
  const lastAnalysis = accepted.at(-1)?.accepted_analysis;
  if ((expectedTerminal === "COMPLETED" && lastAnalysis?.status !== "complete")
    || (expectedTerminal === "ABSTAINED" && lastAnalysis?.status !== "abstain")) {
    contractFailure("INVALID_TERMINAL", "Terminal analysis status does not match run status.");
  }
  return { ordered, accepted };
}

function bindInvocations(document, accepted) {
  const invocations = boundedArray(document.analysis_invocations, 20, "analysis_invocations");
  invocations.forEach((item) => validateInvocation(item, document.scope));
  uniqueBy(invocations, "invocation_id", "analysis_invocations");
  if (invocations.length !== accepted.length) {
    contractFailure("INVOCATION_AMBIGUITY", "Invocation count does not match accepted analyses.");
  }
  const unused = new Set(invocations.map((item) => item.invocation_id));
  const bindings = accepted.map((event) => {
    const matches = invocations.filter((invocation) => (
      stableStringify(invocation.response_payload) === stableStringify(event.accepted_analysis)
    ));
    if (matches.length !== 1 || !unused.delete(matches[0].invocation_id)) {
      contractFailure("INVOCATION_AMBIGUITY", "Accepted analysis has zero or multiple invocation matches.");
    }
    return { event, invocation: matches[0] };
  });
  if (unused.size !== 0) contractFailure("INVOCATION_AMBIGUITY", "Unbound invocation exists.");
  return bindings;
}

function bindEvidenceAndReceipts(document, ordered) {
  const evidence = boundedArray(document.evidence_records, 200, "evidence_records");
  evidence.forEach((item) => validateEvidence(item, document.scope));
  uniqueBy(evidence, "evidence_id", "evidence_records");
  uniqueBy(evidence, "investigation_event_id", "evidence_records");
  uniqueBy(evidence, "execution_id", "evidence_records");
  uniqueBy(evidence, "gateway_audit_event_id", "evidence_records");
  if (JSON.stringify(sorted(evidence.map((item) => item.evidence_id)))
    !== JSON.stringify(sorted(document.run.evidence_ids))) {
    contractFailure("EVIDENCE_BINDING", "Evidence rows do not match the run snapshot.");
  }
  const eventById = new Map(ordered.map((event) => [event.event_id, event]));
  if (evidence.some((item) => (
    eventById.get(item.investigation_event_id)?.event_type !== "EVIDENCE_APPENDED"
  ))) contractFailure("EVIDENCE_BINDING", "Evidence row is not bound to an append event.");

  const receipts = boundedArray(document.tool_receipts, 20, "tool_receipts");
  receipts.forEach((item) => validateReceipt(item, document.scope));
  uniqueBy(receipts, "execution_id", "tool_receipts");
  uniqueBy(receipts, "audit_event_id", "tool_receipts");
  if (receipts.length !== document.run.tool_calls) {
    contractFailure("RECEIPT_BINDING", "Receipt count does not match tool-call count.");
  }
  if (receipts.length !== evidence.length) {
    contractFailure(
      "RECEIPT_BINDING",
      "Each successful receipt must bind exactly one evidence record.",
    );
  }
  const receiptByExecution = new Map(receipts.map((item) => [item.execution_id, item]));
  for (const item of evidence) {
    const receipt = receiptByExecution.get(item.execution_id);
    if (!receipt
      || receipt.audit_event_id !== item.gateway_audit_event_id
      || receipt.request_digest !== item.gateway_request_digest
      || receipt.result_digest !== item.content_digest
      || receipt.evidence_digests[0] !== item.content_digest) {
      contractFailure("RECEIPT_BINDING", "Evidence metadata does not match its receipt and audit.");
    }
  }
  return { evidence, receipts };
}

export function parseAndValidateEvaluationExport(rawBytes) {
  const parsed = parseUntrustedJsonExport(rawBytes);
  validateEnvelope(parsed.document);
  validateRun(parsed.document.run);
  validateScope(parsed.document.run, parsed.document.scope);
  const timeline = validateTimeline(parsed.document);
  const analysisBindings = bindInvocations(parsed.document, timeline.accepted);
  const records = bindEvidenceAndReceipts(parsed.document, timeline.ordered);
  return { ...parsed, timeline: timeline.ordered, analysisBindings, ...records };
}
