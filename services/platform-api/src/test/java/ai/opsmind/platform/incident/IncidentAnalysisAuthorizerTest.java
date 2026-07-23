package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.EvidenceRecordReader;
import ai.opsmind.platform.evidence.ResolvedEvidenceRecord;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class IncidentAnalysisAuthorizerTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTOR_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionStatus transaction = mock(TransactionStatus.class);
    private final IncidentAccessRepository access = mock(IncidentAccessRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final EvidenceRecordReader evidence = mock(EvidenceRecordReader.class);
    private final IncidentAnalysisAuthorizer authorizer = new IncidentAnalysisAuthorizer(
        transactions,
        access,
        incidents,
        evidence
    );

    @BeforeEach
    void configureTransaction() {
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(transaction);
    }

    @Test
    void authorizesExistingIncidentAndReturnsInternalActor() {
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.ANALYZE)
        )).thenReturn(new IncidentActor(ACTOR_ID, "SRE", "SRE"));
        when(incidents.find(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(Optional.of(snapshot()));

        UUID result = authorizer.requireAccess(
            principal(Set.of("incident:analyze")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        );

        assertThat(result).isEqualTo(ACTOR_ID);
        verify(transactions).commit(transaction);
    }

    @Test
    void readAuthorizationUsesReadScopeAndRoleMode() {
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)
        )).thenReturn(new IncidentActor(ACTOR_ID, "VIEWER", "VIEWER"));
        when(incidents.find(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(Optional.of(snapshot()));

        UUID result = authorizer.requireReadAccess(
            principal(Set.of("incident:read")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        );

        assertThat(result).isEqualTo(ACTOR_ID);
        verify(access).requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.READ)
        );
        verify(transactions).commit(transaction);
    }

    @Test
    void resolvesEvidenceInsideTheSameAnalyzeAuthorizationTransaction() {
        UUID runId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        ResolvedEvidenceRecord record = new ResolvedEvidenceRecord(
            evidenceId, runId, "sha256:" + "0".repeat(64), "metric",
            "fixture-prometheus", "prometheus:synthetic/opsmind-api", Instant.now(),
            "synthetic", "{}", false
        );
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.ANALYZE)
        )).thenReturn(new IncidentActor(ACTOR_ID, "SRE", "SRE"));
        when(incidents.find(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(Optional.of(snapshot()));
        when(evidence.resolve(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, runId, List.of(evidenceId)
        )).thenReturn(List.of(record));

        AuthorizedIncidentAnalysisContext result = authorizer.requireEvidenceRecords(
            principal(Set.of("incident:analyze")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID,
            runId, List.of(evidenceId)
        );

        assertThat(result.incident().actorId()).isEqualTo(ACTOR_ID);
        assertThat(result.evidence()).containsExactly(record);
        verify(transactions).commit(transaction);
    }

    @Test
    void rejectsMissingScopeBeforeDatabaseAndViewerAnalyzeRole() {
        assertThatThrownBy(() -> authorizer.requireAccess(
            principal(Set.of("incident:read")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("authorization.scope-required"));
        verify(transactions, never()).getTransaction(any());

        assertThatThrownBy(() -> IncidentRolePolicy.requireAllowed(
            "VIEWER", "VIEWER", IncidentAccessMode.ANALYZE
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("resource.not-found"));
        assertThatCode(() -> IncidentRolePolicy.requireAllowed(
            "SRE", "SRE", IncidentAccessMode.ANALYZE
        )).doesNotThrowAnyException();
    }

    @Test
    void hidesMissingIncidentAfterAuthorizedMembership() {
        when(access.requireAccess(
            any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.ANALYZE)
        )).thenReturn(new IncidentActor(ACTOR_ID, "SRE", "SRE"));
        when(incidents.find(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorizer.requireAccess(
            principal(Set.of("incident:analyze")), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("resource.not-found"));
        verify(transactions).rollback(transaction);
    }

    private OpsMindPrincipal principal(Set<String> scopes) {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "operator-001",
            null,
            null,
            scopes
        );
    }

    private IncidentSnapshot snapshot() {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        return new IncidentSnapshot(
            INCIDENT_ID, ORGANIZATION_ID, PROJECT_ID, "API unavailable", "5xx spike",
            IncidentSeverity.SEV1, IncidentStatus.INVESTIGATING, null, null,
            ACTOR_ID, ACTOR_ID, now, now, 1
        );
    }
}
