package ai.opsmind.toolgateway.api;

import ai.opsmind.toolgateway.config.GatewaySettings;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class PlatformWorkloadAuthorization {

    private PlatformWorkloadAuthorization() {
    }

    static boolean matches(Authentication authentication, GatewaySettings settings) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
            || !authentication.isAuthenticated()) {
            return false;
        }
        String clientId = jwtAuthentication.getToken().getClaimAsString("client_id");
        String authorizedParty = jwtAuthentication.getToken().getClaimAsString("azp");
        return settings.platformCallerId().equals(clientId)
            || settings.platformCallerId().equals(authorizedParty);
    }
}
