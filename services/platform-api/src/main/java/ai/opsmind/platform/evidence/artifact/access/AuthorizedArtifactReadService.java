package ai.opsmind.platform.evidence.artifact.access;

import java.util.Objects;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;

import org.springframework.stereotype.Component;

/** Fail-closed metadata gate. Object I/O must happen only after this method returns. */
@Component
public final class AuthorizedArtifactReadService {
    public EvidenceArtifactMetadata authorize(
        EvidenceArtifactMetadata metadata,
        AuthorizedArtifactReadRequest request,
        ArtifactObjectProbeFacts probe
    ) {
        if (metadata == null || request == null || probe == null || !probe.present()
            || !metadata.lifecycleState().isReadable()
            || !Objects.equals(metadata.organizationId(), request.organizationId())
            || !Objects.equals(metadata.projectId(), request.projectId())
            || !Objects.equals(metadata.incidentId(), request.incidentId())
            || !Objects.equals(metadata.runId(), request.runId())
            || !Objects.equals(metadata.actorId(), request.actorId())
            || metadata.authorizationEpoch() != request.authorizationEpoch()
            || !metadata.expectedDigest().equals(request.expectedDigest())
            || !metadata.expectedDigest().equals(probe.digest())
            || metadata.expectedByteCount() != probe.byteCount()) {
            throw new ArtifactAccessDeniedException();
        }
        return metadata;
    }
}
