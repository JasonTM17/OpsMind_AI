package ai.opsmind.platform.evidence.artifact;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectExpectation;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

/** A committed, fenced upload attempt. Object location stays inside this control-plane value. */
public record EvidenceArtifactUploadClaim(
    EvidenceArtifactMetadata artifact,
    String storageKey,
    UUID uploadAttemptId,
    int uploadAttemptCount,
    Instant uploadLeaseExpiresAt,
    boolean probeRequired,
    boolean reconciliationRequired
) {
    public EvidenceArtifactUploadClaim {
        if (artifact == null || storageKey == null || storageKey.isBlank() || storageKey.length() > 512
            || uploadAttemptId == null || uploadAttemptCount < 1 || uploadLeaseExpiresAt == null
            || probeRequired && reconciliationRequired) {
            throw new IllegalArgumentException("Artifact upload claim is invalid.");
        }
    }

    public ArtifactObjectExpectation expectation() {
        return new ArtifactObjectExpectation(
            artifact.artifactId(), storageKey, artifact.expectedDigest(), artifact.expectedByteCount()
        );
    }

    boolean matches(AuthorizedIncidentAnalysisScope scope) {
        return scope != null
            && artifact.organizationId().equals(scope.organizationId())
            && artifact.projectId().equals(scope.projectId())
            && artifact.incidentId().equals(scope.incidentId())
            && artifact.actorId().equals(scope.actorId())
            && artifact.authorizationEpoch() == scope.authorizationEpoch();
    }
}
