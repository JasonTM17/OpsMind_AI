package ai.opsmind.toolgateway.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformedJson() {
        return problem(HttpStatus.BAD_REQUEST, "request.invalid", "The tool request body is invalid.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "request.invalid", "The tool request body is invalid.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> missingHeader() {
        return problem(
            HttpStatus.FORBIDDEN,
            "capability.invalid",
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
}
