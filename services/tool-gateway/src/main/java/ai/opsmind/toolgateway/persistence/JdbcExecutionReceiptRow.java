package ai.opsmind.toolgateway.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

record JdbcExecutionReceiptRow(
    UUID tenantId,
    UUID projectId,
    UUID incidentId,
    UUID runId,
    String requestDigest,
    String status,
    boolean leaseActive,
    String responseJson
) {
    static JdbcExecutionReceiptRow from(ResultSet result) throws SQLException {
        return new JdbcExecutionReceiptRow(
            result.getObject("tenant_id", UUID.class),
            result.getObject("project_id", UUID.class),
            result.getObject("incident_id", UUID.class),
            result.getObject("run_id", UUID.class),
            result.getString("request_digest"),
            result.getString("status"),
            result.getBoolean("lease_active"),
            result.getString("response_json")
        );
    }
}
