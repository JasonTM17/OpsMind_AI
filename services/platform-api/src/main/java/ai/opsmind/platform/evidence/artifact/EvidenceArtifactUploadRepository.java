package ai.opsmind.platform.evidence.artifact;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectStored;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** JDBC capabilities; kept non-final so Spring can proxy this no-interface repository. */
@Repository
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public class EvidenceArtifactUploadRepository {

    private final JdbcTemplate jdbcTemplate;
    private final EvidenceArtifactMetadataReader metadataReader;
    private final EvidenceArtifactStoredLifecycleAppender storedLifecycleAppender;

    public EvidenceArtifactUploadRepository(
        JdbcTemplate jdbcTemplate,
        EvidenceArtifactMetadataReader metadataReader,
        EvidenceArtifactStoredLifecycleAppender storedLifecycleAppender
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataReader = metadataReader;
        this.storedLifecycleAppender = storedLifecycleAppender;
    }

    public EvidenceArtifactUploadClaim claim(
        AuthorizedIncidentAnalysisScope scope,
        UUID artifactId,
        UUID attemptId,
        Duration leaseDuration
    ) {
        requireTransaction();
        if (scope == null || artifactId == null || attemptId == null) {
            throw new IllegalArgumentException("Artifact upload claim scope is required.");
        }
        try {
            EvidenceArtifactMetadata artifact = pendingArtifact(scope, artifactId);
            List<EvidenceArtifactUploadClaim> rows = jdbcTemplate.query("""
                SELECT artifact_id, storage_key, expected_content_digest, expected_byte_count,
                       authorization_epoch, lifecycle_version, upload_attempt_id,
                       upload_attempt_count, upload_lease_expires_at, probe_required
                  FROM public.opsmind_claim_evidence_artifact_upload(?, ?, ?, ?, ?, ?, ?, ?)
                """, (resultSet, rowNumber) -> mapClaim(artifact, resultSet),
                scope.organizationId(), scope.projectId(), scope.incidentId(), artifact.runId(), artifactId,
                attemptId, artifact.lifecycleVersion(), milliseconds(leaseDuration));
            if (rows.size() != 1) throw hidden();
            return rows.getFirst();
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw unavailable();
        }
        catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    public EvidenceArtifactUploadSettlement settle(
        AuthorizedIncidentAnalysisScope scope,
        EvidenceArtifactUploadClaim claim,
        EvidenceArtifactUploadOutcome outcome,
        ArtifactObjectStored stored,
        String failureCode
    ) {
        requireTransaction();
        if (scope == null || claim == null || outcome == null || !claim.matches(scope)) throw hidden();
        validateSettlement(outcome, claim, stored, failureCode);
        try {
            List<EvidenceArtifactUploadSettlement> rows = jdbcTemplate.query("""
                SELECT transition_applied, lifecycle_state, lifecycle_version, storage_generation,
                       lifecycle_updated_at
                  FROM public.opsmind_settle_evidence_artifact_upload(
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                  )
                """, (resultSet, rowNumber) -> mapSettlement(resultSet),
                scope.organizationId(), scope.projectId(), scope.incidentId(), claim.artifact().runId(),
                claim.artifact().artifactId(), claim.uploadAttemptId(), claim.artifact().lifecycleVersion(),
                outcome.name(), stored == null ? null : stored.digest().bytes(),
                stored == null ? null : stored.byteCount(),
                stored == null ? null : stored.versionReference(),
                stored == null ? null : stored.encryptionMetadataReference(), failureCode);
            if (rows.size() != 1) throw hidden();
            EvidenceArtifactUploadSettlement settlement = rows.getFirst();
            if (outcome == EvidenceArtifactUploadOutcome.STORED && settlement.transitionApplied()
                && settlement.isStored()) {
                storedLifecycleAppender.append(claim, settlement);
            }
            return settlement;
        }
        catch (PlatformProblemException exception) {
            throw exception;
        }
        catch (DataAccessException exception) {
            throw unavailable();
        }
        catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private EvidenceArtifactMetadata pendingArtifact(AuthorizedIncidentAnalysisScope scope, UUID artifactId) {
        EvidenceArtifactMetadata artifact = metadataReader.findVisible(scope, artifactId).orElseThrow(this::hidden);
        if (artifact.lifecycleState() != EvidenceArtifactLifecycleState.PENDING_UPLOAD
            || artifact.authorizationEpoch() != scope.authorizationEpoch()) {
            throw hidden();
        }
        return artifact;
    }

    private EvidenceArtifactUploadClaim mapClaim(EvidenceArtifactMetadata artifact, ResultSet resultSet)
        throws SQLException {
        UUID artifactId = resultSet.getObject("artifact_id", UUID.class);
        EvidenceArtifactDigest digest = new EvidenceArtifactDigest(
            "sha256:" + HexFormat.of().formatHex(resultSet.getBytes("expected_content_digest"))
        );
        if (!artifact.artifactId().equals(artifactId) || !artifact.expectedDigest().equals(digest)
            || artifact.expectedByteCount() != resultSet.getLong("expected_byte_count")
            || artifact.authorizationEpoch() != resultSet.getLong("authorization_epoch")
            || artifact.lifecycleVersion() != resultSet.getLong("lifecycle_version")) {
            throw unavailable();
        }
        return new EvidenceArtifactUploadClaim(
            artifact, resultSet.getString("storage_key"), resultSet.getObject("upload_attempt_id", UUID.class),
            resultSet.getInt("upload_attempt_count"),
            resultSet.getTimestamp("upload_lease_expires_at").toInstant(), resultSet.getBoolean("probe_required")
        );
    }

    private EvidenceArtifactUploadSettlement mapSettlement(ResultSet resultSet) throws SQLException {
        return new EvidenceArtifactUploadSettlement(
            resultSet.getBoolean("transition_applied"), EvidenceArtifactLifecycleState.valueOf(
                resultSet.getString("lifecycle_state")
            ), resultSet.getLong("lifecycle_version"), resultSet.getLong("storage_generation"),
            resultSet.getTimestamp("lifecycle_updated_at").toInstant()
        );
    }

    private static void validateSettlement(
        EvidenceArtifactUploadOutcome outcome,
        EvidenceArtifactUploadClaim claim,
        ArtifactObjectStored stored,
        String failureCode
    ) {
        if (outcome == EvidenceArtifactUploadOutcome.STORED) {
            if (stored == null || failureCode != null || !stored.digest().equals(claim.artifact().expectedDigest())
                || stored.byteCount() != claim.artifact().expectedByteCount()) {
                throw new IllegalArgumentException("Stored artifact settlement is invalid.");
            }
            return;
        }
        if (stored != null || failureCode == null || !failureCode.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Failed artifact settlement is invalid.");
        }
    }

    private static long milliseconds(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Artifact upload lease duration is invalid.");
        }
        long milliseconds = duration.toMillis();
        if (milliseconds < 1) throw new IllegalArgumentException("Artifact upload lease duration is invalid.");
        return milliseconds;
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Artifact upload metadata requires an authorization transaction.");
        }
    }

    private PlatformProblemException hidden() {
        return new PlatformProblemException(
            HttpStatus.NOT_FOUND, "evidence-artifact.not-found", "Evidence artifact was not found or is not visible."
        );
    }

    private PlatformProblemException unavailable() {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "evidence-artifact.persistence-unavailable",
            "Artifact metadata persistence is temporarily unavailable."
        );
    }
}
