package ai.opsmind.platform.investigation.integration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

/** Deterministic fixture model used only by the local vertical-slice profile. */
public final class FixtureInvestigationAiRuntimeClient implements InvestigationAiRuntimeClient {

    private static final UUID INTENT_ID = UUID.nameUUIDFromBytes(
        "opsmind-fixture-metrics-intent".getBytes(StandardCharsets.UTF_8)
    );
    private static final String ARGUMENTS_DIGEST = digest("fixture-metrics-arguments");
    private static final String EVIDENCE_CONTENT =
        "{\"metric\":\"http_request_duration_seconds\",\"service\":\"opsmind-api\",\"value\":1.25}";
    private static final String EVIDENCE_DIGEST =
        "sha256:8ef591caf657c9a4010f686512b8c9bfe4ce08df6b96e7ea09ce942750bbcb47";

    @Override
    public AnalysisRuntimeResponse analyze(UUID runId, Set<UUID> evidenceIds, int round) {
        if (evidenceIds.isEmpty()) {
            AnalysisRuntimeResponse.ToolIntent intent = new AnalysisRuntimeResponse.ToolIntent(
                INTENT_ID, "metrics", "query", ARGUMENTS_DIGEST,
                "Compare synthetic latency before and after the deployment."
            );
            return new AnalysisRuntimeResponse(
                "need_more_evidence", runId, "fixture-analysis", "prompt-incident-investigation-v1",
                "analysis-v1", List.of(), List.of(), List.of("deployment latency metric"), List.of(),
                0.2, new AnalysisRuntimeResponse.Usage(12, 8, 20),
                new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO), List.of(intent)
            );
        }
        UUID evidenceId = evidenceIds.stream().sorted().findFirst()
            .orElseThrow(() -> new IllegalStateException("Fixture evidence is missing."));
        AnalysisRuntimeResponse.Citation citation = new AnalysisRuntimeResponse.Citation(
            evidenceId, EVIDENCE_DIGEST, "Synthetic latency increased immediately after deployment."
        );
        return new AnalysisRuntimeResponse(
            "complete", runId, "fixture-analysis", "prompt-incident-investigation-v1", "analysis-v1",
            List.of(new AnalysisRuntimeResponse.Hypothesis(
                "Deployment-caused latency regression",
                "The deployment is temporally associated with the synthetic latency increase.",
                0.92, List.of(citation)
            )), List.of(), List.of(), List.of(citation), 0.92,
            new AnalysisRuntimeResponse.Usage(18, 24, 42),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO), List.of()
        );
    }

    public static String evidenceDigest() {
        return EVIDENCE_DIGEST;
    }

    public static String evidenceContent() {
        return EVIDENCE_CONTENT;
    }

    private static String digest(String value) {
        return "sha256:" + java.util.HexFormat.of().formatHex(
            sha256(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
