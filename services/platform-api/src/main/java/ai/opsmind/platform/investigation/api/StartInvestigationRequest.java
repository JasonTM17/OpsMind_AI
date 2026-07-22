package ai.opsmind.platform.investigation.api;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StartInvestigationRequest(
    @JsonProperty("run_id") UUID runId,
    @JsonProperty("max_rounds") Integer maxRounds,
    @JsonProperty("max_tool_calls") Integer maxToolCalls,
    @JsonProperty("max_evidence_items") Integer maxEvidenceItems,
    @JsonProperty("max_tokens") Integer maxTokens,
    @JsonProperty("deadline_at") Instant deadlineAt
) {
    public StartInvestigationRequest {
        if (runId == null || maxRounds == null || maxRounds < 1 || maxRounds > 20
            || maxToolCalls == null || maxToolCalls < 0 || maxToolCalls > 20
            || maxEvidenceItems == null || maxEvidenceItems < 1 || maxEvidenceItems > 200
            || maxTokens == null || maxTokens < 1 || maxTokens > 100_000
            || deadlineAt == null) {
            throw new IllegalArgumentException("Investigation request is outside policy.");
        }
    }
}
