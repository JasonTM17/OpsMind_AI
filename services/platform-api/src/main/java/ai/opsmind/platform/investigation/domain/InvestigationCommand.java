package ai.opsmind.platform.investigation.domain;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.evidence.CollectedEvidence;

/** Pure commands accepted by the bounded investigation reducer. */
public sealed interface InvestigationCommand
    permits InvestigationCommand.Start, InvestigationCommand.AnalysisReceived,
        InvestigationCommand.ToolEvidenceReceived, InvestigationCommand.Failed {

    record Start(
        UUID runId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID actorId,
        Budget budget,
        Instant startedAt,
        Instant deadlineAt
    ) implements InvestigationCommand { }

    record AnalysisReceived(AnalysisRuntimeResponse response) implements InvestigationCommand { }

    record ToolEvidenceReceived(
        UUID intentId,
        UUID evidenceId,
        String digest,
        String sourceType,
        CollectedEvidence collectedEvidence
    ) implements InvestigationCommand {
        public ToolEvidenceReceived(
            UUID intentId,
            UUID evidenceId,
            String digest,
            String sourceType
        ) {
            this(intentId, evidenceId, digest, sourceType, null);
        }
    }

    record Failed(String reason) implements InvestigationCommand { }

    record Budget(int maxRounds, int maxToolCalls, int maxEvidenceItems, int maxTokens) {
        public Budget {
            if (maxRounds < 1 || maxRounds > 20 || maxToolCalls < 0 || maxToolCalls > 20
                || maxEvidenceItems < 1 || maxEvidenceItems > 200 || maxTokens < 1
                || maxTokens > 100_000) {
                throw new IllegalArgumentException("Investigation budget is outside policy.");
            }
        }
    }
}
