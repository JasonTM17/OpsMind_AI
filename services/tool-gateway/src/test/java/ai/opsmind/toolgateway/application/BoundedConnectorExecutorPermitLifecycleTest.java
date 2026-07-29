package ai.opsmind.toolgateway.application;

import static ai.opsmind.toolgateway.application.BoundedConnectorExecutorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;

import ai.opsmind.toolgateway.application.BoundedConnectorExecutorTestSupport.QueuedExecutorService;

class BoundedConnectorExecutorPermitLifecycleTest {

    @Test
    void saturatedTenantCannotBlockAnotherTenant() throws Exception {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(2, 1));
        try (
            ExecutorService connectorExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                fixedClock(), connectorExecutor, bulkhead
            );
            ExecutorService callerExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        ) {
            ToolExecutionRequest firstRequest = request("1", "1", NOW.plusSeconds(5));
            ToolExecutionRequest secondRequest = request("1", "2", NOW.plusSeconds(5));
            ToolExecutionRequest otherRequest = request("2", "1", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(firstRequest);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Future<String> first = callerExecutor.submit(() -> executor.execute(
                () -> {
                    started.countDown();
                    release.await();
                    return "tenant-a";
                },
                scope(firstRequest),
                firstRequest,
                manifest
            ));

            try {
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                assertBackpressure(() -> executor.execute(
                    () -> "same-tenant",
                    scope(secondRequest),
                    secondRequest,
                    manifest
                ));
                assertThat(executor.execute(
                    () -> "tenant-b",
                    scope(otherRequest),
                    otherRequest,
                    manifest
                )).isEqualTo("tenant-b");
            }
            finally {
                release.countDown();
            }
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("tenant-a");
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    @Test
    void ignoredInterruptRetainsPermitUntilConnectorReturns() throws Exception {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        try (
            ExecutorService connectorExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                fixedClock(), connectorExecutor, bulkhead
            )
        ) {
            ToolExecutionRequest expiringRequest = request("1", "1", NOW.plusMillis(20));
            ToolExecutionRequest waitingRequest = request("2", "1", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(expiringRequest);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch returned = new CountDownLatch(1);

            try {
                assertThatThrownBy(() -> executor.execute(
                    () -> {
                        started.countDown();
                        try {
                            release.await();
                        }
                        catch (InterruptedException ignored) {
                            interrupted.countDown();
                            release.await();
                        }
                        returned.countDown();
                        return "late";
                    },
                    scope(expiringRequest),
                    expiringRequest,
                    manifest
                )).isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                    assertThat(exception.code()).isEqualTo(DenialCode.CONNECTOR_TIMEOUT)
                );
                assertThat(started.getCount()).isZero();
                await(interrupted);
                assertBackpressure(() -> executor.execute(
                    () -> "must-wait",
                    scope(waitingRequest),
                    waitingRequest,
                    manifest
                ));
            }
            finally {
                release.countDown();
            }
            await(returned);
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    @Test
    void cooperativeTimeoutReleasesAfterInterruptionAndSupportsSameTenantReacquire() throws Exception {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        try (
            ExecutorService connectorExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                fixedClock(), connectorExecutor, bulkhead
            );
            ExecutorService callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        ) {
            ToolExecutionRequest expiringRequest = request("1", "1", NOW.plusMillis(50));
            ToolExecutionRequest retryRequest = request("1", "2", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(expiringRequest);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch allowExit = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch exited = new CountDownLatch(1);

            Future<String> expired = callers.submit(() -> executor.execute(
                () -> {
                    started.countDown();
                    try {
                        allowExit.await();
                        return "released";
                    }
                    catch (InterruptedException expected) {
                        interrupted.countDown();
                        return "cancelled";
                    }
                    finally {
                        exited.countDown();
                    }
                },
                scope(expiringRequest),
                expiringRequest,
                manifest
            ));

            try {
                await(started);
                assertThatThrownBy(() -> expired.get(1, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(java.util.concurrent.ExecutionException.class, exception ->
                        assertThat(exception.getCause())
                            .isInstanceOfSatisfying(ToolDeniedException.class, denied ->
                                assertThat(denied.code()).isEqualTo(DenialCode.CONNECTOR_TIMEOUT)
                            )
                    );
                await(interrupted);
            }
            finally {
                allowExit.countDown();
            }
            await(exited);
            assertThat(executor.execute(
                () -> "reacquired",
                scope(retryRequest),
                retryRequest,
                manifest
            )).isEqualTo("reacquired");
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    @Test
    void cancellationBeforeTaskStartReleasesBothPermits() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        QueuedExecutorService queuedExecutor = new QueuedExecutorService();
        BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
            fixedClock(), queuedExecutor, bulkhead
        );
        ToolExecutionRequest request = request("1", "1", NOW.plusMillis(20));
        ToolManifest manifest = manifest(request);

        assertThatThrownBy(() -> executor.execute(
            () -> "never-started",
            scope(request),
            request,
            manifest
        )).isInstanceOfSatisfying(ToolDeniedException.class, exception ->
            assertThat(exception.code()).isEqualTo(DenialCode.CONNECTOR_TIMEOUT)
        );
        assertThat(bulkhead.trackedTenantCount()).isZero();
        queuedExecutor.runQueued();
        executor.close();
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
                scope(request),
                request,
                manifest
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
                scope(request),
                request,
                manifest
            )).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    @Test
    void rejectedSubmissionReleasesBothPermits() {
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(properties(1, 1));
        try (
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                fixedClock(), new RejectingExecutorService(), bulkhead
            )
        ) {
            ToolExecutionRequest request = request("1", "1", NOW.plusSeconds(5));
            ToolManifest manifest = manifest(request);

            assertThatThrownBy(() -> executor.execute(
                () -> "not-submitted",
                scope(request),
                request,
                manifest
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
                null,
                scope(request),
                request,
                manifest
            )).isInstanceOf(IllegalArgumentException.class);
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

}
