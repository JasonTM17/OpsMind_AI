package ai.opsmind.platform.delegation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.tenancy.TenantScope;

public record DelegatedCapability(
    URI issuer,
    String subject,
    String audience,
    TenantScope tenant,
    UUID incidentId,
    List<String> resources,
    Set<String> actions,
    CapabilityBudget budget,
    String nonce,
    Instant issuedAt,
    Instant expiresAt,
    String policyVersion
) {
    public DelegatedCapability {
        if (issuer == null || !issuer.isAbsolute()) throw new IllegalArgumentException("Capability issuer is invalid.");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Capability subject is required.");
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("Capability audience is required.");
        if (tenant == null) throw new IllegalArgumentException("Capability tenant scope is required.");
        resources = resources == null ? List.of() : List.copyOf(resources);
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        if (actions.isEmpty()) throw new IllegalArgumentException("Capability must contain an action.");
        if (budget == null) throw new IllegalArgumentException("Capability budget is required.");
        if (nonce == null || nonce.length() < 16) throw new IllegalArgumentException("Capability nonce is invalid.");
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Capability lifetime is invalid.");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("Capability policy version is required.");
        }
    }

    public boolean allowsAction(String requestedAction) {
        return actions.contains(requestedAction);
    }

    public boolean allowsResource(String requestedResource) {
        return resources.contains(requestedResource);
    }

    public record CapabilityBudget(int maxCalls, long maxBytes) {
        public CapabilityBudget {
            if (maxCalls < 1 || maxCalls > 1000 || maxBytes < 1 || maxBytes > 100_000_000L) {
                throw new IllegalArgumentException("Capability budget is outside the bounded contract.");
            }
        }
    }
}
