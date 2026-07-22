package ai.opsmind.toolgateway.audit;

import java.util.UUID;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

@FunctionalInterface
public interface ToolAuditWriter {

    default boolean available() {
        return true;
    }

    UUID record(
        UUID executionId,
        ToolOutcome outcome,
        String requestDigest,
        String capabilityId,
        String manifestVersion,
        String resultDigest,
        String policyVersion,
        DenialCode denialCode
    );
}
