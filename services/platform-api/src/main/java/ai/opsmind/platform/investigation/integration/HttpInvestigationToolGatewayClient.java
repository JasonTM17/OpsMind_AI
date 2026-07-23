package ai.opsmind.platform.investigation.integration;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.delegation.ToolCapabilityGrant;
import ai.opsmind.platform.delegation.ToolCapabilityTokenIssuer;
import ai.opsmind.platform.delegation.WorkloadTokenProvider;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;

import org.springframework.http.HttpStatus;

import tools.jackson.databind.ObjectMapper;

/** Strict one-shot client for the read-only Tool Gateway execution boundary. */
public final class HttpInvestigationToolGatewayClient implements InvestigationToolGatewayClient {

    private static final String CAPABILITY_HEADER = "X-OpsMind-Delegated-Capability";

    private final ToolGatewayClientProperties properties;
    private final WorkloadTokenProvider workloadTokens;
    private final ToolCapabilityTokenIssuer capabilityTokens;
    private final ToolGatewayRequestCanonicalizer canonicalizer;
    private final ToolGatewayResponseValidator responseValidator;
    private final ToolGatewayDependencyFailureMapper failureMapper;
    private final ToolGatewayHttpExchange exchange;
    private final Clock clock;

    public HttpInvestigationToolGatewayClient(
        ToolGatewayClientProperties properties,
        WorkloadTokenProvider workloadTokens,
        ToolCapabilityTokenIssuer capabilityTokens,
        InvestigationToolIntentCatalog catalog,
        EvidenceContentCanonicalizer evidenceCanonicalizer,
        ObjectMapper objectMapper,
        Duration maximumCapabilityLifetime,
        Clock clock
    ) {
        this(
            properties, workloadTokens, capabilityTokens, catalog, evidenceCanonicalizer,
            objectMapper, maximumCapabilityLifetime, clock,
            new DeadlineBoundedToolGatewayHttpExchange(
                ToolGatewayHttpClientFactory.create(validated(properties)),
                properties.maximumResponseBodyBytes()
            )
        );
    }

    HttpInvestigationToolGatewayClient(
        ToolGatewayClientProperties properties,
        WorkloadTokenProvider workloadTokens,
        ToolCapabilityTokenIssuer capabilityTokens,
        InvestigationToolIntentCatalog catalog,
        EvidenceContentCanonicalizer evidenceCanonicalizer,
        ObjectMapper objectMapper,
        Duration maximumCapabilityLifetime,
        Clock clock,
        ToolGatewayHttpExchange exchange
    ) {
        properties.validateEnabled();
        this.properties = properties;
        this.workloadTokens = java.util.Objects.requireNonNull(workloadTokens, "workloadTokens");
        this.capabilityTokens = java.util.Objects.requireNonNull(capabilityTokens, "capabilityTokens");
        this.canonicalizer = new ToolGatewayRequestCanonicalizer(
            catalog, objectMapper, maximumCapabilityLifetime, clock
        );
        this.responseValidator = new ToolGatewayResponseValidator(evidenceCanonicalizer, objectMapper);
        this.failureMapper = new ToolGatewayDependencyFailureMapper(responseValidator, objectMapper);
        this.exchange = java.util.Objects.requireNonNull(exchange, "exchange");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ToolEvidence execute(
        AnalysisRuntimeResponse.ToolIntent intent,
        ToolExecutionContext context
    ) {
        PreparedToolGatewayRequest prepared = canonicalizer.prepare(intent, context);
        String workloadToken = acquireWorkloadToken();
        String capabilityToken = issueCapability(prepared, context);
        validateHeader(workloadToken, 16_384);
        validateHeader(capabilityToken, 16_384);
        Duration timeout = effectiveTimeout(prepared.deadlineAt());
        HttpRequest outbound = HttpRequest.newBuilder(properties.endpoint())
            .timeout(timeout)
            .header("Authorization", "Bearer " + workloadToken)
            .header(CAPABILITY_HEADER, capabilityToken)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, application/problem+json")
            .header("X-Correlation-ID", prepared.executionId().toString())
            .POST(HttpRequest.BodyPublishers.ofByteArray(prepared.body()))
            .build();
        HttpResponse<byte[]> response = exchange.send(outbound, timeout);
        String contentType = contentType(response);
        if (response.statusCode() == 200 && "application/json".equals(contentType)) {
            return responseValidator.validate(response.body(), prepared);
        }
        if (response.statusCode() != 200 && "application/json".equals(contentType)) {
            throw failureMapper.decisionFailure(response.statusCode(), response.body(), prepared);
        }
        if (response.statusCode() != 200 && "application/problem+json".equals(contentType)) {
            throw failureMapper.problemFailure(response.statusCode(), response.body());
        }
        throw responseValidator.invalidResponse(null);
    }

    private String acquireWorkloadToken() {
        try {
            return workloadTokens.accessToken();
        }
        catch (RuntimeException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "dependency.tool-gateway-workload-auth-unavailable",
                "Tool Gateway workload authentication is unavailable.",
                exception
            );
        }
    }

    private String issueCapability(
        PreparedToolGatewayRequest request,
        ToolExecutionContext context
    ) {
        InvestigationToolInvocation invocation = request.invocation();
        try {
            return capabilityTokens.issue(new ToolCapabilityGrant(
                request.actorSubject(), context.organizationId(), context.projectId(),
                context.incidentId(), context.runId(), invocation.canonicalAction(),
                invocation.resource(), Set.of(invocation.requiredRole()), invocation.maximumBytes(),
                request.requestDigest(), invocation.policyVersion(), request.deadlineAt()
            ));
        }
        catch (RuntimeException exception) {
            throw new PlatformProblemException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "dependency.tool-gateway-capability-unavailable",
                "Tool Gateway delegated capability issuance is unavailable.",
                exception
            );
        }
    }

    private Duration effectiveTimeout(java.time.Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        Duration timeout = remaining.compareTo(properties.requestTimeout()) < 0
            ? remaining : properties.requestTimeout();
        if (timeout.isNegative() || timeout.isZero()) {
            throw new PlatformProblemException(
                HttpStatus.REQUEST_TIMEOUT,
                "tool-gateway.deadline-exceeded",
                "The tool execution deadline has elapsed."
            );
        }
        return timeout;
    }

    private String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
            .map(value -> value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
            .orElse("");
    }

    private void validateHeader(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
            || value.chars().anyMatch(character ->
                Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new IllegalArgumentException("Tool Gateway credential header is invalid.");
        }
    }

    private static ToolGatewayClientProperties validated(ToolGatewayClientProperties properties) {
        properties.validateEnabled();
        return properties;
    }
}
