import {
  array,
  enumeration,
  integer,
  nullableText,
  record,
  timestamp,
  uuid,
} from "./contract-validation";
import {
  INVESTIGATION_STATUSES,
  type InvestigationBudget,
  type InvestigationView,
} from "./investigation-types";
import { parseAnalysis, parseToolIntent } from "./parse-investigation-analysis";

const REQUIRED_KEYS = [
  "runId",
  "organizationId",
  "projectId",
  "incidentId",
  "status",
  "budget",
  "rounds",
  "toolCalls",
  "totalTokens",
  "evidenceIds",
  "pendingToolCalls",
  "startedAt",
  "deadlineAt",
] as const;
const ALL_KEYS = [
  ...REQUIRED_KEYS,
  "analysis",
  "terminalReason",
  "endedAt",
] as const;

function parseBudget(value: unknown): InvestigationBudget {
  const source = record(
    value,
    "investigation.budget",
    ["maxRounds", "maxToolCalls", "maxEvidenceItems", "maxTokens"],
    ["maxRounds", "maxToolCalls", "maxEvidenceItems", "maxTokens"],
  );
  return {
    maxRounds: integer(source.maxRounds, "investigation.budget.maxRounds", 1, 20),
    maxToolCalls: integer(source.maxToolCalls, "investigation.budget.maxToolCalls", 0, 20),
    maxEvidenceItems: integer(
      source.maxEvidenceItems,
      "investigation.budget.maxEvidenceItems",
      1,
      200,
    ),
    maxTokens: integer(source.maxTokens, "investigation.budget.maxTokens", 1, 100_000),
  };
}

export function parseInvestigation(value: unknown): InvestigationView {
  const source = record(value, "investigation", ALL_KEYS, REQUIRED_KEYS);
  const runId = uuid(source.runId, "investigation.runId");
  const analysis = source.analysis === undefined || source.analysis === null
    ? null
    : parseAnalysis(source.analysis);
  if (analysis !== null && analysis.runId !== runId) {
    throw new TypeError("analysis run does not match the investigation run");
  }
  const evidenceIds = array(source.evidenceIds, "investigation.evidenceIds", 200)
    .map((item, index) => uuid(item, `investigation.evidenceIds[${index}]`));
  if (new Set(evidenceIds).size !== evidenceIds.length) {
    throw new TypeError("investigation evidence identifiers must be unique");
  }
  const result: InvestigationView = {
    runId,
    organizationId: uuid(source.organizationId, "investigation.organizationId"),
    projectId: uuid(source.projectId, "investigation.projectId"),
    incidentId: uuid(source.incidentId, "investigation.incidentId"),
    status: enumeration(source.status, "investigation.status", INVESTIGATION_STATUSES),
    budget: parseBudget(source.budget),
    rounds: integer(source.rounds, "investigation.rounds", 0, 20),
    toolCalls: integer(source.toolCalls, "investigation.toolCalls", 0, 20),
    totalTokens: integer(source.totalTokens, "investigation.totalTokens", 0, 100_000),
    evidenceIds,
    pendingToolCalls: array(
      source.pendingToolCalls,
      "investigation.pendingToolCalls",
      20,
    ).map((item, index) => parseToolIntent(item, `investigation.pendingToolCalls[${index}]`)),
    analysis,
    terminalReason: source.terminalReason === undefined
      ? null
      : nullableText(source.terminalReason, "investigation.terminalReason", 1_024),
    startedAt: timestamp(source.startedAt, "investigation.startedAt"),
    deadlineAt: timestamp(source.deadlineAt, "investigation.deadlineAt"),
    endedAt: source.endedAt === undefined || source.endedAt === null
      ? null
      : timestamp(source.endedAt, "investigation.endedAt"),
  };
  verifyInvestigationSemantics(result);
  return result;
}

function verifyInvestigationSemantics(result: InvestigationView): void {
  if (
    result.rounds > result.budget.maxRounds ||
    result.toolCalls > result.budget.maxToolCalls ||
    result.evidenceIds.length > result.budget.maxEvidenceItems ||
    result.totalTokens > result.budget.maxTokens
  ) {
    throw new TypeError("investigation usage exceeds its accepted budget");
  }
  const startedAt = Date.parse(result.startedAt);
  const deadlineAt = Date.parse(result.deadlineAt);
  if (deadlineAt <= startedAt) {
    throw new TypeError("investigation deadline must follow its start");
  }
  const terminal = ["COMPLETED", "ABSTAINED", "BUDGET_EXCEEDED", "NO_PROGRESS", "FAILED"]
    .includes(result.status);
  if (terminal !== (result.endedAt !== null)) {
    throw new TypeError("investigation terminal timestamp is inconsistent");
  }
  if (result.endedAt !== null && Date.parse(result.endedAt) < startedAt) {
    throw new TypeError("investigation end precedes its start");
  }
  const waitingForEvidence = result.status === "WAITING_FOR_EVIDENCE";
  if (waitingForEvidence !== (result.pendingToolCalls.length > 0)) {
    throw new TypeError("pending tool intents do not match investigation status");
  }
  if (
    result.analysis !== null &&
    result.analysis.usage.totalTokens > result.totalTokens
  ) {
    throw new TypeError("analysis usage exceeds cumulative investigation usage");
  }
  if (result.status === "COMPLETED") {
    if (result.analysis?.status !== "complete") {
      throw new TypeError("completed investigation requires complete analysis");
    }
    const evidence = new Set(result.evidenceIds);
    if (result.analysis.citations.some((citation) => !evidence.has(citation.evidenceId))) {
      throw new TypeError("completed analysis cites unpersisted evidence");
    }
  } else if (result.analysis !== null) {
    throw new TypeError("non-completed investigation cannot expose a final analysis");
  }
}
