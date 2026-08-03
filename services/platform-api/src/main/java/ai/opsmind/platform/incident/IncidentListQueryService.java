package ai.opsmind.platform.incident;

import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class IncidentListQueryService {

    private final TransactionTemplate transactions;
    private final IncidentAccessRepository accessRepository;
    private final IncidentListRepository listRepository;
    private final IncidentListPageToken pageToken;

    IncidentListQueryService(
        PlatformTransactionManager transactionManager,
        IncidentAccessRepository accessRepository,
        IncidentListRepository listRepository,
        IncidentListPageToken pageToken
    ) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.accessRepository = accessRepository;
        this.listRepository = listRepository;
        this.pageToken = pageToken;
    }

    IncidentListPage list(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        IncidentStatus status,
        int pageSize,
        String rawPageToken
    ) {
        IncidentScopePolicy.require(principal, IncidentScopePolicy.READ_SCOPE);
        IncidentCommandValidator.requireCollectionIds(organizationId, projectId);
        IncidentCommandValidator.requirePageSize(pageSize);
        IncidentListPageToken.Claims claims = pageToken.parse(rawPageToken);
        try {
            IncidentListPage result = transactions.execute(transactionStatus -> {
                accessRepository.requireAccess(
                    principal,
                    organizationId,
                    projectId,
                    IncidentAccessMode.READ
                );
                IncidentListPageToken.Cursor cursor = pageToken.bind(
                    claims, organizationId, projectId, status
                );
                List<IncidentSummary> queried = listRepository.list(
                    organizationId, projectId, status, cursor, pageSize + 1
                );
                boolean hasMore = queried.size() > pageSize;
                List<IncidentSummary> items = hasMore
                    ? List.copyOf(queried.subList(0, pageSize))
                    : List.copyOf(queried);
                String nextToken = hasMore
                    ? encodeNext(organizationId, projectId, status, items.getLast())
                    : null;
                return new IncidentListPage(items, pageSize, nextToken, hasMore);
            });
            if (result == null) {
                throw new IllegalStateException("Incident list transaction returned no result.");
            }
            return result;
        }
        catch (TransactionException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "incident.transaction-unavailable",
                "The incident transaction could not be completed.",
                exception
            );
        }
    }

    private String encodeNext(
        UUID organizationId,
        UUID projectId,
        IncidentStatus status,
        IncidentSummary lastItem
    ) {
        return pageToken.encode(
            organizationId, projectId, status, lastItem.updatedAt(), lastItem.id()
        );
    }
}
