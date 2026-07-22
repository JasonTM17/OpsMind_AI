package ai.opsmind.platform.tenancy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public final class TenantProjectQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final PageTokenCodec pageTokenCodec;
    private final TenantContextSql tenantContextSql;

    public TenantProjectQueryService(
        JdbcTemplate jdbcTemplate,
        PlatformTransactionManager transactionManager,
        PageTokenCodec pageTokenCodec,
        TenantContextSql tenantContextSql
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setReadOnly(true);
        this.pageTokenCodec = pageTokenCodec;
        this.tenantContextSql = tenantContextSql;
    }

    public ProjectPage listProjects(
        OpsMindPrincipal principal,
        UUID organizationId,
        int pageSize,
        String pageToken
    ) {
        if (principal == null || organizationId == null || pageSize < 1 || pageSize > 100) {
            throw new PlatformProblemException(
                HttpStatus.BAD_REQUEST,
                "request.validation-failed",
                "The request did not satisfy the API contract."
            );
        }
        UUID afterProjectId = pageTokenCodec.decode(pageToken);
        try {
            ProjectPage page = transactionTemplate.execute(status -> listWithinTransaction(
                principal,
                organizationId,
                pageSize,
                afterProjectId
            ));
            if (page == null) {
                throw dependencyUnavailable();
            }
            return page;
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw dependencyUnavailable(exception);
        }
    }

    private ProjectPage listWithinTransaction(
        OpsMindPrincipal principal,
        UUID organizationId,
        int pageSize,
        UUID afterProjectId
    ) {
        UserIdentity user = resolveUser(principal);
        tenantContextSql.apply(organizationId, user.id());
        resolveMembershipRole(organizationId, user.id());
        List<ProjectSummary> queried = queryProjects(organizationId, pageSize + 1, afterProjectId);
        boolean hasMore = queried.size() > pageSize;
        List<ProjectSummary> items = hasMore
            ? List.copyOf(queried.subList(0, pageSize))
            : List.copyOf(queried);
        String nextPageToken = hasMore ? pageTokenCodec.encode(items.get(items.size() - 1).id()) : null;
        return new ProjectPage(items, pageSize, nextPageToken, hasMore);
    }

    private UserIdentity resolveUser(OpsMindPrincipal principal) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT id, status FROM public.opsmind_resolve_user(?, ?)",
                (resultSet, rowNumber) -> new UserIdentity(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("status")
                ),
                principal.issuer().toString(),
                principal.subject()
            );
        }
        catch (EmptyResultDataAccessException exception) {
            throw hiddenAuthorizationDenial();
        }
    }

    private String resolveMembershipRole(UUID organizationId, UUID userId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT role FROM organization_memberships "
                    + "WHERE organization_id = ? AND user_id = ? AND status = 'active'",
                String.class,
                organizationId,
                userId
            );
        }
        catch (EmptyResultDataAccessException exception) {
            throw hiddenAuthorizationDenial();
        }
    }

    private List<ProjectSummary> queryProjects(UUID organizationId, int limit, UUID afterProjectId) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(organizationId);
        String afterClause = "";
        if (afterProjectId != null) {
            afterClause = " AND id > ?";
            parameters.add(afterProjectId);
        }
        parameters.add(limit);
        return jdbcTemplate.query(
            "SELECT id, organization_id, slug, name, version FROM projects "
                + "WHERE organization_id = ? AND status = 'active'" + afterClause
                + " ORDER BY id LIMIT ?",
            this::mapProject,
            parameters.toArray()
        );
    }

    private ProjectSummary mapProject(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProjectSummary(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("organization_id", UUID.class),
            resultSet.getString("slug"),
            resultSet.getString("name"),
            resultSet.getLong("version")
        );
    }

    private PlatformProblemException hiddenAuthorizationDenial() {
        return new PlatformProblemException(
            HttpStatus.NOT_FOUND,
            "resource.not-found",
            "The resource does not exist or is not visible to this principal."
        );
    }

    private PlatformProblemException dependencyUnavailable() {
        return dependencyUnavailable(null);
    }

    private PlatformProblemException dependencyUnavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "dependency.database-unavailable",
            "Tenant data is temporarily unavailable.",
            cause
        );
    }

    private record UserIdentity(UUID id, String status) {
        UserIdentity {
            if (!"active".equals(status)) {
                throw new PlatformProblemException(
                    HttpStatus.FORBIDDEN,
                    "identity.deprovisioned",
                    "The verified principal is not active."
                );
            }
        }
    }

    public record ProjectSummary(UUID id, UUID organizationId, String slug, String name, long version) {
    }

    public record ProjectPage(
        List<ProjectSummary> items,
        int pageSize,
        String nextPageToken,
        boolean hasMore
    ) {
    }
}
