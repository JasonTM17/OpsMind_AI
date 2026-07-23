package ai.opsmind.platform.investigation.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.OperatorProjection;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.junit.jupiter.api.Test;

class OperatorInvestigationProjectionTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID EVIDENCE_ID = UUID.randomUUID();
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void withholdsAllModelAuthoredProseButPreservesCitedProvenance() {
        AnalysisRuntimeResponse.Citation citation = new AnalysisRuntimeResponse.Citation(
            EVIDENCE_ID, DIGEST, "Raw model claim with internal detail."
        );
        AnalysisRuntimeResponse analysis = new AnalysisRuntimeResponse(
            "complete", RUN_ID, "provider-model", "prompt-incident-investigation-v1",
            "analysis-v1",
            List.of(new AnalysisRuntimeResponse.Hypothesis(
                "Raw title", "Raw explanation", 0.8, List.of(citation)
            )),
            List.of("Raw counter evidence"), List.of(), List.of(citation), 0.8,
            new AnalysisRuntimeResponse.Usage(10, 5, 15),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ONE),
            List.of()
        );
        InvestigationRunReadModel source = readModel(analysis, List.of(), "See https://internal.test");

        OperatorProjection<InvestigationRunReadModel> result =
            OperatorInvestigationProjection.from(source);

        String rendered = result.body().toString();
        assertThat(result.redactionCount()).isEqualTo(7);
        assertThat(rendered)
            .contains("platform-analysis-adapter", EVIDENCE_ID.toString(), DIGEST)
            .doesNotContain(
                "Raw model claim", "Raw title", "Raw explanation",
                "Raw counter evidence", "internal.test"
            );
        assertThat(result.body().analysis().hypotheses().getFirst().citations())
            .containsExactlyElementsOf(result.body().analysis().citations());
    }

    @Test
    void rejectsASelectorThatHasNotPassedTheDisplayCatalog() {
        AnalysisRuntimeResponse.ToolIntent unsafe = new AnalysisRuntimeResponse.ToolIntent(
            UUID.randomUUID(), "logs", "query", DIGEST, "raw rationale"
        );

        assertThatThrownBy(() -> OperatorInvestigationProjection.from(
            readModel(null, List.of(unsafe), null)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not display-approved");
    }

    private InvestigationRunReadModel readModel(
        AnalysisRuntimeResponse analysis,
        List<AnalysisRuntimeResponse.ToolIntent> pending,
        String terminalReason
    ) {
        return new InvestigationRunReadModel(
            RUN_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            analysis == null
                ? InvestigationStateMachine.Status.WAITING_FOR_EVIDENCE
                : InvestigationStateMachine.Status.COMPLETED,
            new InvestigationRunReadModel.BudgetView(4, 4, 20, 8_000),
            analysis == null ? 1 : 2, pending.size(), analysis == null ? 0 : 15,
            analysis == null ? List.of() : List.of(EVIDENCE_ID),
            pending, analysis, terminalReason,
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:10:00Z"),
            analysis == null ? null : Instant.parse("2030-01-01T00:02:00Z")
        );
    }
}
