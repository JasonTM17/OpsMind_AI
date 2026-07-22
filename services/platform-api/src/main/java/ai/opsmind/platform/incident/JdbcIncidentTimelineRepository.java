package ai.opsmind.platform.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class JdbcIncidentTimelineRepository implements IncidentTimelineRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcIncidentTimelineRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(IncidentTimelineEvent event, String payloadJson, String externalTraceId) {
        try {
            jdbcTemplate.update(
                "INSERT INTO incident_timeline_events (event_id, organization_id, project_id, incident_id, "
                    + "incident_version, event_kind, actor_id, operation_id, external_trace_id, reason, "
                    + "payload, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)",
                event.eventId(), event.organizationId(), event.projectId(), event.incidentId(),
                event.incidentVersion(), event.eventType(), event.actorId(), event.operationId(),
                externalTraceId, event.reason(), payloadJson, Timestamp.from(event.occurredAt())
            );
        }
        catch (DataAccessException exception) {
            throw persistenceProblem(exception);
        }
    }

    @Override
    public List<IncidentTimelineEvent> list(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        Long afterIncidentVersion,
        int limit
    ) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(organizationId);
        parameters.add(projectId);
        parameters.add(incidentId);
        String cursorClause = "";
        if (afterIncidentVersion != null) {
            cursorClause = " AND incident_version > ?";
            parameters.add(afterIncidentVersion);
        }
        parameters.add(limit);
        try {
            return jdbcTemplate.query(
                "SELECT event_id, organization_id, project_id, incident_id, incident_version, "
                    + "event_kind, actor_id, operation_id, occurred_at, reason, "
                    + "payload ->> 'fromStatus' AS from_status, payload ->> 'toStatus' AS to_status, "
                    + "payload ->> 'rootCause' AS root_cause, "
                    + "payload ->> 'resolutionSummary' AS resolution_summary "
                    + "FROM incident_timeline_events WHERE organization_id = ? AND project_id = ? "
                    + "AND incident_id = ?" + cursorClause
                    + " ORDER BY incident_version ASC LIMIT ?",
                this::mapEvent,
                parameters.toArray()
            );
        }
        catch (DataAccessException exception) {
            throw persistenceProblem(exception);
        }
    }

    private IncidentTimelineEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IncidentTimelineEvent(
            resultSet.getObject("event_id", UUID.class),
            resultSet.getObject("organization_id", UUID.class),
            resultSet.getObject("project_id", UUID.class),
            resultSet.getObject("incident_id", UUID.class),
            resultSet.getLong("incident_version"),
            resultSet.getString("event_kind"),
            resultSet.getObject("actor_id", UUID.class),
            resultSet.getObject("operation_id", UUID.class),
            resultSet.getTimestamp("occurred_at").toInstant(),
            resultSet.getString("reason"),
            status(resultSet.getString("from_status")),
            status(resultSet.getString("to_status")),
            resultSet.getString("root_cause"),
            resultSet.getString("resolution_summary")
        );
    }

    private IncidentStatus status(String value) {
        return value == null ? null : IncidentStatus.valueOf(value);
    }

    private PlatformProblemException persistenceProblem(DataAccessException exception) {
        if (hasSqlState(exception, "23505") || hasSqlState(exception, "P4004")) {
            return new PlatformProblemException(
                HttpStatus.CONFLICT,
                "incident.timeline-conflict",
                "The incident timeline changed before the event could be appended.",
                exception
            );
        }
        if (exception instanceof DataIntegrityViolationException) {
            return new PlatformProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "incident.timeline-rejected",
                "The timeline event did not satisfy its persistence contract.",
                exception
            );
        }
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "incident.timeline-unavailable",
            "Incident timeline persistence is temporarily unavailable.",
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
