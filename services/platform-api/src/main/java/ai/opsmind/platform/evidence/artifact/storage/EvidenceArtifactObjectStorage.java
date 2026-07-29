package ai.opsmind.platform.evidence.artifact.storage;

import java.io.InputStream;

/**
 * Internal object-body boundary. Implementations never authorize callers and
 * never return object URLs, credentials, bucket names, or raw key material.
 */
public interface EvidenceArtifactObjectStorage {

    ArtifactObjectStored putIfAbsent(
        ArtifactObjectExpectation expectation,
        InputStream content
    );

    ArtifactObjectProbe probe(ArtifactObjectExpectation expectation);
}
