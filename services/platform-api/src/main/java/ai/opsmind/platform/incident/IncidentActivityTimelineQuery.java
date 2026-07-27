package ai.opsmind.platform.incident;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class IncidentActivityTimelineQuery {

    private IncidentActivityTimelineQuery() {
    }

    static Prepared build(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        IncidentTimelinePageToken.ActivityCursor after,
        int limit
    ) {
        String incidentCursorClause = "";
        String investigationCursorClause = "";
        Timestamp cursorTimestamp = null;
        if (after != null) {
            cursorTimestamp = Timestamp.from(after.occurredAt());
            if (after.sourceRank() == 0) {
                incidentCursorClause = " AND (occurred_at, event_id) > (?, ?)";
                investigationCursorClause = " AND occurred_at >= ?";
            }
            else if (after.sourceRank() == 1) {
                incidentCursorClause = " AND occurred_at > ?";
                investigationCursorClause = " AND (occurred_at, event_id) > (?, ?)";
            }
            else {
                throw new IllegalArgumentException(
                    "Activity timeline cursor source rank is invalid."
                );
            }
        }

        List<Object> parameters = new ArrayList<>();
        parameters.add(organizationId);
        parameters.add(projectId);
        parameters.add(incidentId);
        if (after != null) {
            parameters.add(cursorTimestamp);
            if (after.sourceRank() == 0) parameters.add(after.eventId());
        }
        parameters.add(limit);
        parameters.add(organizationId);
        parameters.add(projectId);
        parameters.add(incidentId);
        if (after != null) {
            parameters.add(cursorTimestamp);
            if (after.sourceRank() == 1) parameters.add(after.eventId());
        }
        parameters.add(limit);
        parameters.add(limit);

        String sql = "SELECT event_id, source, event_type, occurred_at, actor_id, "
            + "incident_version, investigation_run_id, investigation_sequence "
            + "FROM ("
            + "SELECT event_id, source, event_type, occurred_at, actor_id, "
            + "incident_version, investigation_run_id, investigation_sequence, source_rank "
            + "FROM ("
            + "SELECT event_id, 'INCIDENT' AS source, event_kind AS event_type, "
            + "occurred_at, actor_id, incident_version, "
            + "NULL::uuid AS investigation_run_id, NULL::bigint AS investigation_sequence, "
            + "0 AS source_rank "
            + "FROM incident_timeline_events "
            + "WHERE organization_id = ? AND project_id = ? AND incident_id = ?"
            + incidentCursorClause
            + " ORDER BY occurred_at ASC, event_id ASC LIMIT ?"
            + ") incident_activity "
            + " UNION ALL "
            + "SELECT event_id, source, event_type, occurred_at, actor_id, "
            + "incident_version, investigation_run_id, investigation_sequence, source_rank "
            + "FROM ("
            + "SELECT event_id, 'INVESTIGATION' AS source, event_type, occurred_at, actor_id, "
            + "NULL::bigint AS incident_version, run_id AS investigation_run_id, "
            + "sequence_no AS investigation_sequence, 1 AS source_rank "
            + "FROM investigation_run_events "
            + "WHERE organization_id = ? AND project_id = ? AND incident_id = ?"
            + investigationCursorClause
            + " ORDER BY occurred_at ASC, event_id ASC LIMIT ?"
            + ") investigation_activity "
            + ") activity "
            + "ORDER BY occurred_at ASC, source_rank ASC, event_id ASC LIMIT ?";
        return new Prepared(sql, parameters);
    }

    record Prepared(String sql, List<Object> parameters) {
        Prepared {
            parameters = List.copyOf(parameters);
        }
    }
}
