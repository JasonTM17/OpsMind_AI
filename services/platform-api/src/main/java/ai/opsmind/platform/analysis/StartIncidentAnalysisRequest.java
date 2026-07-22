package ai.opsmind.platform.analysis;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StartIncidentAnalysisRequest(
    @JsonProperty("run_id") UUID runId,
    @JsonProperty("analysis_mode") String analysisMode,
    String purpose,
    @JsonProperty("token_budget") Integer tokenBudget,
    @JsonProperty("tool_budget") Integer toolBudget,
    @JsonProperty("deadline_at") Instant deadlineAt
) {
    private static final Set<String> MODES = Set.of("investigate", "summarize");
    private static final Set<String> PURPOSES = Set.of(
        "incident_investigation", "incident_summary"
    );

    public StartIncidentAnalysisRequest {
        if (runId == null || !MODES.contains(analysisMode) || !PURPOSES.contains(purpose)) {
            throw new IllegalArgumentException("Analysis run, mode, or purpose is invalid.");
        }
        if (tokenBudget == null || tokenBudget < 1 || tokenBudget > 100_000
            || toolBudget == null || toolBudget < 0 || toolBudget > 20) {
            throw new IllegalArgumentException("Analysis budget is invalid.");
        }
        if (deadlineAt == null) {
            throw new IllegalArgumentException("Analysis deadline is required.");
        }
    }
}
