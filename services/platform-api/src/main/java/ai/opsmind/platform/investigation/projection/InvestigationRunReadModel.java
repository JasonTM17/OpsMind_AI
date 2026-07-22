package ai.opsmind.platform.investigation.projection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

public record InvestigationRunReadModel(
    UUID runId,
    UUID organizationId,
    UUID projectId,
    UUID incidentId,
    InvestigationStateMachine.Status status,
    BudgetView budget,
    int rounds,
    int toolCalls,
    int totalTokens,
    List<UUID> evidenceIds,
    List<AnalysisRuntimeResponse.ToolIntent> pendingToolCalls,
    AnalysisRuntimeResponse analysis,
    String terminalReason,
    Instant startedAt,
    Instant deadlineAt,
    Instant endedAt
) {
    public record BudgetView(int maxRounds, int maxToolCalls, int maxEvidenceItems, int maxTokens) { }
}
