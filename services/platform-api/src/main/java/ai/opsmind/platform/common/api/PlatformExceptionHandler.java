package ai.opsmind.platform.common.api;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public final class PlatformExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformExceptionHandler.class);

    private static final URI PROBLEM_TYPE_BASE = URI.create("https://docs.opsmind.invalid/problems/");

    @ExceptionHandler(PlatformProblemException.class)
    ProblemDetail handlePlatformProblem(PlatformProblemException exception, HttpServletRequest request) {
        if (exception.status().is5xxServerError()) {
            LOGGER.error(
                "Handled platform service failure. traceId={} code={} status={}",
                request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME),
                exception.code(),
                exception.status().value(),
                exception
            );
        }
        return createProblem(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class
    })
    ProblemDetail handleValidation(Exception exception, HttpServletRequest request) {
        ProblemDetail problem = createProblem(
            HttpStatus.BAD_REQUEST,
            "request.validation-failed",
            "The request did not satisfy the API contract.",
            request
        );
        if (exception instanceof MethodArgumentNotValidException validationException) {
            List<FieldViolation> errors = validationException.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), safeMessage(error.getDefaultMessage())))
                .limit(50)
                .toList();
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpServletRequest request) {
        return createProblem(
            HttpStatus.BAD_REQUEST,
            "request.body-invalid",
            "The request body is missing, malformed, or contains an unsupported value.",
            request
        );
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class})
    ProblemDetail handleInvalidRequestParameter(HttpServletRequest request) {
        return createProblem(
            HttpStatus.BAD_REQUEST,
            "request.parameter-invalid",
            "A path, query, or header parameter is invalid.",
            request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail handleUnsupportedMediaType(HttpServletRequest request) {
        return createProblem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "request.media-type-unsupported",
            "The request media type is not supported for this operation.",
            request
        );
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ProblemDetail handleUnacceptableMediaType(HttpServletRequest request) {
        return createProblem(
            HttpStatus.NOT_ACCEPTABLE,
            "request.response-media-type-unacceptable",
            "The requested response media type is not available.",
            request
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail handleUnsupportedMethod(HttpServletRequest request) {
        return createProblem(
            HttpStatus.METHOD_NOT_ALLOWED,
            "request.method-not-allowed",
            "The HTTP method is not supported for this resource.",
            request
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleMissingResource(HttpServletRequest request) {
        return createProblem(
            HttpStatus.NOT_FOUND,
            "resource.not-found",
            "The requested resource does not exist or is not visible.",
            request
        );
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error(
            "Unhandled platform request failure. traceId={} exceptionType={}",
            request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME),
            exception.getClass().getName(),
            exception
        );
        return createProblem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "platform.internal-error",
            "The platform could not complete the request.",
            request
        );
    }

    private ProblemDetail createProblem(
        HttpStatus status,
        String code,
        String safeDetail,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, safeDetail);
        problem.setType(PROBLEM_TYPE_BASE.resolve(code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(ProblemInstanceUri.forRequest(status, request));
        problem.setProperty("code", code);
        Object traceId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        if (traceId instanceof String value) {
            problem.setProperty("traceId", value);
        }
        return problem;
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Invalid value." : message;
    }

    private record FieldViolation(String field, String message) {
    }
}
