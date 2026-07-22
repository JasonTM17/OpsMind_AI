package ai.opsmind.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;

final class OutboxLeaseRowMapper implements RowMapper<OutboxLease> {

    static final OutboxLeaseRowMapper INSTANCE = new OutboxLeaseRowMapper();

    private OutboxLeaseRowMapper() {
    }

    @Override
    public OutboxLease mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        byte[] payloadBytes = resultSet.getBytes("payload_bytes");
        EventEnvelope event = new EventEnvelope(
            resultSet.getObject("event_id", UUID.class),
            resultSet.getObject("organization_id", UUID.class),
            resultSet.getString("aggregate_type"),
            resultSet.getObject("aggregate_id", UUID.class),
            resultSet.getLong("aggregate_sequence"),
            resultSet.getString("event_type"),
            resultSet.getString("schema_version"),
            resultSet.getObject("causation_id", UUID.class),
            resultSet.getObject("correlation_id", UUID.class),
            resultSet.getTimestamp("occurred_at").toInstant(),
            new String(payloadBytes, StandardCharsets.UTF_8),
            resultSet.getBytes("payload_digest")
        );
        return new OutboxLease(
            event,
            resultSet.getObject("lease_token", UUID.class),
            resultSet.getTimestamp("lease_expires_at").toInstant(),
            resultSet.getInt("attempts")
        );
    }
}
