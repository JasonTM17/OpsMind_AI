package ai.opsmind.platform.evidence.artifact.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.evidence.artifact.EvidenceArtifactDigest;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactLifecycleState;
import ai.opsmind.platform.evidence.artifact.EvidenceArtifactMetadata;

import org.junit.jupiter.api.Test;

class ArtifactReconciliationServiceTest {
    private static final UUID ACTOR = UUID.randomUUID();
    private static final EvidenceArtifactDigest DIGEST =
        new EvidenceArtifactDigest("sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");

    @Test
    void defersMatchingUploadSettlementAndMarksMissingStoredObjectOrphaned() {
        var service = new ArtifactReconciliationService();
        var command = new ArtifactLifecycleCommand(ACTOR, 7, DIGEST,
            EvidenceArtifactLifecycleState.STORED, "reconcile", Instant.now());
        var rebound = service.reconcile(metadata(EvidenceArtifactLifecycleState.PENDING_UPLOAD), command,
            ArtifactReconciliationObservation.OBJECT_MATCH);
        assertEquals(ArtifactReconciliationOutcome.UPLOAD_SETTLEMENT_REQUIRED, rebound.outcome());

        var stored = metadata(EvidenceArtifactLifecycleState.STORED);
        var orphanCommand = new ArtifactLifecycleCommand(ACTOR, 7, DIGEST,
            EvidenceArtifactLifecycleState.ORPHANED, "reconcile", Instant.now());
        var orphan = service.reconcile(stored, orphanCommand,
            ArtifactReconciliationObservation.OBJECT_ABSENT);
        assertEquals(ArtifactReconciliationOutcome.MARKED_ORPHANED, orphan.outcome());
    }

    @Test
    void doesNotInferPurgeReceiptFromAbsence() {
        var service = new ArtifactReconciliationService();
        var command = new ArtifactLifecycleCommand(ACTOR, 7, DIGEST,
            EvidenceArtifactLifecycleState.PURGED, "reconcile", Instant.now());
        var result = service.reconcile(metadata(EvidenceArtifactLifecycleState.PURGED), command,
            ArtifactReconciliationObservation.OBJECT_ABSENT);
        assertEquals(ArtifactReconciliationOutcome.NO_CHANGE_UNCERTAIN, result.outcome());
    }

    @Test
    void rejectsCommandTargetThatDoesNotMatchProbeObservation() {
        var command = new ArtifactLifecycleCommand(ACTOR, 7, DIGEST,
            EvidenceArtifactLifecycleState.ORPHANED, "reconcile", Instant.now());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ArtifactReconciliationService().reconcile(
                metadata(EvidenceArtifactLifecycleState.PENDING_UPLOAD), command,
                ArtifactReconciliationObservation.OBJECT_MATCH));
    }

    private static EvidenceArtifactMetadata metadata(EvidenceArtifactLifecycleState state) {
        return new EvidenceArtifactMetadata(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), ACTOR, UUID.randomUUID(), "log", "source", "v1", "internal", DIGEST, 3, 7,
            "standard", "sg", "operator", state, 2, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
