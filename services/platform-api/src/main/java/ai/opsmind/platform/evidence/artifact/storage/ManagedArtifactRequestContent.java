package ai.opsmind.platform.evidence.artifact.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import software.amazon.awssdk.http.ContentStreamProvider;

/**
 * Provides at most two bounded source views for SDK inspection and HTTP wire
 * transfer. Every completed replay is verified before another one opens.
 */
final class ManagedArtifactRequestContent implements ContentStreamProvider, AutoCloseable {

    private static final int MAXIMUM_STREAM_VIEWS = 2;

    private final ManagedArtifactSource source;
    private final long expectedByteCount;
    private final byte[] expectedDigest;
    private final ArtifactSourceIoExecutor executor;
    private final ArtifactSourceReadBudget budget;
    private final List<BoundedDigestInputStream> openedStreams = new ArrayList<>();

    ManagedArtifactRequestContent(
        ManagedArtifactSource source,
        long expectedByteCount,
        byte[] expectedDigest,
        Duration sourceReadBudget,
        Instant sourceDeadline,
        ArtifactSourceIoExecutor executor
    ) {
        this.source = Objects.requireNonNull(source, "Managed artifact source is required.");
        if (expectedByteCount < 1) throw new IllegalArgumentException("Artifact length must be positive.");
        this.expectedByteCount = expectedByteCount;
        this.expectedDigest = Objects.requireNonNull(expectedDigest, "Artifact digest is required.").clone();
        this.executor = Objects.requireNonNull(executor, "Artifact source executor is required.");
        this.budget = new ArtifactSourceReadBudget(sourceReadBudget, sourceDeadline, executor);
    }

    @Override
    public synchronized InputStream newStream() {
        try {
            verifyOpenedStreams();
            if (openedStreams.size() >= MAXIMUM_STREAM_VIEWS) {
                throw new ArtifactSourceContractViolationException(
                    "Artifact source replay limit was exceeded."
                );
            }
            InputStream view = source.openStream();
            var deadlineBounded = new DeadlineBoundedArtifactInputStream(
                view,
                budget,
                () -> executor.detachCleanup(source)
            );
            var bounded = new BoundedDigestInputStream(deadlineBounded, expectedByteCount);
            openedStreams.add(bounded);
            return new NonClosingInputStream(bounded);
        } catch (IOException failure) {
            executor.detachCleanup(source);
            throw new UncheckedIOException("Artifact source view could not be opened.", failure);
        }
    }

    synchronized void verifyAfterPut() throws IOException {
        verifyOpenedStreams();
        if (source.size() != expectedByteCount) {
            throw new ArtifactSourceContractViolationException(
                "Artifact spool length changed during object upload."
            );
        }
    }

    @Override
    public void close() {
        executor.detachCleanup(source);
    }

    private void verifyOpenedStreams() throws IOException {
        for (BoundedDigestInputStream stream : openedStreams) {
            stream.verifyExactEofAndDigest(expectedDigest);
        }
    }

    /** SDK close must not preempt explicit exact-EOF validation. */
    private static final class NonClosingInputStream extends FilterInputStream {

        private NonClosingInputStream(InputStream source) {
            super(source);
        }

        @Override
        public void close() { }
    }
}
