package ai.opsmind.toolgateway.application;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.toolgateway.audit.ToolAuditWriter;
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

    public ToolExecutionResponseFactory(ToolAuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    public ToolExecutionResponse denial(
        UUID executionId,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        String policyVersion,
        DenialCode code
    ) {
        UUID auditId = null;
        DenialCode effectiveCode = code;
        ToolOutcome outcome = outcome(code);
        try {
            if (auditWriter.available()) {
                auditId = auditWriter.record(
                    executionId, outcome, requestDigest, capabilityId, manifestVersion,
                    null, policyVersion, code
                );
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
