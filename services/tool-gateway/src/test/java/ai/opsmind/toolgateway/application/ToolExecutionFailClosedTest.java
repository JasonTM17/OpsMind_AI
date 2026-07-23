package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.toolgateway.audit.FailClosedToolAuditWriter;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.connectors.observability.FixtureObservabilityConnector;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ToolExecutionFailClosedTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void failsClosedWithoutFabricatingAnAuditIdentity() {
        JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        GatewaySettings settings = new GatewaySettings(
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway",
            "opsmind-platform-api",
            null,
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload",
            "tool.execute",
            null,
            Duration.ofMinutes(5),
            65_536,
            262_144
        );
        ToolExecutionResponse response;
        try (BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
            Clock.fixed(NOW, ZoneOffset.UTC)
        )) {
            ToolExecutionService service = new ToolExecutionService(
                (token, request) -> capability(request),
                new ToolManifestResourceLoader(objectMapper).loadFixtureRegistry(),
                new PolicyEvaluator(objectMapper, settings, Clock.fixed(NOW, ZoneOffset.UTC)),
                new FixtureExecutionReceiptStore(),
                new EvidenceNormalizer(objectMapper, settings),
                new FailClosedToolAuditWriter(),
                new RequestDigester(objectMapper),
                executor,
                List.of(new FixtureObservabilityConnector())
            );
            response = service.execute("verified", request());
        }

        assertThat(response.status()).isEqualTo(ToolOutcome.FAILED);
        assertThat(response.denialCode()).isEqualTo(DenialCode.AUDIT_UNAVAILABLE);
        assertThat(response.auditEventId()).isNull();
    }

    private VerifiedCapability capability(ToolExecutionRequest request) {
        return new VerifiedCapability(
            "capability-test-002", request.actorSubject(), request.tenantId(), request.projectId(),
            request.incidentId(), request.runId(), Set.of("observability:metrics.query:1.0"),
            Set.of(request.resource()), Set.of("operator:read"), 1, 65_536,
            "policy-test", NOW.plusSeconds(300)
        );
    }

    private ToolExecutionRequest request() {
        return new ToolExecutionRequest(
            UUID.randomUUID(),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "operator-001", "observability", "metrics.query", "1.0",
            "prometheus:synthetic/opsmind-api", Map.of("service", "opsmind-api"),
            NOW.plusSeconds(4), new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }

}
