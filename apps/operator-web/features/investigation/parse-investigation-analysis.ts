import {
  array,
  ContractValidationError,
  decimal,
  digest,
  enumeration,
  integer,
  record,
  text,
  uuid,
} from "./contract-validation";
import type {
  AnalysisCitation,
  AnalysisHypothesis,
  AnalysisView,
  ToolIntent,
} from "./investigation-types";

const ANALYSIS_STATUSES = [
  "complete",
  "need_more_evidence",
  "abstain",
  "provider_unavailable",
  "budget_exceeded",
] as const;
const CONNECTORS = ["metrics", "logs", "traces", "changes", "runbooks"] as const;
const BROWSER_SAFE_CATALOG_OPERATIONS = new Set(["metrics.query"]);

export function parseToolIntent(value: unknown, path: string): ToolIntent {
  const source = record(
    value,
    path,
    ["intent_id", "connector", "operation", "arguments_digest", "rationale"],
    ["intent_id", "connector", "operation", "arguments_digest", "rationale"],
  );
  const connector = enumeration(source.connector, `${path}.connector`, CONNECTORS);
  const operation = text(source.operation, `${path}.operation`, 1, 256);
  if (!BROWSER_SAFE_CATALOG_OPERATIONS.has(`${connector}.${operation}`)) {
    throw new ContractValidationError(
      `${path}.operation`,
      "is not a reviewed browser-safe catalog label",
    );
  }
  return {
    intentId: uuid(source.intent_id, `${path}.intent_id`),
    connector,
    operation,
    argumentsDigest: digest(source.arguments_digest, `${path}.arguments_digest`),
    rationale: text(source.rationale, `${path}.rationale`, 1, 1_024),
  };
}

function parseCitation(value: unknown, path: string): AnalysisCitation {
  const source = record(
    value,
    path,
    ["evidence_id", "digest", "claim"],
    ["evidence_id", "digest", "claim"],
  );
  return {
    evidenceId: uuid(source.evidence_id, `${path}.evidence_id`),
    digest: digest(source.digest, `${path}.digest`),
    claim: text(source.claim, `${path}.claim`, 1, 1_024),
  };
}

function parseHypothesis(value: unknown, path: string): AnalysisHypothesis {
  const source = record(
    value,
    path,
    ["title", "explanation", "confidence", "citations"],
    ["title", "explanation", "confidence"],
  );
  return {
    title: text(source.title, `${path}.title`, 1, 256),
    explanation: text(source.explanation, `${path}.explanation`, 1, 4_096),
    confidence: decimal(source.confidence, `${path}.confidence`, 0, 1),
    citations: source.citations === undefined
      ? []
      : array(source.citations, `${path}.citations`, 50)
          .map((item, index) => parseCitation(item, `${path}.citations[${index}]`)),
  };
}

function parseUsage(value: unknown): AnalysisView["usage"] {
  const source = record(
    value,
    "analysis.usage",
    ["prompt_tokens", "completion_tokens", "total_tokens"],
    ["prompt_tokens", "completion_tokens", "total_tokens"],
  );
  const promptTokens = integer(source.prompt_tokens, "analysis.usage.prompt_tokens", 0, 100_000);
  const completionTokens = integer(
    source.completion_tokens,
    "analysis.usage.completion_tokens",
    0,
    100_000,
  );
  const totalTokens = integer(source.total_tokens, "analysis.usage.total_tokens", 0, 200_000);
  if (totalTokens !== promptTokens + completionTokens) {
    throw new TypeError("analysis.usage.total_tokens must equal its components");
  }
  return { promptTokens, completionTokens, totalTokens };
}

export function parseAnalysis(value: unknown): AnalysisView {
  const source = record(
    value,
    "analysis",
    [
      "status", "run_id", "model_id", "prompt_version", "schema_version",
      "hypotheses", "counter_evidence", "missing_evidence", "citations",
      "confidence", "usage", "cost_estimate", "requested_tool_calls",
    ],
    ["status", "run_id", "model_id", "prompt_version", "schema_version", "usage", "cost_estimate"],
  );
  const hypotheses = source.hypotheses === undefined ? [] : array(
    source.hypotheses,
    "analysis.hypotheses",
    20,
  ).map((item, index) => parseHypothesis(item, `analysis.hypotheses[${index}]`));
  const citations = source.citations === undefined ? [] : array(
    source.citations,
    "analysis.citations",
    100,
  ).map((item, index) => parseCitation(item, `analysis.citations[${index}]`));
  const status = enumeration(source.status, "analysis.status", ANALYSIS_STATUSES);
  if (
    status === "complete" &&
    (
      hypotheses.length === 0 ||
      citations.length === 0 ||
      hypotheses.some((hypothesis) => hypothesis.citations.length === 0)
    )
  ) {
    throw new TypeError("complete analysis must cite every hypothesis");
  }
  const topLevelCitations = new Set(citations.map(citationKey));
  if (
    hypotheses.some((hypothesis) =>
      hypothesis.citations.some((citation) => !topLevelCitations.has(citationKey(citation)))
    )
  ) {
    throw new TypeError("hypothesis citations must appear in top-level citations");
  }
  const cost = record(
    source.cost_estimate,
    "analysis.cost_estimate",
    ["currency", "amount"],
    ["currency", "amount"],
  );
  if (cost.currency !== "USD") throw new TypeError("analysis cost currency must be USD");
  const requestedToolCalls = source.requested_tool_calls === undefined
    ? []
    : array(source.requested_tool_calls, "analysis.requested_tool_calls", 20)
        .map((item, index) => parseToolIntent(item, `analysis.requested_tool_calls[${index}]`));
  const missingEvidence = stringItems(source.missing_evidence, "analysis.missing_evidence");
  if (requestedToolCalls.length > 0 && status !== "need_more_evidence") {
    throw new TypeError("tool requests require need_more_evidence status");
  }
  if (status === "need_more_evidence" && requestedToolCalls.length === 0 && missingEvidence.length === 0) {
    throw new TypeError("need_more_evidence requires a missing item or tool request");
  }
  return {
    status,
    runId: uuid(source.run_id, "analysis.run_id"),
    modelId: text(source.model_id, "analysis.model_id", 1, 256),
    promptVersion: text(source.prompt_version, "analysis.prompt_version", 1, 256),
    schemaVersion: enumeration(source.schema_version, "analysis.schema_version", ["analysis-v1"] as const),
    hypotheses,
    counterEvidence: stringItems(source.counter_evidence, "analysis.counter_evidence"),
    missingEvidence,
    citations,
    confidence: source.confidence === undefined
      ? null
      : decimal(source.confidence, "analysis.confidence", 0, 1),
    usage: parseUsage(source.usage),
    costEstimate: {
      currency: "USD",
      amount: decimal(cost.amount, "analysis.cost_estimate.amount", 0, 1_000_000),
    },
    requestedToolCalls,
  };
}

function stringItems(value: unknown, path: string): string[] {
  if (value === undefined) return [];
  return array(value, path, 100).map((item, index) => text(item, `${path}[${index}]`, 1, 1_024));
}

function citationKey(citation: AnalysisCitation): string {
  return `${citation.evidenceId}\u0000${citation.digest}\u0000${citation.claim}`;
}
