package ai.opsmind.platform.investigation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.evidence.CollectedEvidence;

/** Immutable events emitted by the investigation reducer for persistence/projection. */
public sealed interface InvestigationEvent
    permits InvestigationEvent.RunStarted, InvestigationEvent.AnalysisAccepted,
        InvestigationEvent.ToolRequested, InvestigationEvent.EvidenceAppended,
        InvestigationEvent.Completed, InvestigationEvent.Abstained,
        InvestigationEvent.BudgetExceeded, InvestigationEvent.NoProgress,
        InvestigationEvent.Failed {

    record RunStarted(UUID runId, UUID incidentId, InvestigationCommand.Budget budget, Instant occurredAt)
        implements InvestigationEvent { }

    record AnalysisAccepted(UUID runId, String status, int round, int totalTokens, Instant occurredAt)
        implements InvestigationEvent { }

    record ToolRequested(UUID runId, List<AnalysisRuntimeResponse.ToolIntent> intents, Instant occurredAt)
        implements InvestigationEvent {
        public ToolRequested {
            intents = List.copyOf(intents);
        }
    }

    record EvidenceAppended(
        UUID runId,
        UUID intentId,
        UUID evidenceId,
        String digest,
        String sourceType,
        CollectedEvidence collectedEvidence,
        Instant occurredAt
    ) implements InvestigationEvent {
        public EvidenceAppended(
            UUID runId,
            UUID intentId,
            UUID evidenceId,
            String digest,
            String sourceType,
            Instant occurredAt
        ) {
            this(runId, intentId, evidenceId, digest, sourceType, null, occurredAt);
        }
    }

    record Completed(UUID runId, AnalysisRuntimeResponse response, Instant occurredAt)
        implements InvestigationEvent { }

    record Abstained(UUID runId, String reason, Instant occurredAt) implements InvestigationEvent { }

    record BudgetExceeded(UUID runId, String reason, Instant occurredAt) implements InvestigationEvent { }

    record NoProgress(UUID runId, String reason, Instant occurredAt) implements InvestigationEvent { }

    record Failed(UUID runId, String reason, Instant occurredAt) implements InvestigationEvent { }
}
