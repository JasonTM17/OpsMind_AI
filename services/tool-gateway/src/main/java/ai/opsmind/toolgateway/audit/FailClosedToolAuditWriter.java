package ai.opsmind.toolgateway.audit;

import java.util.UUID;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

public final class FailClosedToolAuditWriter implements ToolAuditWriter {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public UUID record(
        UUID executionId,
        ToolOutcome outcome,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        String resultDigest,
        String policyVersion,
        DenialCode denialCode
    ) {
        return null;
    }
}
