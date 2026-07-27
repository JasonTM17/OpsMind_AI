package ai.opsmind.toolgateway.application;

import java.util.UUID;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

/**
 * Tenant authority derived only from a successfully verified delegated
 * capability. Request scope is compared for defense in depth, never trusted.
 */
public record TenantProjectScope(UUID tenantId, UUID projectId) {

    public TenantProjectScope {
        if (tenantId == null || projectId == null) {
            throw new IllegalArgumentException("Tenant and project scope are required.");
        }
    }

    public static TenantProjectScope fromVerifiedCapability(
        VerifiedCapability capability,
        ToolExecutionRequest request
    ) {
        if (capability == null || request == null) {
            throw new ToolDeniedException(
                DenialCode.CAPABILITY_SCOPE_MISMATCH,
                "Verified capability scope is unavailable."
            );
        }
        TenantProjectScope scope = new TenantProjectScope(
            capability.tenantId(),
            capability.projectId()
        );
        if (!scope.matches(request)) {
            throw new ToolDeniedException(
                DenialCode.CAPABILITY_SCOPE_MISMATCH,
                "Verified capability scope does not match request binding."
            );
        }
        return scope;
    }

    public boolean matches(ToolExecutionRequest request) {
        return request != null
            && tenantId.equals(request.tenantId())
            && projectId.equals(request.projectId());
    }
}
