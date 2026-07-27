package ai.opsmind.toolgateway.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ai.opsmind.toolgateway.application.ExecutionReceiptStore;
import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.audit.ToolExecutionProvenance;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
    named = "OPSMIND_TOOL_GATEWAY_DB_INTEGRATION",
    matches = "true"
)
class ToolGatewayPersistenceIntegrationTest {

    private final ToolGatewayPostgresTestContext database =
        new ToolGatewayPostgresTestContext();

    @BeforeEach
    void cleanMutableState() {
        database.cleanMutableState();
    }

    @Test
    void concurrentClaimsProduceOneLeaseThenReplayExactResponse() throws Exception {
        var store = database.receiptStore(Duration.ofSeconds(5));
        ToolExecutionRequest request = request(UUID.randomUUID(), Instant.now().plusSeconds(20));
        TenantProjectScope scope = scope(request);
        String digest = ToolGatewayPostgresTestContext.digest(request.executionId().toString());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ExecutionReceiptStore.Claim>> futures;

        try (var pool = Executors.newFixedThreadPool(8)) {
            futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> pool.submit(() -> {
                    start.await();
                    return database.inScope(
                        scope,
                        () -> store.claim(scope, request, digest)
                    );
                }))
                .toList();
            start.countDown();
            List<ExecutionReceiptStore.Claim> claims = futures.stream()
                .map(ToolGatewayPersistenceIntegrationTest::get)
                .toList();
            assertThat(claims).filteredOn(
                claim -> claim.status() == ExecutionReceiptStore.ClaimStatus.CLAIMED
            ).hasSize(1);
            assertThat(claims).filteredOn(
                claim -> claim.status() == ExecutionReceiptStore.ClaimStatus.IN_PROGRESS
            ).hasSize(7);
            ExecutionReceiptStore.Lease lease = claims.stream()
                .filter(claim -> claim.lease() != null)
                .findFirst()
                .orElseThrow()
                .lease();
            ToolExecutionResponse response = response(request.executionId(), digest);
            database.inScope(scope, () -> store.complete(lease, response));

            assertThat(claim(store, request, digest).response()).isEqualTo(response);
            assertThat(claim(
                store,
                request,
                ToolGatewayPostgresTestContext.digest("changed")
            ).status())
                .isEqualTo(ExecutionReceiptStore.ClaimStatus.CONFLICT);
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndOldOwnerIsFenced() throws Exception {
        var store = database.receiptStore(Duration.ofMillis(150));
        ToolExecutionRequest request = request(UUID.randomUUID(), Instant.now().plusSeconds(5));
        String digest = ToolGatewayPostgresTestContext.digest(request.executionId().toString());
        ExecutionReceiptStore.Lease first = claim(store, request, digest).lease();

        Thread.sleep(300);
        ExecutionReceiptStore.Lease second = claim(store, request, digest).lease();

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> complete(
            store,
            first,
            response(request.executionId(), digest)
        ))
            .isInstanceOf(IllegalStateException.class);
        complete(store, second, response(request.executionId(), digest));
    }

    @Test
    void completionAfterRequestDeadlineSucceedsInsideBoundedMargin() throws Exception {
        var store = database.receiptStore(Duration.ofSeconds(10));
        Instant deadline = Instant.now().plusSeconds(1);
        ToolExecutionRequest request = request(UUID.randomUUID(), deadline);
        String digest = ToolGatewayPostgresTestContext.digest(request.executionId().toString());
        ExecutionReceiptStore.Lease lease = claim(store, request, digest).lease();

        Instant leaseExpiry = database.adminJdbc().queryForObject(
            "SELECT lease_expires_at FROM tool_gateway.execution_receipts "
                + "WHERE execution_id = ?",
            java.sql.Timestamp.class,
            request.executionId()
        ).toInstant();
        assertThat(leaseExpiry).isAfter(deadline.plusSeconds(4));
        assertThat(leaseExpiry).isBefore(deadline.plusSeconds(6));

        Thread.sleep(1_200);
        complete(store, lease, response(request.executionId(), digest));
    }

    @Test
    void auditAndReceiptFinalizeAtomicallyAndAuditRejectsMutation() {
        var store = database.receiptStore(Duration.ofSeconds(5));
        var audit = database.auditWriter();
        ToolExecutionRequest request = request(UUID.randomUUID(), Instant.now().plusSeconds(10));
        TenantProjectScope scope = scope(request);
        String digest = ToolGatewayPostgresTestContext.digest(request.executionId().toString());
        ExecutionReceiptStore.Lease lease = claim(store, request, digest).lease();

        assertThatThrownBy(() -> database.transactionRunner().required(scope, () -> {
            UUID inserted = audit.recordScoped(
                scope, request.executionId(), ToolOutcome.SUCCEEDED, digest,
                "capability-test", "manifest-v1", provenance(), digest, "policy-v1", null
            );
            assertThat(inserted).isNotNull();
            store.complete(lease, response(request.executionId(), digest));
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(claim(store, request, digest).status())
            .isEqualTo(ExecutionReceiptStore.ClaimStatus.IN_PROGRESS);
        assertThat(database.adminJdbc().queryForObject(
            "SELECT count(*) FROM tool_gateway.tool_audit_events "
                + "WHERE execution_id = ?",
            Integer.class,
            request.executionId()
        )).isZero();

        abandon(store, lease);
        ExecutionReceiptStore.Lease retry = claim(store, request, digest).lease();
        complete(store, retry, response(request.executionId(), digest));
    }

    @Test
    void foreignScopeGlobalIdCollisionIsConflictAndCannotMutateLease() {
        var store = database.receiptStore(Duration.ofSeconds(5));
        UUID executionId = UUID.randomUUID();
        ToolExecutionRequest tenantA = request(
            executionId,
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            Instant.now().plusSeconds(10)
        );
        ToolExecutionRequest tenantB = request(
            executionId,
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            Instant.now().plusSeconds(10)
        );
        ToolExecutionRequest projectB = request(
            executionId,
            tenantA.tenantId(),
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            Instant.now().plusSeconds(10)
        );
        String digest = ToolGatewayPostgresTestContext.digest(executionId.toString());
        ExecutionReceiptStore.Lease lease = claim(store, tenantA, digest).lease();

        assertThat(claim(store, tenantB, digest).status())
            .isEqualTo(ExecutionReceiptStore.ClaimStatus.CONFLICT);
        assertThat(claim(store, projectB, digest).status())
            .isEqualTo(ExecutionReceiptStore.ClaimStatus.CONFLICT);

        ExecutionReceiptStore.Lease forged = new ExecutionReceiptStore.Lease(
            scope(tenantB),
            lease.executionId(),
            lease.requestDigest(),
            lease.token()
        );
        assertThatThrownBy(() -> complete(
            store,
            forged,
            response(executionId, digest)
        )).isInstanceOf(IllegalStateException.class);
        abandon(store, forged);
        complete(store, lease, response(executionId, digest));
        ExecutionReceiptStore.Claim foreignReplay = claim(store, tenantB, digest);
        assertThat(foreignReplay.status())
            .isEqualTo(ExecutionReceiptStore.ClaimStatus.CONFLICT);
        assertThat(foreignReplay.response()).isNull();

        assertThat(database.adminJdbc().queryForObject(
            "SELECT tenant_id = ? AND project_id = ? "
                + "FROM tool_gateway.execution_receipts WHERE execution_id = ?",
            Boolean.class,
            tenantA.tenantId(),
            tenantA.projectId(),
            executionId
        )).isTrue();
    }

    private static ExecutionReceiptStore.Claim get(
        Future<ExecutionReceiptStore.Claim> future
    ) {
        try {
            return future.get();
        }
        catch (Exception exception) {
            throw new AssertionError("Concurrent claim failed.", exception);
        }
    }

    private ToolExecutionRequest request(UUID executionId, Instant deadline) {
        return request(
            executionId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            deadline
        );
    }

    private ToolExecutionRequest request(
        UUID executionId,
        UUID tenantId,
        UUID projectId,
        Instant deadline
    ) {
        return new ToolExecutionRequest(
            executionId, tenantId, projectId, UUID.randomUUID(),
            UUID.randomUUID(), "operator-test", "observability", "metrics.query",
            "1.0", "prometheus:test", Map.of("service", "opsmind-api"),
            deadline, new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }

    private ToolExecutionResponse response(UUID executionId, String digest) {
        return new ToolExecutionResponse(
            executionId, ToolOutcome.SUCCEEDED, List.of(), null, UUID.randomUUID(),
            digest, "manifest-v1", "prometheus/v1", 0, false, false
        );
    }

    private ToolExecutionProvenance provenance() {
        return new ToolExecutionProvenance(
            "observability", "metrics.query", "read-only",
            "prometheus-read-only", "prometheus", "sha256:" + "a".repeat(64)
        );
    }

    private ExecutionReceiptStore.Claim claim(
        JdbcExecutionReceiptStore store,
        ToolExecutionRequest request,
        String digest
    ) {
        TenantProjectScope scope = scope(request);
        return database.inScope(scope, () -> store.claim(scope, request, digest));
    }

    private void complete(
        JdbcExecutionReceiptStore store,
        ExecutionReceiptStore.Lease lease,
        ToolExecutionResponse response
    ) {
        database.inScope(lease.scope(), () -> store.complete(lease, response));
    }

    private void abandon(
        JdbcExecutionReceiptStore store,
        ExecutionReceiptStore.Lease lease
    ) {
        database.inScope(lease.scope(), () -> store.abandon(lease));
    }

    private TenantProjectScope scope(ToolExecutionRequest request) {
        return new TenantProjectScope(request.tenantId(), request.projectId());
    }

}
