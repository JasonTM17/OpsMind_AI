package ai.opsmind.platform.evidence.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectProbe;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectStored;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactObjectStorage;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageProperties;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EvidenceArtifactUploadServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ACTOR_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ARTIFACT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final IncidentAnalysisAuthorizer authorizer = mock(IncidentAnalysisAuthorizer.class);
    private final EvidenceArtifactUploadRepository repository = mock(EvidenceArtifactUploadRepository.class);
    private final EvidenceArtifactObjectStorage storage = mock(EvidenceArtifactObjectStorage.class);
    private final EvidenceArtifactStorageProperties storageProperties = mock(EvidenceArtifactStorageProperties.class);
    private final OpsMindPrincipal principal = mock(OpsMindPrincipal.class);
    private final EvidenceArtifactUploadService service = new EvidenceArtifactUploadService(
        authorizer, repository, storage, storageProperties
    );

    @BeforeEach
    void authorizeSynchronously() {
        when(storageProperties.uploadLeaseDuration()).thenReturn(Duration.ofSeconds(30));
        when(storageProperties.maximumObjectBytes()).thenReturn(10_000L);
        doAnswer(invocation -> work(invocation.getArgument(4))).when(authorizer).withAnalyzeAccess(
            any(), any(), any(), any(), any()
        );
    }

    @Test
    void claimsThenWritesOutsideTheAuthorizationTransactionThenSettles() {
        EvidenceArtifactUploadClaim claim = claim(false);
        ArtifactObjectStored stored = stored();
        EvidenceArtifactUploadSettlement settlement = storedSettlement(true);
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), eq(Duration.ofSeconds(30)))).thenReturn(claim);
        when(storage.putIfAbsent(eq(claim.expectation()), any())).thenReturn(stored);
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.STORED), eq(stored), isNull()))
            .thenReturn(settlement);

        EvidenceArtifactUploadResult result = service.upload(
            principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ARTIFACT_ID, new ByteArrayInputStream(new byte[4])
        );

        assertThat(result.lifecycleState()).isEqualTo(EvidenceArtifactLifecycleState.STORED);
        InOrder order = inOrder(authorizer, repository, storage);
        order.verify(authorizer).withAnalyzeAccess(any(), any(), any(), any(), any());
        order.verify(repository).claim(any(), eq(ARTIFACT_ID), any(), eq(Duration.ofSeconds(30)));
        order.verify(storage).putIfAbsent(eq(claim.expectation()), any());
        order.verify(authorizer).withAnalyzeAccess(any(), any(), any(), any(), any());
        order.verify(repository).settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.STORED), eq(stored), isNull());
    }

    @Test
    void probeMatchAdoptsTheExactObjectWithoutAnotherWrite() {
        EvidenceArtifactUploadClaim claim = claim(true);
        ArtifactObjectStored stored = stored();
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        when(storage.probe(claim.expectation())).thenReturn(new ArtifactObjectProbe.Match(stored));
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.STORED), eq(stored), isNull()))
            .thenReturn(storedSettlement(true));

        service.upload(principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ARTIFACT_ID,
            new ByteArrayInputStream(new byte[4]));

        verify(storage).probe(claim.expectation());
        verify(storage, never()).putIfAbsent(any(), any());
    }

    @Test
    void expiredUnsettledClaimRequiresReconciliationWithoutObjectIoOrSettlement() {
        EvidenceArtifactUploadClaim claim = claim(false, true);
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);

        assertThatThrownBy(() -> service.upload(
            principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ARTIFACT_ID,
            new ByteArrayInputStream(new byte[4])
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.upload-orphaned"));

        verify(storage, never()).probe(any());
        verify(storage, never()).putIfAbsent(any(), any());
        verify(repository, never()).settle(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsObjectIoIfAnAuthorizationTransactionWereStillActive() {
        EvidenceArtifactUploadClaim claim = claim(false);
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        try {
            assertThatThrownBy(() -> service.upload(
                principal, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ARTIFACT_ID,
                new ByteArrayInputStream(new byte[4])
            )).isInstanceOf(IllegalStateException.class);
        }
        finally {
            TransactionSynchronizationManager.clear();
        }
        verify(storage, never()).putIfAbsent(any(), any());
    }

    @SuppressWarnings("unchecked")
    private Object work(Object candidate) {
        return ((Function<AuthorizedIncidentAnalysisScope, Object>) candidate).apply(scope());
    }

    private EvidenceArtifactUploadClaim claim(boolean probeRequired) {
        return claim(probeRequired, false);
    }

    private EvidenceArtifactUploadClaim claim(boolean probeRequired, boolean reconciliationRequired) {
        return new EvidenceArtifactUploadClaim(metadata(), "artifacts/v1/internal", UUID.randomUUID(), 1,
            NOW.plusSeconds(30), probeRequired, reconciliationRequired);
    }

    private EvidenceArtifactMetadata metadata() {
        return new EvidenceArtifactMetadata(ARTIFACT_ID, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID,
            ACTOR_ID, UUID.randomUUID(), "metric", "prometheus:synthetic/opsmind-api", "v1",
            "redacted-metrics", EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64)), 4L, 7L,
            "evidence-90d", "singapore", "delete-within-24h", EvidenceArtifactLifecycleState.PENDING_UPLOAD,
            1L, NOW);
    }

    private ArtifactObjectStored stored() {
        return new ArtifactObjectStored(EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64)), 4L,
            "version-1", "kms-reference-1");
    }

    private EvidenceArtifactUploadSettlement storedSettlement(boolean applied) {
        return new EvidenceArtifactUploadSettlement(applied, EvidenceArtifactLifecycleState.STORED, 2L, 1L, NOW);
    }

    private AuthorizedIncidentAnalysisScope scope() {
        return new AuthorizedIncidentAnalysisScope(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID, 7L);
    }
}
