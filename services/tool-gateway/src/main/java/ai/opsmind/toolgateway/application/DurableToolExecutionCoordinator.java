package ai.opsmind.toolgateway.application;

import java.util.UUID;

import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.EvidenceEnvelope;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

/** Owns durable claim, audit, receipt, and lease-finalization failure semantics. */
final class DurableToolExecutionCoordinator {

    private final ExecutionReceiptStore receiptStore;
    private final ToolAuditWriter auditWriter;
    private final ToolExecutionTransactionRunner transactionRunner;

    DurableToolExecutionCoordinator(
        ExecutionReceiptStore receiptStore,
        ToolAuditWriter auditWriter,
        ToolExecutionTransactionRunner transactionRunner
    ) {
        this.receiptStore = receiptStore;
        this.auditWriter = auditWriter;
        this.transactionRunner = transactionRunner;
    }

    boolean auditAvailable() {
        return auditWriter.available();
    }

    ExecutionReceiptStore.Claim claim(
        ToolExecutionRequest request,
        String requestDigest
    ) {
        try {
            return receiptStore.claim(request, requestDigest);
        }
        catch (RuntimeException exception) {
            throw denied(
                DenialCode.EXECUTION_STORE_UNAVAILABLE,
                "Durable execution receipt claim failed.",
                exception
            );
        }
    }

    UUID recordReplay(
        UUID executionId,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        String resultDigest,
        String policyVersion
    ) {
        try {
            return auditWriter.record(
                executionId, ToolOutcome.DUPLICATE, requestDigest, capabilityId,
                manifestVersion, resultDigest, policyVersion, null
            );
        }
        catch (RuntimeException exception) {
            throw denied(
                DenialCode.AUDIT_UNAVAILABLE,
                "Durable replay audit failed.",
                exception
            );
        }
    }

    ToolExecutionResponse finalizeSuccess(
        ExecutionReceiptStore.Lease lease,
        EvidenceEnvelope evidence,
        String capabilityId,
        String manifestVersion,
        String policyVersion
    ) {
        try {
            return transactionRunner.required(() -> finalizeInTransaction(
                lease, evidence, capabilityId, manifestVersion, policyVersion
            ));
        }
        catch (ToolDeniedException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw denied(
                DenialCode.EXECUTION_STORE_UNAVAILABLE,
                "Durable execution transaction failed.",
                exception
            );
        }
    }

    void abandon(ExecutionReceiptStore.Lease lease) {
        if (lease == null) return;
        try {
            receiptStore.abandon(lease);
        }
        catch (RuntimeException ignored) {
            // The bounded lease remains reclaimable after expiry.
        }
    }

    private ToolExecutionResponse finalizeInTransaction(
        ExecutionReceiptStore.Lease lease,
        EvidenceEnvelope evidence,
        String capabilityId,
        String manifestVersion,
        String policyVersion
    ) {
        UUID auditId = recordSuccessAudit(
            lease, evidence, capabilityId, manifestVersion, policyVersion
        );
        ToolExecutionResponse response = new ToolExecutionResponse(
            lease.executionId(), ToolOutcome.SUCCEEDED, java.util.List.of(evidence), null,
            auditId, lease.requestDigest(), manifestVersion,
            evidence.source() + "/" + evidence.connectorVersion(),
            evidence.redactedFields(), evidence.truncated(), false
        );
        try {
            receiptStore.complete(lease, response);
        }
        catch (RuntimeException exception) {
            throw denied(
                DenialCode.EXECUTION_STORE_UNAVAILABLE,
                "Durable execution receipt finalization failed.",
                exception
            );
        }
        return response;
    }

    private UUID recordSuccessAudit(
        ExecutionReceiptStore.Lease lease,
        EvidenceEnvelope evidence,
        String capabilityId,
        String manifestVersion,
        String policyVersion
    ) {
        try {
            return auditWriter.record(
                lease.executionId(), ToolOutcome.SUCCEEDED, lease.requestDigest(), capabilityId,
                manifestVersion, evidence.contentDigest(), policyVersion, null
            );
        }
        catch (RuntimeException exception) {
            throw denied(
                DenialCode.AUDIT_UNAVAILABLE,
                "Durable tool audit finalization failed.",
                exception
            );
        }
    }

    private ToolDeniedException denied(
        DenialCode code,
        String message,
        RuntimeException cause
    ) {
        return new ToolDeniedException(code, message, cause);
    }
}
