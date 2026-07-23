package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.api.StartInvestigationRequest;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.projection.InvestigationProjectionAssembler;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InvestigationRunServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTOR_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RUN_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Test
    void passesVerifiedPrincipalAndAuthorizedSnapshotIntoSynchronousRunner() {
        IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
        InvestigationOrchestrator orchestrator = mock(InvestigationOrchestrator.class);
        InvestigationRunStore store = mock(InvestigationRunStore.class);
        InvestigationRunService service = new InvestigationRunService(
            authorizer, orchestrator, store, new InvestigationProjectionAssembler(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        OpsMindPrincipal principal = principal();
        AuthorizedIncidentAnalysisEvidence authorized = authorizedIncident();
        when(authorizer.requireEvidence(
            principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        )).thenReturn(authorized);
        when(orchestrator.run(any(), any())).thenAnswer(invocation -> {
            InvestigationCommand.Start command = invocation.getArgument(0);
            return InvestigationStateMachine.start(command).state();
        });

        service.start(
            principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID,
            new StartInvestigationRequest(
                RUN_ID, 4, 2, 10, 1_000, NOW.plusSeconds(120)
            )
        );

        ArgumentCaptor<InvestigationCommand.Start> command =
            ArgumentCaptor.forClass(InvestigationCommand.Start.class);
        ArgumentCaptor<InvestigationExecutionContext> context =
            ArgumentCaptor.forClass(InvestigationExecutionContext.class);
        verify(orchestrator).run(command.capture(), context.capture());
        assertThat(command.getValue().actorId()).isEqualTo(ACTOR_ID);
        assertThat(context.getValue().principal()).isSameAs(principal);
        assertThat(context.getValue().initialIncident()).isSameAs(authorized);
        verify(authorizer).requireEvidence(
            eq(principal), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(INCIDENT_ID)
        );
    }

    @Test
    void readsOnlyThroughReadAuthorizationAndFullyScopedStoreLookup() {
        IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
        InvestigationOrchestrator orchestrator = mock(InvestigationOrchestrator.class);
        InvestigationRunStore store = mock(InvestigationRunStore.class);
        InvestigationRunService service = new InvestigationRunService(
            authorizer, orchestrator, store, new InvestigationProjectionAssembler(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        OpsMindPrincipal principal = new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"), "operator-001", null, null,
            Set.of("incident:read")
        );
        InvestigationStateMachine.State state = InvestigationStateMachine.start(
            new InvestigationCommand.Start(
                RUN_ID, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID,
                new InvestigationCommand.Budget(4, 2, 10, 1_000),
                NOW, NOW.plusSeconds(120)
            )
        ).state();
        when(authorizer.requireReadAccess(
            principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        )).thenReturn(ACTOR_ID);
        when(store.requireScoped(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID, RUN_ID
        )).thenReturn(state);

        assertThat(service.get(
            principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID
        ).runId()).isEqualTo(RUN_ID);

        verify(authorizer).requireReadAccess(
            principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        );
        verify(store).requireScoped(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID, RUN_ID
        );
    }

    private OpsMindPrincipal principal() {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"), "operator-001", null, null,
            Set.of("incident:analyze")
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorizedIncident() {
        return new AuthorizedIncidentAnalysisEvidence(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID, "Latency", "Redacted spike",
            IncidentSeverity.SEV1, IncidentStatus.INVESTIGATING, null, null, 1
        );
    }
}
