package ai.opsmind.platform.evidence.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactSourceTestFixtures.BlockingAfterBodyInputStream;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactSourceTestFixtures.CloseBlockingInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3EvidenceArtifactObjectStorageDeadlineTest {

    private static final byte[] BODY = "durable evidence".getBytes(StandardCharsets.UTF_8);
    private static final String KMS_KEY = "arn:aws:kms:ap-southeast-1:123456789012:key/key-1";
    private static final EvidenceArtifactDigest DIGEST = new EvidenceArtifactDigest(
        "sha256:" + HexFormat.of().formatHex(sha256(BODY))
    );
    private static final ArtifactObjectExpectation EXPECTATION = new ArtifactObjectExpectation(
        java.util.UUID.fromString("730c8fea-5479-46f8-aab3-d3b60f871c37"),
        "artifact/730c8fea",
        DIGEST,
        BODY.length
    );

    private final ArtifactSourceIoExecutor sourceIoExecutor = new ArtifactSourceIoExecutor(2);

    @AfterEach
    void closeExecutor() {
        sourceIoExecutor.close();
    }

    @Test
    void rejectsANearExpiredClaimBeforeTheClientCanReadTheSource() {
        S3Client client = mock(S3Client.class);
        S3EvidenceArtifactObjectStorage storage = storage(client, Duration.ofMillis(50));
        ManagedArtifactSource source = managed(BODY);

        assertThatThrownBy(() -> storage.putIfAbsent(
            EXPECTATION,
            source,
            Instant.now().plusSeconds(10)
        )).isInstanceOfSatisfying(EvidenceArtifactStorageException.class, failure -> {
            assertThat(failure.kind()).isEqualTo(EvidenceArtifactStorageException.FailureKind.STREAM_REJECTED);
            assertThat(failure.objectMayExist()).isFalse();
        });
        verifyNoInteractions(client);
        storage.release(source);
    }

    @Test
    void timesOutAndCancelsABlockingBodyRead() throws InterruptedException {
        S3Client client = consumingClient();
        BlockingAfterBodyInputStream source = new BlockingAfterBodyInputStream(new byte[0]);
        ManagedArtifactSource managed = ManagedArtifactSource.forTesting(
            () -> source,
            () -> (long) BODY.length,
            source::release
        );

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            assertThatThrownBy(() -> storage(client, Duration.ofMillis(50))
                .putIfAbsent(EXPECTATION, managed, leaseDeadline()))
                .isInstanceOfSatisfying(EvidenceArtifactStorageException.class, failure ->
                    assertThat(failure.kind()).isEqualTo(
                        EvidenceArtifactStorageException.FailureKind.STREAM_REJECTED
                    )
                )
        );

        assertThat(source.readStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(source.closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void quarantinesABlockingMandatoryExactEofRead() throws InterruptedException {
        S3Client client = consumingClient();
        BlockingAfterBodyInputStream source = new BlockingAfterBodyInputStream(BODY);
        ManagedArtifactSource managed = ManagedArtifactSource.forTesting(
            () -> source,
            () -> (long) BODY.length,
            source::release
        );

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            assertThatThrownBy(() -> storage(client, Duration.ofMillis(50))
                .putIfAbsent(EXPECTATION, managed, leaseDeadline()))
                .isInstanceOfSatisfying(EvidenceArtifactStorageException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(
                        EvidenceArtifactStorageException.FailureKind.SOURCE_CONTRACT_MISMATCH
                    );
                    assertThat(failure.objectMayExist()).isTrue();
                })
        );

        assertThat(source.readStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(source.closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void returnsWithoutWaitingForABlockingSourceClose() throws InterruptedException {
        S3Client client = consumingClient();
        CloseBlockingInputStream source = new CloseBlockingInputStream(BODY);
        ManagedArtifactSource managed = ManagedArtifactSource.forTesting(
            () -> source,
            () -> (long) BODY.length,
            source
        );

        try {
            ArtifactObjectStored stored = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> storage(client, Duration.ofSeconds(1)).putIfAbsent(
                    EXPECTATION,
                    managed,
                    leaseDeadline()
                )
            );
            assertThat(stored.digest()).isEqualTo(DIGEST);
            assertThat(source.closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            source.releaseClose();
        }
        assertThat(source.closed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void keepsReleaseNonBlockingWhenTheBoundedCleanupQueueIsSaturated() throws Exception {
        ArtifactSourceIoExecutor saturatedExecutor = new ArtifactSourceIoExecutor(1);
        CloseBlockingInputStream first = new CloseBlockingInputStream(BODY);
        CloseBlockingInputStream second = new CloseBlockingInputStream(BODY);
        CloseBlockingInputStream third = new CloseBlockingInputStream(BODY);
        S3EvidenceArtifactObjectStorage storage = new S3EvidenceArtifactObjectStorage(
            consumingClient(), properties(Duration.ofSeconds(1)), saturatedExecutor
        );

        try {
            storage.putIfAbsent(EXPECTATION, blockingSource(first), leaseDeadline());
            assertThat(first.closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
            storage.putIfAbsent(EXPECTATION, blockingSource(second), leaseDeadline());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                storage.putIfAbsent(EXPECTATION, blockingSource(third), leaseDeadline())
            );
        } finally {
            first.releaseClose();
            second.releaseClose();
            third.releaseClose();
            saturatedExecutor.close();
        }
    }

    private S3EvidenceArtifactObjectStorage storage(S3Client client, Duration sourceBudget) {
        return new S3EvidenceArtifactObjectStorage(client, properties(sourceBudget), sourceIoExecutor);
    }

    private static S3Client consumingClient() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            consume(call.getArgument(1));
            return storedResponse();
        });
        return client;
    }

    private static EvidenceArtifactStorageProperties properties(Duration sourceBudget) {
        return new EvidenceArtifactStorageProperties(
            true, URI.create("https://storage.example.com"), false, "ap-southeast-1",
            "evidence-artifacts", true, "123456789012", KMS_KEY, KMS_KEY, "production-kms",
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(10), sourceBudget, Duration.ofSeconds(5),
            2, Duration.ofMinutes(1)
        );
    }

    private static PutObjectResponse storedResponse() {
        return PutObjectResponse.builder()
            .checksumSHA256(Base64.getEncoder().encodeToString(DIGEST.bytes()))
            .versionId("opaque-version")
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(KMS_KEY)
            .build();
    }

    private static ManagedArtifactSource managed(byte[] body) {
        return ManagedArtifactSource.forTesting(
            () -> new ByteArrayInputStream(body),
            () -> (long) body.length,
            () -> { }
        );
    }

    private static ManagedArtifactSource blockingSource(CloseBlockingInputStream source) {
        return ManagedArtifactSource.forTesting(
            () -> source,
            () -> (long) BODY.length,
            source
        );
    }

    private static Instant leaseDeadline() {
        return Instant.now().plusSeconds(30);
    }

    private static void consume(RequestBody requestBody) {
        try (InputStream stream = requestBody.contentStreamProvider().newStream()) {
            stream.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
