package ai.opsmind.platform.evidence.artifact.lifecycle;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactAuditPayloadCodec;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactIdentity;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadataReader;
import ai.opsmind.platform.evidence.artifact.access.ArtifactAccessDeniedException;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Tenant-bound metadata lifecycle persistence. Object I/O is deliberately out of scope. */
@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public class ArtifactLifecycleRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EvidenceArtifactMetadataReader metadataReader;
    private final ArtifactLifecycleService lifecycleService;
    private final EvidenceArtifactAuditPayloadCodec auditCodec;
    private final ai.opsmind.platform.audit.AuditRepository auditRepository;

    public ArtifactLifecycleRepository(
        JdbcTemplate jdbcTemplate,
        EvidenceArtifactMetadataReader metadataReader,
        ArtifactLifecycleService lifecycleService,
        EvidenceArtifactAuditPayloadCodec auditCodec,
        ai.opsmind.platform.audit.AuditRepository auditRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataReader = metadataReader;
        this.lifecycleService = lifecycleService;
        this.auditCodec = auditCodec;
        this.auditRepository = auditRepository;
    }

    public ArtifactLifecycleTransition transition(
        AuthorizedIncidentAnalysisScope scope, UUID runId, UUID artifactId,
        ArtifactLifecycleCommand command
    ) {
        requireTransaction();
        if (scope == null || runId == null || artifactId == null || command == null) throw hidden();
        try {
            EvidenceArtifactMetadata metadata = metadataReader.findVisibleForUpdate(
                scope, runId, artifactId
            )
                .orElseThrow(this::hidden);
            Timestamp databaseTime = jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()", Timestamp.class
            );
            if (databaseTime == null) throw new IllegalStateException("Database time is unavailable.");
            Instant occurredAt = databaseTime.toInstant().truncatedTo(ChronoUnit.MICROS);
            ArtifactLifecycleCommand effectiveCommand = new ArtifactLifecycleCommand(
                command.actorId(), command.authorizationEpoch(), command.expectedDigest(),
                command.targetState(), command.reason(), occurredAt
            );
            ArtifactLifecycleTransition transition = lifecycleService.transition(
                metadata, effectiveCommand
            );
            if (transition.idempotent()) return transition;
            Boolean updated = jdbcTemplate.queryForObject("""
                SELECT public.opsmind_transition_evidence_artifact(
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """, Boolean.class, scope.organizationId(), scope.projectId(), scope.incidentId(),
                artifactId, command.actorId(), command.authorizationEpoch(),
                command.expectedDigest().bytes(), transition.fromState().name(),
                transition.lifecycleVersion() - 1, transition.toState().name(),
                Timestamp.from(transition.occurredAt()));
            if (!Boolean.TRUE.equals(updated)) throw hidden();

            UUID eventId = EvidenceArtifactIdentity.controlEventId(
                metadata.organizationId(), metadata.artifactId(), transition.lifecycleVersion()
            );
            jdbcTemplate.update("""
                INSERT INTO evidence_artifact_events (
                    event_id, organization_id, project_id, incident_id, run_id, artifact_id, actor_id,
                    lifecycle_version, lifecycle_from_state, lifecycle_to_state, occurred_at, audit_event_id,
                    upload_attempt_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, eventId, metadata.organizationId(), metadata.projectId(), metadata.incidentId(),
                metadata.runId(), metadata.artifactId(), metadata.actorId(), transition.lifecycleVersion(),
                transition.fromState().name(), transition.toState().name(),
                Timestamp.from(transition.occurredAt()), eventId);
            auditRepository.append(auditCodec.lifecycleChanged(metadata, transition.fromState(),
                transition.toState(), transition.lifecycleVersion(), eventId, transition.reason(),
                transition.occurredAt()));
            return transition;
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (ArtifactAccessDeniedException | IllegalStateException exception) {
            throw hidden();
        }
        catch (DataAccessException exception) {
            throw new PlatformProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                "evidence-artifact.persistence-unavailable",
                "Artifact lifecycle persistence is temporarily unavailable.", exception);
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact lifecycle requires an authorization transaction.");
        }
    }

    private PlatformProblemException hidden() {
        return new PlatformProblemException(HttpStatus.NOT_FOUND, "evidence-artifact.not-found",
            "Evidence artifact was not found or is not visible.");
    }
}
