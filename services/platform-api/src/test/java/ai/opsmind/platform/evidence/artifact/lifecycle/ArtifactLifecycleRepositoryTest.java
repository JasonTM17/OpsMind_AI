package ai.opsmind.platform.evidence.artifact.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.audit.AuditRepository;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactAuditPayloadCodec;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadataReader;
import ai.opsmind.platform.evidence.artifact.access.ArtifactAccessDeniedException;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ArtifactLifecycleRepositoryTest {
    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID INCIDENT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ARTIFACT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final EvidenceArtifactDigest DIGEST =
        EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64));

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EvidenceArtifactMetadataReader reader = mock(EvidenceArtifactMetadataReader.class);
    private final ArtifactLifecycleService lifecycle = mock(ArtifactLifecycleService.class);
    private final EvidenceArtifactAuditPayloadCodec codec = mock(EvidenceArtifactAuditPayloadCodec.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final ArtifactLifecycleRepository repository =
        new ArtifactLifecycleRepository(jdbc, reader, lifecycle, codec, audit);

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
        assertThat(Modifier.isFinal(ArtifactLifecycleRepository.class.getModifiers())).isFalse();
    }

    @Test
    void persistsMetadataEventAndAuditAsOneAuthorizedTransition() {
        var transition = transition(false);
        var auditEvent = mock(AuditEvent.class);
        when(reader.findVisibleForUpdate(scope(), RUN, ARTIFACT)).thenReturn(Optional.of(metadata()));
        when(jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class))
            .thenReturn(java.sql.Timestamp.from(NOW));
        when(lifecycle.transition(any(), any())).thenReturn(transition);
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
            .thenReturn(Boolean.TRUE);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(codec.lifecycleChanged(any(), any(), any(), any(Long.class), any(), anyString(), any()))
            .thenReturn(auditEvent);

        assertThat(repository.transition(scope(), RUN, ARTIFACT, command())).isEqualTo(transition);

        verify(jdbc).queryForObject(anyString(), any(Class.class), any(Object[].class));
        verify(jdbc).update(anyString(), any(Object[].class));
        verify(audit).append(auditEvent);
    }

    @Test
    void idempotentReceiptDoesNotAppendAnotherEventOrAudit() {
        when(reader.findVisibleForUpdate(scope(), RUN, ARTIFACT)).thenReturn(Optional.of(metadata()));
        when(jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class))
            .thenReturn(java.sql.Timestamp.from(NOW));
        when(lifecycle.transition(any(), any())).thenReturn(transition(true));

        assertThat(repository.transition(scope(), RUN, ARTIFACT, command()).idempotent()).isTrue();

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(audit, never()).append(any());
    }

    @Test
    void hidesMissingArtifactsBeforeMutation() {
        when(reader.findVisibleForUpdate(scope(), RUN, ARTIFACT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repository.transition(scope(), RUN, ARTIFACT, command()))
            .isInstanceOfSatisfying(PlatformProblemException.class, failure ->
                assertThat(failure.code()).isEqualTo("evidence-artifact.not-found"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void hidesAuthorizationAndLifecyclePolicyDenialsBehindTheSameContract() {
        when(reader.findVisibleForUpdate(scope(), RUN, ARTIFACT)).thenReturn(Optional.of(metadata()));
        when(jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class))
            .thenReturn(java.sql.Timestamp.from(NOW));
        when(lifecycle.transition(any(), any()))
            .thenThrow(new ArtifactAccessDeniedException())
            .thenThrow(new IllegalStateException("transition reveals current state"));

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> repository.transition(scope(), RUN, ARTIFACT, command()))
                .isInstanceOfSatisfying(PlatformProblemException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("evidence-artifact.not-found");
                    assertThat(failure.getMessage()).doesNotContain("transition reveals current state");
                });
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void mapsDatabaseFailureToTheSafePersistenceContract() {
        when(reader.findVisibleForUpdate(scope(), RUN, ARTIFACT)).thenReturn(Optional.of(metadata()));
        when(jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class))
            .thenReturn(java.sql.Timestamp.from(NOW));
        when(lifecycle.transition(any(), any())).thenReturn(transition(false));
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("internal-sql-detail"));

        assertThatThrownBy(() -> repository.transition(scope(), RUN, ARTIFACT, command()))
            .isInstanceOfSatisfying(PlatformProblemException.class, failure -> {
                assertThat(failure.code()).isEqualTo("evidence-artifact.persistence-unavailable");
                assertThat(failure.getMessage()).doesNotContain("internal-sql-detail");
            });
        verify(audit, never()).append(any());
    }

    private AuthorizedIncidentAnalysisScope scope() {
        return new AuthorizedIncidentAnalysisScope(ORGANIZATION, PROJECT, INCIDENT, ACTOR, 7L);
    }

    private ArtifactLifecycleCommand command() {
        return new ArtifactLifecycleCommand(
            ACTOR, 7L, DIGEST, EvidenceArtifactLifecycleState.TOMBSTONED, "operator.request", NOW
        );
    }

    private ArtifactLifecycleTransition transition(boolean idempotent) {
        return new ArtifactLifecycleTransition(
            ARTIFACT, EvidenceArtifactLifecycleState.AVAILABLE,
            EvidenceArtifactLifecycleState.TOMBSTONED, 3L, ACTOR, "operator.request", NOW, idempotent
        );
    }

    private EvidenceArtifactMetadata metadata() {
        return new EvidenceArtifactMetadata(
            ARTIFACT, ORGANIZATION, PROJECT, INCIDENT, RUN, ACTOR, UUID.randomUUID(), "log", "source",
            "v1", "internal", DIGEST, 4L, 7L, "standard", "sg", "operator",
            EvidenceArtifactLifecycleState.AVAILABLE, 2L, NOW.minusSeconds(60)
        );
    }
}
