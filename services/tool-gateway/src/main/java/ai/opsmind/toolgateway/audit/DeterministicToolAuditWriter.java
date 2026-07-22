package ai.opsmind.toolgateway.audit;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

/** Secret-free deterministic audit identity; durable append is a separate adapter boundary. */
public final class DeterministicToolAuditWriter implements ToolAuditWriter {

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
        String material = executionId + "|" + outcome + "|" + requestDigest + "|"
            + String.valueOf(capabilityId) + "|" + String.valueOf(manifestVersion) + "|"
            + String.valueOf(resultDigest) + "|" + String.valueOf(policyVersion) + "|"
            + String.valueOf(denialCode);
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
