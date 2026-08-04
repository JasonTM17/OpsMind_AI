package ai.opsmind.platform.incident;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
import ai.opsmind.platform.common.api.OperatorProjection;
import ai.opsmind.platform.identity.JwtPrincipalMapper;
import ai.opsmind.platform.identity.OpsMindPrincipal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

@Validated
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/projects/{projectId}/incidents")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
public class IncidentController {

    static final String OPERATION_ID_HEADER = "X-Operation-Id";
    static final String ACTIVITY_TIMELINE_MEDIA_TYPE_VALUE =
        "application/vnd.opsmind.incident-activity-timeline.v1+json";
    static final MediaType ACTIVITY_TIMELINE_MEDIA_TYPE =
        MediaType.parseMediaType(ACTIVITY_TIMELINE_MEDIA_TYPE_VALUE);

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

    @GetMapping(
        value = "/{incidentId}",
        produces = {MediaType.APPLICATION_JSON_VALUE, OperatorProjection.MEDIA_TYPE_VALUE}
    )
    ResponseEntity<?> detail(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept
    ) throws HttpMediaTypeNotAcceptableException {
        IncidentDetailResult result = queryService.detail(
            principal(authentication), organizationId, projectId, incidentId
        );
        if (OperatorProjection.requested(accept)) {
            OperatorProjection<OperatorIncidentProjection> projection =
                OperatorIncidentProjection.from(result.incident());
            return projection.responseBuilder().body(projection.body());
        }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .varyBy(HttpHeaders.ACCEPT)
            .header(HttpHeaders.ETAG, result.etag())
            .body(result.incident());
    }

    @PatchMapping(
        value = "/{incidentId}",
        consumes = "application/merge-patch+json"
    )
    ResponseEntity<byte[]> patch(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @RequestHeader(name = "Idempotency-Key", required = false) String rawIdempotencyKey,
        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
        @RequestBody PatchIncidentRequest request,
        HttpServletRequest servletRequest
    ) {
        IncidentOperationResult result = mutationService.patch(
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

    @GetMapping(
        value = "/{incidentId}/timeline",
        produces = {MediaType.APPLICATION_JSON_VALUE, ACTIVITY_TIMELINE_MEDIA_TYPE_VALUE}
    )
    ResponseEntity<?> timeline(
        Authentication authentication,
        @PathVariable UUID organizationId,
        @PathVariable UUID projectId,
        @PathVariable UUID incidentId,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 512) String pageToken,
        @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept
    ) throws HttpMediaTypeNotAcceptableException {
        OpsMindPrincipal verifiedPrincipal = principal(authentication);
        if (activityTimelineRequested(accept)) {
            IncidentActivityTimelinePage page = queryService.activityTimeline(
                verifiedPrincipal, organizationId, projectId, incidentId, pageSize, pageToken
            );
            return ResponseEntity.ok()
                .contentType(ACTIVITY_TIMELINE_MEDIA_TYPE)
                .cacheControl(CacheControl.noStore())
                .varyBy(HttpHeaders.ACCEPT)
                .body(page);
        }
        IncidentTimelinePage page = queryService.timeline(
            verifiedPrincipal, organizationId, projectId, incidentId, pageSize, pageToken
        );
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .varyBy(HttpHeaders.ACCEPT)
            .body(page);
    }

    private boolean activityTimelineRequested(String accept)
        throws HttpMediaTypeNotAcceptableException {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        final List<MediaType> accepted;
        try {
            accepted = MediaType.parseMediaTypes(accept);
        }
        catch (InvalidMediaTypeException exception) {
            throw notAcceptable();
        }

        for (MediaType mediaType : accepted) {
            if (isActivityTimelineType(mediaType) && hasNonQualityParameters(mediaType)) {
                throw notAcceptable();
            }
        }

        double vendorQuality = selectedQuality(accepted, ACTIVITY_TIMELINE_MEDIA_TYPE);
        double jsonQuality = selectedQuality(accepted, MediaType.APPLICATION_JSON);
        if (vendorQuality <= 0 && jsonQuality <= 0) {
            throw notAcceptable();
        }
        return vendorQuality > 0 && vendorQuality > jsonQuality;
    }

    private double selectedQuality(List<MediaType> accepted, MediaType representation) {
        int selectedSpecificity = -1;
        double selectedQuality = -1;
        for (MediaType mediaRange : accepted) {
            if (!matches(mediaRange, representation) || hasNonQualityParameters(mediaRange)) {
                continue;
            }
            int specificity = specificity(mediaRange);
            if (specificity > selectedSpecificity) {
                selectedSpecificity = specificity;
                selectedQuality = mediaRange.getQualityValue();
            }
            else if (specificity == selectedSpecificity) {
                selectedQuality = Math.max(selectedQuality, mediaRange.getQualityValue());
            }
        }
        return selectedQuality;
    }

    private boolean matches(MediaType mediaRange, MediaType representation) {
        if ("*".equals(mediaRange.getType())) {
            return "*".equals(mediaRange.getSubtype());
        }
        if (!representation.getType().equalsIgnoreCase(mediaRange.getType())) {
            return false;
        }
        return "*".equals(mediaRange.getSubtype())
            || representation.getSubtype().equalsIgnoreCase(mediaRange.getSubtype());
    }

    private int specificity(MediaType mediaRange) {
        if ("*".equals(mediaRange.getType())) {
            return 0;
        }
        return "*".equals(mediaRange.getSubtype()) ? 1 : 2;
    }

    private boolean hasNonQualityParameters(MediaType mediaType) {
        return mediaType.getParameters().keySet().stream()
            .anyMatch(parameter -> !"q".equalsIgnoreCase(parameter));
    }

    private boolean isActivityTimelineType(MediaType mediaType) {
        return ACTIVITY_TIMELINE_MEDIA_TYPE.getType().equalsIgnoreCase(mediaType.getType())
            && ACTIVITY_TIMELINE_MEDIA_TYPE.getSubtype().equalsIgnoreCase(mediaType.getSubtype());
    }

    private HttpMediaTypeNotAcceptableException notAcceptable() {
        return new HttpMediaTypeNotAcceptableException(
            List.of(MediaType.APPLICATION_JSON, ACTIVITY_TIMELINE_MEDIA_TYPE)
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
