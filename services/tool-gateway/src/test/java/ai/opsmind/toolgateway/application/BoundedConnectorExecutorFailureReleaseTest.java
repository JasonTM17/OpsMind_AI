package ai.opsmind.toolgateway.application;

import static ai.opsmind.toolgateway.application.BoundedConnectorExecutorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import ai.opsmind.toolgateway.application.BoundedConnectorExecutorTestSupport.QueuedExecutorService;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;

class BoundedConnectorExecutorFailureReleaseTest {

    @Test
    void cancellationBeforeTaskStartReleasesBothPermits() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        QueuedExecutorService queuedExecutor = new QueuedExecutorService();
        try (BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
            fixedClock(), queuedExecutor, bulkhead
        )) {
            ToolExecutionRequest request = request("1", "1", NOW.plusMillis(20));
            ToolManifest manifest = manifest(request);

            assertThatThrownBy(() -> executor.execute(
                () -> "never-started", scope(request), request, manifest
            )).isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.CONNECTOR_TIMEOUT)
            );
            assertThat(bulkhead.trackedTenantCount()).isZero();
            queuedExecutor.runQueued();
        }
    }

    @Test
    void runtimeFailureReleasesBothPermits() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        try (
            ExecutorService connectorExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                fixedClock(), connectorExecutor, bulkhead
            )
        ) {
            ToolExecutionRequest request = request("1", "1", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(request);

            assertThatThrownBy(() -> executor.execute(
                () -> {
                    throw new IllegalStateException("connector failure");
                },
                scope(request), request, manifest
            )).isInstanceOf(IllegalStateException.class);
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    @Test
    void checkedFailureReleasesBothPermits() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        try (
            ExecutorService connectorExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                fixedClock(), connectorExecutor, bulkhead
            )
        ) {
            ToolExecutionRequest request = request("1", "1", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(request);

            assertThatThrownBy(() -> executor.execute(
                () -> {
                    throw new IOException("connector checked failure");
                },
                scope(request), request, manifest
            )).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    @Test
    void rejectedSubmissionReleasesBothPermits() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        try (BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
            fixedClock(), new RejectingExecutorService(), bulkhead
        )) {
            ToolExecutionRequest request = request("1", "1", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(request);

            assertThatThrownBy(() -> executor.execute(
                () -> "not-submitted", scope(request), request, manifest
            )).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    @Test
    void operationSetupFailureReleasesBothPermits() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        try (
            ExecutorService connectorExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                fixedClock(), connectorExecutor, bulkhead
            )
        ) {
            ToolExecutionRequest request = request("1", "1", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(request);

            assertThatThrownBy(() -> executor.execute(
                null, scope(request), request, manifest
            )).isInstanceOf(IllegalArgumentException.class);
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }
}
