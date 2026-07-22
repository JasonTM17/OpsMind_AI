package ai.opsmind.toolgateway.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

/** Executes connector work on cancellable virtual threads within the signed request deadline. */
public final class BoundedConnectorExecutor implements AutoCloseable {

    private final Clock clock;
    private final ExecutorService executor;
    private final Semaphore capacity = new Semaphore(32, true);

    public BoundedConnectorExecutor(Clock clock) {
        this.clock = clock;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public <T> T execute(
        Callable<T> operation,
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

        try {
            if (!capacity.tryAcquire(remaining.toNanos(), TimeUnit.NANOSECONDS)) {
                throw denied(
                    DenialCode.EXECUTION_BACKPRESSURE,
                    "Tool connector capacity is exhausted."
                );
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw cancelled(exception);
        }

        Future<T> future;
        try {
            future = executor.submit(() -> {
                try {
                    return operation.call();
                }
                finally {
                    capacity.release();
                }
            });
        }
        catch (RuntimeException exception) {
            capacity.release();
            throw exception;
        }
        try {
            Duration executionRemaining = remaining(effectiveDeadline);
            if (executionRemaining.isNegative() || executionRemaining.isZero()) {
                future.cancel(true);
                throw denied(DenialCode.CONNECTOR_TIMEOUT, "Tool connector deadline elapsed.");
            }
            return future.get(executionRemaining.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException exception) {
            future.cancel(true);
            throw new ToolDeniedException(
                DenialCode.CONNECTOR_TIMEOUT,
                "Tool connector exceeded its bounded deadline.",
                exception
            );
        }
        catch (InterruptedException exception) {
            future.cancel(true);
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
}
