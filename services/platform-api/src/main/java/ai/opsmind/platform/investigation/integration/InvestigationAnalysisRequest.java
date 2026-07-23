package ai.opsmind.platform.investigation.integration;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;

/** Authorization-bound model request derived from the durable investigation state. */
public record InvestigationAnalysisRequest(
    OpsMindPrincipal principal,
    AuthorizedIncidentAnalysisEvidence initialIncident,
    UUID runId,
    Set<UUID> evidenceIds,
    int completedRounds,
    int remainingRounds,
    int remainingTokens,
    int remainingToolCalls,
    Instant deadlineAt
) {
    public InvestigationAnalysisRequest {
        evidenceIds = evidenceIds == null ? Set.of() : Set.copyOf(evidenceIds);
        if (principal == null || initialIncident == null || runId == null
            || evidenceIds.size() > 199 || evidenceIds.stream().anyMatch(java.util.Objects::isNull)
            || completedRounds < 0 || completedRounds > 20
            || remainingRounds < 0 || remainingRounds > 20
            || completedRounds + remainingRounds > 20
            || remainingTokens < 0 || remainingTokens > 100_000
            || remainingToolCalls < 0 || remainingToolCalls > 20 || deadlineAt == null) {
            throw new IllegalArgumentException("Investigation analysis request is invalid.");
        }
    }
}
