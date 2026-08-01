package ai.opsmind.platform.evidence.artifact.storage;

import java.time.Instant;

/**
 * Internal object-body boundary. Implementations never authorize callers and
 * never return object URLs, credentials, bucket names, or raw key material.
 * Uploads accept only a storage-owned, replayable spool source; generic input
 * streams cannot safely satisfy SDK inspection and wire-transfer passes.
 */
public interface EvidenceArtifactObjectStorage {

    ArtifactObjectStored putIfAbsent(
        ArtifactObjectExpectation expectation,
        ManagedArtifactSource source,
        Instant uploadLeaseExpiresAt
    );

    ArtifactObjectProbe probe(ArtifactObjectExpectation expectation);

    void release(ManagedArtifactSource source);
}
