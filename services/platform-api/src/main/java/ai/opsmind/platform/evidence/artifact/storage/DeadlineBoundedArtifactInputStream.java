package ai.opsmind.platform.evidence.artifact.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Runs all source reads through a shared cumulative wall-clock budget. */
final class DeadlineBoundedArtifactInputStream extends InputStream {

    private static final int MAXIMUM_READ_CHUNK_BYTES = 64 * 1_024;

    private final InputStream source;
    private final ArtifactSourceReadBudget budget;
    private final Runnable abort;

    DeadlineBoundedArtifactInputStream(
        InputStream source,
        ArtifactSourceReadBudget budget,
        Runnable abort
    ) {
        this.source = Objects.requireNonNull(source, "Artifact source view is required.");
        this.budget = Objects.requireNonNull(budget, "Artifact source budget is required.");
        this.abort = Objects.requireNonNull(abort, "Artifact source abort action is required.");
    }

    @Override
    public int read() throws IOException {
        return executeRead(source::read);
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) return 0;
        int chunkLength = Math.min(length, MAXIMUM_READ_CHUNK_BYTES);
        byte[] isolatedBuffer = new byte[chunkLength];
        int read = executeRead(() -> source.read(isolatedBuffer, 0, chunkLength));
        if (read == 0 || read > chunkLength) {
            abort.run();
            throw new IOException("Artifact source violated the streaming contract.");
        }
        if (read > 0) {
            System.arraycopy(isolatedBuffer, 0, destination, offset, read);
        }
        return read;
    }

    long remainingNanos() {
        return budget.remainingNanos();
    }

    private <T> T executeRead(Callable<T> operation) throws IOException {
        try {
            return budget.execute(operation);
        } catch (IOException | RuntimeException failure) {
            try {
                abort.run();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
