package ai.opsmind.platform.evidence.artifact.storage;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Caps deadline-aware source reads and detached cleanup work. */
final class ArtifactSourceIoExecutor implements AutoCloseable {

    private static final long IDLE_THREAD_SECONDS = 30;

    private final ThreadPoolExecutor reads;
    private final ThreadPoolExecutor cleanup;
    private final ThreadPoolExecutor emergencyCleanup;

    ArtifactSourceIoExecutor(int maximumConcurrency) {
        if (maximumConcurrency < 1) {
            throw new IllegalArgumentException("Artifact source concurrency must be positive.");
        }
        reads = new ThreadPoolExecutor(
            maximumConcurrency,
            maximumConcurrency,
            IDLE_THREAD_SECONDS,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new DaemonThreadFactory("artifact-source-read-"),
            new ThreadPoolExecutor.AbortPolicy()
        );
        cleanup = new ThreadPoolExecutor(
            maximumConcurrency,
            maximumConcurrency,
            IDLE_THREAD_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(maximumConcurrency),
            new DaemonThreadFactory("artifact-source-cleanup-"),
            new ThreadPoolExecutor.AbortPolicy()
        );
        reads.allowCoreThreadTimeOut(true);
        cleanup.allowCoreThreadTimeOut(true);
        emergencyCleanup = new ThreadPoolExecutor(
            1,
            1,
            IDLE_THREAD_SECONDS,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new DaemonThreadFactory("artifact-source-emergency-cleanup-"),
            new ThreadPoolExecutor.AbortPolicy()
        );
        emergencyCleanup.allowCoreThreadTimeOut(true);
    }

    <T> T execute(Callable<T> operation, long remainingNanos) throws IOException {
        if (remainingNanos <= 0) throw sourceTimeout();
        Future<T> future;
        try {
            future = reads.submit(operation);
        } catch (RuntimeException failure) {
            throw new IOException("Artifact source I/O capacity is unavailable.", failure);
        }
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw sourceTimeout();
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted =
                new InterruptedIOException("Artifact source I/O was cancelled.");
            interrupted.initCause(failure);
            throw interrupted;
        } catch (ExecutionException failure) {
            return propagate(failure.getCause());
        }
    }

    void detachCleanup(ManagedArtifactSource source) {
        if (source == null) return;
        if (!source.claimCleanup()) return;
        try {
            cleanup.execute(new CleanupTask(source));
        } catch (RuntimeException failure) {
            // Production sources pin a FileChannel, whose close unblocks reads.
            // A separate unbounded daemon queue keeps request/deadline paths
            // non-blocking even when a custom close operation is slow.
            try {
                emergencyCleanup.execute(new CleanupTask(source));
            } catch (RuntimeException shutdownFailure) {
                // Executor shutdown is terminal; close synchronously as the
                // owner is already leaving the process.
                source.abort();
            }
        }
    }

    @Override
    public void close() {
        reads.shutdownNow();
        abortAbandoned(cleanup.shutdownNow());
        abortAbandoned(emergencyCleanup.shutdownNow());
    }

    private static <T> T propagate(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) throw ioFailure;
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        throw new IOException("Artifact source I/O failed.", failure);
    }

    private static void cleanup(ManagedArtifactSource source) {
        source.abort();
    }

    private static void abortAbandoned(java.util.List<Runnable> abandonedTasks) {
        for (Runnable abandoned : abandonedTasks) {
            if (abandoned instanceof CleanupTask task) task.source.abort();
        }
    }

    private static final class CleanupTask implements Runnable {

        private final ManagedArtifactSource source;

        private CleanupTask(ManagedArtifactSource source) {
            this.source = source;
        }

        @Override
        public void run() {
            cleanup(source);
        }
    }

    private static SocketTimeoutException sourceTimeout() {
        return new SocketTimeoutException("Artifact source I/O exceeded its bounded budget.");
    }

    private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {

        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable operation) {
            Thread thread = new Thread(operation, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
