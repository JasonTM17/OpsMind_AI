package ai.opsmind.platform.analysis;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

import ai.opsmind.platform.common.api.PlatformProblemException;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class HttpAnalysisRuntimeClient implements AnalysisRuntimeClient {

    private static final String CAPABILITY_HEADER = "X-OpsMind-Delegated-Capability";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final Pattern SAFE_CORRELATION = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final AnalysisRuntimeClientProperties properties;
    private final DeadlineBoundedAnalysisHttpExchange exchange;
    private final ObjectReader responseReader;
    private final ObjectReader problemReader;

    HttpAnalysisRuntimeClient(
        AnalysisRuntimeClientProperties properties,
        HttpClient httpClient,
        ObjectMapper objectMapper
    ) {
        properties.validateEnabled();
        this.properties = properties;
        this.exchange = new DeadlineBoundedAnalysisHttpExchange(properties, httpClient);
        this.responseReader = objectMapper.readerFor(AnalysisRuntimeResponse.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.problemReader = objectMapper.readerFor(RuntimeProblem.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @Override
    public AnalysisRuntimeResponse analyze(
        PreparedAnalysisRequest request,
        String capabilityToken,
        String correlationId
    ) {
        validateHeaders(capabilityToken, correlationId);
        Duration timeout = effectiveTimeout(request.deadlineAt());
        HttpRequest outbound = HttpRequest.newBuilder(properties.endpoint())
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, application/problem+json")
            .header(CAPABILITY_HEADER, capabilityToken)
            .header(CORRELATION_HEADER, correlationId)
            .POST(HttpRequest.BodyPublishers.ofByteArray(request.body()))
            .build();
        try {
            HttpResponse<byte[]> response = exchange.send(outbound, timeout);
            byte[] body = response.body();
            if (!response.headers().firstValue(CORRELATION_HEADER)
                .filter(correlationId::equals).isPresent()) {
                throw invalidResponse(null);
            }
            if (response.statusCode() != 200) {
                throw dependencyFailure(response, body, correlationId);
            }
            if (!hasContentType(response, "application/json")) {
                throw invalidResponse(null);
            }
            AnalysisRuntimeResponse result = responseReader.readValue(body);
            if (!request.runId().equals(result.runId())
                || !request.promptVersion().equals(result.promptVersion())) {
                throw invalidResponse(null);
            }
            return result;
        }
        catch (JacksonException exception) {
            throw invalidResponse(exception);
        }
    }

    private Duration effectiveTimeout(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        Duration timeout = remaining.compareTo(properties.requestTimeout()) < 0
            ? remaining
            : properties.requestTimeout();
        if (timeout.isNegative() || timeout.isZero()) {
            throw new PlatformProblemException(
                HttpStatus.REQUEST_TIMEOUT,
                "analysis.deadline-exceeded",
                "The analysis deadline has elapsed."
            );
        }
        return timeout;
    }

    private void validateHeaders(String capabilityToken, String correlationId) {
        if (capabilityToken == null || capabilityToken.isBlank() || capabilityToken.length() > 16_384
            || correlationId == null || !SAFE_CORRELATION.matcher(correlationId).matches()) {
            throw new IllegalArgumentException("Analysis dependency headers are invalid.");
        }
    }

    private boolean hasContentType(HttpResponse<?> response, String expected) {
        return response.headers().firstValue("Content-Type")
            .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
            .filter(expected::equals)
            .isPresent();
    }

    private PlatformProblemException dependencyFailure(
        HttpResponse<?> response,
        byte[] body,
        String correlationId
    ) throws JacksonException {
        if (!hasContentType(response, "application/problem+json")) {
            return invalidResponse(null);
        }
        RuntimeProblem problem = problemReader.readValue(body);
        if (!"about:blank".equals(problem.type()) || problem.status() != response.statusCode()
            || !correlationId.equals(problem.correlationId()) || problem.title() == null
            || problem.title().isBlank()) {
            return invalidResponse(null);
        }
        return switch (response.statusCode()) {
            case 408 -> "request.deadline_exceeded".equals(problem.code())
                ? new PlatformProblemException(
                    HttpStatus.REQUEST_TIMEOUT,
                    "analysis.deadline-exceeded",
                    "The analysis deadline elapsed before completion."
                )
                : invalidResponse(null);
            case 429 -> "budget.exceeded".equals(problem.code())
                ? new PlatformProblemException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "analysis.budget-exceeded",
                    "The analysis run has exhausted its configured budget."
                )
                : invalidResponse(null);
            case 403 -> "egress.denied".equals(problem.code())
                ? new PlatformProblemException(
                    HttpStatus.FORBIDDEN,
                    "analysis.egress-denied",
                    "The resolved incident evidence is not approved for model egress."
                )
                : invalidResponse(null);
            case 502 -> switch (problem.code()) {
                case "provider.invalid_request" -> providerFailure(
                    "dependency.ai-runtime-invalid-request",
                    "The analysis provider rejected the request."
                );
                case "provider.invalid_response" -> providerFailure(
                    "dependency.ai-runtime-invalid-response",
                    "The analysis provider returned an invalid response."
                );
                case "provider.unauthorized" -> providerFailure(
                    "dependency.ai-runtime-unauthorized",
                    "The analysis provider rejected the configured credential."
                );
                case "provider.insufficient_balance" -> providerFailure(
                    "dependency.ai-runtime-insufficient-balance",
                    "The analysis provider account cannot serve the request."
                );
                case "provider.internal" -> providerFailure(
                    "dependency.ai-runtime-provider-failure",
                    "The analysis provider failed the request."
                );
                default -> invalidResponse(null);
            };
            case 503 -> switch (problem.code()) {
                case "delegation.unavailable", "runtime.state_unavailable",
                    "runtime.overloaded",
                    "provider.unavailable", "provider.rate_limited",
                    "provider.unauthorized", "provider.insufficient_balance",
                    "provider.internal" -> unavailable(null);
                default -> invalidResponse(null);
            };
            case 504 -> "provider.deadline_exceeded".equals(problem.code())
                ? new PlatformProblemException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "dependency.ai-runtime-timeout",
                    "The analysis provider did not respond before the deadline."
                )
                : invalidResponse(null);
            default -> invalidResponse(null);
        };
    }

    private PlatformProblemException unavailable(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "dependency.ai-runtime-unavailable",
            "The analysis runtime is temporarily unavailable.",
            cause
        );
    }

    private PlatformProblemException invalidResponse(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.BAD_GATEWAY,
            "dependency.ai-runtime-invalid-response",
            "The analysis runtime returned an invalid response.",
            cause
        );
    }

    private PlatformProblemException providerFailure(String code, String message) {
        return new PlatformProblemException(HttpStatus.BAD_GATEWAY, code, message);
    }

    private record RuntimeProblem(
        String type,
        String title,
        int status,
        String code,
        @JsonProperty("correlation_id") String correlationId
    ) {
    }
}
