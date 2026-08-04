package ai.opsmind.platform.evidence.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class EvidenceArtifactDomainTest {

    private static final UUID ORGANIZATION_ID =
        UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RUN_ID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID IDEMPOTENCY_KEY =
        UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void lifecycleReservesPendingUploadForTheInitialControlPlaneSlice() {
        assertThat(EvidenceArtifactLifecycleState.PENDING_UPLOAD.isReadable()).isFalse();
        assertThat(EvidenceArtifactLifecycleState.PENDING_UPLOAD.canTransitionTo(
            EvidenceArtifactLifecycleState.STORED
        )).isTrue();
        assertThat(EvidenceArtifactLifecycleState.PENDING_UPLOAD.canTransitionTo(
            EvidenceArtifactLifecycleState.AVAILABLE
        )).isFalse();
        assertThat(EvidenceArtifactLifecycleState.AVAILABLE.isReadable()).isTrue();
        assertThat(EvidenceArtifactLifecycleState.TOMBSTONED.isReadable()).isFalse();
        assertThat(EvidenceArtifactLifecycleState.AVAILABLE.canTransitionTo(
            EvidenceArtifactLifecycleState.TOMBSTONED
        )).isTrue();
        assertThat(EvidenceArtifactLifecycleState.TOMBSTONED.canTransitionTo(
            EvidenceArtifactLifecycleState.PURGED
        )).isTrue();
        assertThat(EvidenceArtifactLifecycleState.RECEIPT_RECORDED.isTerminal()).isTrue();
    }

    @Test
    void lifecycleRejectsSelfTransitionsAndAnyTransitionAfterItsDurableReceipt() {
        assertThat(EvidenceArtifactLifecycleState.AVAILABLE.canTransitionTo(
            EvidenceArtifactLifecycleState.AVAILABLE
        )).isFalse();
        assertThat(EvidenceArtifactLifecycleState.PURGED.canTransitionTo(
            EvidenceArtifactLifecycleState.RECEIPT_RECORDED
        )).isTrue();
        assertThat(EvidenceArtifactLifecycleState.RECEIPT_RECORDED.canTransitionTo(
            EvidenceArtifactLifecycleState.PENDING_UPLOAD
        )).isFalse();
    }

    @Test
    void digestAcceptsOnlyTheCanonicalLowercaseSha256Form() {
        EvidenceArtifactDigest digest = EvidenceArtifactDigest.parse(DIGEST);

        assertThat(digest.value()).isEqualTo(DIGEST);
        assertThat(digest.bytes()).hasSize(32);
        assertThatThrownBy(() -> EvidenceArtifactDigest.parse("sha256:" + "A".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceArtifactDigest.parse("sha256:abc"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createCommandRejectsUnboundedOrAmbiguousMetadata() {
        assertThat(command().expectedByteCount()).isEqualTo(2_048L);

        assertThatThrownBy(() -> new EvidenceArtifactCreateCommand(
            IDEMPOTENCY_KEY, RUN_ID, "metric", "prometheus:synthetic/opsmind-api",
            "v1", "redacted-metrics", EvidenceArtifactDigest.parse(DIGEST), 0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceArtifactCreateCommand(
            IDEMPOTENCY_KEY, RUN_ID, "metric", "source with a query?token=no",
            "v1", "redacted-metrics", EvidenceArtifactDigest.parse(DIGEST), 2_048
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceArtifactCreateCommand(
            IDEMPOTENCY_KEY, RUN_ID, "metric", "prometheus:synthetic/opsmind-api",
            "v1", "redacted-metrics", EvidenceArtifactDigest.parse(DIGEST), (1L << 40) + 1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identitiesAndStorageKeysAreDeterministicScopedAndOpaque() {
        UUID artifactId = EvidenceArtifactIdentity.artifactId(
            ORGANIZATION_ID, RUN_ID, IDEMPOTENCY_KEY
        );
        UUID eventId = EvidenceArtifactIdentity.initialEventId(ORGANIZATION_ID, artifactId);
        UUID lifecycleEventId = EvidenceArtifactIdentity.lifecycleEventId(
            ORGANIZATION_ID, artifactId, 2L, IDEMPOTENCY_KEY
        );

        assertThat(artifactId).isEqualTo(EvidenceArtifactIdentity.artifactId(
            ORGANIZATION_ID, RUN_ID, IDEMPOTENCY_KEY
        ));
        assertThat(artifactId.version()).isEqualTo(8);
        assertThat(eventId.version()).isEqualTo(8);
        assertThat(lifecycleEventId.version()).isEqualTo(8);
        assertThat(lifecycleEventId).isEqualTo(EvidenceArtifactIdentity.lifecycleEventId(
            ORGANIZATION_ID, artifactId, 2L, IDEMPOTENCY_KEY
        ));
        assertThat(EvidenceArtifactStorageKey.derive(
            ORGANIZATION_ID, artifactId, EvidenceArtifactDigest.parse(DIGEST)
        )).isEqualTo("artifacts/v1/" + ORGANIZATION_ID + "/" + artifactId + "/" + "a".repeat(64));
    }

    private EvidenceArtifactCreateCommand command() {
        return new EvidenceArtifactCreateCommand(
            IDEMPOTENCY_KEY,
            RUN_ID,
            "metric",
            "prometheus:synthetic/opsmind-api",
            "v1",
            "redacted-metrics",
            EvidenceArtifactDigest.parse(DIGEST),
            2_048
        );
    }
}
