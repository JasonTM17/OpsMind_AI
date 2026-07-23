export const organizationId = "10000000-0000-4000-8000-000000000702";
export const projectId = "10000000-0000-4000-8000-000000000703";
export const incidentId = "10000000-0000-4000-8000-000000000704";
export const completedRunId = "10000000-0000-4000-8000-000000000701";
export const failedRunId = "10000000-0000-4000-8000-000000000706";
export const invalidRunId = "10000000-0000-4000-8000-000000000707";
export const abstainedRunId = "10000000-0000-4000-8000-000000000708";
export const budgetRunId = "10000000-0000-4000-8000-000000000709";
export const unavailableRunId = "10000000-0000-4000-8000-000000000711";
export const crossScopeRunId = "10000000-0000-4000-8000-000000000712";
export const oversizedRunId = "10000000-0000-4000-8000-000000000713";
export const uncitedRunId = "10000000-0000-4000-8000-000000000714";
export const createdRunId = "10000000-0000-4000-8000-000000000715";
export const analyzingRunId = "10000000-0000-4000-8000-000000000716";
export const waitingRunId = "10000000-0000-4000-8000-000000000717";
export const noProgressRunId = "10000000-0000-4000-8000-000000000718";
export const slowRunId = "10000000-0000-4000-8000-000000000719";
export const unauthorizedRunId = "10000000-0000-4000-8000-000000000720";
export const forbiddenRunId = "10000000-0000-4000-8000-000000000721";
export const missingRunId = "10000000-0000-4000-8000-000000000722";
export const chunkedRunId = "10000000-0000-4000-8000-000000000723";
export const timeoutRunId = "10000000-0000-4000-8000-000000000724";
export const invalidMediaRunId = "10000000-0000-4000-8000-000000000725";
export const invalidJsonRunId = "10000000-0000-4000-8000-000000000726";
export const invalidUtf8RunId = "10000000-0000-4000-8000-000000000727";
export const unclassifiedRunId = "10000000-0000-4000-8000-000000000728";
export const unknownOperationRunId = "10000000-0000-4000-8000-000000000729";
export const incidentPath =
  `/api/v1/organizations/${organizationId}/projects/${projectId}/incidents/${incidentId}`;

export const incident = {
  id: incidentId,
  organizationId,
  projectId,
  title: "Checkout latency regression",
  summary: "Checkout requests slowed immediately after the payment-router deployment.",
  severity: "SEV2",
  status: "INVESTIGATING",
  rootCause: null,
  resolutionSummary: null,
  createdBy: "10000000-0000-4000-8000-000000000710",
  updatedBy: "10000000-0000-4000-8000-000000000710",
  createdAt: "2030-01-01T14:03:00Z",
  updatedAt: "2030-01-01T14:21:42Z",
  version: 7,
};

export const completed = {
  runId: completedRunId,
  organizationId,
  projectId,
  incidentId,
  status: "COMPLETED",
  budget: { maxRounds: 4, maxToolCalls: 4, maxEvidenceItems: 20, maxTokens: 8_000 },
  rounds: 2,
  toolCalls: 1,
  totalTokens: 62,
  evidenceIds: ["10000000-0000-4000-8000-000000000705"],
  pendingToolCalls: [],
  analysis: {
    status: "complete",
    run_id: completedRunId,
    model_id: "platform-analysis-adapter",
    prompt_version: "prompt-incident-investigation-v1",
    schema_version: "analysis-v1",
    hypotheses: [{
      title: "Evidence-backed hypothesis 1",
      explanation: "Model-authored explanation withheld; inspect the cited durable evidence.",
      confidence: 0.83,
      citations: [{
        evidence_id: "10000000-0000-4000-8000-000000000705",
        digest: `sha256:${"a".repeat(64)}`,
        claim: "Platform-verified durable evidence citation 1.",
      }],
    }, {
      title: "Evidence-backed hypothesis 2",
      explanation: "Model-authored explanation withheld; inspect the cited durable evidence.",
      confidence: 0.24,
      citations: [{
        evidence_id: "10000000-0000-4000-8000-000000000705",
        digest: `sha256:${"a".repeat(64)}`,
        claim: "Platform-verified durable evidence citation 2.",
      }],
    }],
    counter_evidence: ["Counter-evidence note 1 withheld by display policy."],
    missing_evidence: [],
    citations: [{
      evidence_id: "10000000-0000-4000-8000-000000000705",
      digest: `sha256:${"a".repeat(64)}`,
      claim: "Platform-verified durable evidence citation 1.",
    }, {
      evidence_id: "10000000-0000-4000-8000-000000000705",
      digest: `sha256:${"a".repeat(64)}`,
      claim: "Platform-verified durable evidence citation 2.",
    }],
    confidence: 0.83,
    usage: { prompt_tokens: 20, completion_tokens: 22, total_tokens: 42 },
    cost_estimate: { currency: "USD", amount: 0 },
    requested_tool_calls: [],
  },
  terminalReason: null,
  startedAt: "2030-01-01T14:03:00Z",
  deadlineAt: "2030-01-01T14:25:00Z",
  endedAt: "2030-01-01T14:21:42Z",
};

export const failed = {
  ...completed,
  runId: failedRunId,
  status: "FAILED",
  rounds: 1,
  toolCalls: 0,
  totalTokens: 20,
  evidenceIds: [],
  analysis: null,
  terminalReason: "Prometheus unavailable — retry was not attempted; durable state unchanged.",
  endedAt: "2030-01-01T14:04:11Z",
};

function stoppedRun(runId, status, terminalReason) {
  return {
    ...completed,
    runId,
    status,
    rounds: 1,
    toolCalls: 0,
    totalTokens: 20,
    evidenceIds: [],
    analysis: null,
    terminalReason,
    endedAt: "2030-01-01T14:04:11Z",
  };
}

export const abstained = stoppedRun(
  abstainedRunId,
  "ABSTAINED",
  "Evidence remained insufficient for a cited conclusion.",
);
export const budgetExceeded = stoppedRun(
  budgetRunId,
  "BUDGET_EXCEEDED",
  "The accepted token budget was exhausted.",
);
