package ai.opsmind.platform.investigation.integration;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.evidence.CollectedEvidence;

/** Port for one-use, read-only tool execution. */
public interface InvestigationToolGatewayClient {

    ToolEvidence execute(AnalysisRuntimeResponse.ToolIntent intent, ToolExecutionContext context);

    record ToolEvidence(
        UUID intentId,
        UUID evidenceId,
        String digest,
        String sourceType,
        CollectedEvidence collectedEvidence
    ) {

        private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
        private static final Set<String> SOURCE_TYPES = Set.of(
            "metric", "log_summary", "trace", "change", "runbook"
        );

        public ToolEvidence {
            if (intentId == null || evidenceId == null || digest == null
                || !DIGEST.matcher(digest).matches() || !SOURCE_TYPES.contains(sourceType)
                || (collectedEvidence != null
                    && (!digest.equals(collectedEvidence.contentDigest())
                        || !sourceType.equals(collectedEvidence.sourceType())))) {
                throw new IllegalArgumentException("Tool evidence metadata is invalid.");
            }
        }

        public ToolEvidence(UUID intentId, UUID evidenceId, String digest, String sourceType) {
            this(intentId, evidenceId, digest, sourceType, null);
        }
    }

    record ToolExecutionContext(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        UUID actorId,
        Instant deadlineAt
    ) {
        public ToolExecutionContext {
            if (organizationId == null || projectId == null || incidentId == null || runId == null
                || actorId == null || deadlineAt == null) {
                throw new IllegalArgumentException("Tool execution context is incomplete.");
            }
        }
    }
}
