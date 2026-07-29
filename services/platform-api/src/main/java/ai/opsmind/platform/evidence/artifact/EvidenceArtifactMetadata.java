package ai.opsmind.platform.evidence.artifact;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

/** Authorized metadata projection. It deliberately excludes object references and artifact bytes. */
public record EvidenceArtifactMetadata(
    UUID artifactId,
    UUID organizationId,
    UUID projectId,
    UUID incidentId,
    UUID runId,
    UUID actorId,
    UUID idempotencyKey,
    String sourceType,
    String sourceIdentity,
    String sourceVersion,
    String dataClassification,
    EvidenceArtifactDigest expectedDigest,
    long expectedByteCount,
    long authorizationEpoch,
    String retentionClass,
    String residencyClass,
    String deletionClass,
    EvidenceArtifactLifecycleState lifecycleState,
    long lifecycleVersion,
    Instant createdAt
) {
    public EvidenceArtifactMetadata {
        if (artifactId == null || organizationId == null || projectId == null || incidentId == null
            || runId == null || actorId == null || idempotencyKey == null || sourceType == null
            || sourceIdentity == null || sourceVersion == null || dataClassification == null
            || expectedDigest == null || expectedByteCount < 1 || authorizationEpoch < 0
            || retentionClass == null || residencyClass == null || deletionClass == null
            || lifecycleState == null || lifecycleVersion < 1 || createdAt == null) {
            throw new IllegalArgumentException("Artifact metadata is invalid.");
        }
    }

    static EvidenceArtifactMetadata pendingUpload(
        AuthorizedIncidentAnalysisScope scope,
        EvidenceArtifactCreateCommand command,
        UUID artifactId,
        Instant createdAt
    ) {
        return new EvidenceArtifactMetadata(
            artifactId, scope.organizationId(), scope.projectId(), scope.incidentId(), command.runId(),
            scope.actorId(), command.idempotencyKey(), command.sourceType(), command.sourceIdentity(),
            command.sourceVersion(), command.dataClassification(), command.expectedDigest(),
            command.expectedByteCount(), scope.authorizationEpoch(), EvidenceArtifactPolicy.RETENTION_CLASS,
            EvidenceArtifactPolicy.RESIDENCY_CLASS, EvidenceArtifactPolicy.DELETION_CLASS,
            EvidenceArtifactLifecycleState.PENDING_UPLOAD,
            EvidenceArtifactPolicy.INITIAL_LIFECYCLE_VERSION,
            createdAt
        );
    }

    boolean matches(AuthorizedIncidentAnalysisScope scope, EvidenceArtifactCreateCommand command) {
        return organizationId.equals(scope.organizationId())
            && projectId.equals(scope.projectId())
            && incidentId.equals(scope.incidentId())
            && actorId.equals(scope.actorId())
            && runId.equals(command.runId())
            && idempotencyKey.equals(command.idempotencyKey())
            && sourceType.equals(command.sourceType())
            && sourceIdentity.equals(command.sourceIdentity())
            && sourceVersion.equals(command.sourceVersion())
            && dataClassification.equals(command.dataClassification())
            && expectedDigest.equals(command.expectedDigest())
            && expectedByteCount == command.expectedByteCount()
            && authorizationEpoch == scope.authorizationEpoch()
            && retentionClass.equals(EvidenceArtifactPolicy.RETENTION_CLASS)
            && residencyClass.equals(EvidenceArtifactPolicy.RESIDENCY_CLASS)
            && deletionClass.equals(EvidenceArtifactPolicy.DELETION_CLASS);
    }
}
