package ai.opsmind.platform.tenancy;

import java.util.Set;
import java.util.UUID;

public record TenantScope(
    UUID organizationId,
    UUID projectId,
    UUID environmentId,
    Set<String> roles
) {
    public TenantScope {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization scope is required.");
        }
        if (environmentId != null && projectId == null) {
            throw new IllegalArgumentException("Environment scope requires a project scope.");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
