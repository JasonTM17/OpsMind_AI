package ai.opsmind.platform.incident;

import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.EvidenceRecordReader;
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
    private final EvidenceRecordReader evidenceReader;

    IncidentAnalysisAuthorizer(
        PlatformTransactionManager transactionManager,
        IncidentAccessRepository accessRepository,
        IncidentRepository incidentRepository,
        EvidenceRecordReader evidenceReader
    ) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.accessRepository = accessRepository;
        this.incidentRepository = incidentRepository;
        this.evidenceReader = evidenceReader;
    }

    public UUID requireAccess(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId
    ) {
        return requireEvidence(principal, organizationId, projectId, incidentId).actorId();
    }

    public UUID requireReadAccess(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId
    ) {
        return authorize(
            principal, organizationId, projectId, incidentId,
            IncidentScopePolicy.READ_SCOPE, IncidentAccessMode.READ,
            (incident, actor) -> actor.id()
        );
    }

    public AuthorizedIncidentAnalysisEvidence requireEvidence(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId
    ) {
        return authorize(
            principal, organizationId, projectId, incidentId,
            IncidentScopePolicy.ANALYZE_SCOPE, IncidentAccessMode.ANALYZE,
            (incident, actor) -> AuthorizedIncidentAnalysisEvidence.from(incident, actor.id()));
    }

    public AuthorizedIncidentAnalysisContext requireEvidenceRecords(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        List<UUID> evidenceIds
    ) {
        if (runId == null) throw new IllegalArgumentException("Investigation run is required.");
        return authorize(
            principal, organizationId, projectId, incidentId,
            IncidentScopePolicy.ANALYZE_SCOPE, IncidentAccessMode.ANALYZE,
            (incident, actor) ->
                new AuthorizedIncidentAnalysisContext(
                    AuthorizedIncidentAnalysisEvidence.from(incident, actor.id()),
                    evidenceReader.resolve(
                        organizationId, projectId, incidentId, runId, evidenceIds
                    )
                )
        );
    }

    private <T> T authorize(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        String requiredScope,
        IncidentAccessMode accessMode,
        AuthorizedWork<T> work
    ) {
        IncidentScopePolicy.require(principal, requiredScope);
        IncidentCommandValidator.requireResourceIds(organizationId, projectId, incidentId);
        try {
            T result = transactions.execute(status -> {
                IncidentActor actor = accessRepository.requireAccess(
                    principal, organizationId, projectId, accessMode
                );
                IncidentSnapshot incident = incidentRepository.find(
                    organizationId, projectId, incidentId
                ).orElseThrow(IncidentRolePolicy::hiddenDenial);
                return work.run(incident, actor);
            });
            if (result == null) {
                throw new IllegalStateException("Incident analysis authorization returned no result.");
            }
            return result;
        }
        catch (TransactionException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE, "incident.transaction-unavailable",
                "The incident authorization transaction could not be completed.", exception
            );
        }
    }

    @FunctionalInterface
    private interface AuthorizedWork<T> {
        T run(IncidentSnapshot incident, IncidentActor actor);
    }
}
