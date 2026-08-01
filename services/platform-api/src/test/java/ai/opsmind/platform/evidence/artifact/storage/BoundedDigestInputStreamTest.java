package ai.opsmind.platform.evidence.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BoundedDigestInputStreamTest {

    private final ArtifactSourceIoExecutor sourceIoExecutor = new ArtifactSourceIoExecutor(2);

    @AfterEach
    void closeExecutor() {
        sourceIoExecutor.close();
    }

    @Test
    void streamsExactlyTheDeclaredBytesThenAcceptsExactEofAndDigest() throws IOException {
        byte[] body = "durable evidence".getBytes(StandardCharsets.UTF_8);

        try (var stream = stream(body, body.length)) {
            assertThat(stream.readAllBytes()).isEqualTo(body);
            stream.verifyExactEofAndDigest(sha256(body));
            assertThat(stream.bytesRead()).isEqualTo(body.length);
        }
    }

    @Test
    void rejectsAStreamWithTrailingBytesAfterTheDeclaredLength() throws IOException {
        byte[] expected = "evidence".getBytes(StandardCharsets.UTF_8);
        byte[] withTrailingByte = "evidence!".getBytes(StandardCharsets.UTF_8);

        try (var stream = stream(withTrailingByte, expected.length)) {
            assertThat(stream.readAllBytes()).isEqualTo(expected);
            assertThatThrownBy(() -> stream.verifyExactEofAndDigest(sha256(expected)))
                .isInstanceOf(IOException.class);
        }
    }

    @Test
    void rejectsShortAndDigestDriftStreams() throws IOException {
        byte[] declared = "evidence".getBytes(StandardCharsets.UTF_8);

        try (var shortStream = stream(
            "short".getBytes(StandardCharsets.UTF_8),
            declared.length
        )) {
            assertThatThrownBy(shortStream::readAllBytes)
                .isInstanceOf(IOException.class);
        }
        try (var drifted = stream(
            "EVIDENCE".getBytes(StandardCharsets.UTF_8),
            declared.length
        )) {
            drifted.readAllBytes();
            assertThatThrownBy(() -> drifted.verifyExactEofAndDigest(sha256(declared)))
                .isInstanceOf(IOException.class);
        }
    }

    private BoundedDigestInputStream stream(byte[] body, long expectedByteCount) {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        DeadlineBoundedArtifactInputStream deadlineBounded =
            new DeadlineBoundedArtifactInputStream(
                source,
                new ArtifactSourceReadBudget(
                    Duration.ofSeconds(1),
                    Instant.now().plusSeconds(2),
                    sourceIoExecutor
                ),
                () -> { }
            );
        return new BoundedDigestInputStream(deadlineBounded, expectedByteCount);
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
