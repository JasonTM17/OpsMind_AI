package ai.opsmind.toolgateway.audit;

import java.util.UUID;

import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

public final class FailClosedToolAuditWriter implements ToolAuditWriter {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public UUID recordScoped(
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
    ) {
        return null;
    }

    @Override
    public UUID recordUnverified(
        UUID executionId,
        ToolOutcome outcome,
        String requestDigest,
        DenialCode denialCode
    ) {
        return null;
    }
}
