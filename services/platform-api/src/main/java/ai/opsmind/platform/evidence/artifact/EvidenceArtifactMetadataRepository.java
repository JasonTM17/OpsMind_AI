package ai.opsmind.platform.evidence.artifact;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditRepository;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** JDBC authority for metadata only. It cannot open, upload, or expose an artifact stream. */
@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public class EvidenceArtifactMetadataRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AuditRepository auditRepository;
    private final EvidenceArtifactAuditPayloadCodec auditCodec;
    private final EvidenceArtifactMetadataReader metadataReader;

    public EvidenceArtifactMetadataRepository(
        JdbcTemplate jdbcTemplate,
        AuditRepository auditRepository,
        EvidenceArtifactAuditPayloadCodec auditCodec,
        EvidenceArtifactMetadataReader metadataReader
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditRepository = auditRepository;
        this.auditCodec = auditCodec;
        this.metadataReader = metadataReader;
    }

    public EvidenceArtifactMetadata create(
        AuthorizedIncidentAnalysisScope scope,
        EvidenceArtifactCreateCommand command,
        Instant occurredAt
    ) {
        requireCreateInputs(scope, command, occurredAt);
        UUID artifactId = EvidenceArtifactIdentity.artifactId(
            scope.organizationId(), command.runId(), command.idempotencyKey()
        );
        EvidenceArtifactMetadata proposed = EvidenceArtifactMetadata.pendingUpload(
            scope, command, artifactId, occurredAt
        );
        try {
            if (insert(proposed) == 1) {
                UUID eventId = EvidenceArtifactIdentity.initialEventId(
                    scope.organizationId(), artifactId
                );
                appendInitialEvent(proposed, eventId);
                auditRepository.append(auditCodec.pendingUpload(proposed, eventId, occurredAt));
                return proposed;
            }
            EvidenceArtifactMetadata existing = metadataReader.findIdempotencyBound(scope, command)
                .orElseThrow(this::hidden);
            if (!existing.matches(scope, command)) throw idempotencyConflict();
            return existing;
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataIntegrityViolationException exception) {
            throw rejected(exception);
        }
        catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public EvidenceArtifactMetadata requireReadableMetadata(
        AuthorizedIncidentAnalysisScope scope,
        UUID artifactId
    ) {
        requireTransaction();
        if (scope == null || artifactId == null) throw new IllegalArgumentException("Artifact scope is required.");
        try {
            EvidenceArtifactMetadata artifact = metadataReader.findVisible(scope, artifactId)
                .orElseThrow(this::hidden);
            if (!artifact.lifecycleState().isReadable()) throw hidden();
            return artifact;
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void requireCreateInputs(
        AuthorizedIncidentAnalysisScope scope,
        EvidenceArtifactCreateCommand command,
        Instant occurredAt
    ) {
        requireTransaction();
        if (scope == null || command == null || occurredAt == null) {
            throw new IllegalArgumentException("Artifact metadata scope and time are required.");
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact metadata requires an authorization transaction.");
        }
    }

    private int insert(EvidenceArtifactMetadata artifact) {
        return jdbcTemplate.update("""
            INSERT INTO evidence_artifacts (
                artifact_id, organization_id, project_id, incident_id, run_id, actor_id,
                idempotency_key, source_type, source_identity, source_version, data_classification,
                expected_content_digest, expected_byte_count, authorization_epoch, retention_class,
                residency_class, deletion_class, storage_key, lifecycle_state, lifecycle_version,
                storage_generation, upload_attempt_count, created_at, lifecycle_updated_at
            )
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
              FROM investigation_runs run_row
              JOIN incidents incident_row
                ON incident_row.id = run_row.incident_id
               AND incident_row.organization_id = run_row.organization_id
               AND incident_row.project_id = run_row.project_id
              WHERE run_row.organization_id = ?
                AND run_row.project_id = ?
                AND run_row.incident_id = ?
                AND run_row.run_id = ?
                AND run_row.actor_id = ?
                AND incident_row.version = ?
            ON CONFLICT (organization_id, run_id, idempotency_key) DO NOTHING
            """,
            artifact.artifactId(), artifact.organizationId(), artifact.projectId(), artifact.incidentId(),
            artifact.runId(), artifact.actorId(), artifact.idempotencyKey(), artifact.sourceType(),
            artifact.sourceIdentity(), artifact.sourceVersion(), artifact.dataClassification(),
            artifact.expectedDigest().bytes(), artifact.expectedByteCount(), artifact.authorizationEpoch(),
            artifact.retentionClass(), artifact.residencyClass(), artifact.deletionClass(),
            EvidenceArtifactStorageKey.derive(
                artifact.organizationId(), artifact.artifactId(), artifact.expectedDigest()
            ),
            artifact.lifecycleState().name(), artifact.lifecycleVersion(), 0L, 0,
            Timestamp.from(artifact.createdAt()), Timestamp.from(artifact.createdAt()),
            artifact.organizationId(), artifact.projectId(), artifact.incidentId(), artifact.runId(),
            artifact.actorId(), artifact.authorizationEpoch()
        );
    }

    private void appendInitialEvent(EvidenceArtifactMetadata artifact, UUID eventId) {
        jdbcTemplate.update("""
            INSERT INTO evidence_artifact_events (
                event_id, organization_id, project_id, incident_id, run_id, artifact_id, actor_id,
                lifecycle_version, lifecycle_from_state, lifecycle_to_state, occurred_at, audit_event_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
            """,
            eventId, artifact.organizationId(), artifact.projectId(), artifact.incidentId(), artifact.runId(),
            artifact.artifactId(), artifact.actorId(), artifact.lifecycleVersion(),
            artifact.lifecycleState().name(), Timestamp.from(artifact.createdAt()), eventId
        );
    }

    private PlatformProblemException hidden() {
        return new PlatformProblemException(
            HttpStatus.NOT_FOUND, "evidence-artifact.not-found",
            "Evidence artifact was not found or is not visible."
        );
    }

    private PlatformProblemException idempotencyConflict() {
        return new PlatformProblemException(
            HttpStatus.CONFLICT, "evidence-artifact.idempotency-conflict",
            "The artifact idempotency key is already bound to different metadata."
        );
    }

    private PlatformProblemException rejected(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT, "evidence-artifact.persistence-rejected",
            "Artifact metadata did not satisfy its persistence contract.", cause
        );
    }

    private PlatformProblemException unavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE, "evidence-artifact.persistence-unavailable",
            "Artifact metadata persistence is temporarily unavailable.", cause
        );
    }
}
