package ai.opsmind.platform.evidence.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3EvidenceArtifactObjectStorageTest {

    private static final UUID ARTIFACT_ID = UUID.fromString("730c8fea-5479-46f8-aab3-d3b60f871c37");
    private static final byte[] BODY = "durable evidence".getBytes(StandardCharsets.UTF_8);
    private static final EvidenceArtifactDigest DIGEST = new EvidenceArtifactDigest(
        "sha256:" + HexFormat.of().formatHex(sha256(BODY))
    );
    private static final ArtifactObjectExpectation EXPECTATION = new ArtifactObjectExpectation(
        ARTIFACT_ID, "artifact/730c8fea", DIGEST, BODY.length
    );
    private static final String KMS_KEY = "arn:aws:kms:ap-southeast-1:123456789012:key/key-1";
    private static final String ENCRYPTION_PROFILE = "production-kms";

    @Test
    void putsOneBoundedImmutableChecksumVerifiedObject() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            consume(call.getArgument(1));
            return storedResponse();
        });
        S3EvidenceArtifactObjectStorage storage = new S3EvidenceArtifactObjectStorage(client, properties());

        ArtifactObjectStored stored = storage.putIfAbsent(EXPECTATION, new ByteArrayInputStream(BODY));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().contentLength()).isEqualTo((long) BODY.length);
        assertThat(request.getValue().checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
        assertThat(request.getValue().checksumSHA256()).isEqualTo(encodedDigest());
        assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(request.getValue().ssekmsKeyId()).isEqualTo(KMS_KEY);
        assertThat(request.getValue().expectedBucketOwner()).isEqualTo("123456789012");
        assertThat(request.getValue().metadata()).containsExactlyInAnyOrderEntriesOf(metadata());
        assertThat(stored.versionReference()).isEqualTo("opaque-version");
        assertThat(stored.encryptionMetadataReference()).isEqualTo(ENCRYPTION_PROFILE);
    }

    @Test
    void rejectsTrailingInputAfterTheRemotePutMayHaveSucceeded() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            consume(call.getArgument(1));
            return storedResponse();
        });
        S3EvidenceArtifactObjectStorage storage = new S3EvidenceArtifactObjectStorage(client, properties());

        assertThatThrownBy(() -> storage.putIfAbsent(
            EXPECTATION, new ByteArrayInputStream("durable evidence!".getBytes(StandardCharsets.UTF_8))
        )).satisfies(failure -> {
            EvidenceArtifactStorageException storageFailure = (EvidenceArtifactStorageException) failure;
            assertThat(storageFailure.kind())
                .isEqualTo(EvidenceArtifactStorageException.FailureKind.STREAM_REJECTED);
            assertThat(storageFailure.objectMayExist()).isTrue();
        });
    }

    @Test
    void mapsConditionalPutConflictToImmutableConflict() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(S3Exception.builder().statusCode(412).build());
        S3EvidenceArtifactObjectStorage storage = new S3EvidenceArtifactObjectStorage(client, properties());

        assertThatThrownBy(() -> storage.putIfAbsent(EXPECTATION, new ByteArrayInputStream(BODY)))
            .satisfies(failure -> {
                EvidenceArtifactStorageException storageFailure = (EvidenceArtifactStorageException) failure;
                assertThat(storageFailure.kind())
                    .isEqualTo(EvidenceArtifactStorageException.FailureKind.IMMUTABLE_CONFLICT);
                assertThat(storageFailure.objectMayExist()).isTrue();
            });
    }

    @Test
    void requiresThePutResponseChecksumBeforeReturningStored() {
        S3Client client = mock(S3Client.class);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(call -> {
            consume(call.getArgument(1));
            return storedResponse().toBuilder().checksumSHA256(null).build();
        });
        S3EvidenceArtifactObjectStorage storage = new S3EvidenceArtifactObjectStorage(client, properties());

        assertThatThrownBy(() -> storage.putIfAbsent(EXPECTATION, new ByteArrayInputStream(BODY)))
            .satisfies(failure -> assertThat(((EvidenceArtifactStorageException) failure).kind())
                .isEqualTo(EvidenceArtifactStorageException.FailureKind.REMOTE_METADATA_MISMATCH));
    }

    @Test
    void probesWithChecksumModeAndReturnsOnlyMatchAbsentOrMismatch() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse());
        S3EvidenceArtifactObjectStorage storage = new S3EvidenceArtifactObjectStorage(client, properties());

        ArtifactObjectProbe probe = storage.probe(EXPECTATION);

        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(client).headObject(request.capture());
        assertThat(request.getValue().checksumMode()).isEqualTo(ChecksumMode.ENABLED);
        assertThat(request.getValue().expectedBucketOwner()).isEqualTo("123456789012");
        assertThat(probe).isInstanceOf(ArtifactObjectProbe.Match.class);

        when(client.headObject(any(HeadObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThat(storage.probe(EXPECTATION)).isInstanceOf(ArtifactObjectProbe.Absent.class);

        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(
            headResponse().toBuilder().contentLength((long) BODY.length + 1).build()
        );
        assertThat(storage.probe(EXPECTATION)).isInstanceOf(ArtifactObjectProbe.Mismatch.class);
    }

    private static EvidenceArtifactStorageProperties properties() {
        return new EvidenceArtifactStorageProperties(
            true, java.net.URI.create("https://storage.example.com"), false, "ap-southeast-1",
            "evidence-artifacts", true, "123456789012", KMS_KEY, ENCRYPTION_PROFILE,
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(10), 4, Duration.ofMinutes(1)
        );
    }

    private static PutObjectResponse storedResponse() {
        return PutObjectResponse.builder().checksumSHA256(encodedDigest()).versionId("opaque-version")
            .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(KMS_KEY).build();
    }

    private static HeadObjectResponse headResponse() {
        return HeadObjectResponse.builder().contentLength((long) BODY.length).checksumSHA256(encodedDigest())
            .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(KMS_KEY)
            .versionId("opaque-version").metadata(metadata()).build();
    }

    private static Map<String, String> metadata() {
        return Map.of(
            "artifact-id", ARTIFACT_ID.toString(), "digest", DIGEST.value(),
            "byte-count", Integer.toString(BODY.length), "encryption-profile", ENCRYPTION_PROFILE
        );
    }

    private static String encodedDigest() {
        return Base64.getEncoder().encodeToString(DIGEST.bytes());
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
