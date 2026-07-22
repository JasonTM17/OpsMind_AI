package ai.opsmind.platform.common.api;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;

/** Prevents hidden-resource identifiers from being reflected in not-found responses. */
final class ProblemInstanceUri {

    private static final URI REDACTED_NOT_FOUND = URI.create("urn:opsmind:problem:not-found");

    private ProblemInstanceUri() {
    }

    static URI forRequest(HttpStatus status, HttpServletRequest request) {
        if (status != HttpStatus.NOT_FOUND) {
            return URI.create(request.getRequestURI());
        }
        Object traceId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        if (traceId instanceof String value && CorrelationIdFilter.isSafe(value)) {
            return URI.create("urn:opsmind:problem:" + value);
        }
        return REDACTED_NOT_FOUND;
    }
}
