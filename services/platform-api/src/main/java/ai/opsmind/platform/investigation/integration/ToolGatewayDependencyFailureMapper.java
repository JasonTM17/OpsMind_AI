package ai.opsmind.platform.investigation.integration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class ToolGatewayDependencyFailureMapper {

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> BAD_REQUEST = Set.of(
        "request.invalid", "arguments.invalid", "deadline.outside-capability"
    );
    private static final Set<String> FORBIDDEN = Set.of(
        "caller.unauthorized", "capability.invalid", "capability.expired",
        "capability.scope-mismatch", "action.unknown", "action.disabled"
    );
    private static final Set<String> CONFLICT = Set.of(
        "execution.conflict", "execution.in-progress", "capability.replayed"
    );
    private static final Set<String> UNAVAILABLE = Set.of(
        "capability.verifier-unavailable", "execution.backpressure",
        "execution.store-unavailable", "audit.unavailable", "connector.cancelled"
    );
    private static final Set<String> FAILURE_CODES = Set.of(
        "capability.verifier-unavailable", "execution.backpressure",
        "execution.store-unavailable", "audit.unavailable", "connector.timeout",
        "connector.cancelled", "connector.failed"
    );

    private final ToolGatewayResponseValidator responseValidator;
    private final ObjectReader problemReader;

    ToolGatewayDependencyFailureMapper(
        ToolGatewayResponseValidator responseValidator,
        ObjectMapper objectMapper
    ) {
        this.responseValidator = responseValidator;
        this.problemReader = objectMapper.readerFor(GatewayProblem.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    PlatformProblemException decisionFailure(
        int httpStatus,
        byte[] body,
        PreparedToolGatewayRequest request
    ) {
        ToolGatewayExecutionResponse response = responseValidator.parseDecision(body);
        boolean expectedOutcome = FAILURE_CODES.contains(response.denialCode())
            ? "FAILED".equals(response.status()) : "DENIED".equals(response.status());
        if (!expectedOutcome
            || response.denialCode() == null || response.evidence() == null
            || !response.evidence().isEmpty() || !request.executionId().equals(response.executionId())
            || !same(request.requestDigest(), response.requestDigest())
            || !Boolean.FALSE.equals(response.truncated())
            || !Boolean.FALSE.equals(response.duplicate()) || response.redactionCount() == null
            || response.redactionCount() != 0) {
            return responseValidator.invalidResponse(null);
        }
        return mapped(httpStatus, response.denialCode());
    }

    PlatformProblemException problemFailure(int httpStatus, byte[] body) {
        try {
            GatewayProblem problem = problemReader.readValue(body);
            if (problem.instance() == null) return responseValidator.invalidResponse(null);
            URI instance = URI.create(problem.instance());
            String instancePrefix = "urn:opsmind:error:";
            UUID.fromString(problem.instance().substring(instancePrefix.length()));
            if (problem.status() == null || problem.status() != httpStatus
                || problem.code() == null || problem.title() == null || problem.title().isBlank()
                || !("urn:opsmind:problem:" + problem.code()).equals(problem.type())
                || !"urn".equalsIgnoreCase(instance.getScheme())
                || !problem.instance().startsWith(instancePrefix)) {
                return responseValidator.invalidResponse(null);
            }
            return mapped(httpStatus, problem.code());
        }
        catch (JacksonException | IllegalArgumentException exception) {
            return responseValidator.invalidResponse(exception);
        }
    }

    private PlatformProblemException mapped(int status, String code) {
        if (status == 401 && "caller.unauthenticated".equals(code)) {
            return problem(
                HttpStatus.BAD_GATEWAY,
                "dependency.tool-gateway-workload-unauthenticated",
                "The Tool Gateway rejected the platform workload identity."
            );
        }
        if (status == 408 && "deadline.expired".equals(code)) {
            return problem(HttpStatus.REQUEST_TIMEOUT, "tool-gateway.deadline-exceeded",
                "The tool execution deadline elapsed.");
        }
        if (status == 409 && CONFLICT.contains(code)) {
            return problem(HttpStatus.CONFLICT, "dependency.tool-gateway-conflict",
                "The Tool Gateway could not establish an unambiguous execution result.");
        }
        if (status == 413 && Set.of("request.oversize", "result.oversize").contains(code)) {
            return rejected();
        }
        if (status == 400 && BAD_REQUEST.contains(code)) return rejected();
        if (status == 403 && FORBIDDEN.contains(code)) return rejected();
        if (status == 502 && "connector.failed".equals(code)) {
            return problem(HttpStatus.BAD_GATEWAY, "dependency.tool-gateway-connector-failed",
                "The Tool Gateway connector failed.");
        }
        if (status == 503 && UNAVAILABLE.contains(code)) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "dependency.tool-gateway-unavailable",
                "The Tool Gateway is temporarily unavailable.");
        }
        if (status == 504 && "connector.timeout".equals(code)) {
            return problem(HttpStatus.GATEWAY_TIMEOUT, "dependency.tool-gateway-timeout",
                "The Tool Gateway connector did not complete before its deadline.");
        }
        return responseValidator.invalidResponse(null);
    }

    private PlatformProblemException rejected() {
        return problem(HttpStatus.BAD_GATEWAY, "dependency.tool-gateway-request-rejected",
            "The Tool Gateway rejected the approved tool request.");
    }

    private PlatformProblemException problem(HttpStatus status, String code, String message) {
        return new PlatformProblemException(status, code, message);
    }

    private boolean same(String expected, String actual) {
        return expected != null && actual != null
            && DIGEST.matcher(expected).matches() && DIGEST.matcher(actual).matches()
            && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private record GatewayProblem(
        String type,
        String title,
        Integer status,
        String code,
        String instance
    ) { }
}
