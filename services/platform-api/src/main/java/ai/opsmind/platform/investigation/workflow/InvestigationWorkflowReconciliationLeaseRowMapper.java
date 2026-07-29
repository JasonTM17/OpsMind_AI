package ai.opsmind.platform.investigation.workflow;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

import ai.opsmind.platform.messaging.EventEnvelope;

import org.springframework.jdbc.core.RowMapper;

final class InvestigationWorkflowReconciliationLeaseRowMapper
    implements RowMapper<InvestigationWorkflowReconciliationLease> {

    static final InvestigationWorkflowReconciliationLeaseRowMapper INSTANCE =
        new InvestigationWorkflowReconciliationLeaseRowMapper();

    private InvestigationWorkflowReconciliationLeaseRowMapper() {
    }

    @Override
    public InvestigationWorkflowReconciliationLease mapRow(
        ResultSet resultSet,
        int rowNumber
    ) throws SQLException {
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
            new String(resultSet.getBytes("payload_bytes"), StandardCharsets.UTF_8),
            resultSet.getBytes("payload_digest")
        );
        return new InvestigationWorkflowReconciliationLease(
            event,
            resultSet.getObject("lease_token", UUID.class),
            resultSet.getTimestamp("lease_expires_at").toInstant(),
            resultSet.getInt("outbox_attempts"),
            resultSet.getString("temporal_cluster_id"),
            resultSet.getString("temporal_namespace"),
            resultSet.getString("workflow_id"),
            resultSet.getString("workflow_type"),
            resultSet.getString("task_queue"),
            resultSet.getBytes("start_payload_digest"),
            resultSet.getInt("reconciliation_attempt"),
            resultSet.getTimestamp("reconciliation_received_at").toInstant(),
            resultSet.getString("reconciliation_last_code"),
            optionalInstant(resultSet.getTimestamp("reconciliation_last_observed_at"))
        );
    }

    private static java.time.Instant optionalInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
