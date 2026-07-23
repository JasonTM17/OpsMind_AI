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
        String digest = ToolGatewayPostgresTestContext.digest(request.executionId().toString());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ExecutionReceiptStore.Claim>> futures;

        try (var pool = Executors.newFixedThreadPool(8)) {
            futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> pool.submit(() -> {
                    start.await();
                    return store.claim(request, digest);
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
            store.complete(lease, response);

            assertThat(store.claim(request, digest).response()).isEqualTo(response);
            assertThat(store.claim(request, ToolGatewayPostgresTestContext.digest("changed")).status())
                .isEqualTo(ExecutionReceiptStore.ClaimStatus.CONFLICT);
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndOldOwnerIsFenced() throws Exception {
        var store = database.receiptStore(Duration.ofMillis(150));
        ToolExecutionRequest request = request(UUID.randomUUID(), Instant.now().plusSeconds(5));
        String digest = ToolGatewayPostgresTestContext.digest(request.executionId().toString());
        ExecutionReceiptStore.Lease first = store.claim(request, digest).lease();

        Thread.sleep(300);
        ExecutionReceiptStore.Lease second = store.claim(request, digest).lease();

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> store.complete(first, response(request.executionId(), digest)))
            .isInstanceOf(IllegalStateException.class);
        store.complete(second, response(request.executionId(), digest));
    }

    @Test
    void auditAndReceiptFinalizeAtomicallyAndAuditRejectsMutation() {
        var store = database.receiptStore(Duration.ofSeconds(5));
        var audit = database.auditWriter();
        ToolExecutionRequest request = request(UUID.randomUUID(), Instant.now().plusSeconds(10));
        String digest = ToolGatewayPostgresTestContext.digest(request.executionId().toString());
        ExecutionReceiptStore.Lease lease = store.claim(request, digest).lease();

        assertThatThrownBy(() -> database.transactionRunner().required(() -> {
            UUID inserted = audit.record(
                request.executionId(), ToolOutcome.SUCCEEDED, digest,
                "capability-test", "manifest-v1", digest, "policy-v1", null
            );
            assertThat(inserted).isNotNull();
            store.complete(lease, response(request.executionId(), digest));
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.claim(request, digest).status())
            .isEqualTo(ExecutionReceiptStore.ClaimStatus.IN_PROGRESS);
        assertThat(database.migratorJdbc().queryForObject(
            "SELECT count(*) FROM tool_gateway.tool_audit_events "
                + "WHERE execution_id = ?",
            Integer.class,
            request.executionId()
        )).isZero();

        store.abandon(lease);
        ExecutionReceiptStore.Lease retry = store.claim(request, digest).lease();
        store.complete(retry, response(request.executionId(), digest));
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
        return new ToolExecutionRequest(
            executionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
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

}
