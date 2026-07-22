package ai.opsmind.platform.incident;

import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Short authorization transaction used before any model-network operation. */
@Service
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class IncidentAnalysisAuthorizer {

    private final TransactionTemplate transactions;
    private final IncidentAccessRepository accessRepository;
    private final IncidentRepository incidentRepository;

    IncidentAnalysisAuthorizer(
        PlatformTransactionManager transactionManager,
        IncidentAccessRepository accessRepository,
        IncidentRepository incidentRepository
    ) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.accessRepository = accessRepository;
        this.incidentRepository = incidentRepository;
    }

    public UUID requireAccess(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId
    ) {
        return requireEvidence(principal, organizationId, projectId, incidentId).actorId();
    }

    public AuthorizedIncidentAnalysisEvidence requireEvidence(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId
    ) {
        IncidentScopePolicy.require(principal, IncidentScopePolicy.ANALYZE_SCOPE);
        IncidentCommandValidator.requireResourceIds(organizationId, projectId, incidentId);
        try {
            AuthorizedIncidentAnalysisEvidence evidence = transactions.execute(status -> {
                IncidentActor actor = accessRepository.requireAccess(
                    principal,
                    organizationId,
                    projectId,
                    IncidentAccessMode.ANALYZE
                );
                IncidentSnapshot incident = incidentRepository.find(
                    organizationId,
                    projectId,
                    incidentId
                )
                    .orElseThrow(IncidentRolePolicy::hiddenDenial);
                return AuthorizedIncidentAnalysisEvidence.from(incident, actor.id());
            });
            if (evidence == null) {
                throw new IllegalStateException("Incident analysis authorization returned no evidence.");
            }
            return evidence;
        }
        catch (TransactionException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "incident.transaction-unavailable",
                "The incident authorization transaction could not be completed.",
                exception
            );
        }
    }
}
