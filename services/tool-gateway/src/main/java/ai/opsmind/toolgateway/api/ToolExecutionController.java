package ai.opsmind.toolgateway.api;

import ai.opsmind.toolgateway.application.ToolExecutionService;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/internal/v1/tools")
public class ToolExecutionController {

    public static final String CAPABILITY_HEADER = "X-OpsMind-Delegated-Capability";

    private final ToolExecutionService executionService;
    private final GatewaySettings settings;

    public ToolExecutionController(ToolExecutionService executionService, GatewaySettings settings) {
        this.executionService = executionService;
        this.settings = settings;
    }

    @PostMapping("/execute")
    public ResponseEntity<ToolExecutionResponse> execute(
        Authentication authentication,
        @RequestHeader(CAPABILITY_HEADER) String capabilityToken,
        @Valid @RequestBody ToolExecutionRequest request
    ) {
        if (!isPlatformWorkload(authentication)) throw new GatewayCallerDeniedException();
        ToolExecutionResponse response = executionService.execute(capabilityToken, request);
        return ResponseEntity.status(httpStatus(response)).body(response);
    }

    private boolean isPlatformWorkload(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
            || !authentication.isAuthenticated()) {
            return false;
        }
        String clientId = jwtAuthentication.getToken().getClaimAsString("client_id");
        String authorizedParty = jwtAuthentication.getToken().getClaimAsString("azp");
        return settings.platformCallerId().equals(clientId)
            || settings.platformCallerId().equals(authorizedParty);
    }

    private HttpStatus httpStatus(ToolExecutionResponse response) {
        if (response.status() == ToolOutcome.SUCCEEDED || response.status() == ToolOutcome.DUPLICATE) {
            return HttpStatus.OK;
        }
        DenialCode code = response.denialCode();
        if (code == DenialCode.REQUEST_OVERSIZE || code == DenialCode.RESULT_OVERSIZE) {
            return HttpStatus.PAYLOAD_TOO_LARGE;
        }
        if (code == DenialCode.EXECUTION_CONFLICT || code == DenialCode.EXECUTION_IN_PROGRESS
            || code == DenialCode.CAPABILITY_REPLAYED) {
            return HttpStatus.CONFLICT;
        }
        if (code == DenialCode.CAPABILITY_UNAVAILABLE
            || code == DenialCode.EXECUTION_STORE_UNAVAILABLE
            || code == DenialCode.EXECUTION_BACKPRESSURE
            || code == DenialCode.AUDIT_UNAVAILABLE
            || code == DenialCode.CONNECTOR_CANCELLED) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (code == DenialCode.CONNECTOR_TIMEOUT) return HttpStatus.GATEWAY_TIMEOUT;
        if (code == DenialCode.CONNECTOR_FAILED) return HttpStatus.BAD_GATEWAY;
        if (code == DenialCode.DEADLINE_EXPIRED) return HttpStatus.REQUEST_TIMEOUT;
        if (code == DenialCode.DEADLINE_OUTSIDE_CAPABILITY) return HttpStatus.BAD_REQUEST;
        if (code == DenialCode.REQUEST_INVALID || code == DenialCode.ARGUMENTS_INVALID) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.FORBIDDEN;
    }
}
