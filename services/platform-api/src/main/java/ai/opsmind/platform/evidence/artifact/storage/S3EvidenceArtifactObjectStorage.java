package ai.opsmind.platform.evidence.artifact.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** S3-compatible, single-attempt object adapter; authorization and fencing remain outside this port. */
final class S3EvidenceArtifactObjectStorage implements EvidenceArtifactObjectStorage {

    private final S3Client client;
    private final EvidenceArtifactStorageProperties properties;
    private final S3ArtifactObjectRequestFactory requests;

    S3EvidenceArtifactObjectStorage(S3Client client, EvidenceArtifactStorageProperties properties) {
        this.client = Objects.requireNonNull(client, "S3 client is required.");
        this.properties = Objects.requireNonNull(properties, "Storage properties are required.");
        this.requests = new S3ArtifactObjectRequestFactory(properties);
    }

    @Override
    public ArtifactObjectStored putIfAbsent(ArtifactObjectExpectation expectation, InputStream content) {
        if (expectation == null || content == null
            || expectation.expectedByteCount() > properties.maximumObjectBytes()) {
            throw S3EvidenceArtifactStorageFailureMapper.streamRejected(false, null);
        }
        try (var bounded = new BoundedDigestInputStream(content, expectation.expectedByteCount())) {
            PutObjectResponse response;
            try {
                response = client.putObject(
                    requests.putRequest(expectation),
                    RequestBody.fromInputStream(new NonClosingInputStream(bounded), expectation.expectedByteCount())
                );
            } catch (RuntimeException failure) {
                throw S3EvidenceArtifactStorageFailureMapper.putFailure(failure);
            }
            bounded.verifyExactEofAndDigest(expectation.expectedDigest().bytes());
            return requests.verifiedPut(response, expectation);
        } catch (EvidenceArtifactStorageException exception) {
            throw exception;
        } catch (IOException failure) {
            throw S3EvidenceArtifactStorageFailureMapper.streamRejected(true, failure);
        }
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

    /** Lets the SDK close its request body without preventing the mandatory post-write EOF check. */
    private static final class NonClosingInputStream extends FilterInputStream {

        private NonClosingInputStream(InputStream source) {
            super(source);
        }

        @Override
        public void close() { }
    }
}
