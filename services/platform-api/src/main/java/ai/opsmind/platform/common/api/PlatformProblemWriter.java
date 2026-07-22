package ai.opsmind.platform.common.api;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/** Writes sanitized RFC 9457 responses from filters outside controller advice. */
@Component
public final class PlatformProblemWriter {

    private final ObjectMapper objectMapper;

    public PlatformProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
        HttpServletRequest request,
        HttpServletResponse response,
        HttpStatus status,
        String code,
        String safeDetail
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, safeDetail);
        problem.setType(URI.create("https://docs.opsmind.invalid/problems/" + code));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(ProblemInstanceUri.forRequest(status, request));
        problem.setProperty("code", code);
        Object traceId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        if (traceId instanceof String value) {
            problem.setProperty("traceId", value);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
