package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.FixtureInvestigationToolGatewayClient;
import ai.opsmind.platform.investigation.integration.InvestigationAnalysisRequest;

import org.junit.jupiter.api.Test;

class InvestigationOrchestratorTest {

    @Test
    void fixtureSliceProducesCitedCompletionWithoutWriteCapableTools() {
        InMemoryInvestigationRunStore store = new InMemoryInvestigationRunStore();
        List<InvestigationAnalysisRequest> requests = new ArrayList<>();
        FixtureInvestigationAiRuntimeClient fixture = new FixtureInvestigationAiRuntimeClient();
        InvestigationOrchestrator orchestrator = new InvestigationOrchestrator(
            store,
            request -> {
                requests.add(request);
                return fixture.analyze(request);
            },
            new FixtureInvestigationToolGatewayClient(),
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
        UUID runId = UUID.randomUUID();
        InvestigationCommand.Start command = new InvestigationCommand.Start(
            runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new InvestigationCommand.Budget(4, 4, 10, 1_000), Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:01:00Z")
        );
        InvestigationStateMachine.State result = orchestrator.run(
            command, InvestigationTestFixtures.context(command)
        );

        assertThat(result.status()).isEqualTo(InvestigationStateMachine.Status.COMPLETED);
        assertThat(result.evidenceIds()).hasSize(1);
        assertThat(result.finalResponse()).isNotNull();
        assertThat(result.finalResponse().citations()).extracting(value -> value.evidenceId())
            .containsExactlyElementsOf(result.evidenceIds());
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).satisfies(request -> {
            assertThat(request.completedRounds()).isZero();
            assertThat(request.remainingRounds()).isEqualTo(4);
            assertThat(request.remainingTokens()).isEqualTo(1_000);
            assertThat(request.remainingToolCalls()).isEqualTo(4);
            assertThat(request.evidenceIds()).isEmpty();
        });
        assertThat(requests.get(1)).satisfies(request -> {
            assertThat(request.completedRounds()).isEqualTo(1);
            assertThat(request.remainingRounds()).isEqualTo(3);
            assertThat(request.remainingTokens()).isEqualTo(980);
            assertThat(request.remainingToolCalls()).isEqualTo(3);
            assertThat(request.evidenceIds()).hasSize(1);
        });
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

        InvestigationCommand.Start command = new InvestigationCommand.Start(
            runId, organizationId, UUID.randomUUID(), UUID.randomUUID(), actorId,
            new InvestigationCommand.Budget(4, 4, 10, 1_000), Instant.now(clock),
            Instant.now(clock).plusSeconds(60)
        );
        InvestigationStateMachine.State result = orchestrator.run(
            command, InvestigationTestFixtures.context(command)
        );

        assertThat(result.status()).isEqualTo(InvestigationStateMachine.Status.FAILED);
        assertThat(result.terminalReason()).isEqualTo("Tool Gateway returned invalid evidence.");
        assertThat(store.require(organizationId, actorId, runId)).isEqualTo(result);
    }
}
