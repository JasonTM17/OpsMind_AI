package ai.opsmind.platform.evidence.artifact.storage;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** S3-compatible, single-attempt object adapter; authorization and fencing remain outside this port. */
final class S3EvidenceArtifactObjectStorage implements EvidenceArtifactObjectStorage, AutoCloseable {

    private final S3Client client;
    private final EvidenceArtifactStorageProperties properties;
    private final S3ArtifactObjectRequestFactory requests;
    private final ArtifactSourceIoExecutor sourceIoExecutor;

    S3EvidenceArtifactObjectStorage(S3Client client, EvidenceArtifactStorageProperties properties) {
        this(client, properties, new ArtifactSourceIoExecutor(properties.maximumConnections()));
    }

    S3EvidenceArtifactObjectStorage(
        S3Client client,
        EvidenceArtifactStorageProperties properties,
        ArtifactSourceIoExecutor sourceIoExecutor
    ) {
        this.client = Objects.requireNonNull(client, "S3 client is required.");
        this.properties = Objects.requireNonNull(properties, "Storage properties are required.");
        this.requests = new S3ArtifactObjectRequestFactory(properties);
        this.sourceIoExecutor = Objects.requireNonNull(
            sourceIoExecutor,
            "Artifact source executor is required."
        );
    }

    @Override
    public ArtifactObjectStored putIfAbsent(
        ArtifactObjectExpectation expectation,
        ManagedArtifactSource source,
        Instant uploadLeaseExpiresAt
    ) {
        Instant startedAt = Instant.now();
        if (expectation == null || source == null) {
            if (source != null) release(source);
            throw S3EvidenceArtifactStorageFailureMapper.streamRejected(false, null);
        }
        try {
            if (expectation.expectedByteCount() > properties.maximumObjectBytes()
                || !hasFullUploadBudget(startedAt, uploadLeaseExpiresAt)) {
                throw S3EvidenceArtifactStorageFailureMapper.streamRejected(false, null);
            }
            if (source.size() != expectation.expectedByteCount()) {
                throw S3EvidenceArtifactStorageFailureMapper.streamRejected(false, null);
            }
            try (var content = new ManagedArtifactRequestContent(
                source,
                expectation.expectedByteCount(),
                expectation.expectedDigest().bytes(),
                properties.sourceVerificationBudget(),
                sourceDeadline(startedAt, uploadLeaseExpiresAt),
                sourceIoExecutor
            )) {
                PutObjectResponse response;
                try {
                    response = client.putObject(
                        requests.putRequest(expectation),
                        RequestBody.fromContentProvider(
                            content,
                            expectation.expectedByteCount(),
                            "application/octet-stream"
                        )
                    );
                } catch (RuntimeException failure) {
                    throw S3EvidenceArtifactStorageFailureMapper.putFailure(failure);
                }
                try {
                    content.verifyAfterPut();
                } catch (IOException failure) {
                    throw S3EvidenceArtifactStorageFailureMapper.sourceContractMismatch(failure);
                }
                return requests.verifiedPut(response, expectation);
            }
        } catch (EvidenceArtifactStorageException exception) {
            throw exception;
        } catch (IOException failure) {
            throw S3EvidenceArtifactStorageFailureMapper.streamRejected(false, failure);
        } finally {
            release(source);
        }
    }

    @Override
    public void release(ManagedArtifactSource source) {
        sourceIoExecutor.detachCleanup(source);
    }

    @Override
    public void close() {
        sourceIoExecutor.close();
    }

    private boolean hasFullUploadBudget(Instant startedAt, Instant uploadLeaseExpiresAt) {
        if (uploadLeaseExpiresAt == null) return false;
        try {
            Duration remainingLease = Duration.between(startedAt, uploadLeaseExpiresAt);
            return remainingLease.compareTo(properties.requiredUploadBudget()) > 0;
        } catch (ArithmeticException | DateTimeException invalidDeadline) {
            return false;
        }
    }

    private Instant sourceDeadline(Instant startedAt, Instant uploadLeaseExpiresAt) {
        Instant configuredDeadline = startedAt
            .plus(properties.apiCallTimeout())
            .plus(properties.sourceVerificationBudget());
        Instant leaseDeadline = uploadLeaseExpiresAt.minus(properties.settlementSafetyMargin());
        return configuredDeadline.isBefore(leaseDeadline) ? configuredDeadline : leaseDeadline;
    }

    @Override
    public ArtifactObjectProbe probe(ArtifactObjectExpectation expectation) {
        if (expectation == null) {
            throw S3EvidenceArtifactStorageFailureMapper.probeFailure(
                new IllegalArgumentException("Artifact expectation is required.")
            );
        }
        try {
            HeadObjectResponse response = client.headObject(requests.probeRequest(expectation));
            ArtifactObjectStored stored = requests.matchedHead(response, expectation);
            return stored == null ? new ArtifactObjectProbe.Mismatch() : new ArtifactObjectProbe.Match(stored);
        } catch (RuntimeException failure) {
            if (S3EvidenceArtifactStorageFailureMapper.objectAbsent(failure)) {
                return new ArtifactObjectProbe.Absent();
            }
            throw S3EvidenceArtifactStorageFailureMapper.probeFailure(failure);
        }
    }
}
