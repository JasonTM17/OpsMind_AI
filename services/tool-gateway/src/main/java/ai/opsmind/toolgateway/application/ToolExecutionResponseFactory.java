package ai.opsmind.toolgateway.application;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.audit.ToolExecutionProvenance;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

/** Creates stable decision responses and keeps audit-unavailable handling fail closed. */
public final class ToolExecutionResponseFactory {

    private static final Set<DenialCode> FAILURE_CODES = EnumSet.of(
        DenialCode.CAPABILITY_UNAVAILABLE,
        DenialCode.EXECUTION_STORE_UNAVAILABLE,
        DenialCode.EXECUTION_BACKPRESSURE,
        DenialCode.AUDIT_UNAVAILABLE,
        DenialCode.CONNECTOR_TIMEOUT,
        DenialCode.CONNECTOR_CANCELLED,
        DenialCode.CONNECTOR_FAILED
    );

    private final ToolAuditWriter auditWriter;
    private final ToolExecutionTransactionRunner transactionRunner;

    public ToolExecutionResponseFactory(
        ToolAuditWriter auditWriter,
        ToolExecutionTransactionRunner transactionRunner
    ) {
        this.auditWriter = auditWriter;
        this.transactionRunner = transactionRunner;
    }

    public ToolExecutionResponse scopedDenial(
        TenantProjectScope scope,
        UUID executionId,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        ToolExecutionProvenance provenance,
        String policyVersion,
        DenialCode code
    ) {
        if (scope == null) {
            throw new IllegalArgumentException("Verified denial scope is required.");
        }
        return denial(
            executionId,
            requestDigest,
            manifestVersion,
            code,
            () -> transactionRunner.required(
                scope,
                () -> auditWriter.recordScoped(
                    scope, executionId, outcome(code), requestDigest, capabilityId,
                    manifestVersion, provenance, null, policyVersion, code
                )
            )
        );
    }

    public ToolExecutionResponse unverifiedDenial(
        UUID executionId,
        String requestDigest,
        DenialCode code
    ) {
        return denial(
            executionId,
            requestDigest,
            null,
            code,
            () -> auditWriter.recordUnverified(
                executionId,
                outcome(code),
                requestDigest,
                code
            )
        );
    }

    private ToolExecutionResponse denial(
        UUID executionId,
        String requestDigest,
        String manifestVersion,
        DenialCode code,
        Supplier<UUID> auditAppend
    ) {
        UUID auditId = null;
        DenialCode effectiveCode = code;
        ToolOutcome outcome = outcome(code);
        try {
            if (auditWriter.available()) {
                auditId = auditAppend.get();
            }
            else {
                effectiveCode = DenialCode.AUDIT_UNAVAILABLE;
                outcome = ToolOutcome.FAILED;
            }
        }
        catch (RuntimeException exception) {
            effectiveCode = DenialCode.AUDIT_UNAVAILABLE;
            outcome = ToolOutcome.FAILED;
        }
        return new ToolExecutionResponse(
            executionId, outcome, java.util.List.of(), effectiveCode, auditId,
            requestDigest, manifestVersion, null, 0, false, false
        );
    }

    public String evidenceDigest(ToolExecutionResponse response) {
        return response == null || response.evidence().isEmpty()
            ? null : response.evidence().getFirst().contentDigest();
    }

    private ToolOutcome outcome(DenialCode code) {
        return FAILURE_CODES.contains(code) ? ToolOutcome.FAILED : ToolOutcome.DENIED;
    }
}
