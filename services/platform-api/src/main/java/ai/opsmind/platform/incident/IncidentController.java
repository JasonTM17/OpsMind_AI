package ai.opsmind.platform.incident;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import ai.opsmind.platform.common.api.CorrelationIdFilter;
import ai.opsmind.platform.common.api.IdempotencyKey;
import ai.opsmind.platform.common.api.OptimisticConcurrency;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.JwtPrincipalMapper;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/incidents")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public class IncidentController {

    static final String OPERATION_ID_HEADER = "X-Operation-Id";

    private final JwtPrincipalMapper principalMapper;
    private final IncidentMutationService mutationService;
    private final IncidentQueryService queryService;

    public IncidentController(
        JwtPrincipalMapper principalMapper,
        IncidentMutationService mutationService,
        IncidentQueryService queryService
    ) {
        this.principalMapper = principalMapper;
        this.mutationService = mutationService;
        this.queryService = queryService;
    }

    @PostMapping
    ResponseEntity<byte[]> create(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @RequestHeader(name = "Idempotency-Key", required = false) String rawIdempotencyKey,
        @Valid @RequestBody CreateIncidentRequest request,
        HttpServletRequest servletRequest
    ) {
        IncidentOperationResult result = mutationService.create(
            principal(authentication),
            organizationId,
            projectId,
            IdempotencyKey.parse(rawIdempotencyKey),
            request,
            traceId(servletRequest)
        );
        return mutationResponse(result);
    }

    @GetMapping("/{incidentId}")
    ResponseEntity<IncidentResponse> detail(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId
    ) {
        IncidentDetailResult result = queryService.detail(
            principal(authentication), organizationId, projectId, incidentId
        );
        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, result.etag())
            .body(result.incident());
    }

    @PostMapping("/{incidentId}/transitions")
    ResponseEntity<byte[]> transition(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @RequestHeader(name = "Idempotency-Key", required = false) String rawIdempotencyKey,
        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @Valid @RequestBody TransitionIncidentRequest request,
        HttpServletRequest servletRequest
    ) {
        IncidentOperationResult result = mutationService.transition(
            principal(authentication),
            organizationId,
            projectId,
            incidentId,
            IdempotencyKey.parse(rawIdempotencyKey),
            OptimisticConcurrency.requireIfMatch(ifMatch),
            request,
            traceId(servletRequest)
        );
        return mutationResponse(result);
    }

    @GetMapping("/{incidentId}/timeline")
    IncidentTimelinePage timeline(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 512) String pageToken
    ) {
        return queryService.timeline(
            principal(authentication), organizationId, projectId, incidentId, pageSize, pageToken
        );
    }

    private ResponseEntity<byte[]> mutationResponse(IncidentOperationResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.responseStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.ETAG, result.etag())
            .header(OPERATION_ID_HEADER, result.operationId().toString());
        if (result.location() != null) {
            builder.location(result.location());
        }
        // ByteArrayHttpMessageConverter preserves canonical JSON bytes. A
        // String body with application/json is otherwise serialized as a JSON
        // string by Jackson, breaking the response contract and replay bytes.
        return builder.body(result.responseBody().getBytes(StandardCharsets.UTF_8));
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

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        return value instanceof String traceId ? traceId : null;
    }
}
