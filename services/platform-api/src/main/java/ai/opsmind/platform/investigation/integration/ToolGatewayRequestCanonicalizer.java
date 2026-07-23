package ai.opsmind.platform.investigation.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.evidence.EvidenceIdentity;

import org.springframework.http.HttpStatus;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class ToolGatewayRequestCanonicalizer {

    private final InvestigationToolIntentCatalog catalog;
    private final ObjectMapper objectMapper;
    private final Duration maximumCapabilityLifetime;
    private final Clock clock;

    ToolGatewayRequestCanonicalizer(
        InvestigationToolIntentCatalog catalog,
        ObjectMapper objectMapper,
        Duration maximumCapabilityLifetime,
        Clock clock
    ) {
        if (catalog == null || objectMapper == null || maximumCapabilityLifetime == null
            || maximumCapabilityLifetime.isNegative() || maximumCapabilityLifetime.isZero()
            || clock == null) {
            throw new IllegalArgumentException("Tool request canonicalizer configuration is invalid.");
        }
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.maximumCapabilityLifetime = maximumCapabilityLifetime;
        this.clock = clock;
    }

    PreparedToolGatewayRequest prepare(
        AnalysisRuntimeResponse.ToolIntent intent,
        InvestigationToolGatewayClient.ToolExecutionContext context
    ) {
        InvestigationToolInvocation invocation = catalog.resolve(intent);
        Instant now = clock.instant();
        Instant deadline = earliest(
            context.deadlineAt(),
            now.plus(maximumCapabilityLifetime),
            now.plus(invocation.maximumDuration())
        );
        if (!deadline.isAfter(now)) throw deadlineExceeded();
        UUID executionId = EvidenceIdentity.executionId(
            context.organizationId(), context.runId(), intent.intentId()
        );
        UUID evidenceId = EvidenceIdentity.evidenceId(
            context.organizationId(), context.runId(), intent.intentId()
        );
        String actorSubject = context.actorId().toString();
        byte[] body = canonicalBody(invocation, context, executionId, actorSubject, deadline);
        String digest = HexFormat.of().formatHex(RequestDigest.sha256(body));
        return new PreparedToolGatewayRequest(
            intent.intentId(), executionId, evidenceId, invocation, actorSubject,
            deadline, body, digest
        );
    }

    private byte[] canonicalBody(
        InvestigationToolInvocation invocation,
        InvestigationToolGatewayClient.ToolExecutionContext context,
        UUID executionId,
        String actorSubject,
        Instant deadline
    ) {
        TreeMap<String, Object> budget = new TreeMap<>();
        budget.put("max_bytes", invocation.maximumBytes());
        budget.put("max_items", invocation.maximumItems());
        TreeMap<String, Object> request = new TreeMap<>();
        request.put("action", invocation.action());
        request.put("actor_subject", actorSubject);
        request.put("arguments", new TreeMap<>(invocation.arguments()));
        request.put("deadline_at", deadline);
        request.put("execution_id", executionId);
        request.put("incident_id", context.incidentId());
        request.put("project_id", context.projectId());
        request.put("resource", invocation.resource());
        request.put("result_budget", budget);
        request.put("run_id", context.runId());
        request.put("schema_version", invocation.schemaVersion());
        request.put("tenant_id", context.organizationId());
        request.put("tool", invocation.tool());
        try {
            return objectMapper.writeValueAsBytes(request);
        }
        catch (JacksonException exception) {
            throw invalidRequest(exception);
        }
    }

    private Instant earliest(Instant first, Instant second, Instant third) {
        Instant value = first.isBefore(second) ? first : second;
        return value.isBefore(third) ? value : third;
    }

    private PlatformProblemException deadlineExceeded() {
        return new PlatformProblemException(
            HttpStatus.REQUEST_TIMEOUT,
            "tool-gateway.deadline-exceeded",
            "The tool execution deadline has elapsed."
        );
    }

    private PlatformProblemException invalidRequest(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.BAD_GATEWAY,
            "dependency.tool-gateway-invalid-request",
            "The approved tool request could not be prepared.",
            cause
        );
    }
}
