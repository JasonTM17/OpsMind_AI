package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import ai.opsmind.toolgateway.config.ConnectorBulkheadProperties;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import tools.jackson.databind.json.JsonMapper;

final class BoundedConnectorExecutorTestSupport {

    static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private BoundedConnectorExecutorTestSupport() {
    }

    static void assertBackpressure(ThrowingOperation operation) {
        assertThatThrownBy(operation::run)
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.EXECUTION_BACKPRESSURE)
            );
    }

    static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }

    static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    static ConnectorBulkheadProperties properties(int global, int perTenant) {
        return new ConnectorBulkheadProperties(global, perTenant);
    }

    static TenantProjectScope scope(ToolExecutionRequest request) {
        return new TenantProjectScope(request.tenantId(), request.projectId());
    }

    static ToolManifest manifest(ToolExecutionRequest request) {
        return new ToolManifestResourceLoader(
            JsonMapper.builder().findAndAddModules().build()
        ).loadFixtureRegistry().require(request);
    }

    static ToolExecutionRequest request(
        String tenantSuffix,
        String projectSuffix,
        Instant deadline
    ) {
        return new ToolExecutionRequest(
            UUID.randomUUID(),
            UUID.fromString("00000000-0000-0000-0000-00000000000" + tenantSuffix),
            UUID.fromString("00000000-0000-0000-0000-00000000000" + projectSuffix),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "operator-001",
            "observability",
            "metrics.query",
            "1.0",
            "prometheus:synthetic/opsmind-api",
            Map.of("service", "opsmind-api"),
            deadline,
            new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }

    @FunctionalInterface
    interface ThrowingOperation {
        void run() throws Exception;
    }

    static final class QueuedExecutorService extends AbstractExecutorService {

        private Runnable queued;
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new IllegalStateException("executor is shut down");
            if (queued != null) throw new IllegalStateException("only one task is supported");
            queued = command;
        }

        void runQueued() {
            Runnable command = queued;
            queued = null;
            if (command != null) command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            Runnable command = queued;
            queued = null;
            return command == null ? List.of() : List.of(command);
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queued == null;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    static final class RejectingExecutorService extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("test-only submission rejection");
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
