package ai.opsmind.platform.incident;

import java.sql.SQLException;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.tenancy.TenantContextSql;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class JdbcIncidentAccessRepository implements IncidentAccessRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContextSql tenantContextSql;

    JdbcIncidentAccessRepository(JdbcTemplate jdbcTemplate, TenantContextSql tenantContextSql) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContextSql = tenantContextSql;
    }

    @Override
    public IncidentActor requireAccess(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        IncidentAccessMode mode
    ) {
        try {
            UserIdentity user = resolveUser(principal);
            if (!"active".equals(user.status())) {
                throw new PlatformProblemException(
                    HttpStatus.FORBIDDEN,
                    "identity.deprovisioned",
                    "The verified principal is not active."
                );
            }
            applyTenantContext(organizationId, user.id());
            AccessIdentity access = resolveAccess(principal, organizationId, projectId);
            if (!user.id().equals(access.userId())
                || !"active".equals(access.userStatus())
                || !"active".equals(access.organizationStatus())
                || !"active".equals(access.organizationMembershipStatus())
                || !"active".equals(access.projectStatus())
                || !"active".equals(access.projectMembershipStatus())) {
                throw IncidentRolePolicy.hiddenDenial();
            }
            IncidentRolePolicy.requireAllowed(
                access.organizationRole(), access.projectRole(), mode
            );
            return new IncidentActor(
                access.userId(), access.organizationRole(), access.projectRole()
            );
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw databaseUnavailable(exception);
        }
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
            throw IncidentRolePolicy.hiddenDenial();
        }
    }

    private AccessIdentity resolveAccess(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId
    ) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT user_id, user_status, organization_status, "
                    + "organization_membership_status, organization_role, project_status, "
                    + "project_membership_status, project_role "
                    + "FROM public.opsmind_resolve_incident_access(?, ?, ?, ?)",
                (resultSet, rowNumber) -> new AccessIdentity(
                    resultSet.getObject("user_id", UUID.class),
                    resultSet.getString("user_status"),
                    resultSet.getString("organization_status"),
                    resultSet.getString("organization_membership_status"),
                    resultSet.getString("organization_role"),
                    resultSet.getString("project_status"),
                    resultSet.getString("project_membership_status"),
                    resultSet.getString("project_role")
                ),
                principal.issuer().toString(),
                principal.subject(),
                organizationId,
                projectId
            );
        }
        catch (EmptyResultDataAccessException exception) {
            throw IncidentRolePolicy.hiddenDenial();
        }
    }

    private void applyTenantContext(UUID organizationId, UUID actorId) {
        try {
            tenantContextSql.apply(organizationId, actorId);
        }
        catch (DataAccessException exception) {
            if (hasSqlState(exception, "P0001")) {
                throw IncidentRolePolicy.hiddenDenial();
            }
            throw exception;
        }
    }

    private boolean hasSqlState(Throwable throwable, String expected) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                && expected.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private PlatformProblemException databaseUnavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "dependency.database-unavailable",
            "Incident authorization is temporarily unavailable.",
            cause
        );
    }

    private record AccessIdentity(
        UUID userId,
        String userStatus,
        String organizationStatus,
        String organizationMembershipStatus,
        String organizationRole,
        String projectStatus,
        String projectMembershipStatus,
        String projectRole
    ) {
    }

    private record UserIdentity(UUID id, String status) {
    }
}
