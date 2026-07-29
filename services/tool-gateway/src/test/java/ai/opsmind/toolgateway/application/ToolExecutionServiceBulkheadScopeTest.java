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
import java.util.concurrent.atomic.AtomicBoolean;

import ai.opsmind.toolgateway.audit.DeterministicToolAuditWriter;
import ai.opsmind.toolgateway.config.ConnectorBulkheadProperties;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.connectors.ConnectorEvidence;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.connectors.observability.FixtureObservabilityConnector;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ToolExecutionServiceBulkheadScopeTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void rejectsMismatchedVerifiedScopeBeforeConnectorAdmission() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TenantConnectorBulkhead bulkhead = new TenantConnectorBulkhead(
            new ConnectorBulkheadProperties(1, 1)
        );
        AtomicBoolean connectorCalled = new AtomicBoolean();
        try (
            var connectorThreads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
                clock, connectorThreads, bulkhead
            )
        ) {
            ToolExecutionService service = new ToolExecutionService(
                (token, request) -> mismatchedCapability(request),
                new ToolManifestResourceLoader(mapper()).loadFixtureRegistry(),
                new PolicyEvaluator(mapper(), settings(), clock),
                new FixtureExecutionReceiptStore(),
                new EvidenceNormalizer(mapper(), settings()),
                new DeterministicToolAuditWriter(),
                new RequestDigester(mapper()),
                executor,
                new DirectToolExecutionTransactionRunner(),
                List.of(trackingConnector(connectorCalled))
            );

            var response = service.execute("verified", request());

            assertThat(response.denialCode()).isEqualTo(DenialCode.CAPABILITY_SCOPE_MISMATCH);
            assertThat(connectorCalled).isFalse();
            assertThat(bulkhead.trackedTenantCount()).isZero();
        }
    }

    private ToolConnector trackingConnector(AtomicBoolean connectorCalled) {
        return new ToolConnector() {
            @Override
            public String id() {
                return "fixture-observability";
            }

            @Override
            public ConnectorEvidence execute(ToolExecutionRequest request, ToolManifest manifest) {
                connectorCalled.set(true);
                return new FixtureObservabilityConnector().execute(request, manifest);
            }
        };
    }

    private VerifiedCapability mismatchedCapability(ToolExecutionRequest request) {
        return new VerifiedCapability(
            "capability-test-001", request.actorSubject(),
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            request.incidentId(), request.runId(),
            Set.of(request.tool() + ":" + request.action() + ":" + request.schemaVersion()),
            Set.of(request.resource()), Set.of("operator:read"), 1, 65_536,
            "policy-test", NOW.plusSeconds(300)
        );
    }

    private ToolExecutionRequest request() {
        return new ToolExecutionRequest(
            UUID.randomUUID(), TENANT_ID, PROJECT_ID, UUID.randomUUID(), UUID.randomUUID(),
            "operator-001", "observability", "metrics.query", "1.0",
            "prometheus:synthetic/opsmind-api", Map.of("service", "opsmind-api"),
            NOW.plusSeconds(4), new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }

    private JsonMapper mapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    private GatewaySettings settings() {
        return new GatewaySettings(
            URI.create("https://platform.invalid.example"), "opsmind-tool-gateway",
            "opsmind-platform-api", null, URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload", "tool.execute", null,
            Duration.ofMinutes(5), 65_536, 262_144
        );
    }
}
