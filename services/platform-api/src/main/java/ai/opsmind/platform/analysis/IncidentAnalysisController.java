package ai.opsmind.platform.analysis;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import ai.opsmind.platform.common.api.CorrelationIdFilter;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.JwtPrincipalMapper;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/incidents")
@ConditionalOnProperty(
    prefix = "opsmind.ai-runtime.client",
    name = "enabled",
    havingValue = "true"
)
final class IncidentAnalysisController {

    private final JwtPrincipalMapper principalMapper;
    private final IncidentAnalysisService analysisService;

    IncidentAnalysisController(
        JwtPrincipalMapper principalMapper,
        IncidentAnalysisService analysisService
    ) {
        this.principalMapper = principalMapper;
        this.analysisService = analysisService;
    }

    @PostMapping("/{incidentId}/analysis")
    AnalysisRuntimeResponse analyze(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @Valid @RequestBody StartIncidentAnalysisRequest request,
        HttpServletRequest servletRequest
    ) {
        return analysisService.analyze(
            principal(authentication),
            organizationId,
            projectId,
            incidentId,
            request,
            correlationId(servletRequest)
        );
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

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        if (value instanceof String correlationId) {
            return correlationId;
        }
        throw new IllegalStateException("A correlation ID is required for analysis calls.");
    }
}
