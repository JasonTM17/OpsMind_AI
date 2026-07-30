package ai.opsmind.platform.evidence.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectStored;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EvidenceArtifactUploadRepositoryTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ACTOR_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ARTIFACT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EvidenceArtifactMetadataReader metadataReader = mock(EvidenceArtifactMetadataReader.class);
    private final EvidenceArtifactStoredLifecycleAppender appender = mock(EvidenceArtifactStoredLifecycleAppender.class);
    private final EvidenceArtifactUploadRepository repository = new EvidenceArtifactUploadRepository(
        jdbcTemplate, metadataReader, appender
    );

    @BeforeEach
    void beginAuthorizationTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void endAuthorizationTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void repositoryRemainsSubclassProxyableForSpringExceptionTranslation() {
        assertThat(Modifier.isFinal(EvidenceArtifactUploadRepository.class.getModifiers())).isFalse();
    }

    @Test
    void missingOrForeignArtifactIsDeniedBeforeTheClaimCapabilityRuns() {
        when(metadataReader.findVisible(scope(), ARTIFACT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repository.claim(scope(), ARTIFACT_ID, UUID.randomUUID(), Duration.ofSeconds(30)))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("evidence-artifact.not-found"));
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void staleClaimRejectionIsAlsoReturnedAsTheSafeNotFoundContract() {
        when(metadataReader.findVisible(scope(), ARTIFACT_ID)).thenReturn(Optional.of(metadata()));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> repository.claim(scope(), ARTIFACT_ID, UUID.randomUUID(), Duration.ofSeconds(30)))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("evidence-artifact.not-found"));
    }

    @Test
    void mapsExpiredUnsettledAttemptAsReconciliationOnly() throws Exception {
        when(metadataReader.findVisible(scope(), ARTIFACT_ID)).thenReturn(Optional.of(metadata()));
        ResultSet row = claimRow(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });

        EvidenceArtifactUploadClaim claim = repository.claim(
            scope(), ARTIFACT_ID, UUID.randomUUID(), Duration.ofSeconds(30)
        );

        assertThat(claim.reconciliationRequired()).isTrue();
        assertThat(claim.probeRequired()).isFalse();
    }

    @Test
    void persistenceFailurePreservesItsCauseBehindTheSafeContract() {
        when(metadataReader.findVisible(scope(), ARTIFACT_ID)).thenReturn(Optional.of(metadata()));
        DataAccessResourceFailureException cause = new DataAccessResourceFailureException("internal-sql-detail");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenThrow(cause);

        assertThatThrownBy(() ->
            repository.claim(scope(), ARTIFACT_ID, UUID.randomUUID(), Duration.ofSeconds(30))
        ).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.code()).isEqualTo("evidence-artifact.persistence-unavailable");
            assertThat(exception.getMessage()).doesNotContain("internal-sql-detail");
            assertThat(exception.getCause()).isSameAs(cause);
        });
    }

    @Test
    void exactStoredSettlementAppendsOnceButRepeatedFinalizationDoesNotDuplicateIt() throws Exception {
        when(metadataReader.findVisible(scope(), ARTIFACT_ID)).thenReturn(Optional.of(metadata()));
        ResultSet claimRow = claimRow();
        ResultSet settlementRow = settlementRow(true, false);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet row = invocation.<String>getArgument(0).contains("opsmind_claim") ? claimRow : settlementRow;
            return List.of(mapper.mapRow(row, 0));
        });

        EvidenceArtifactUploadClaim claim = repository.claim(
            scope(), ARTIFACT_ID, UUID.fromString("77777777-7777-4777-8777-777777777777"), Duration.ofSeconds(30)
        );
        ArtifactObjectStored stored = new ArtifactObjectStored(
            metadata().expectedDigest(), metadata().expectedByteCount(), "version-1", "kms-reference-1"
        );
        EvidenceArtifactUploadSettlement first = repository.settle(
            scope(), claim, EvidenceArtifactUploadOutcome.STORED, stored, null
        );
        EvidenceArtifactUploadSettlement replay = repository.settle(
            scope(), claim, EvidenceArtifactUploadOutcome.STORED, stored, null
        );

        assertThat(first.transitionApplied()).isTrue();
        assertThat(replay.transitionApplied()).isFalse();
        verify(appender).append(claim, first);
        verify(appender, never()).append(claim, replay);
    }

    @Test
    void settlementRefusesAChangedAuthorizationScopeWithoutCallingTheFunction() {
        EvidenceArtifactUploadClaim claim = new EvidenceArtifactUploadClaim(
            metadata(), "artifacts/v1/internal", UUID.randomUUID(), 1, NOW.plusSeconds(30), false, false
        );
        AuthorizedIncidentAnalysisScope foreignScope = new AuthorizedIncidentAnalysisScope(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, UUID.randomUUID(), 7L
        );

        assertThatThrownBy(() -> repository.settle(
            foreignScope, claim, EvidenceArtifactUploadOutcome.FAILED, null, "artifact.storage-failed"
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.not-found"));
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ResultSet claimRow() throws Exception {
        return claimRow(false);
    }

    private ResultSet claimRow(boolean reconciliationRequired) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("artifact_id", UUID.class)).thenReturn(ARTIFACT_ID);
        when(row.getString("storage_key")).thenReturn("artifacts/v1/internal");
        when(row.getBytes("expected_content_digest")).thenReturn(metadata().expectedDigest().bytes());
        when(row.getLong("expected_byte_count")).thenReturn(4L);
        when(row.getLong("authorization_epoch")).thenReturn(7L);
        when(row.getLong("lifecycle_version")).thenReturn(1L);
        when(row.getObject("upload_attempt_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getInt("upload_attempt_count")).thenReturn(1);
        when(row.getTimestamp("upload_lease_expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(30)));
        when(row.getBoolean("probe_required")).thenReturn(false);
        when(row.getBoolean("reconciliation_required")).thenReturn(reconciliationRequired);
        return row;
    }

    private ResultSet settlementRow(boolean... applied) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getBoolean("transition_applied")).thenReturn(applied[0], applied[1]);
        when(row.getString("lifecycle_state")).thenReturn("STORED");
        when(row.getLong("lifecycle_version")).thenReturn(2L);
        when(row.getLong("storage_generation")).thenReturn(1L);
        when(row.getTimestamp("lifecycle_updated_at")).thenReturn(Timestamp.from(NOW));
        return row;
    }

    private EvidenceArtifactMetadata metadata() {
        return new EvidenceArtifactMetadata(ARTIFACT_ID, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID,
            ACTOR_ID, UUID.randomUUID(), "metric", "prometheus:synthetic/opsmind-api", "v1",
            "redacted-metrics", EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64)), 4L, 7L,
            "evidence-90d", "singapore", "delete-within-24h", EvidenceArtifactLifecycleState.PENDING_UPLOAD,
            1L, NOW);
    }

    private AuthorizedIncidentAnalysisScope scope() {
        return new AuthorizedIncidentAnalysisScope(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID, 7L);
    }
}
