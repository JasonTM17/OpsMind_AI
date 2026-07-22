package ai.opsmind.platform.investigation.integration;

import java.util.UUID;
import java.util.Set;
import java.util.regex.Pattern;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/** Port for one-use, read-only tool execution. */
public interface InvestigationToolGatewayClient {

    ToolEvidence execute(AnalysisRuntimeResponse.ToolIntent intent, UUID runId);

    record ToolEvidence(UUID intentId, UUID evidenceId, String digest, String sourceType) {

        private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
        private static final Set<String> SOURCE_TYPES = Set.of(
            "metric", "log_summary", "trace", "change", "runbook"
        );

        public ToolEvidence {
            if (intentId == null || evidenceId == null || digest == null
                || !DIGEST.matcher(digest).matches() || !SOURCE_TYPES.contains(sourceType)) {
                throw new IllegalArgumentException("Tool evidence metadata is invalid.");
            }
        }
    }
}
