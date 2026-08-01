package ai.opsmind.platform.evidence.artifact.storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Streams no more than the declared object length and hashes only those bytes.
 * Replay owners verify exact EOF and the expected digest before another view
 * can be opened and again after the remote write returns.
 */
final class BoundedDigestInputStream extends InputStream {

    private final DeadlineBoundedArtifactInputStream source;
    private final long expectedByteCount;
    private final MessageDigest digest;
    private long bytesRead;
    private boolean verified;

    BoundedDigestInputStream(
        DeadlineBoundedArtifactInputStream source,
        long expectedByteCount
    ) {
        this.source = Objects.requireNonNull(source, "Artifact source is required.");
        if (expectedByteCount < 1) throw new IllegalArgumentException("Artifact length must be positive.");
        this.expectedByteCount = expectedByteCount;
        this.digest = sha256();
    }

    @Override
    public int read() throws IOException {
        if (bytesRead == expectedByteCount) return -1;
        int value = source.read();
        if (value < 0) throw shortStream();
        digest.update((byte) value);
        bytesRead++;
        return value;
    }

    @Override
    public int read(byte[] destination, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, destination.length);
        if (length == 0) return 0;
        if (bytesRead == expectedByteCount) return -1;
        int allowed = (int) Math.min(length, expectedByteCount - bytesRead);
        int read = source.read(destination, offset, allowed);
        if (read < 0) throw shortStream();
        digest.update(destination, offset, read);
        bytesRead += read;
        return read;
    }

    void verifyExactEofAndDigest(byte[] expectedDigest) throws IOException {
        if (verified) return;
        if (bytesRead != expectedByteCount) throw shortStream();
        int trailingByte = source.read();
        if (trailingByte != -1) {
            throw new ArtifactSourceContractViolationException(
                "Artifact stream length does not match the declared value."
            );
        }
        if (!MessageDigest.isEqual(digest.digest(), expectedDigest)) {
            throw new ArtifactSourceContractViolationException(
                "Artifact stream digest does not match the declared value."
            );
        }
        verified = true;
    }

    long bytesRead() {
        return bytesRead;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static ArtifactSourceContractViolationException shortStream() {
        return new ArtifactSourceContractViolationException(
            "Artifact stream ended before the declared byte count."
        );
    }
}
