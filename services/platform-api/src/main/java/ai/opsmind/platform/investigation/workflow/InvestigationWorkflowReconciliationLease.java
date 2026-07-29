package ai.opsmind.platform.investigation.workflow;

import java.time.Instant;
import java.util.UUID;

import ai.opsmind.platform.messaging.EventEnvelope;

public record InvestigationWorkflowReconciliationLease(
    EventEnvelope event,
    UUID leaseToken,
    Instant leaseExpiresAt,
    int outboxAttempts,
    String temporalClusterId,
    String temporalNamespace,
    String workflowId,
    String workflowType,
    String taskQueue,
    byte[] startPayloadDigest,
    int reconciliationAttempt,
    Instant reconciliationReceivedAt,
    String reconciliationLastCode,
    Instant reconciliationLastObservedAt
) {
    public InvestigationWorkflowReconciliationLease {
        if (event == null || leaseToken == null || leaseExpiresAt == null
            || outboxAttempts < 1 || reconciliationAttempt < 1
            || reconciliationReceivedAt == null
            || blank(temporalClusterId) || blank(temporalNamespace)
            || blank(workflowId) || blank(workflowType) || blank(taskQueue)
            || startPayloadDigest == null || startPayloadDigest.length != 32) {
            throw new IllegalArgumentException("Workflow reconciliation lease is invalid.");
        }
        startPayloadDigest = startPayloadDigest.clone();
    }

    @Override
    public byte[] startPayloadDigest() {
        return startPayloadDigest.clone();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
