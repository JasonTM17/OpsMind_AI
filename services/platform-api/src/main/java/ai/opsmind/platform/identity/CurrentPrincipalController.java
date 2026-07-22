package ai.opsmind.platform.identity;

import java.util.Set;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public final class CurrentPrincipalController {

    private final JwtPrincipalMapper principalMapper;

    public CurrentPrincipalController(JwtPrincipalMapper principalMapper) {
        this.principalMapper = principalMapper;
    }

    @GetMapping("/me")
    CurrentPrincipalResponse currentPrincipal(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new PlatformProblemException(
                HttpStatus.UNAUTHORIZED,
                "identity.unsupported-authentication",
                "A verified OIDC access token is required."
            );
        }
        OpsMindPrincipal principal;
        try {
            principal = principalMapper.map(jwtAuthentication.getToken());
        }
        catch (IllegalArgumentException exception) {
            throw new PlatformProblemException(
                HttpStatus.UNAUTHORIZED,
                "identity.claims-invalid",
                "The access token claims are not acceptable."
            );
        }
        return new CurrentPrincipalResponse(
            principal.subject(),
            principal.issuer().toString(),
            principal.displayName(),
            principal.email(),
            principal.scopes()
        );
    }

    record CurrentPrincipalResponse(
        String subject,
        String issuer,
        String displayName,
        String email,
        Set<String> scopes
    ) {
    }
}
