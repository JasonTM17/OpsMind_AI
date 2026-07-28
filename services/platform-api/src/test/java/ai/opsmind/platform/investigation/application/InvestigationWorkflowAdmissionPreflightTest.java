package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class InvestigationWorkflowAdmissionPreflightTest {

    @Test
    void reauthorizesInsideAdmissionAndRequiresEligibleDispatcher() {
        IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        InvestigationWorkflowAdmissionPreflight preflight =
            new InvestigationWorkflowAdmissionPreflight(authorizer, jdbcTemplate);
        InvestigationCommand.Start command = command();
        InvestigationExecutionContext context = context(command, authorized(command, 1));
        AuthorizedIncidentAnalysisEvidence refreshed = authorized(command, 7);
        when(authorizer.requireEvidence(
            context.principal(),
            command.organizationId(),
            command.projectId(),
            command.incidentId()
        )).thenReturn(refreshed);
        when(jdbcTemplate.queryForObject(
            eq(dispatcherEligibilitySql()),
            eq(UUID.class),
            eq(command.organizationId())
        )).thenReturn(UUID.randomUUID());

        AuthorizedIncidentAnalysisEvidence result =
            preflight.requireFreshAdmission(command, context);

        assertThat(result).isSameAs(refreshed);
        verify(authorizer).requireEvidence(
            context.principal(),
            command.organizationId(),
            command.projectId(),
            command.incidentId()
        );
        verify(jdbcTemplate).queryForObject(
            eq(dispatcherEligibilitySql()),
            eq(UUID.class),
            eq(command.organizationId())
        );
    }

    @Test
    void missingEligibleDispatcherFailsClosedWithRetryableAdmissionError() {
        IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        InvestigationWorkflowAdmissionPreflight preflight =
            new InvestigationWorkflowAdmissionPreflight(authorizer, jdbcTemplate);
        InvestigationCommand.Start command = command();
        InvestigationExecutionContext context = context(command, authorized(command, 1));
        when(authorizer.requireEvidence(
            context.principal(),
            command.organizationId(),
            command.projectId(),
            command.incidentId()
        )).thenReturn(authorized(command, 2));
        when(jdbcTemplate.queryForObject(
            eq(dispatcherEligibilitySql()),
            eq(UUID.class),
            eq(command.organizationId())
        )).thenReturn(null);

        assertThatThrownBy(() -> preflight.requireFreshAdmission(command, context))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.status().value()).isEqualTo(503);
                assertThat(exception.code())
                    .isEqualTo("investigation.workflow-dispatcher-unavailable");
            });
    }

    @Test
    void dispatcherLookupFailureRemainsSafeAndPreservesCause() {
        IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        InvestigationWorkflowAdmissionPreflight preflight =
            new InvestigationWorkflowAdmissionPreflight(authorizer, jdbcTemplate);
        InvestigationCommand.Start command = command();
        InvestigationExecutionContext context = context(command, authorized(command, 1));
        DataAccessResourceFailureException failure =
            new DataAccessResourceFailureException("dispatcher lookup down");
        when(authorizer.requireEvidence(
            context.principal(),
            command.organizationId(),
            command.projectId(),
            command.incidentId()
        )).thenReturn(authorized(command, 3));
        when(jdbcTemplate.queryForObject(
            eq(dispatcherEligibilitySql()),
            eq(UUID.class),
            eq(command.organizationId())
        )).thenThrow(failure);

        assertThatThrownBy(() -> preflight.requireFreshAdmission(command, context))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.status().value()).isEqualTo(503);
                assertThat(exception.code())
                    .isEqualTo("investigation.workflow-dispatcher-unavailable");
                assertThat(exception.getCause()).isSameAs(failure);
            });
    }

    @Test
    void existingHandoffReadReauthorizesWithoutRequiringDispatcherAvailability() {
        IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        InvestigationWorkflowAdmissionPreflight preflight =
            new InvestigationWorkflowAdmissionPreflight(authorizer, jdbcTemplate);
        InvestigationCommand.Start command = command();
        InvestigationExecutionContext context = context(command, authorized(command, 1));
        AuthorizedIncidentAnalysisEvidence refreshed = authorized(command, 9);
        when(authorizer.requireEvidence(
            context.principal(),
            command.organizationId(),
            command.projectId(),
            command.incidentId()
        )).thenReturn(refreshed);

        assertThat(preflight.requireFreshOperatorAccess(command, context))
            .isSameAs(refreshed);

        verifyNoInteractions(jdbcTemplate);
    }

    private String dispatcherEligibilitySql() {
        return "SELECT public.opsmind_lock_eligible_investigation_dispatcher(?)";
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

    private InvestigationExecutionContext context(
        InvestigationCommand.Start command,
        AuthorizedIncidentAnalysisEvidence authorized
    ) {
        return new InvestigationExecutionContext(
            new OpsMindPrincipal(
                URI.create("https://idp.example.test/opsmind"),
                "operator-001",
                null,
                null,
                Set.of("incident:analyze")
            ),
            authorized
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorized(
        InvestigationCommand.Start command,
        long version
    ) {
        return new AuthorizedIncidentAnalysisEvidence(
            command.organizationId(),
            command.projectId(),
            command.incidentId(),
            command.actorId(),
            "Latency",
            "Redacted spike",
            IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING,
            null,
            null,
            version
        );
    }
}
