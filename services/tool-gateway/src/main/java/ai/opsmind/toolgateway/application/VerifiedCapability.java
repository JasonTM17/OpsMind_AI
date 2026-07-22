package ai.opsmind.toolgateway.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record VerifiedCapability(
    String capabilityId,
    String subject,
    UUID tenantId,
    UUID projectId,
    UUID incidentId,
    UUID runId,
    Set<String> actions,
    Set<String> resources,
    Set<String> roles,
    int maximumCalls,
    long maximumBytes,
    String policyVersion,
    Instant expiresAt
) {
    public VerifiedCapability {
        actions = Set.copyOf(actions);
        resources = Set.copyOf(resources);
        roles = Set.copyOf(roles);
    }
}
