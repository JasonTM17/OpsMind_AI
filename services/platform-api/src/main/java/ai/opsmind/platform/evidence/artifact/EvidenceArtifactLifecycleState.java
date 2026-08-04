package ai.opsmind.platform.evidence.artifact;

import java.util.Set;

/** Lifecycle policy for durable artifact metadata; storage I/O is owned by a later slice. */
public enum EvidenceArtifactLifecycleState {
    PENDING_UPLOAD,
    STORED,
    SCANNING,
    AVAILABLE,
    QUARANTINED,
    HELD,
    TOMBSTONED,
    DELETION_REQUESTED,
    EXPIRED,
    PURGED,
    RECEIPT_RECORDED,
    ORPHANED,
    FAILED;

    public boolean canTransitionTo(EvidenceArtifactLifecycleState target) {
        if (target == null || target == this || isTerminal()) return false;
        return switch (this) {
            case PENDING_UPLOAD -> Set.of(STORED, FAILED, ORPHANED).contains(target);
            case STORED -> Set.of(SCANNING, FAILED, ORPHANED).contains(target);
            case SCANNING -> Set.of(AVAILABLE, QUARANTINED, FAILED).contains(target);
            case AVAILABLE -> Set.of(HELD, TOMBSTONED, DELETION_REQUESTED, EXPIRED).contains(target);
            case QUARANTINED -> Set.of(TOMBSTONED, DELETION_REQUESTED, PURGED).contains(target);
            case HELD -> Set.of(AVAILABLE, TOMBSTONED, DELETION_REQUESTED).contains(target);
            case TOMBSTONED -> Set.of(AVAILABLE, HELD, PURGED).contains(target);
            case DELETION_REQUESTED, EXPIRED -> Set.of(TOMBSTONED, PURGED, HELD).contains(target);
            case PURGED -> target == RECEIPT_RECORDED;
            case ORPHANED, FAILED -> Set.of(TOMBSTONED, DELETION_REQUESTED, PURGED).contains(target);
            case RECEIPT_RECORDED -> false;
        };
    }

    public boolean isReadable() {
        return this == AVAILABLE || this == HELD;
    }

    public boolean isTerminal() {
        return this == RECEIPT_RECORDED;
    }
}
