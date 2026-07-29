package ai.opsmind.platform.evidence.artifact.storage;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/** Builds and verifies the narrow S3 protocol used by the artifact storage port. */
final class S3ArtifactObjectRequestFactory {

    private final EvidenceArtifactStorageProperties properties;

    S3ArtifactObjectRequestFactory(EvidenceArtifactStorageProperties properties) {
        this.properties = properties;
    }

    PutObjectRequest putRequest(ArtifactObjectExpectation expectation) {
        var builder = PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(expectation.storageKey())
            .contentLength(expectation.expectedByteCount())
            .ifNoneMatch("*")
            .checksumAlgorithm(ChecksumAlgorithm.SHA256)
            .checksumSHA256(encodedDigest(expectation))
            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
            .ssekmsKeyId(properties.kmsKeyId())
            .metadata(metadata(expectation));
        applyExpectedBucketOwner(builder, properties.expectedBucketOwner());
        return builder.build();
    }

    HeadObjectRequest probeRequest(ArtifactObjectExpectation expectation) {
        var builder = HeadObjectRequest.builder()
            .bucket(properties.bucket())
            .key(expectation.storageKey())
            .checksumMode(ChecksumMode.ENABLED);
        applyExpectedBucketOwner(builder, properties.expectedBucketOwner());
        return builder.build();
    }

    ArtifactObjectStored verifiedPut(PutObjectResponse response, ArtifactObjectExpectation expectation) {
        if (response == null || !matchesChecksum(response.checksumSHA256(), expectation)
            || response.serverSideEncryption() != ServerSideEncryption.AWS_KMS
            || !properties.kmsKeyId().equals(response.ssekmsKeyId())
            || !validVersionReference(response.versionId())) {
            throw remoteMetadataMismatch();
        }
        return stored(expectation, response.versionId());
    }

    ArtifactObjectStored matchedHead(HeadObjectResponse response, ArtifactObjectExpectation expectation) {
        if (!matchesHead(response, expectation)) return null;
        return stored(expectation, response.versionId());
    }

    private boolean matchesHead(HeadObjectResponse response, ArtifactObjectExpectation expectation) {
        return response != null && response.contentLength() != null
            && response.contentLength() == expectation.expectedByteCount()
            && matchesChecksum(response.checksumSHA256(), expectation)
            && response.serverSideEncryption() == ServerSideEncryption.AWS_KMS
            && properties.kmsKeyId().equals(response.ssekmsKeyId())
            && metadata(expectation).equals(response.metadata())
            && validVersionReference(response.versionId());
    }

    private ArtifactObjectStored stored(ArtifactObjectExpectation expectation, String versionReference) {
        return new ArtifactObjectStored(
            expectation.expectedDigest(),
            expectation.expectedByteCount(),
            versionReference,
            properties.encryptionProfile()
        );
    }

    private boolean matchesChecksum(String checksum, ArtifactObjectExpectation expectation) {
        if (checksum == null || checksum.isBlank()) return false;
        try {
            return MessageDigest.isEqual(expectation.expectedDigest().bytes(), Base64.getDecoder().decode(checksum));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String encodedDigest(ArtifactObjectExpectation expectation) {
        return Base64.getEncoder().encodeToString(expectation.expectedDigest().bytes());
    }

    private Map<String, String> metadata(ArtifactObjectExpectation expectation) {
        return Map.of(
            "artifact-id", expectation.artifactId().toString(),
            "digest", expectation.expectedDigest().value(),
            "byte-count", Long.toString(expectation.expectedByteCount()),
            "encryption-profile", properties.encryptionProfile()
        );
    }

    private static void applyExpectedBucketOwner(
        PutObjectRequest.Builder builder,
        String expectedBucketOwner
    ) {
        if (!expectedBucketOwner.isBlank()) builder.expectedBucketOwner(expectedBucketOwner);
    }

    private static void applyExpectedBucketOwner(
        HeadObjectRequest.Builder builder,
        String expectedBucketOwner
    ) {
        if (!expectedBucketOwner.isBlank()) builder.expectedBucketOwner(expectedBucketOwner);
    }

    private static boolean validVersionReference(String value) {
        return value != null && !value.isBlank() && value.length() <= 256;
    }

    private static EvidenceArtifactStorageException remoteMetadataMismatch() {
        return new EvidenceArtifactStorageException(
            EvidenceArtifactStorageException.FailureKind.REMOTE_METADATA_MISMATCH,
            true,
            null
        );
    }
}
