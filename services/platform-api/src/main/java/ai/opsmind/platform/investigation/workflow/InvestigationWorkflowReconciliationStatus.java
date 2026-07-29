package ai.opsmind.platform.investigation.workflow;

public record InvestigationWorkflowReconciliationStatus(
    long claimReadyCount,
    long pendingCount,
    long blockedCount,
    long exhaustedCount,
    long retentionIneligibleCount,
    double oldestPendingAgeSeconds
) {
    public InvestigationWorkflowReconciliationStatus {
        if (claimReadyCount < 0 || pendingCount < 0 || blockedCount < 0
            || exhaustedCount < 0 || retentionIneligibleCount < 0
            || oldestPendingAgeSeconds < 0 || !Double.isFinite(oldestPendingAgeSeconds)) {
            throw new IllegalArgumentException("Reconciliation aggregate status is invalid.");
        }
    }
}
