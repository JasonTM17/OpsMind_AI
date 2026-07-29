package ai.opsmind.platform.evidence.artifact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Scoped metadata queries. Returned projections never contain object references or artifact bytes. */
@Component
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
class EvidenceArtifactMetadataReader {

    private final JdbcTemplate jdbcTemplate;

    EvidenceArtifactMetadataReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<EvidenceArtifactMetadata> findIdempotencyBound(
        AuthorizedIncidentAnalysisScope scope,
        EvidenceArtifactCreateCommand command
    ) {
        return query("""
            WHERE organization_id = ? AND project_id = ? AND incident_id = ? AND run_id = ?
              AND actor_id = ? AND idempotency_key = ? AND authorization_epoch = ?
            """, scope.organizationId(), scope.projectId(), scope.incidentId(), command.runId(),
            scope.actorId(), command.idempotencyKey(), scope.authorizationEpoch());
    }

    Optional<EvidenceArtifactMetadata> findVisible(
        AuthorizedIncidentAnalysisScope scope,
        UUID artifactId
    ) {
        return query("""
            WHERE organization_id = ? AND project_id = ? AND incident_id = ? AND artifact_id = ?
              AND actor_id = ? AND authorization_epoch = ?
            """, scope.organizationId(), scope.projectId(), scope.incidentId(), artifactId,
            scope.actorId(), scope.authorizationEpoch());
    }

    private Optional<EvidenceArtifactMetadata> query(String condition, Object... values) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact metadata lookup requires an authorization transaction.");
        }
        List<EvidenceArtifactMetadata> rows = jdbcTemplate.query("""
            SELECT artifact_id, organization_id, project_id, incident_id, run_id, actor_id,
                   idempotency_key, source_type, source_identity, source_version, data_classification,
                   'sha256:' || encode(expected_content_digest, 'hex') AS expected_digest,
                   expected_byte_count, authorization_epoch, retention_class, residency_class,
                   deletion_class, lifecycle_state, lifecycle_version, created_at
              FROM evidence_artifacts
            """ + condition,
            (resultSet, rowNumber) -> new EvidenceArtifactMetadata(
                resultSet.getObject("artifact_id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("incident_id", UUID.class),
                resultSet.getObject("run_id", UUID.class),
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getObject("idempotency_key", UUID.class),
                resultSet.getString("source_type"), resultSet.getString("source_identity"),
                resultSet.getString("source_version"), resultSet.getString("data_classification"),
                EvidenceArtifactDigest.parse(resultSet.getString("expected_digest")),
                resultSet.getLong("expected_byte_count"), resultSet.getLong("authorization_epoch"),
                resultSet.getString("retention_class"), resultSet.getString("residency_class"),
                resultSet.getString("deletion_class"), EvidenceArtifactLifecycleState.valueOf(
                    resultSet.getString("lifecycle_state")
                ), resultSet.getLong("lifecycle_version"), resultSet.getTimestamp("created_at").toInstant()
            ), values);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
