package ai.opsmind.platform.evidence.artifact.access;

import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;

/** Caller-bound claims required before an artifact stream may be opened by a later adapter. */
public record AuthorizedArtifactReadRequest(
    UUID organizationId, UUID projectId, UUID incidentId, UUID runId, UUID actorId,
    long authorizationEpoch, EvidenceArtifactDigest expectedDigest
) {
    public AuthorizedArtifactReadRequest {
        if (organizationId == null || projectId == null || incidentId == null || runId == null
            || actorId == null || authorizationEpoch < 0 || expectedDigest == null) {
            throw new IllegalArgumentException("Artifact read authorization claims are invalid.");
        }
    }
}
