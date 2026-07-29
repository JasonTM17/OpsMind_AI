package ai.opsmind.platform.evidence.artifact.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3ArtifactObjectRequestFactoryTest {

    @Test
    void rejectsTheUnversionedNullSentinel() {
        S3ArtifactObjectRequestFactory requests = new S3ArtifactObjectRequestFactory(properties());

        assertThatThrownBy(() -> requests.verifiedPut(response("null", "kms-key"), expectation()))
            .isInstanceOf(EvidenceArtifactStorageException.class);
    }

    @Test
    void preservesAmbiguityWhenAProbeIsDenied() {
        EvidenceArtifactStorageException failure = S3EvidenceArtifactStorageFailureMapper.probeFailure(
            software.amazon.awssdk.services.s3.model.S3Exception.builder().statusCode(403).build()
        );

        assertThat(failure.kind()).isEqualTo(EvidenceArtifactStorageException.FailureKind.ACCESS_DENIED);
        assertThat(failure.objectMayExist()).isTrue();
    }

    @Test
    void verifiesAProviderCanonicalKmsReferenceSeparatelyFromTheRequestKey() {
        String canonicalKey = "arn:aws:kms:ap-southeast-1:123456789012:key/key-1";
        S3ArtifactObjectRequestFactory requests = new S3ArtifactObjectRequestFactory(
            properties("alias/opsmind-artifacts", canonicalKey)
        );

        assertThat(requests.putRequest(expectation()).ssekmsKeyId()).isEqualTo("alias/opsmind-artifacts");
        assertThat(requests.verifiedPut(response("opaque-version", canonicalKey), expectation())
            .versionReference())
            .isEqualTo("opaque-version");
    }

    @Test
    void acceptsTheDocumentedS3VersionIdBoundAndRejectsAnOversizedUtf8Value() {
        String maximumVersion = "v".repeat(1_024);
        S3ArtifactObjectRequestFactory requests = new S3ArtifactObjectRequestFactory(properties());

        assertThat(requests.verifiedPut(response(maximumVersion, "kms-key"), expectation())
            .versionReference()).isEqualTo(maximumVersion);
        assertThatThrownBy(() -> new ArtifactObjectStored(
            expectation().expectedDigest(), 4L, "é".repeat(513), "production-kms"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static ArtifactObjectExpectation expectation() {
        return new ArtifactObjectExpectation(
            UUID.fromString("730c8fea-5479-46f8-aab3-d3b60f871c37"),
            "artifact/730c8fea",
            EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64)),
            4L
        );
    }

    private static PutObjectResponse response(String versionReference, String kmsKey) {
        return PutObjectResponse.builder()
            .checksumSHA256("qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqo=")
            .versionId(versionReference)
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(kmsKey)
            .build();
    }

    private static EvidenceArtifactStorageProperties properties() {
        return properties("kms-key", "kms-key");
    }

    private static EvidenceArtifactStorageProperties properties(String requestKey, String expectedKey) {
        return new EvidenceArtifactStorageProperties(
            true, URI.create("https://storage.example.com"), false, "ap-southeast-1",
            "evidence-artifacts", true, "123456789012", requestKey, expectedKey, "production-kms",
            1_024, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(10), 4, Duration.ofMinutes(1)
        );
    }
}
