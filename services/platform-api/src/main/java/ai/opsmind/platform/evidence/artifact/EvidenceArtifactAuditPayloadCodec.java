package ai.opsmind.platform.evidence.artifact;

import java.time.Instant;
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

    private String write(PendingUploadPayload payload) {
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
}
