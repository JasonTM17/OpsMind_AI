import {
  analyzingRunId,
  completed,
  createdRunId,
  noProgressRunId,
  slowRunId,
  waitingRunId,
} from "./investigation-fixtures.mjs";

function activeRun(runId, status) {
  return {
    ...completed,
    runId,
    status,
    rounds: status === "CREATED" ? 0 : 1,
    toolCalls: 0,
    totalTokens: status === "CREATED" ? 0 : 20,
    evidenceIds: [],
    pendingToolCalls: [],
    analysis: null,
    terminalReason: null,
    endedAt: null,
  };
}

export const created = activeRun(createdRunId, "CREATED");
export const analyzing = activeRun(analyzingRunId, "ANALYZING");
export const slowCreated = activeRun(slowRunId, "CREATED");

export const waiting = {
  ...activeRun(waitingRunId, "WAITING_FOR_EVIDENCE"),
  toolCalls: 1,
  pendingToolCalls: [{
    intent_id: "10000000-0000-4000-8000-000000000730",
    connector: "metrics",
    operation: "query",
    arguments_digest: `sha256:${"b".repeat(64)}`,
    rationale: "Model-authored rationale withheld by display policy.",
  }],
};

export const noProgress = {
  ...activeRun(noProgressRunId, "NO_PROGRESS"),
  terminalReason: "The model requested more evidence without a cataloged read intent.",
  endedAt: "2030-01-01T14:04:11Z",
};
