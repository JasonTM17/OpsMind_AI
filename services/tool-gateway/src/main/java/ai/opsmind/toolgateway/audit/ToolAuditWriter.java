package ai.opsmind.toolgateway.audit;

import java.util.UUID;

import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

public interface ToolAuditWriter {

    default boolean available() {
        return true;
    }

    UUID recordScoped(
        TenantProjectScope scope,
        UUID executionId,
        ToolOutcome outcome,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        ToolExecutionProvenance provenance,
        String resultDigest,
        String policyVersion,
        DenialCode denialCode
    );

    UUID recordUnverified(
        UUID executionId,
        ToolOutcome outcome,
        String requestDigest,
        DenialCode denialCode
    );
}
