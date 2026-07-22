package ai.opsmind.platform.incident;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.http.HttpStatus;

final class IncidentScopePolicy {

    static final String READ_SCOPE = "incident:read";
    static final String ANALYZE_SCOPE = "incident:analyze";
    static final String WRITE_SCOPE = "incident:write";

    private IncidentScopePolicy() {
    }

    static void require(OpsMindPrincipal principal, String scope) {
        if (principal == null) {
            throw new PlatformProblemException(
                HttpStatus.UNAUTHORIZED,
                "identity.unsupported-authentication",
                "A verified OIDC access token is required."
            );
        }
        if (!principal.scopes().contains(scope)) {
            throw new PlatformProblemException(
                HttpStatus.FORBIDDEN,
                "authorization.scope-required",
                "The verified principal does not have the required incident scope."
            );
        }
    }
}
