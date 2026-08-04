package ai.opsmind.platform.evidence.artifact.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;
import ai.opsmind.platform.evidence.artifact.access.ArtifactAccessDeniedException;

import org.junit.jupiter.api.Test;

class ArtifactLifecycleServiceTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final EvidenceArtifactDigest DIGEST =
        new EvidenceArtifactDigest("sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    @Test
    void supportsTombstoneRestoreDeletionAndSinglePurgeReceipt() {
        var service = new ArtifactLifecycleService();
        var available = metadata(EvidenceArtifactLifecycleState.AVAILABLE);
        var tombstone = service.transition(available, command(EvidenceArtifactLifecycleState.TOMBSTONED));
        assertEquals(EvidenceArtifactLifecycleState.TOMBSTONED, tombstone.toState());
        var restored = service.transition(metadata(EvidenceArtifactLifecycleState.TOMBSTONED),
            command(EvidenceArtifactLifecycleState.AVAILABLE));
        assertEquals(EvidenceArtifactLifecycleState.AVAILABLE, restored.toState());
        var purged = service.transition(metadata(EvidenceArtifactLifecycleState.TOMBSTONED),
            command(EvidenceArtifactLifecycleState.PURGED));
        assertEquals(3, purged.lifecycleVersion());
        var receipt = service.transition(metadata(EvidenceArtifactLifecycleState.PURGED),
            command(EvidenceArtifactLifecycleState.RECEIPT_RECORDED));
        assertEquals(EvidenceArtifactLifecycleState.RECEIPT_RECORDED, receipt.toState());
        assertThrows(IllegalStateException.class,
            () -> service.transition(metadata(EvidenceArtifactLifecycleState.AVAILABLE),
                command(EvidenceArtifactLifecycleState.PURGED)));
    }

    @Test
    void rejectsReasonsOutsideAuditContract() {
        assertThrows(IllegalArgumentException.class,
            () -> new ArtifactLifecycleCommand(ACTOR, 7, DIGEST,
                EvidenceArtifactLifecycleState.TOMBSTONED, "Operator request", Instant.now()));
        assertThrows(IllegalArgumentException.class,
            () -> new ArtifactLifecycleCommand(ACTOR, 7, DIGEST,
                EvidenceArtifactLifecycleState.TOMBSTONED, "", Instant.now()));
    }

    @Test
    void hidesLifecycleAuthorizationMismatch() {
        var metadata = metadata(EvidenceArtifactLifecycleState.AVAILABLE);
        var wrongActor = new ArtifactLifecycleCommand(UUID.randomUUID(), 7, DIGEST,
            EvidenceArtifactLifecycleState.TOMBSTONED, "operator.request", Instant.now());
        assertThrows(ArtifactAccessDeniedException.class,
            () -> new ArtifactLifecycleService().transition(metadata, wrongActor));
    }

    private static ArtifactLifecycleCommand command(EvidenceArtifactLifecycleState target) {
        return new ArtifactLifecycleCommand(ACTOR, 7, DIGEST, target, "operator.request", Instant.now());
    }

    private static EvidenceArtifactMetadata metadata(EvidenceArtifactLifecycleState state) {
        return new EvidenceArtifactMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), ACTOR, UUID.randomUUID(), "log", "source", "v1", "internal", DIGEST, 3, 7,
            "standard", "sg", "operator", state, 2, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
