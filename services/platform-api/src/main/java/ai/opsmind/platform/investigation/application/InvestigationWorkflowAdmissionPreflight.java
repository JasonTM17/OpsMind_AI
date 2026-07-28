package ai.opsmind.platform.investigation.application;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;

import org.springframework.dao.DataAccessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Revalidates operator access and new-handoff eligibility inside the
 * authoritative repository transaction.
 */
@Component
@Profile("persistence")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "store", havingValue = "postgres")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "execution-mode", havingValue = "temporal")
final class InvestigationWorkflowAdmissionPreflight {

    private final IncidentAnalysisAuthorizer authorizer;
    private final JdbcTemplate jdbcTemplate;

    InvestigationWorkflowAdmissionPreflight(
        IncidentAnalysisAuthorizer authorizer,
        JdbcTemplate jdbcTemplate
    ) {
        this.authorizer = authorizer;
        this.jdbcTemplate = jdbcTemplate;
    }

    AuthorizedIncidentAnalysisEvidence requireFreshAdmission(
        InvestigationCommand.Start command,
        InvestigationExecutionContext context
    ) {
        AuthorizedIncidentAnalysisEvidence authorized =
            requireFreshOperatorAccess(command, context);
        requireEligibleDispatcher(command.organizationId());
        return authorized;
    }

    AuthorizedIncidentAnalysisEvidence requireFreshOperatorAccess(
        InvestigationCommand.Start command,
        InvestigationExecutionContext context
    ) {
        if (command == null || context == null) {
            throw new IllegalArgumentException("Durable investigation admission requires context.");
        }
        return authorizer.requireEvidence(
            context.principal(),
            command.organizationId(),
            command.projectId(),
            command.incidentId()
        );
    }

    private void requireEligibleDispatcher(java.util.UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Dispatcher admission requires an organization.");
        }
        try {
            java.util.UUID accountId = jdbcTemplate.queryForObject(
                "SELECT public.opsmind_lock_eligible_investigation_dispatcher(?)",
                java.util.UUID.class,
                organizationId
            );
            if (accountId == null) throw dispatcherUnavailable(null);
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw dispatcherUnavailable(exception);
        }
    }

    private PlatformProblemException dispatcherUnavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "investigation.workflow-dispatcher-unavailable",
            "Durable investigation admission is temporarily unavailable.",
            cause
        );
    }
}
