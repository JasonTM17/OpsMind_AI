package ai.opsmind.toolgateway.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import ai.opsmind.toolgateway.application.ExecutionReceiptStore;
import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class JdbcExecutionReceiptStore implements ExecutionReceiptStore {

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;
    private final ToolExecutionResponseJsonCodec codec;
    private final long leaseMilliseconds;
    private final long completionMarginMilliseconds;
    private final Clock clock;

    public JdbcExecutionReceiptStore(
        JdbcTemplate jdbc,
        tools.jackson.databind.ObjectMapper objectMapper,
        int maximumResponseBytes,
        Duration leaseDuration,
        Duration completionMargin,
        Clock clock
    ) {
        if (leaseDuration == null || leaseDuration.toMillis() < 1) {
            throw new IllegalArgumentException("Execution receipt lease is invalid.");
        }
        if (completionMargin == null || completionMargin.toMillis() < 1) {
            throw new IllegalArgumentException("Execution completion margin is invalid.");
        }
        this.jdbc = jdbc;
        this.codec = new ToolExecutionResponseJsonCodec(objectMapper, maximumResponseBytes);
        this.leaseMilliseconds = leaseDuration.toMillis();
        this.completionMarginMilliseconds = completionMargin.toMillis();
        this.clock = clock;
    }

    @Override
    public boolean available() {
        try {
            return GatewayIsolationReadinessSql.receiptStoreReady(jdbc);
        }
        catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public Claim claim(
        TenantProjectScope scope,
        ToolExecutionRequest request,
        String requestDigest
    ) {
        requireScopedTransaction();
        validateRequest(scope, request, requestDigest);
        return claimInTransaction(scope, request, requestDigest);
    }

    private Claim claimInTransaction(
        TenantProjectScope scope,
        ToolExecutionRequest request,
        String requestDigest
    ) {
        UUID leaseToken = UUID.randomUUID();
        int inserted = jdbc.update(
            "INSERT INTO tool_gateway.execution_receipts "
                + "(execution_id, tenant_id, project_id, incident_id, run_id, request_digest, "
                + "status, lease_token, lease_expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, "
                + "LEAST(? + (? * INTERVAL '1 millisecond'), "
                + "transaction_timestamp() + (? * INTERVAL '1 millisecond'))) "
                + "ON CONFLICT (execution_id) DO NOTHING",
            request.executionId(), scope.tenantId(), scope.projectId(), request.incidentId(),
            request.runId(), requestDigest, leaseToken, Timestamp.from(request.deadlineAt()),
            completionMarginMilliseconds, leaseMilliseconds
        );
        if (inserted == 1) {
            return Claim.claimed(lease(scope, request, requestDigest, leaseToken));
        }

        List<JdbcExecutionReceiptRow> rows = jdbc.query(
            "SELECT tenant_id, project_id, incident_id, run_id, request_digest, status, "
                + "lease_expires_at > transaction_timestamp() AS lease_active, "
                + "response_json::text AS response_json "
                + "FROM tool_gateway.execution_receipts "
                + "WHERE execution_id = ? AND tenant_id = ? AND project_id = ? FOR UPDATE",
            (result, ignored) -> JdbcExecutionReceiptRow.from(result),
            request.executionId(),
            scope.tenantId(),
            scope.projectId()
        );
        if (rows.isEmpty()) return Claim.of(ClaimStatus.CONFLICT);
        JdbcExecutionReceiptRow row = rows.getFirst();
        if (!sameScope(row, request) || !sameDigest(row.requestDigest(), requestDigest)) {
            return Claim.of(ClaimStatus.CONFLICT);
        }
        if ("COMPLETED".equals(row.status())) {
            ToolExecutionResponse response = codec.read(row.responseJson());
            validateCompletedResponse(request.executionId(), requestDigest, response);
            return new Claim(ClaimStatus.REPLAY, response, null);
        }
        if (!"IN_PROGRESS".equals(row.status())) {
            throw new IllegalStateException("Stored execution receipt state is invalid.");
        }
        if (row.leaseActive()) {
            return Claim.of(ClaimStatus.IN_PROGRESS);
        }
        int reclaimed = jdbc.update(
            "UPDATE tool_gateway.execution_receipts "
                + "SET lease_token = ?, lease_expires_at = "
                + "LEAST(? + (? * INTERVAL '1 millisecond'), "
                + "transaction_timestamp() + (? * INTERVAL '1 millisecond')), "
                + "updated_at = transaction_timestamp() "
                + "WHERE execution_id = ? AND status = 'IN_PROGRESS' "
                + "AND tenant_id = ? AND project_id = ? "
                + "AND lease_expires_at <= transaction_timestamp()",
            leaseToken, Timestamp.from(request.deadlineAt()),
            completionMarginMilliseconds, leaseMilliseconds,
            request.executionId(), scope.tenantId(), scope.projectId()
        );
        if (reclaimed != 1) throw new IllegalStateException("Execution receipt reclaim failed.");
        return Claim.claimed(lease(scope, request, requestDigest, leaseToken));
    }

    @Override
    public void complete(Lease lease, ToolExecutionResponse response) {
        if (lease == null) throw new IllegalArgumentException("Execution lease is required.");
        requireScopedTransaction();
        validateCompletedResponse(lease.executionId(), lease.requestDigest(), response);
        String json = codec.write(response);
        int updated = jdbc.update(
            "UPDATE tool_gateway.execution_receipts "
                + "SET status = 'COMPLETED', lease_token = NULL, lease_expires_at = NULL, "
                + "response_json = CAST(? AS jsonb), completed_at = transaction_timestamp(), "
                + "updated_at = transaction_timestamp() "
                + "WHERE execution_id = ? AND request_digest = ? AND status = 'IN_PROGRESS' "
                + "AND tenant_id = ? AND project_id = ? "
                + "AND lease_token = ? AND lease_expires_at > transaction_timestamp()",
            json, lease.executionId(), lease.requestDigest(),
            lease.scope().tenantId(), lease.scope().projectId(), lease.token()
        );
        if (updated != 1) throw new IllegalStateException("Execution receipt lease was lost.");
    }

    @Override
    public void abandon(Lease lease) {
        if (lease == null) throw new IllegalArgumentException("Execution lease is required.");
        requireScopedTransaction();
        jdbc.update(
            "UPDATE tool_gateway.execution_receipts "
                + "SET lease_expires_at = transaction_timestamp(), "
                + "updated_at = transaction_timestamp() "
                + "WHERE execution_id = ? AND request_digest = ? AND status = 'IN_PROGRESS' "
                + "AND tenant_id = ? AND project_id = ? "
                + "AND lease_token = ?",
            lease.executionId(), lease.requestDigest(),
            lease.scope().tenantId(), lease.scope().projectId(), lease.token()
        );
    }

    private Lease lease(
        TenantProjectScope scope,
        ToolExecutionRequest request,
        String digest,
        UUID token
    ) {
        return new Lease(scope, request.executionId(), digest, token);
    }

    private boolean sameScope(JdbcExecutionReceiptRow row, ToolExecutionRequest request) {
        return request.tenantId().equals(row.tenantId())
            && request.projectId().equals(row.projectId())
            && request.incidentId().equals(row.incidentId())
            && request.runId().equals(row.runId());
    }

    private boolean sameDigest(String expected, String actual) {
        return expected != null && actual != null && DIGEST.matcher(expected).matches()
            && DIGEST.matcher(actual).matches() && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
            );
    }

    private void validateRequest(
        TenantProjectScope scope,
        ToolExecutionRequest request,
        String requestDigest
    ) {
        if (scope == null || !scope.matches(request)
            || request.executionId() == null || request.tenantId() == null
            || request.projectId() == null || request.incidentId() == null || request.runId() == null
            || request.deadlineAt() == null || !request.deadlineAt().isAfter(clock.instant())
            || requestDigest == null || !DIGEST.matcher(requestDigest).matches()) {
            throw new IllegalArgumentException("Execution receipt claim is invalid.");
        }
    }

    private void requireScopedTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Execution receipt operation requires a scoped transaction.");
        }
    }

    private void validateCompletedResponse(
        UUID executionId,
        String requestDigest,
        ToolExecutionResponse response
    ) {
        if (response == null
            || !executionId.equals(response.executionId())
            || !sameDigest(requestDigest, response.requestDigest())
            || response.status() != ToolOutcome.SUCCEEDED
            || response.auditEventId() == null
            || response.denialCode() != null
            || response.duplicate()) {
            throw new IllegalStateException("Completed execution response is inconsistent.");
        }
    }
}
