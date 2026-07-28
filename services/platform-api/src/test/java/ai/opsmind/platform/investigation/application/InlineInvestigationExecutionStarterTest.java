package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;

import org.junit.jupiter.api.Test;

class InlineInvestigationExecutionStarterTest {

    @Test
    void expiredNewStartFailsBeforeInlineExecution() {
        InvestigationOrchestrator orchestrator = mock(InvestigationOrchestrator.class);
        InlineInvestigationExecutionStarter starter =
            new InlineInvestigationExecutionStarter(orchestrator);

        assertThatThrownBy(() -> starter.start(expiredCommand(), mock(
            InvestigationExecutionContext.class
        ))).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            org.assertj.core.api.Assertions.assertThat(exception.code())
                .isEqualTo("investigation.deadline-exceeded")
        );
        verifyNoInteractions(orchestrator);
    }

    private InvestigationCommand.Start expiredCommand() {
        return new InvestigationCommand.Start(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            new InvestigationCommand.Budget(4, 2, 10, 1_000),
            Instant.parse("2030-01-01T00:20:00Z"),
            Instant.parse("2030-01-01T00:10:00Z")
        );
    }
}
