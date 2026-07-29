package ai.opsmind.platform.evidence.artifact;

import java.sql.Timestamp;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Appends the immutable STORED lifecycle event before its exact audit-chain entry. */
@Component
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class EvidenceArtifactStoredLifecycleAppender {

    private final JdbcTemplate jdbcTemplate;
    private final AuditRepository auditRepository;
    private final EvidenceArtifactAuditPayloadCodec auditCodec;

    EvidenceArtifactStoredLifecycleAppender(
        JdbcTemplate jdbcTemplate,
        AuditRepository auditRepository,
        EvidenceArtifactAuditPayloadCodec auditCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditRepository = auditRepository;
        this.auditCodec = auditCodec;
    }

    void append(EvidenceArtifactUploadClaim claim, EvidenceArtifactUploadSettlement settlement) {
        EvidenceArtifactMetadata artifact = claim.artifact();
        UUID eventId = EvidenceArtifactIdentity.lifecycleEventId(
            artifact.organizationId(), artifact.artifactId(), settlement.lifecycleVersion(), claim.uploadAttemptId()
        );
        jdbcTemplate.update("""
            INSERT INTO evidence_artifact_events (
                event_id, organization_id, project_id, incident_id, run_id, artifact_id, actor_id,
                lifecycle_version, lifecycle_from_state, lifecycle_to_state, occurred_at, audit_event_id,
                upload_attempt_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, eventId, artifact.organizationId(), artifact.projectId(), artifact.incidentId(), artifact.runId(),
            artifact.artifactId(), artifact.actorId(), settlement.lifecycleVersion(),
            EvidenceArtifactLifecycleState.PENDING_UPLOAD.name(), settlement.lifecycleState().name(),
            Timestamp.from(settlement.lifecycleUpdatedAt()), eventId, claim.uploadAttemptId());
        auditRepository.append(auditCodec.stored(claim, settlement, eventId));
    }
}
