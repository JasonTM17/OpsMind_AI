package ai.opsmind.platform.incident;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.JwtPrincipalMapper;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/incidents")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public class IncidentListController {

    private final JwtPrincipalMapper principalMapper;
    private final IncidentListQueryService queryService;

    public IncidentListController(
        JwtPrincipalMapper principalMapper,
        IncidentListQueryService queryService
    ) {
        this.principalMapper = principalMapper;
        this.queryService = queryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IncidentListPage> list(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @RequestParam(required = false) IncidentStatus status,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 512) String pageToken,
        HttpServletRequest request
    ) {
        String rawPageToken = pageToken == null
            ? request.getParameter("pageToken")
            : pageToken;
        IncidentListPage page = queryService.list(
            principal(authentication),
            organizationId,
            projectId,
            status,
            pageSize,
            rawPageToken
        );
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.noStore())
            .body(page);
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
