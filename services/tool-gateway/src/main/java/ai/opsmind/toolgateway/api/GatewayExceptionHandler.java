package ai.opsmind.toolgateway.api;

import java.util.Map;
import java.util.UUID;

import ai.opsmind.toolgateway.application.RequestDigester;
import ai.opsmind.toolgateway.audit.ToolAuditWriter;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final ToolAuditWriter auditWriter;
    private final GatewaySettings settings;

    public GatewayExceptionHandler(ToolAuditWriter auditWriter, GatewaySettings settings) {
        this.auditWriter = auditWriter;
        this.settings = settings;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformedJson() {
        return auditedProblem(
            HttpStatus.BAD_REQUEST,
            DenialCode.REQUEST_INVALID,
            "The tool request body is invalid."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidRequest() {
        return auditedProblem(
            HttpStatus.BAD_REQUEST,
            DenialCode.REQUEST_INVALID,
            "The tool request body is invalid."
        );
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> missingHeader() {
        return auditedProblem(
            HttpStatus.FORBIDDEN,
            DenialCode.CAPABILITY_INVALID,
            "The delegated capability is required."
        );
    }

    @ExceptionHandler(GatewayCallerDeniedException.class)
    ResponseEntity<Map<String, Object>> callerDenied() {
        return problem(
            HttpStatus.FORBIDDEN,
            "caller.unauthorized",
            "The workload is not authorized to invoke the Tool Gateway."
        );
    }

    private ResponseEntity<Map<String, Object>> problem(HttpStatus status, String code, String title) {
        return ResponseEntity.status(status)
            .header("Content-Type", "application/problem+json")
            .body(Map.of(
                "type", "urn:opsmind:problem:" + code,
                "title", title,
                "status", status.value(),
                "code", code,
                "instance", "urn:opsmind:error:" + UUID.randomUUID()
            ));
    }

    private ResponseEntity<Map<String, Object>> auditedProblem(
        HttpStatus status,
        DenialCode code,
        String title
    ) {
        if (!recordAuthenticatedDeliveryDenial(code)) {
            return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                DenialCode.AUDIT_UNAVAILABLE.value(),
                "Durable tool audit storage is unavailable."
            );
        }
        return problem(status, code.value(), title);
    }

    private boolean recordAuthenticatedDeliveryDenial(DenialCode code) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!PlatformWorkloadAuthorization.matches(authentication, settings)) {
            return true;
        }
        try {
            if (!auditWriter.available()) return false;
            auditWriter.recordUnverified(
                null,
                ToolOutcome.DENIED,
                RequestDigester.fallbackDigest("delivery-rejection:" + code.value()),
                code
            );
            return true;
        }
        catch (RuntimeException exception) {
            LOGGER.debug(
                "Authenticated delivery rejection audit failed. code={} failureType={}",
                code.value(),
                exception.getClass().getSimpleName()
            );
            return false;
        }
    }
}
