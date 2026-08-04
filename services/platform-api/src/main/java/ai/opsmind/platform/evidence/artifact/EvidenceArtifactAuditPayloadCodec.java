package ai.opsmind.platform.evidence.artifact;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Emits the small, exact audit envelope that the V014 database trigger independently verifies. */
@Component
public final class EvidenceArtifactAuditPayloadCodec {

    private final ObjectMapper objectMapper;

    public EvidenceArtifactAuditPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuditEvent pendingUpload(
        EvidenceArtifactMetadata artifact,
        UUID eventId,
        Instant occurredAt
    ) {
        return new AuditEvent(
            eventId,
            artifact.organizationId(),
            artifact.actorId(),
            "ARTIFACT_PENDING_UPLOAD",
            AuditEvent.EVIDENCE_ARTIFACT_SCHEMA_VERSION,
            "evidence_artifact",
            artifact.artifactId().toString(),
            artifact.artifactId(),
            occurredAt,
            write(new PendingUploadPayload(
                eventId, artifact.organizationId(), artifact.projectId(), artifact.incidentId(),
                artifact.runId(), artifact.artifactId(), artifact.actorId(), artifact.lifecycleVersion(),
                artifact.lifecycleState().name(), artifact.expectedDigest().value(),
                artifact.expectedByteCount(), artifact.dataClassification(), artifact.retentionClass(),
                occurredAt
            ))
        );
    }

    public AuditEvent stored(
        EvidenceArtifactUploadClaim claim,
        EvidenceArtifactUploadSettlement settlement,
        UUID eventId
    ) {
        if (claim == null || settlement == null || eventId == null
            || settlement.lifecycleState() != EvidenceArtifactLifecycleState.STORED) {
            throw new IllegalArgumentException("Stored artifact audit inputs are invalid.");
        }
        EvidenceArtifactMetadata artifact = claim.artifact();
        Instant occurredAt = settlement.lifecycleUpdatedAt();
        return new AuditEvent(
            eventId,
            artifact.organizationId(),
            artifact.actorId(),
            "ARTIFACT_STORED",
            AuditEvent.EVIDENCE_ARTIFACT_SCHEMA_VERSION,
            "evidence_artifact",
            artifact.artifactId().toString(),
            artifact.artifactId(),
            occurredAt,
            write(new StoredPayload(
                eventId, artifact.organizationId(), artifact.projectId(), artifact.incidentId(),
                artifact.runId(), artifact.artifactId(), artifact.actorId(), settlement.lifecycleVersion(),
                settlement.lifecycleState().name(), artifact.expectedDigest().value(), artifact.expectedByteCount(),
                artifact.dataClassification(), artifact.retentionClass(), settlement.storageGeneration(), occurredAt
            ))
        );
    }

    public AuditEvent lifecycleChanged(
        EvidenceArtifactMetadata artifact,
        EvidenceArtifactLifecycleState fromState,
        EvidenceArtifactLifecycleState toState,
        long lifecycleVersion,
        UUID eventId,
        String reason,
        Instant occurredAt
    ) {
        if (artifact == null || fromState == null || toState == null || lifecycleVersion < 3
            || eventId == null || reason == null || occurredAt == null) {
            throw new IllegalArgumentException("Artifact lifecycle audit inputs are invalid.");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("organizationId", artifact.organizationId());
        payload.put("projectId", artifact.projectId());
        payload.put("incidentId", artifact.incidentId());
        payload.put("runId", artifact.runId());
        payload.put("artifactId", artifact.artifactId());
        payload.put("actorId", artifact.actorId());
        payload.put("lifecycleVersion", lifecycleVersion);
        payload.put("lifecycleState", toState.name());
        payload.put("fromState", fromState.name());
        payload.put("toState", toState.name());
        payload.put("occurredAt", occurredAt);
        payload.put("reason", reason);
        return new AuditEvent(eventId, artifact.organizationId(), artifact.actorId(),
            "ARTIFACT_LIFECYCLE_CHANGED", AuditEvent.EVIDENCE_ARTIFACT_SCHEMA_VERSION,
            "evidence_artifact", artifact.artifactId().toString(), artifact.artifactId(),
            occurredAt, write(payload));
    }

    private String write(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JacksonException exception) {
            throw new PlatformProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "evidence-artifact.serialization-failed",
                "Artifact audit metadata could not be serialized safely.",
                exception
            );
        }
    }

    private record PendingUploadPayload(
        UUID eventId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        UUID artifactId,
        UUID actorId,
        long lifecycleVersion,
        String lifecycleState,
        String contentDigest,
        long byteCount,
        String dataClassification,
        String retentionClass,
        Instant occurredAt
    ) { }

    private record StoredPayload(
        UUID eventId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        UUID artifactId,
        UUID actorId,
        long lifecycleVersion,
        String lifecycleState,
        String contentDigest,
        long byteCount,
        String dataClassification,
        String retentionClass,
        long storageGeneration,
        Instant occurredAt
    ) { }
}
