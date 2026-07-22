package ai.opsmind.platform.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.opsmind.platform.common.api.OptimisticConcurrency;
import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class JdbcIncidentRepository implements IncidentRepository {

    private static final String INCIDENT_COLUMNS = "id, organization_id, project_id, title, "
        + "description, severity, status, root_cause, resolution_summary, created_by, updated_by, "
        + "created_at, updated_at, version";

    private final JdbcTemplate jdbcTemplate;

    JdbcIncidentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insert(IncidentSnapshot incident) {
        try {
            jdbcTemplate.update(
                "INSERT INTO incidents (id, organization_id, project_id, title, description, severity, "
                    + "status, root_cause, resolution_summary, created_by, updated_by, created_at, "
                    + "updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                incident.id(),
                incident.organizationId(),
                incident.projectId(),
                incident.title(),
                incident.summary(),
                incident.severity().name(),
                incident.status().name(),
                incident.rootCause(),
                incident.resolutionSummary(),
                incident.createdBy(),
                incident.updatedBy(),
                Timestamp.from(incident.createdAt()),
                Timestamp.from(incident.updatedAt()),
                incident.version()
            );
        }
        catch (DataAccessException exception) {
            throw persistenceProblem(exception);
        }
    }

    @Override
    public Optional<IncidentSnapshot> find(UUID organizationId, UUID projectId, UUID incidentId) {
        return find(organizationId, projectId, incidentId, false);
    }

    @Override
    public Optional<IncidentSnapshot> findForUpdate(
        UUID organizationId,
        UUID projectId,
        UUID incidentId
    ) {
        return find(organizationId, projectId, incidentId, true);
    }

    private Optional<IncidentSnapshot> find(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        boolean lockForUpdate
    ) {
        try {
            return jdbcTemplate.query(
                "SELECT " + INCIDENT_COLUMNS + " FROM incidents "
                    + "WHERE organization_id = ? AND project_id = ? AND id = ?"
                    + (lockForUpdate ? " FOR UPDATE" : ""),
                this::mapIncident,
                organizationId,
                projectId,
                incidentId
            ).stream().findFirst();
        }
        catch (DataAccessException exception) {
            throw persistenceProblem(exception);
        }
    }

    @Override
    public IncidentSnapshot transition(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        long expectedVersion,
        IncidentStatus targetStatus,
        String rootCause,
        String resolutionSummary,
        UUID actorId,
        java.time.Instant occurredAt
    ) {
        try {
            List<IncidentSnapshot> updated = jdbcTemplate.query(
                "UPDATE incidents SET status = ?, root_cause = ?, resolution_summary = ?, "
                    + "updated_by = ?, updated_at = ?, version = version + 1 "
                    + "WHERE organization_id = ? AND project_id = ? AND id = ? AND version = ? "
                    + "RETURNING " + INCIDENT_COLUMNS,
                this::mapIncident,
                targetStatus.name(),
                rootCause,
                resolutionSummary,
                actorId,
                Timestamp.from(occurredAt),
                organizationId,
                projectId,
                incidentId,
                expectedVersion
            );
            OptimisticConcurrency.requireExactlyOneUpdated(updated.size());
            return updated.getFirst();
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw persistenceProblem(exception);
        }
    }

    private IncidentSnapshot mapIncident(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IncidentSnapshot(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("organization_id", UUID.class),
            resultSet.getObject("project_id", UUID.class),
            resultSet.getString("title"),
            resultSet.getString("description"),
            IncidentSeverity.valueOf(resultSet.getString("severity")),
            IncidentStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("root_cause"),
            resultSet.getString("resolution_summary"),
            resultSet.getObject("created_by", UUID.class),
            resultSet.getObject("updated_by", UUID.class),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("updated_at").toInstant(),
            resultSet.getLong("version")
        );
    }

    private PlatformProblemException persistenceProblem(DataAccessException exception) {
        if (hasSqlState(exception, "23505") || hasSqlState(exception, "P4001")
            || hasSqlState(exception, "P4002") || hasSqlState(exception, "P4004")) {
            return new PlatformProblemException(
                HttpStatus.CONFLICT,
                "incident.persistence-conflict",
                "The incident changed before the operation could be applied.",
                exception
            );
        }
        if (hasSqlState(exception, "P4003")) {
            return new PlatformProblemException(
                HttpStatus.CONFLICT,
                "incident.transition-not-allowed",
                "The requested incident transition is not allowed from the current status.",
                exception
            );
        }
        if (exception instanceof DataIntegrityViolationException) {
            return new PlatformProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "incident.persistence-rejected",
                "The incident did not satisfy its persistence contract.",
                exception
            );
        }
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "incident.persistence-unavailable",
            "Incident persistence is temporarily unavailable.",
            exception
        );
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
}
