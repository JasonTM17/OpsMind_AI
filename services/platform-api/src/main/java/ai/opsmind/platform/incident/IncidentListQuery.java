package ai.opsmind.platform.incident;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class IncidentListQuery {

    private static final String SELECT_COLUMNS =
        "SELECT id, title, severity, status, updated_at, version FROM incidents ";

    private IncidentListQuery() {
    }

    static Prepared build(
        UUID organizationId,
        UUID projectId,
        IncidentStatus status,
        IncidentListPageToken.Cursor after,
        int limit
    ) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
            .append("WHERE organization_id = ? AND project_id = ? ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(organizationId);
        parameters.add(projectId);
        if (status != null) {
            sql.append("AND status = ? ");
            parameters.add(status.name());
        }
        if (after != null) {
            sql.append("AND (updated_at, id) < (?, ?) ");
            parameters.add(Timestamp.from(after.updatedAt()));
            parameters.add(after.incidentId());
        }
        sql.append("ORDER BY updated_at DESC, id DESC LIMIT ?");
        parameters.add(limit);
        return new Prepared(sql.toString(), List.copyOf(parameters));
    }

    record Prepared(String sql, List<Object> parameters) {
    }
}
