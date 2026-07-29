package ai.opsmind.toolgateway.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import ai.opsmind.toolgateway.config.ConnectorBulkheadProperties;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

/** Executes connector work on cancellable virtual threads within the signed request deadline. */
public final class BoundedConnectorExecutor implements AutoCloseable {

    private final Clock clock;
    private final ExecutorService executor;
    private final TenantConnectorBulkhead bulkhead;

    public BoundedConnectorExecutor(Clock clock) {
        this(clock, new ConnectorBulkheadProperties(null, null));
    }

    public BoundedConnectorExecutor(
        Clock clock,
        ConnectorBulkheadProperties properties
    ) {
        this(clock, Executors.newVirtualThreadPerTaskExecutor(), new TenantConnectorBulkhead(properties));
    }

    BoundedConnectorExecutor(
        Clock clock,
        ExecutorService executor,
        TenantConnectorBulkhead bulkhead
    ) {
        if (clock == null || executor == null || bulkhead == null) {
            throw new IllegalArgumentException("Connector executor dependencies are required.");
        }
        this.clock = clock;
        this.executor = executor;
        this.bulkhead = bulkhead;
    }

    public <T> T execute(
        Callable<T> operation,
        TenantProjectScope trustedScope,
        ToolExecutionRequest request,
        ToolManifest manifest
    ) {
        Instant now = clock.instant();
        Instant manifestDeadline = now.plus(manifest.maximumDuration());
        Instant effectiveDeadline = request.deadlineAt().isBefore(manifestDeadline)
            ? request.deadlineAt() : manifestDeadline;
        Duration remaining = remaining(effectiveDeadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw denied(DenialCode.DEADLINE_EXPIRED, "Tool execution deadline is expired.");
        }

        TenantConnectorBulkhead.Permit permit = bulkhead.acquire(trustedScope);
        PermitGuardedOperation<T> guardedOperation = new PermitGuardedOperation<>(
            operation,
            permit
        );
        Future<T> future;
        try {
            future = executor.submit(guardedOperation);
        }
        catch (RuntimeException | Error exception) {
            guardedOperation.releaseIfQueued();
            throw exception;
        }
        try {
            Duration executionRemaining = remaining(effectiveDeadline);
            if (executionRemaining.isNegative() || executionRemaining.isZero()) {
                cancel(future, guardedOperation);
                throw denied(DenialCode.CONNECTOR_TIMEOUT, "Tool connector deadline elapsed.");
            }
            return future.get(executionRemaining.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException exception) {
            cancel(future, guardedOperation);
            throw new ToolDeniedException(
                DenialCode.CONNECTOR_TIMEOUT,
                "Tool connector exceeded its bounded deadline.",
                exception
            );
        }
        catch (InterruptedException exception) {
            cancel(future, guardedOperation);
            Thread.currentThread().interrupt();
            throw cancelled(exception);
        }
        catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ToolDeniedException denied) throw denied;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Tool connector failed.", cause);
        }
    }

    private void cancel(
        Future<?> future,
        PermitGuardedOperation<?> guardedOperation
    ) {
        if (future.cancel(true)) {
            guardedOperation.releaseIfQueued();
        }
    }

    private Duration remaining(Instant deadline) {
        return Duration.between(clock.instant(), deadline);
    }

    private ToolDeniedException cancelled(InterruptedException exception) {
        return new ToolDeniedException(
            DenialCode.CONNECTOR_CANCELLED,
            "Tool connector execution was cancelled.",
            exception
        );
    }

    @Override
    public void close() {
        executor.close();
    }

    private ToolDeniedException denied(DenialCode code, String message) {
        return new ToolDeniedException(code, message);
    }

    private static final class PermitGuardedOperation<T> implements Callable<T> {

        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int RELEASED = 2;

        private final Callable<T> operation;
        private final TenantConnectorBulkhead.Permit permit;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private PermitGuardedOperation(
            Callable<T> operation,
            TenantConnectorBulkhead.Permit permit
        ) {
            if (operation == null) {
                permit.close();
                throw new IllegalArgumentException("Connector operation is required.");
            }
            this.operation = operation;
            this.permit = permit;
        }

        @Override
        public T call() throws Exception {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                throw new CancellationException("Connector operation was cancelled before start.");
            }
            try {
                return operation.call();
            }
            finally {
                release(RUNNING);
            }
        }

        private void releaseIfQueued() {
            release(QUEUED);
        }

        private void release(int expectedState) {
            if (state.compareAndSet(expectedState, RELEASED)) {
                permit.close();
            }
        }
    }
}
