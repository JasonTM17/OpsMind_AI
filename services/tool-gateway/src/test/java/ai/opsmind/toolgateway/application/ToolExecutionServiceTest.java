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

import ai.opsmind.toolgateway.audit.DeterministicToolAuditWriter;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.connectors.observability.FixtureObservabilityConnector;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolOutcome;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ToolExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private JsonMapper objectMapper;
    private BoundedConnectorExecutor connectorExecutor;
    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        GatewaySettings settings = new GatewaySettings(
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway",
            "opsmind-platform-api",
            null,
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload",
            null,
            Duration.ofMinutes(5),
            65_536,
            262_144
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        connectorExecutor = new BoundedConnectorExecutor(clock);
        DelegatedCapabilityVerifier verifier = (token, request) -> new VerifiedCapability(
            "capability-test-001",
            request.actorSubject(),
            request.tenantId(),
            request.projectId(),
            request.incidentId(),
            request.runId(),
            Set.of(request.tool() + ":" + request.action() + ":" + request.schemaVersion()),
            Set.of(request.resource()),
            Set.of("operator:read"),
            1,
            65_536,
            "policy-test",
            NOW.plusSeconds(300)
        );
        service = new ToolExecutionService(
            verifier,
            new ToolManifestResourceLoader(objectMapper).loadFixtureRegistry(),
            new PolicyEvaluator(objectMapper, settings, clock),
            new FixtureExecutionReceiptStore(),
            new EvidenceNormalizer(objectMapper, settings),
            new DeterministicToolAuditWriter(),
            new RequestDigester(objectMapper),
            connectorExecutor,
            List.of(new FixtureObservabilityConnector())
        );
    }

    @AfterEach
    void tearDown() {
        connectorExecutor.close();
    }

    @Test
    void executesReadOnlyFixtureAndRedactsSecretBeforeReturn() throws Exception {
        var response = service.execute("verified-by-test-boundary", validRequest(UUID.randomUUID()));

        assertThat(response.status()).isEqualTo(ToolOutcome.SUCCEEDED);
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.redactionCount()).isEqualTo(2);
        String serialized = objectMapper.writeValueAsString(response);
        assertThat(serialized)
            .doesNotContain("fixture-secret-canary")
            .doesNotContain("fixture-diagnostic-canary")
            .doesNotContain("operator@example.invalid")
            .doesNotContain("Bearer fixture")
            .contains("[REDACTED]", "[REDACTED_EMAIL]");
        assertThat(response.evidence().getFirst().artifactReference()).isNull();
    }

    @Test
    void replaysCompatibleExecutionAndRejectsChangedBody() {
        UUID executionId = UUID.randomUUID();
        var first = service.execute("first", validRequest(executionId));
        var replay = service.execute("fresh-capability", validRequest(executionId));
        ToolExecutionRequest changed = request(
            executionId,
            "observability",
            "metrics.query",
            new ToolExecutionRequest.ResultBudget(4_096, 10),
            Map.of("service", "opsmind-api", "metric", "different_metric")
        );
        var conflict = service.execute("fresh-capability-2", changed);

        assertThat(first.status()).isEqualTo(ToolOutcome.SUCCEEDED);
        assertThat(replay.status()).isEqualTo(ToolOutcome.DUPLICATE);
        assertThat(replay.evidence()).isEqualTo(first.evidence());
        assertThat(conflict.denialCode()).isEqualTo(DenialCode.EXECUTION_CONFLICT);
    }

    @Test
    void deniesUnknownActionAndOversizedBudget() {
        var unknown = service.execute(
            "verified",
            request(
                UUID.randomUUID(),
                "observability",
                "metrics.delete",
                new ToolExecutionRequest.ResultBudget(4_096, 10),
                Map.of("service", "opsmind-api")
            )
        );
        var oversized = service.execute(
            "verified",
            request(
                UUID.randomUUID(),
                "observability",
                "metrics.query",
                new ToolExecutionRequest.ResultBudget(65_537, 10),
                Map.of("service", "opsmind-api")
            )
        );

        assertThat(unknown.denialCode()).isEqualTo(DenialCode.UNKNOWN_ACTION);
        assertThat(oversized.denialCode()).isEqualTo(DenialCode.RESULT_OVERSIZE);
    }

    @Test
    void deniesNestedAndWrongTypeArguments() {
        var nested = service.execute(
            "verified",
            request(
                UUID.randomUUID(),
                "observability",
                "metrics.query",
                new ToolExecutionRequest.ResultBudget(4_096, 10),
                Map.of("service", Map.of("name", "opsmind-api"))
            )
        );
        var decimalLimit = service.execute(
            "verified",
            request(
                UUID.randomUUID(),
                "observability",
                "metrics.query",
                new ToolExecutionRequest.ResultBudget(4_096, 10),
                Map.of("max_points", 1.5)
            )
        );

        assertThat(nested.denialCode()).isEqualTo(DenialCode.ARGUMENTS_INVALID);
        assertThat(decimalLimit.denialCode()).isEqualTo(DenialCode.ARGUMENTS_INVALID);
    }

    private ToolExecutionRequest validRequest(UUID executionId) {
        return request(
            executionId,
            "observability",
            "metrics.query",
            new ToolExecutionRequest.ResultBudget(4_096, 10),
            Map.of("service", "opsmind-api", "metric", "http_errors_total", "max_points", 3)
        );
    }

    private ToolExecutionRequest request(
        UUID executionId,
        String tool,
        String action,
        ToolExecutionRequest.ResultBudget budget,
        Map<String, Object> arguments
    ) {
        return new ToolExecutionRequest(
            executionId,
            TENANT_ID,
            PROJECT_ID,
            INCIDENT_ID,
            RUN_ID,
            "operator-001",
            tool,
            action,
            "1.0",
            "prometheus:synthetic/opsmind-api",
            arguments,
            NOW.plusSeconds(4),
            budget
        );
    }
}
