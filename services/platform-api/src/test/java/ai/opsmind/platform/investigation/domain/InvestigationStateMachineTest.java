package ai.opsmind.platform.investigation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;

import org.junit.jupiter.api.Test;

class InvestigationStateMachineTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID EVIDENCE = UUID.randomUUID();

    @Test
    void completesOnlyAfterPersistedEvidenceSupportsEveryCitation() {
        InvestigationStateMachine.Step step = InvestigationStateMachine.start(start());
        AnalysisRuntimeResponse.ToolIntent intent = intent();
        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.AnalysisReceived(
            responseWithTool(intent)), NOW.plusSeconds(1));
        assertThat(step.state().status()).isEqualTo(InvestigationStateMachine.Status.WAITING_FOR_EVIDENCE);

        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.ToolEvidenceReceived(
            intent.intentId(), EVIDENCE, digest(2), "metric"), NOW.plusSeconds(2));
        assertThat(step.state().status()).isEqualTo(InvestigationStateMachine.Status.ANALYZING);

        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.AnalysisReceived(
            complete()), NOW.plusSeconds(3));
        assertThat(step.state().status()).isEqualTo(InvestigationStateMachine.Status.COMPLETED);
        assertThat(step.state().evidenceIds()).containsExactly(EVIDENCE);
    }

    @Test
    void stopsRepeatedToolIntentWithoutCallingTheProviderAgain() {
        InvestigationStateMachine.Step step = InvestigationStateMachine.start(start());
        AnalysisRuntimeResponse.ToolIntent intent = intent();
        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.AnalysisReceived(
            responseWithTool(intent)), NOW.plusSeconds(1));
        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.AnalysisReceived(
            responseWithTool(intent)), NOW.plusSeconds(2));
        assertThat(step.state().status()).isEqualTo(InvestigationStateMachine.Status.NO_PROGRESS);
        assertThat(step.events()).anyMatch(event -> event instanceof InvestigationEvent.NoProgress);
    }

    @Test
    void turnsAnUncitedFinalResponseIntoVisibleAbstention() {
        InvestigationStateMachine.Step step = InvestigationStateMachine.start(start());
        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.AnalysisReceived(
            complete()), NOW.plusSeconds(1));
        assertThat(step.state().status()).isEqualTo(InvestigationStateMachine.Status.ABSTAINED);
        assertThat(step.state().terminalReason()).contains("unpersisted");
    }

    @Test
    void enforcesRoundBudgetBeforeAcceptingMoreWork() {
        InvestigationStateMachine.Step step = InvestigationStateMachine.start(new InvestigationCommand.Start(
            RUN, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new InvestigationCommand.Budget(1, 2, 10, 100), NOW, NOW.plusSeconds(30)
        ));
        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.AnalysisReceived(
            responseWithTool(intent())), NOW.plusSeconds(1));
        step = InvestigationStateMachine.apply(step.state(), new InvestigationCommand.AnalysisReceived(
            responseWithTool(newIntent())), NOW.plusSeconds(2));
        assertThat(step.state().status()).isEqualTo(InvestigationStateMachine.Status.BUDGET_EXCEEDED);
        assertThat(step.state().rounds()).isEqualTo(2);
        assertThat(step.state().totalTokens()).isEqualTo(6);
        assertThat(step.events()).hasSize(2);
    }

    private InvestigationCommand.Start start() {
        return new InvestigationCommand.Start(
            RUN, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new InvestigationCommand.Budget(4, 4, 10, 1_000), NOW, NOW.plusSeconds(30)
        );
    }

    private AnalysisRuntimeResponse.ToolIntent intent() {
        return newIntent();
    }

    private AnalysisRuntimeResponse.ToolIntent newIntent() {
        return new AnalysisRuntimeResponse.ToolIntent(
            UUID.randomUUID(), "metrics", "query", digest(1), "Inspect the service latency metric."
        );
    }

    private AnalysisRuntimeResponse responseWithTool(AnalysisRuntimeResponse.ToolIntent intent) {
        return new AnalysisRuntimeResponse(
            "need_more_evidence", RUN, "deepseek-v4-flash", "prompt-incident-investigation-v1",
            "analysis-v1", List.of(), List.of(), List.of("latency evidence"), List.of(), 0.2,
            new AnalysisRuntimeResponse.Usage(2, 1, 3),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO), List.of(intent)
        );
    }

    private AnalysisRuntimeResponse complete() {
        AnalysisRuntimeResponse.Citation citation = new AnalysisRuntimeResponse.Citation(
            EVIDENCE, digest(2), "Latency increased after deployment."
        );
        return new AnalysisRuntimeResponse(
            "complete", RUN, "deepseek-v4-flash", "prompt-incident-investigation-v1", "analysis-v1",
            List.of(new AnalysisRuntimeResponse.Hypothesis(
                "Deployment regression", "The deployment correlates with the latency increase.",
                0.9, List.of(citation)
            )), List.of(), List.of(), List.of(citation), 0.9,
            new AnalysisRuntimeResponse.Usage(2, 2, 4),
            new AnalysisRuntimeResponse.CostEstimate("USD", BigDecimal.ZERO), List.of()
        );
    }

    private String digest(int suffix) {
        return "sha256:" + ("%064d".formatted(suffix));
    }
}
