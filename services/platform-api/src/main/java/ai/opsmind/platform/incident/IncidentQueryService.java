package ai.opsmind.platform.incident;

import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.OptimisticConcurrency;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class IncidentQueryService {

    private final TransactionTemplate transactions;
    private final IncidentAccessRepository accessRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentTimelineRepository timelineRepository;
    private final IncidentTimelinePageToken pageToken;

    IncidentQueryService(
        PlatformTransactionManager transactionManager,
        IncidentAccessRepository accessRepository,
        IncidentRepository incidentRepository,
        IncidentTimelineRepository timelineRepository,
        IncidentTimelinePageToken pageToken
    ) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.accessRepository = accessRepository;
        this.incidentRepository = incidentRepository;
        this.timelineRepository = timelineRepository;
        this.pageToken = pageToken;
    }

    IncidentDetailResult detail(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId
    ) {
        IncidentScopePolicy.require(principal, IncidentScopePolicy.READ_SCOPE);
        IncidentCommandValidator.requireResourceIds(organizationId, projectId, incidentId);
        try {
            IncidentDetailResult result = transactions.execute(status -> {
                accessRepository.requireAccess(
                    principal,
                    organizationId,
                    projectId,
                    IncidentAccessMode.READ
                );
                IncidentSnapshot incident = incidentRepository.find(organizationId, projectId, incidentId)
                    .orElseThrow(IncidentRolePolicy::hiddenDenial);
                return new IncidentDetailResult(
                    IncidentResponse.from(incident),
                    OptimisticConcurrency.etag(incident.version())
                );
            });
            return requireResult(result);
        }
        catch (TransactionException exception) {
            throw transactionUnavailable(exception);
        }
    }

    IncidentTimelinePage timeline(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        int pageSize,
        String rawPageToken
    ) {
        IncidentScopePolicy.require(principal, IncidentScopePolicy.READ_SCOPE);
        IncidentCommandValidator.requireResourceIds(organizationId, projectId, incidentId);
        IncidentCommandValidator.requirePageSize(pageSize);
        Long afterVersion = pageToken.decode(rawPageToken, incidentId);
        try {
            IncidentTimelinePage result = transactions.execute(status -> {
                accessRepository.requireAccess(
                    principal,
                    organizationId,
                    projectId,
                    IncidentAccessMode.READ
                );
                incidentRepository.find(organizationId, projectId, incidentId)
                    .orElseThrow(IncidentRolePolicy::hiddenDenial);
                List<IncidentTimelineEvent> queried = timelineRepository.list(
                    organizationId,
                    projectId,
                    incidentId,
                    afterVersion,
                    pageSize + 1
                );
                boolean hasMore = queried.size() > pageSize;
                List<IncidentTimelineEvent> items = hasMore
                    ? List.copyOf(queried.subList(0, pageSize))
                    : List.copyOf(queried);
                String nextToken = hasMore
                    ? pageToken.encode(incidentId, items.getLast().incidentVersion())
                    : null;
                return new IncidentTimelinePage(items, pageSize, nextToken, hasMore);
            });
            return requireResult(result);
        }
        catch (TransactionException exception) {
            throw transactionUnavailable(exception);
        }
    }

    private <T> T requireResult(T result) {
        if (result == null) {
            throw new IllegalStateException("Incident query transaction returned no result.");
        }
        return result;
    }

    private PlatformProblemException transactionUnavailable(TransactionException cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "incident.transaction-unavailable",
            "The incident transaction could not be completed.",
            cause
        );
    }
}
