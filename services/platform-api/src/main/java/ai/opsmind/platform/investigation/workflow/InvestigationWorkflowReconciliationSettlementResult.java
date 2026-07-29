package ai.opsmind.platform.investigation.workflow;

public enum InvestigationWorkflowReconciliationSettlementResult {
    STARTED("workflow.reconciliation-started", "match"),
    ABSENCE_CANDIDATE("workflow.reconciliation-absence-candidate", "absence_candidate"),
    RELEASED_TO_STARTER("workflow.reconciliation-released-to-starter", "released"),
    VERIFIED_ABSENCE("workflow.reconciliation-verified-absence", "verified_absence"),
    CONTRACT_MISMATCH("workflow.reconciliation-contract-mismatch", "mismatch"),
    RETRY_SCHEDULED("workflow.reconciliation-retry-scheduled", "retry"),
    BLOCKED("workflow.reconciliation-blocked", "blocked"),
    LEASE_LOST("workflow.reconciliation-lease-lost", "lease_lost");

    private final String code;
    private final String metricOutcome;

    InvestigationWorkflowReconciliationSettlementResult(
        String code,
        String metricOutcome
    ) {
        this.code = code;
        this.metricOutcome = metricOutcome;
    }

    public String metricOutcome() {
        return metricOutcome;
    }

    public boolean handled() {
        return this != LEASE_LOST;
    }

    public static InvestigationWorkflowReconciliationSettlementResult fromCode(
        String code
    ) {
        for (var result : values()) {
            if (result.code.equals(code)) {
                return result;
            }
        }
        throw new IllegalStateException(
            "Unknown workflow reconciliation settlement result."
        );
    }
}
