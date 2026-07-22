package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationToolGatewayClient;

import org.junit.jupiter.api.Test;

class InvestigationOrchestratorTest {

    @Test
    void fixtureSliceProducesCitedCompletionWithoutWriteCapableTools() {
        InMemoryInvestigationRunStore store = new InMemoryInvestigationRunStore();
        InvestigationOrchestrator orchestrator = new InvestigationOrchestrator(
            store,
            new FixtureInvestigationAiRuntimeClient(),
            new FixtureInvestigationToolGatewayClient(),
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
        UUID runId = UUID.randomUUID();
        InvestigationStateMachine.State result = orchestrator.run(new InvestigationCommand.Start(
            runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new InvestigationCommand.Budget(4, 4, 10, 1_000), Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:01:00Z")
        ));

        assertThat(result.status()).isEqualTo(InvestigationStateMachine.Status.COMPLETED);
        assertThat(result.evidenceIds()).hasSize(1);
        assertThat(result.finalResponse()).isNotNull();
        assertThat(result.finalResponse().citations()).extracting(value -> value.evidenceId())
            .containsExactlyElementsOf(result.evidenceIds());
    }

    @Test
    void nullToolEvidencePersistsATerminalFailure() {
        InMemoryInvestigationRunStore store = new InMemoryInvestigationRunStore();
        Clock clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        UUID runId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        InvestigationOrchestrator orchestrator = new InvestigationOrchestrator(
            store,
            new FixtureInvestigationAiRuntimeClient(),
            (intent, ignoredRunId) -> null,
            clock
        );

        InvestigationStateMachine.State result = orchestrator.run(new InvestigationCommand.Start(
            runId, organizationId, UUID.randomUUID(), UUID.randomUUID(), actorId,
            new InvestigationCommand.Budget(4, 4, 10, 1_000), Instant.now(clock),
            Instant.now(clock).plusSeconds(60)
        ));

        assertThat(result.status()).isEqualTo(InvestigationStateMachine.Status.FAILED);
        assertThat(result.terminalReason()).isEqualTo("Tool Gateway returned invalid evidence.");
        assertThat(store.require(organizationId, actorId, runId)).isEqualTo(result);
    }
}
