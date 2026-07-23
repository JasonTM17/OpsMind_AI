export const INVESTIGATION_STATUSES = [
  "CREATED",
  "ANALYZING",
  "WAITING_FOR_EVIDENCE",
  "COMPLETED",
  "ABSTAINED",
  "BUDGET_EXCEEDED",
  "NO_PROGRESS",
  "FAILED",
] as const;

export type InvestigationStatus = (typeof INVESTIGATION_STATUSES)[number];

export const INCIDENT_SEVERITIES = ["SEV1", "SEV2", "SEV3", "SEV4"] as const;
export type IncidentSeverity = (typeof INCIDENT_SEVERITIES)[number];

export const INCIDENT_STATUSES = [
  "OPEN",
  "INVESTIGATING",
  "AWAITING_APPROVAL",
  "MITIGATING",
  "RESOLVED",
  "CLOSED",
] as const;
export type IncidentStatus = (typeof INCIDENT_STATUSES)[number];

export interface IncidentView {
  id: string;
  organizationId: string;
  projectId: string;
  title: string;
  summary: string;
  severity: IncidentSeverity;
  status: IncidentStatus;
  rootCause: string | null;
  resolutionSummary: string | null;
  createdBy: string;
  updatedBy: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface InvestigationBudget {
  maxRounds: number;
  maxToolCalls: number;
  maxEvidenceItems: number;
  maxTokens: number;
}

export interface AnalysisCitation {
  evidenceId: string;
  digest: string;
  claim: string;
}

export interface AnalysisHypothesis {
  title: string;
  explanation: string;
  confidence: number;
  citations: AnalysisCitation[];
}

export interface ToolIntent {
  intentId: string;
  connector: "metrics" | "logs" | "traces" | "changes" | "runbooks";
  operation: string;
  argumentsDigest: string;
  rationale: string;
}

export interface AnalysisView {
  status:
    | "complete"
    | "need_more_evidence"
    | "abstain"
    | "provider_unavailable"
    | "budget_exceeded";
  runId: string;
  modelId: string;
  promptVersion: string;
  schemaVersion: "analysis-v1";
  hypotheses: AnalysisHypothesis[];
  counterEvidence: string[];
  missingEvidence: string[];
  citations: AnalysisCitation[];
  confidence: number | null;
  usage: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
  costEstimate: {
    currency: "USD";
    amount: number;
  };
  requestedToolCalls: ToolIntent[];
}

export interface InvestigationView {
  runId: string;
  organizationId: string;
  projectId: string;
  incidentId: string;
  status: InvestigationStatus;
  budget: InvestigationBudget;
  rounds: number;
  toolCalls: number;
  totalTokens: number;
  evidenceIds: string[];
  pendingToolCalls: ToolIntent[];
  analysis: AnalysisView | null;
  terminalReason: string | null;
  startedAt: string;
  deadlineAt: string;
  endedAt: string | null;
}

export interface InvestigationWorkspaceData {
  incident: IncidentView;
  investigation: InvestigationView;
  refreshedAt: string;
  correlationId: string;
  projectionSafety: {
    classification: "operator-browser-safe-v1";
    redactionVersion: "display-redaction-v1";
    redactionCount: number;
    projectionCount: 2;
  };
}

export interface InvestigationRouteIdentity {
  organizationId: string;
  projectId: string;
  incidentId: string;
  runId: string;
}

export type WorkspaceUnavailableReason =
  | "session-unavailable"
  | "not-found"
  | "access-denied"
  | "dependency-unavailable"
  | "invalid-response"
  | "configuration-error"
  | "internal-error";

export type InvestigationWorkspaceResult =
  | { kind: "ready"; data: InvestigationWorkspaceData }
  | {
      kind: "unavailable";
      reason: WorkspaceUnavailableReason;
      title: string;
      detail: string;
      correlationId?: string;
    };
