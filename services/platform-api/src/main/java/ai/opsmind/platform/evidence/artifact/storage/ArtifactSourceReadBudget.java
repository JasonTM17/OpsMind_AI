package ai.opsmind.platform.evidence.artifact.storage;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Callable;

/** One monotonic, cumulative read budget shared by every replayed source view. */
final class ArtifactSourceReadBudget {

    private final ArtifactSourceIoExecutor executor;
    private final long deadlineNanos;
    private long remainingNanos;

    ArtifactSourceReadBudget(
        Duration budget,
        Instant absoluteDeadline,
        ArtifactSourceIoExecutor executor
    ) {
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Artifact source I/O budget must be positive.");
        }
        this.executor = Objects.requireNonNull(executor, "Artifact source executor is required.");
        this.remainingNanos = toNanosSaturated(budget);
        this.deadlineNanos = deadlineFrom(absoluteDeadline);
    }

    synchronized <T> T execute(Callable<T> operation) throws IOException {
        long startedAt = System.nanoTime();
        try {
            return executor.execute(operation, availableNanos(startedAt));
        } finally {
            long elapsed = Math.max(0, System.nanoTime() - startedAt);
            remainingNanos = Math.max(0, remainingNanos - elapsed);
        }
    }

    synchronized long remainingNanos() {
        return Math.min(remainingNanos, Math.max(0, deadlineNanos - System.nanoTime()));
    }

    private long availableNanos(long now) throws SocketTimeoutException {
        long available = Math.min(remainingNanos, Math.max(0, deadlineNanos - now));
        if (available <= 0) {
            throw new SocketTimeoutException("Artifact source I/O exceeded its bounded budget.");
        }
        return available;
    }

    private static long deadlineFrom(Instant absoluteDeadline) {
        Objects.requireNonNull(absoluteDeadline, "Artifact source deadline is required.");
        long remaining;
        try {
            Duration duration = Duration.between(Instant.now(), absoluteDeadline);
            remaining = duration.isNegative() || duration.isZero()
                ? 0
                : toNanosSaturated(duration);
        } catch (ArithmeticException | DateTimeException invalidDeadline) {
            remaining = 0;
        }
        return addSaturated(System.nanoTime(), remaining);
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long addSaturated(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
