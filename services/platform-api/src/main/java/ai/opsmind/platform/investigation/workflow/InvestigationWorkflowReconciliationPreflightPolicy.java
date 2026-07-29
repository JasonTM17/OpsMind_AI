package ai.opsmind.platform.investigation.workflow;

import java.time.Instant;

final class InvestigationWorkflowReconciliationPreflightPolicy {

    private InvestigationWorkflowReconciliationPreflightPolicy() {
    }

    static String safeCode(
        Instant occurredAt,
        Instant reconciliationReceivedAt,
        int reconciliationAttempt,
        InvestigationWorkflowReconcilerProperties properties,
        Instant now
    ) {
        if (!now.isBefore(occurredAt.plus(properties.maximumVerifiableAge()))) {
            return "workflow.reconciliation-retention-unverifiable";
        }
        if (reconciliationReceivedAt.isAfter(
            occurredAt.plus(properties.maximumHandoffAge())
        )) {
            return "workflow.reconciliation-handoff-age-exceeded";
        }
        if (reconciliationAttempt > properties.maximumAttempts()
            || !now.isBefore(
                reconciliationReceivedAt.plus(properties.maximumAge())
            )) {
            return "workflow.reconciliation-exhausted";
        }
        return null;
    }
}
