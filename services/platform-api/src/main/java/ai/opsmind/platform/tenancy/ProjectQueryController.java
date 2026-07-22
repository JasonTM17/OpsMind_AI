package ai.opsmind.platform.tenancy;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.JwtPrincipalMapper;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public class ProjectQueryController {

    private final JwtPrincipalMapper principalMapper;
    private final TenantProjectQueryService projectQueryService;

    public ProjectQueryController(
        JwtPrincipalMapper principalMapper,
        TenantProjectQueryService projectQueryService
    ) {
        this.principalMapper = principalMapper;
        this.projectQueryService = projectQueryService;
    }

    @GetMapping("/organizations/{organizationId}/projects")
    TenantProjectQueryService.ProjectPage listProjects(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 512) String pageToken
    ) {
        OpsMindPrincipal principal = principal(authentication);
        return projectQueryService.listProjects(principal, organizationId, pageSize, pageToken);
    }

    private OpsMindPrincipal principal(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            try {
                return principalMapper.map(jwtAuthentication.getToken());
            }
            catch (IllegalArgumentException exception) {
                throw new PlatformProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "identity.claims-invalid",
                    "The access token claims are not acceptable."
                );
            }
        }
        throw new PlatformProblemException(
            HttpStatus.UNAUTHORIZED,
            "identity.unsupported-authentication",
            "A verified OIDC access token is required."
        );
    }
}
