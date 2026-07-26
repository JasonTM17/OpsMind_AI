const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const DIGEST_PATTERN = /^sha256:[a-f0-9]{64}$/u;
const TOOL_CONNECTORS = new Set(["metrics", "logs", "traces", "changes", "runbooks"]);
const MAX_PROMPT_TOKENS = 100_000;
const MAX_COMPLETION_TOKENS = 100_000;
const MAX_TOTAL_TOKENS = 200_000;
const MAX_COST_USD = 1_000_000;

function exactKeys(value, required, optional = []) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const allowed = new Set([...required, ...optional]);
  return required.every((key) => Object.hasOwn(value, key))
    && Object.keys(value).every((key) => allowed.has(key));
}

function boundedString(value, maxLength) {
  return typeof value === "string" && value.length >= 1 && value.length <= maxLength;
}

function boundedConfidence(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1;
}

function boundedInteger(value, max) {
  return Number.isInteger(value) && value >= 0 && value <= max;
}

function validCitation(value) {
  return exactKeys(value, ["evidence_id", "digest", "claim"])
    && UUID_PATTERN.test(value.evidence_id)
    && DIGEST_PATTERN.test(value.digest)
    && boundedString(value.claim, 1024);
}

function validHypothesis(value) {
  return exactKeys(value, ["title", "explanation", "confidence"], ["citations"])
    && boundedString(value.title, 256)
    && boundedString(value.explanation, 4096)
    && boundedConfidence(value.confidence)
    && (value.citations === undefined
      || (Array.isArray(value.citations)
        && value.citations.length <= 50
        && value.citations.every(validCitation)));
}

function validToolIntent(value) {
  return exactKeys(value, [
    "intent_id",
    "connector",
    "operation",
    "arguments_digest",
    "rationale",
  ])
    && UUID_PATTERN.test(value.intent_id)
    && TOOL_CONNECTORS.has(value.connector)
    && boundedString(value.operation, 256)
    && DIGEST_PATTERN.test(value.arguments_digest)
    && boundedString(value.rationale, 1024);
}

function validUsage(value) {
  return exactKeys(value, ["prompt_tokens", "completion_tokens", "total_tokens"])
    && boundedInteger(value.prompt_tokens, MAX_PROMPT_TOKENS)
    && boundedInteger(value.completion_tokens, MAX_COMPLETION_TOKENS)
    && boundedInteger(value.total_tokens, MAX_TOTAL_TOKENS)
    && value.total_tokens === value.prompt_tokens + value.completion_tokens;
}

function validCost(value) {
  return exactKeys(value, ["currency", "amount"])
    && value.currency === "USD"
    && typeof value.amount === "number"
    && Number.isFinite(value.amount)
    && value.amount >= 0
    && value.amount <= MAX_COST_USD;
}

function statusShapeValid(analysis) {
  if (analysis.status === "complete") {
    return analysis.hypotheses.length > 0
      && analysis.citations.length > 0
      && analysis.hypotheses.every((hypothesis) => (
        Array.isArray(hypothesis.citations) && hypothesis.citations.length > 0
      ))
      && analysis.requested_tool_calls.length === 0;
  }
  if (analysis.status === "abstain") {
    return analysis.hypotheses.length === 0
      && analysis.citations.length === 0
      && analysis.requested_tool_calls.length === 0
      && analysis.missing_evidence.length > 0;
  }
  if (analysis.status === "need_more_evidence") {
    return analysis.requested_tool_calls.length > 0 || analysis.missing_evidence.length > 0;
  }
  return ["provider_unavailable", "budget_exceeded"].includes(analysis.status)
    && analysis.hypotheses.length === 0
    && analysis.citations.length === 0
    && analysis.requested_tool_calls.length === 0;
}

export function rawAnalysisShapeValid(analysis, allowedStatuses = null) {
  const required = [
    "status",
    "run_id",
    "model_id",
    "prompt_version",
    "schema_version",
    "hypotheses",
    "counter_evidence",
    "missing_evidence",
    "citations",
    "confidence",
    "usage",
    "cost_estimate",
    "requested_tool_calls",
  ];
  if (!exactKeys(analysis, required)
    || !["complete", "need_more_evidence", "abstain", "provider_unavailable", "budget_exceeded"]
      .includes(analysis.status)
    || (allowedStatuses && !allowedStatuses.includes(analysis.status))
    || !UUID_PATTERN.test(analysis.run_id)
    || !boundedString(analysis.model_id, 256)
    || !boundedString(analysis.prompt_version, 256)
    || analysis.schema_version !== "analysis-v1"
    || !Array.isArray(analysis.hypotheses)
    || analysis.hypotheses.length > 20
    || !analysis.hypotheses.every(validHypothesis)
    || !Array.isArray(analysis.counter_evidence)
    || analysis.counter_evidence.length > 100
    || !analysis.counter_evidence.every((item) => boundedString(item, 1024))
    || !Array.isArray(analysis.missing_evidence)
    || analysis.missing_evidence.length > 100
    || !analysis.missing_evidence.every((item) => boundedString(item, 1024))
    || !Array.isArray(analysis.citations)
    || analysis.citations.length > 100
    || !analysis.citations.every(validCitation)
    || !boundedConfidence(analysis.confidence)
    || !validUsage(analysis.usage)
    || !validCost(analysis.cost_estimate)
    || !Array.isArray(analysis.requested_tool_calls)
    || analysis.requested_tool_calls.length > 20
    || !analysis.requested_tool_calls.every(validToolIntent)
    || !statusShapeValid(analysis)) {
    return false;
  }
  const topLevelCitations = new Set(
    analysis.citations.map((citation) => `${citation.evidence_id}:${citation.digest}`),
  );
  return analysis.hypotheses.every((hypothesis) => hypothesis.citations.every((citation) => (
    topLevelCitations.has(`${citation.evidence_id}:${citation.digest}`)
  )));
}
