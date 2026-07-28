package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowAdmission;
import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowProperties;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DurableInvestigationExecutionStarterTest {

    @Test
    void checksExistingBindingThenRequiresReadinessBeforeNewPersistence() {
        InvestigationWorkflowAdmission admission = mock(InvestigationWorkflowAdmission.class);
        DurableInvestigationAdmissionRepository repository =
            mock(DurableInvestigationAdmissionRepository.class);
        InvestigationWorkflowProperties properties = new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", "opsmind-investigation-v1",
            "opsmind-investigation-prod"
        );
        InvestigationCommand.Start command = command();
        AuthorizedIncidentAnalysisEvidence authorized = authorized();
        InvestigationStateMachine.State state = InvestigationStateMachine.start(command).state();
        InvestigationExecutionContext context = context(authorized);
        when(repository.loadExisting(command, context)).thenReturn(Optional.empty());
        when(repository.createOrLoad(command, context)).thenReturn(state);
        DurableInvestigationExecutionStarter starter =
            new DurableInvestigationExecutionStarter(admission, repository, properties);

        InvestigationExecutionStarter.StartResult result = starter.start(command, context);

        InOrder order = inOrder(admission, repository);
        order.verify(repository).loadExisting(command, context);
        order.verify(admission).requireReady(properties);
        order.verify(repository).createOrLoad(command, context);
        assertThat(result.asynchronous()).isTrue();
        assertThat(result.state()).isSameAs(state);
    }

    @Test
    void exactExistingBindingRemainsReadableWhenWorkerReadinessIsLost() {
        InvestigationWorkflowAdmission admission = mock(InvestigationWorkflowAdmission.class);
        DurableInvestigationAdmissionRepository repository =
            mock(DurableInvestigationAdmissionRepository.class);
        InvestigationWorkflowProperties properties = new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", "opsmind-investigation-v1",
            "opsmind-investigation-prod"
        );
        InvestigationCommand.Start command = command();
        AuthorizedIncidentAnalysisEvidence authorized = authorized();
        InvestigationStateMachine.State state = InvestigationStateMachine.start(command).state();
        InvestigationExecutionContext context = context(authorized);
        when(repository.loadExisting(command, context)).thenReturn(Optional.of(state));
        DurableInvestigationExecutionStarter starter =
            new DurableInvestigationExecutionStarter(admission, repository, properties);

        InvestigationExecutionStarter.StartResult result = starter.start(command, context);

        verifyNoInteractions(admission);
        verify(repository).loadExisting(command, context);
        verifyNoMoreInteractions(repository);
        assertThat(result.asynchronous()).isTrue();
        assertThat(result.state()).isSameAs(state);
    }

    @Test
    void exactExistingBindingRemainsReadableAfterItsDeadline() {
        InvestigationWorkflowAdmission admission = mock(InvestigationWorkflowAdmission.class);
        DurableInvestigationAdmissionRepository repository =
            mock(DurableInvestigationAdmissionRepository.class);
        InvestigationWorkflowProperties properties = properties();
        InvestigationCommand.Start expired = expiredCommand();
        AuthorizedIncidentAnalysisEvidence authorized = authorized();
        InvestigationStateMachine.State existing =
            InvestigationStateMachine.start(command()).state();
        InvestigationExecutionContext context = context(authorized);
        when(repository.loadExisting(expired, context)).thenReturn(Optional.of(existing));
        DurableInvestigationExecutionStarter starter =
            new DurableInvestigationExecutionStarter(admission, repository, properties);

        InvestigationExecutionStarter.StartResult result = starter.start(expired, context);

        verifyNoInteractions(admission);
        verify(repository).loadExisting(expired, context);
        verifyNoMoreInteractions(repository);
        assertThat(result.state()).isSameAs(existing);
    }

    @Test
    void expiredNewStartFailsBeforeAdmissionOrPersistence() {
        InvestigationWorkflowAdmission admission = mock(InvestigationWorkflowAdmission.class);
        DurableInvestigationAdmissionRepository repository =
            mock(DurableInvestigationAdmissionRepository.class);
        InvestigationCommand.Start expired = expiredCommand();
        AuthorizedIncidentAnalysisEvidence authorized = authorized();
        InvestigationExecutionContext context = context(authorized);
        when(repository.loadExisting(expired, context)).thenReturn(Optional.empty());
        DurableInvestigationExecutionStarter starter =
            new DurableInvestigationExecutionStarter(admission, repository, properties());

        assertThatThrownBy(() -> starter.start(expired, context))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("investigation.deadline-exceeded")
            );
        verifyNoInteractions(admission);
        verify(repository).loadExisting(expired, context);
        verifyNoMoreInteractions(repository);
    }

    private InvestigationWorkflowProperties properties() {
        return new InvestigationWorkflowProperties(
            "temporal-primary", "opsmind-prod", "opsmind-investigation-v1",
            "opsmind-investigation-prod"
        );
    }

    private InvestigationExecutionContext context(
        AuthorizedIncidentAnalysisEvidence authorized
    ) {
        return new InvestigationExecutionContext(
            new OpsMindPrincipal(
                URI.create("https://idp.example.test/opsmind"),
                "operator-001", null, null, Set.of("incident:analyze")
            ),
            authorized
        );
    }

    private InvestigationCommand.Start expiredCommand() {
        InvestigationCommand.Start command = command();
        return new InvestigationCommand.Start(
            command.runId(), command.organizationId(), command.projectId(),
            command.incidentId(), command.actorId(), command.budget(),
            command.deadlineAt().plusSeconds(1), command.deadlineAt()
        );
    }

    private InvestigationCommand.Start command() {
        return new InvestigationCommand.Start(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            new InvestigationCommand.Budget(4, 2, 10, 1_000),
            Instant.parse("2030-01-01T00:00:00Z"),
            Instant.parse("2030-01-01T00:10:00Z")
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorized() {
        InvestigationCommand.Start command = command();
        return new AuthorizedIncidentAnalysisEvidence(
            command.organizationId(), command.projectId(), command.incidentId(), command.actorId(),
            "Latency", "Redacted spike", IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING, null, null, 1
        );
    }
}
