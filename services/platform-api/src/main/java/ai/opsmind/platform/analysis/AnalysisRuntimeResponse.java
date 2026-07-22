package ai.opsmind.platform.analysis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AnalysisRuntimeResponse(
    String status,
    @JsonProperty("run_id") UUID runId,
    @JsonProperty("model_id") String modelId,
    @JsonProperty("prompt_version") String promptVersion,
    @JsonProperty("schema_version") String schemaVersion,
    List<Hypothesis> hypotheses,
    @JsonProperty("counter_evidence") List<String> counterEvidence,
    @JsonProperty("missing_evidence") List<String> missingEvidence,
    List<Citation> citations,
    double confidence,
    Usage usage,
    @JsonProperty("cost_estimate") CostEstimate costEstimate,
    @JsonProperty("requested_tool_calls") List<ToolIntent> requestedToolCalls
) {
    private static final Set<String> STATUSES = Set.of(
        "complete", "need_more_evidence", "abstain", "provider_unavailable", "budget_exceeded"
    );

    public AnalysisRuntimeResponse {
        if (!STATUSES.contains(status) || runId == null || !bounded(modelId, 256)
            || !bounded(promptVersion, 256) || !"analysis-v1".equals(schemaVersion)
            || !confidence(confidence) || usage == null || costEstimate == null) {
            throw new IllegalArgumentException("AI Runtime response metadata is invalid.");
        }
        hypotheses = boundedList(hypotheses, 20, "hypotheses");
        counterEvidence = boundedNotes(counterEvidence, 100);
        missingEvidence = boundedNotes(missingEvidence, 100);
        citations = boundedList(citations, 100, "citations");
        requestedToolCalls = boundedList(requestedToolCalls, 20, "tool intents");
        if ("complete".equals(status) && (hypotheses.isEmpty() || citations.isEmpty())) {
            throw new IllegalArgumentException("Complete AI Runtime response requires evidence.");
        }
        Set<Citation> topLevelCitations = Set.copyOf(citations);
        if ("complete".equals(status) && hypotheses.stream().anyMatch(hypothesis ->
            hypothesis.citations().isEmpty()
                || !topLevelCitations.containsAll(hypothesis.citations()))) {
            throw new IllegalArgumentException("AI Runtime hypothesis citations are not bound.");
        }
        if (!requestedToolCalls.isEmpty() && !"need_more_evidence".equals(status)) {
            throw new IllegalArgumentException("AI Runtime tool intents require more evidence.");
        }
        if ("need_more_evidence".equals(status)
            && requestedToolCalls.isEmpty() && missingEvidence.isEmpty()) {
            throw new IllegalArgumentException("AI Runtime response does not identify missing evidence.");
        }
    }

    public record Citation(
        @JsonProperty("evidence_id") UUID evidenceId,
        String digest,
        String claim
    ) {
        private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

        public Citation {
            if (evidenceId == null || digest == null || !DIGEST.matcher(digest).matches()
                || !bounded(claim, 1_024)) {
                throw new IllegalArgumentException("AI Runtime citation is invalid.");
            }
        }
    }

    public record Hypothesis(
        String title,
        String explanation,
        double confidence,
        List<Citation> citations
    ) {
        public Hypothesis {
            if (!bounded(title, 256) || !bounded(explanation, 4_096)
                || !AnalysisRuntimeResponse.confidence(confidence)) {
                throw new IllegalArgumentException("AI Runtime hypothesis is invalid.");
            }
            citations = boundedList(citations, 50, "hypothesis citations");
        }
    }

    public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
    ) {
        public Usage {
            if (promptTokens < 0 || completionTokens < 0
                || totalTokens != promptTokens + completionTokens) {
                throw new IllegalArgumentException("AI Runtime usage is invalid.");
            }
        }
    }

    public record CostEstimate(String currency, BigDecimal amount) {
        public CostEstimate {
            if (!"USD".equals(currency) || amount == null || amount.signum() < 0
                || amount.compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
                throw new IllegalArgumentException("AI Runtime cost estimate is invalid.");
            }
        }
    }

    public record ToolIntent(
        @JsonProperty("intent_id") UUID intentId,
        String connector,
        String operation,
        @JsonProperty("arguments_digest") String argumentsDigest,
        String rationale
    ) {
        private static final Set<String> CONNECTORS = Set.of(
            "metrics", "logs", "traces", "changes", "runbooks"
        );
        private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

        public ToolIntent {
            if (intentId == null || !CONNECTORS.contains(connector) || !bounded(operation, 256)
                || argumentsDigest == null || !DIGEST.matcher(argumentsDigest).matches()
                || !bounded(rationale, 1_024)) {
                throw new IllegalArgumentException("AI Runtime tool intent is invalid.");
            }
        }
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isEmpty()
            && value.codePointCount(0, value.length()) <= maximum;
    }

    private static boolean confidence(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static <T> List<T> boundedList(List<T> values, int maximum, String name) {
        List<T> normalized = values == null ? List.of() : List.copyOf(values);
        if (normalized.size() > maximum || normalized.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("AI Runtime " + name + " are invalid.");
        }
        return normalized;
    }

    private static List<String> boundedNotes(List<String> values, int maximum) {
        List<String> normalized = boundedList(values, maximum, "evidence notes");
        if (normalized.stream().anyMatch(value -> !bounded(value, 1_024))) {
            throw new IllegalArgumentException("AI Runtime evidence notes are invalid.");
        }
        return normalized;
    }
}
