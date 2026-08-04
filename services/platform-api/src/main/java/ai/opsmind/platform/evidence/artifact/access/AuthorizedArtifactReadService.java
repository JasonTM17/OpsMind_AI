package ai.opsmind.platform.evidence.artifact.access;

import java.util.Objects;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;

/** Fail-closed metadata gate. Object I/O must happen only after this method returns. */
public final class AuthorizedArtifactReadService {
    public EvidenceArtifactMetadata authorize(
        EvidenceArtifactMetadata metadata,
        AuthorizedArtifactReadRequest request,
        boolean objectPresent
    ) {
        if (metadata == null || request == null || !objectPresent
            || !metadata.lifecycleState().isReadable()
            || !Objects.equals(metadata.organizationId(), request.organizationId())
            || !Objects.equals(metadata.projectId(), request.projectId())
            || !Objects.equals(metadata.incidentId(), request.incidentId())
            || !Objects.equals(metadata.runId(), request.runId())
            || !Objects.equals(metadata.actorId(), request.actorId())
            || metadata.authorizationEpoch() != request.authorizationEpoch()
            || !metadata.expectedDigest().equals(request.expectedDigest())) {
            throw new ArtifactAccessDeniedException();
        }
        return metadata;
    }
}
