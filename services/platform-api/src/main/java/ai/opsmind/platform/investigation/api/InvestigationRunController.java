package ai.opsmind.platform.investigation.api;

import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.JwtPrincipalMapper;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.investigation.application.InvestigationRunService;
import ai.opsmind.platform.investigation.projection.InvestigationRunReadModel;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/incidents/{incidentId}/investigations")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "enabled", havingValue = "true")
public final class InvestigationRunController {

    private final JwtPrincipalMapper principalMapper;
    private final InvestigationRunService service;

    public InvestigationRunController(JwtPrincipalMapper principalMapper, InvestigationRunService service) {
        this.principalMapper = principalMapper;
        this.service = service;
    }

    @PostMapping
    InvestigationRunReadModel start(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @RequestBody StartInvestigationRequest request
    ) {
        return service.start(principal(authentication), organizationId, projectId, incidentId, request);
    }

    @GetMapping("/{runId}")
    InvestigationRunReadModel get(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @PathVariable UUID runId
    ) {
        // The incident path is part of the authorization boundary; the store rechecks the tenant/run key.
        return service.get(principal(authentication), organizationId, projectId, incidentId, runId);
    }

    private OpsMindPrincipal principal(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            try {
                return principalMapper.map(jwt.getToken());
            }
            catch (IllegalArgumentException exception) {
                throw new PlatformProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "identity.claims-invalid",
                    "The access token claims are not acceptable.",
                    exception
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
