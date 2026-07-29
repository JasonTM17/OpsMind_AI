package ai.opsmind.platform.evidence.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.audit.AuditRepository;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

class EvidenceArtifactMetadataRepositoryTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ACTOR_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant OCCURRED_AT = Instant.parse("2030-01-01T00:00:00Z");

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final AuditRepository auditRepository = org.mockito.Mockito.mock(AuditRepository.class);
    private final EvidenceArtifactMetadataReader metadataReader =
        org.mockito.Mockito.mock(EvidenceArtifactMetadataReader.class);
    private final EvidenceArtifactMetadataRepository repository = new EvidenceArtifactMetadataRepository(
        jdbcTemplate,
        auditRepository,
        new EvidenceArtifactAuditPayloadCodec(JsonMapper.builder().findAndAddModules().build()),
        metadataReader
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
    void createsMetadataThenItsSinglePendingEventAndSecretFreeAudit() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        EvidenceArtifactMetadata result = repository.create(scope(), command(), OCCURRED_AT);

        assertThat(result.lifecycleState()).isEqualTo(EvidenceArtifactLifecycleState.PENDING_UPLOAD);
        assertThat(result.authorizationEpoch()).isEqualTo(7L);
        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepository).append(audit.capture());
        assertThat(audit.getValue().schemaVersion())
            .isEqualTo(AuditEvent.EVIDENCE_ARTIFACT_SCHEMA_VERSION);
        assertThat(audit.getValue().payloadJson())
            .contains("PENDING_UPLOAD")
            .doesNotContain("storageKey", "credential", "kms", "objectUrl", "raw");
    }

    @Test
    void exactReplayReturnsExistingMetadataWithoutAnotherAuditEffect() {
        EvidenceArtifactMetadata existing = metadata();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(metadataReader.findIdempotencyBound(scope(), command())).thenReturn(Optional.of(existing));

        assertThat(repository.create(scope(), command(), OCCURRED_AT)).isEqualTo(existing);

        verify(auditRepository, never()).append(any());
    }

    @Test
    void driftBehindTheSameIdempotencyKeyFailsClosed() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(metadataReader.findIdempotencyBound(any(), any())).thenReturn(Optional.of(metadata()));
        EvidenceArtifactCreateCommand drifted = new EvidenceArtifactCreateCommand(
            command().idempotencyKey(), RUN_ID, "metric", "prometheus:synthetic/opsmind-api",
            "v2", "redacted-metrics", command().expectedDigest(), 2_048
        );

        assertThatThrownBy(() -> repository.create(scope(), drifted, OCCURRED_AT))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("evidence-artifact.idempotency-conflict"));
        verify(auditRepository, never()).append(any());
    }

    @Test
    void pendingMetadataCannotBeReadEvenAfterCurrentScopeWasReauthorized() {
        when(metadataReader.findVisible(scope(), metadata().artifactId())).thenReturn(Optional.of(metadata()));

        assertThatThrownBy(() -> repository.requireReadableMetadata(scope(), metadata().artifactId()))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("evidence-artifact.not-found"));
    }

    @Test
    void hidesAnAbsentOrForeignIdempotencyRecordAfterANonInsertingAttempt() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(metadataReader.findIdempotencyBound(scope(), command())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repository.create(scope(), command(), OCCURRED_AT))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("evidence-artifact.not-found"));
        verify(auditRepository, never()).append(any());
    }

    @Test
    void mapsDatabaseUnavailabilityWithoutLeakingThePersistenceFailure() {
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> repository.create(scope(), command(), OCCURRED_AT))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("evidence-artifact.persistence-unavailable");
                assertThat(exception.getMessage()).doesNotContain("database unavailable");
            });
        verify(auditRepository, never()).append(any());
    }

    @Test
    void propagatesAuditAppendFailureSoTheAuthorizationTransactionCanRollBackMetadata() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "audit.persistence-unavailable",
            "The audit event could not be persisted."
        )).when(auditRepository).append(any());

        assertThatThrownBy(() -> repository.create(scope(), command(), OCCURRED_AT))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("audit.persistence-unavailable"));
    }

    private AuthorizedIncidentAnalysisScope scope() {
        return new AuthorizedIncidentAnalysisScope(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID, 7L
        );
    }

    private EvidenceArtifactCreateCommand command() {
        return new EvidenceArtifactCreateCommand(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            RUN_ID,
            "metric",
            "prometheus:synthetic/opsmind-api",
            "v1",
            "redacted-metrics",
            EvidenceArtifactDigest.parse("sha256:" + "b".repeat(64)),
            1_024
        );
    }

    private EvidenceArtifactMetadata metadata() {
        return EvidenceArtifactMetadata.pendingUpload(
            scope(), command(), EvidenceArtifactIdentity.artifactId(
                ORGANIZATION_ID, RUN_ID, command().idempotencyKey()
            ), OCCURRED_AT
        );
    }
}
