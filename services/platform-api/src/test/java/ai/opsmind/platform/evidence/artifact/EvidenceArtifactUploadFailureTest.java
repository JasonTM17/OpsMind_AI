package ai.opsmind.platform.evidence.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.artifact.storage.ArtifactObjectProbe;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactObjectStorage;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageException;
import ai.opsmind.platform.evidence.artifact.storage.EvidenceArtifactStorageProperties;
import ai.opsmind.platform.evidence.artifact.storage.ManagedArtifactSource;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisScope;
import ai.opsmind.platform.incident.IncidentAnalysisAuthorizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceArtifactUploadFailureTest {

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
    private final EvidenceArtifactStorageProperties properties = mock(EvidenceArtifactStorageProperties.class);
    private final AtomicInteger sourceSequence = new AtomicInteger();
    private final EvidenceArtifactUploadService service = new EvidenceArtifactUploadService(
        authorizer, repository, storage, properties
    );
    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void configureAuthorization() {
        when(properties.uploadLeaseDuration()).thenReturn(Duration.ofSeconds(30));
        when(properties.maximumObjectBytes()).thenReturn(10_000L);
        doAnswer(invocation -> work(invocation.getArgument(4))).when(authorizer).withAnalyzeAccess(
            any(), any(), any(), any(), any()
        );
        doAnswer(invocation -> {
            invocation.getArgument(0, ManagedArtifactSource.class).close();
            return null;
        }).when(storage).release(any());
    }

    @Test
    void storageFailureWithoutPossibleResidueSettlesFailedAndPreservesItsSafeCauseChain() {
        EvidenceArtifactUploadClaim claim = claim(false);
        EvidenceArtifactStorageException storageFailure = new EvidenceArtifactStorageException(
            EvidenceArtifactStorageException.FailureKind.UNAVAILABLE, false,
            new IllegalStateException("bucket/key/kms/url/body")
        );
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        doThrow(storageFailure).when(storage).putIfAbsent(any(), any(), any());
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.FAILED), isNull(),
            eq("artifact.storage-failed"))).thenReturn(pendingSettlement());

        assertThatThrownBy(() -> upload()).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.code()).isEqualTo("evidence-artifact.upload-failed");
            assertThat(exception.getMessage()).doesNotContain("bucket", "key", "kms", "url", "body");
            assertThat(exception.getCause()).isSameAs(storageFailure);
        });
    }

    @Test
    void settlementFailureRetainsTheAmbiguousStorageFailureAsSuppressed() {
        EvidenceArtifactUploadClaim claim = claim(false);
        EvidenceArtifactStorageException storageFailure = new EvidenceArtifactStorageException(
            EvidenceArtifactStorageException.FailureKind.OUTCOME_UNCERTAIN, true, null
        );
        PlatformProblemException settlementFailure = new PlatformProblemException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "evidence-artifact.persistence-unavailable",
            "Artifact metadata persistence is temporarily unavailable."
        );
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        doThrow(storageFailure).when(storage).putIfAbsent(any(), any(), any());
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.UNCERTAIN), isNull(),
            eq("artifact.storage-uncertain"))).thenThrow(settlementFailure);

        assertThatThrownBy(this::upload).isSameAs(settlementFailure);
        assertThat(settlementFailure.getSuppressed()).containsExactly(storageFailure);
    }

    @Test
    void unclassifiedSettlementFailureIsWrappedBeforeStorageFailureIsAttached() {
        EvidenceArtifactUploadClaim claim = claim(false);
        EvidenceArtifactStorageException storageFailure = new EvidenceArtifactStorageException(
            EvidenceArtifactStorageException.FailureKind.OUTCOME_UNCERTAIN, true,
            new IllegalStateException("sensitive-storage-provider-detail")
        );
        IllegalStateException settlementFailure =
            new IllegalStateException("sensitive-settlement-detail");
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        doThrow(storageFailure).when(storage).putIfAbsent(any(), any(), any());
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.UNCERTAIN), isNull(),
            eq("artifact.storage-uncertain"))).thenThrow(settlementFailure);

        assertThatThrownBy(this::upload)
            .isInstanceOfSatisfying(PlatformProblemException.class, classifiedFailure -> {
                assertThat(classifiedFailure.code())
                    .isEqualTo("evidence-artifact.settlement-failed");
                assertThat(classifiedFailure.getCause()).isSameAs(settlementFailure);
                assertThat(classifiedFailure.getSuppressed()).containsExactly(storageFailure);
                assertThat(classifiedFailure.getMessage())
                    .doesNotContain("sensitive-storage", "sensitive-settlement");
            });
    }

    @Test
    void unavailableRetryProbeSettlesUncertainAndNeverIssuesAnotherPut() {
        EvidenceArtifactUploadClaim claim = claim(true);
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        doThrow(new EvidenceArtifactStorageException(
            EvidenceArtifactStorageException.FailureKind.OUTCOME_UNCERTAIN, true, null
        )).when(storage).probe(claim.expectation());
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.UNCERTAIN), isNull(),
            eq("artifact.storage-uncertain"))).thenReturn(pendingSettlement());

        assertThatThrownBy(() -> upload()).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.upload-uncertain"));
        verify(storage, never()).putIfAbsent(any(), any(), any());
        verify(storage).release(any());
    }

    @Test
    void mismatchedRetryProbeSettlesOrphanedWithoutOverwritingTheObject() {
        EvidenceArtifactUploadClaim claim = claim(true);
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        when(storage.probe(claim.expectation())).thenReturn(new ArtifactObjectProbe.Mismatch());
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.ORPHANED), isNull(),
            eq("artifact.object-mismatch"))).thenReturn(pendingSettlement());

        assertThatThrownBy(() -> upload()).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.upload-orphaned"));
        verify(storage, never()).putIfAbsent(any(), any(), any());
        verify(storage).release(any());
    }

    @Test
    void postPutSourceContractMismatchSettlesOrphaned() {
        EvidenceArtifactUploadClaim claim = claim(false);
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        doThrow(new EvidenceArtifactStorageException(
            EvidenceArtifactStorageException.FailureKind.SOURCE_CONTRACT_MISMATCH, true, null
        )).when(storage).putIfAbsent(any(), any(), any());
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.ORPHANED), isNull(),
            eq("artifact.object-mismatch"))).thenReturn(pendingSettlement());

        assertThatThrownBy(() -> upload()).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.upload-orphaned"));
        verify(repository).settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.ORPHANED), isNull(),
            eq("artifact.object-mismatch"));
    }

    @Test
    void invalidRemoteSuccessMetadataAlsoSettlesOrphaned() {
        EvidenceArtifactUploadClaim claim = claim(false);
        when(repository.claim(any(), eq(ARTIFACT_ID), any(), any())).thenReturn(claim);
        doThrow(new EvidenceArtifactStorageException(
            EvidenceArtifactStorageException.FailureKind.REMOTE_METADATA_MISMATCH, true, null
        )).when(storage).putIfAbsent(any(), any(), any());
        when(repository.settle(any(), eq(claim), eq(EvidenceArtifactUploadOutcome.ORPHANED), isNull(),
            eq("artifact.object-mismatch"))).thenReturn(pendingSettlement());

        assertThatThrownBy(() -> upload()).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.upload-orphaned"));
    }

    private void upload() {
        service.upload(mock(OpsMindPrincipal.class), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ARTIFACT_ID,
            source());
    }

    @SuppressWarnings("unchecked")
    private Object work(Object candidate) {
        return ((Function<AuthorizedIncidentAnalysisScope, Object>) candidate).apply(scope());
    }

    private EvidenceArtifactUploadClaim claim(boolean probeRequired) {
        return new EvidenceArtifactUploadClaim(metadata(), "artifacts/v1/internal", UUID.randomUUID(), 1,
            NOW.plusSeconds(30), probeRequired, false);
    }

    private EvidenceArtifactMetadata metadata() {
        return new EvidenceArtifactMetadata(ARTIFACT_ID, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, RUN_ID,
            ACTOR_ID, UUID.randomUUID(), "metric", "prometheus:synthetic/opsmind-api", "v1",
            "redacted-metrics", EvidenceArtifactDigest.parse("sha256:" + "a".repeat(64)), 4L, 7L,
            "evidence-90d", "singapore", "delete-within-24h", EvidenceArtifactLifecycleState.PENDING_UPLOAD,
            1L, NOW);
    }

    private EvidenceArtifactUploadSettlement pendingSettlement() {
        return new EvidenceArtifactUploadSettlement(false, EvidenceArtifactLifecycleState.PENDING_UPLOAD,
            1L, 0L, NOW);
    }

    private ManagedArtifactSource source() {
        try {
            Path sourcePath = temporaryDirectory.resolve(
                "artifact-source-" + sourceSequence.incrementAndGet()
            );
            Files.write(sourcePath, new byte[4]);
            return ManagedArtifactSource.open(sourcePath);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private AuthorizedIncidentAnalysisScope scope() {
        return new AuthorizedIncidentAnalysisScope(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, ACTOR_ID, 7L);
    }
}
