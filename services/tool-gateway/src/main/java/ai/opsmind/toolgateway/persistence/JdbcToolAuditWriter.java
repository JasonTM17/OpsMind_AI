package ai.opsmind.toolgateway.persistence;

import java.util.UUID;

import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcToolAuditWriter implements ToolAuditWriter {

    private final JdbcTemplate jdbc;

    public JdbcToolAuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean available() {
        try {
            Boolean available = jdbc.queryForObject(
                "SELECT to_regclass('tool_gateway.tool_audit_events') IS NOT NULL "
                    + "AND has_table_privilege(current_user, "
                    + "'tool_gateway.tool_audit_events', 'INSERT')",
                Boolean.class
            );
            return Boolean.TRUE.equals(available);
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public UUID record(
        UUID executionId,
        ToolOutcome outcome,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        String resultDigest,
        String policyVersion,
        DenialCode denialCode
    ) {
        UUID auditId = UUID.randomUUID();
        int inserted = jdbc.update(
            "INSERT INTO tool_gateway.tool_audit_events "
                + "(audit_event_id, execution_id, outcome, request_digest, capability_id, "
                + "manifest_version, result_digest, policy_version, denial_code) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            auditId, executionId, outcome.name(), requestDigest, capabilityId,
            manifestVersion, resultDigest, policyVersion,
            denialCode == null ? null : denialCode.value()
        );
        if (inserted != 1) throw new IllegalStateException("Tool audit append failed.");
        return auditId;
    }
}
