package ai.opsmind.platform.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class JdbcIncidentListRepository implements IncidentListRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcIncidentListRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<IncidentSummary> list(
        UUID organizationId,
        UUID projectId,
        IncidentStatus status,
        IncidentListPageToken.Cursor after,
        int limit
    ) {
        IncidentListQuery.Prepared query = IncidentListQuery.build(
            organizationId, projectId, status, after, limit
        );
        try {
            return jdbcTemplate.query(
                query.sql(), this::mapSummary, query.parameters().toArray()
            );
        }
        catch (DataAccessException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "incident.list-unavailable",
                "The incident list is temporarily unavailable.",
                exception
            );
        }
    }

    private IncidentSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IncidentSummary(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("title"),
            IncidentSeverity.valueOf(resultSet.getString("severity")),
            IncidentStatus.valueOf(resultSet.getString("status")),
            resultSet.getTimestamp("updated_at").toInstant(),
            resultSet.getLong("version")
        );
    }
}
