package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AnalysisRuntimeResponseContractTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVIDENCE_ID = UUID.fromString(
        "22222222-2222-4222-8222-222222222222"
    );
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void rejectsUsageThatDoesNotEqualItsComponents() {
        assertThatThrownBy(() -> new AnalysisRuntimeResponse.Usage(1, 1, 3))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("usage");
    }

    @Test
    void rejectsCompleteHypothesisCitationAbsentFromTopLevelCitations() {
        AnalysisRuntimeResponse.Citation nested = citation("nested");
        AnalysisRuntimeResponse.Citation topLevel = citation("top-level");
        AnalysisRuntimeResponse.Hypothesis hypothesis = new AnalysisRuntimeResponse.Hypothesis(
            "Bounded hypothesis",
            "Synthetic explanation",
            0.5,
            List.of(nested)
        );

        assertThatThrownBy(() -> new AnalysisRuntimeResponse(
            "complete",
            RUN_ID,
            "deepseek-v4-flash",
            "prompt-incident-v1",
            "analysis-v1",
            List.of(hypothesis),
            List.of(),
            List.of(),
            List.of(topLevel),
            0.5,
            new AnalysisRuntimeResponse.Usage(1, 1, 2),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO),
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("citations");
    }

    private AnalysisRuntimeResponse.Citation citation(String claim) {
        return new AnalysisRuntimeResponse.Citation(EVIDENCE_ID, DIGEST, claim);
    }
}
