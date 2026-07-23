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
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ToolExecutionDurabilityFailureTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void classifiesClaimFailureAsExecutionStoreUnavailable() {
        assertThat(execute(FailurePoint.CLAIM).denialCode())
            .isEqualTo(DenialCode.EXECUTION_STORE_UNAVAILABLE);
    }

    @Test
    void classifiesFinalizationFailureAsExecutionStoreUnavailable() {
        assertThat(execute(FailurePoint.COMPLETE).denialCode())
            .isEqualTo(DenialCode.EXECUTION_STORE_UNAVAILABLE);
    }

    private ToolExecutionResponse execute(FailurePoint failurePoint) {
        JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
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
        ToolExecutionRequest request = request();
        try (var executor = new BoundedConnectorExecutor(clock)) {
            ToolExecutionService service = new ToolExecutionService(
                (token, ignored) -> capability(request),
                new ToolManifestResourceLoader(objectMapper).loadFixtureRegistry(),
                new PolicyEvaluator(objectMapper, settings, clock),
                new ThrowingReceiptStore(failurePoint),
                new EvidenceNormalizer(objectMapper, settings),
                new DeterministicToolAuditWriter(),
                new RequestDigester(objectMapper),
                executor,
                new DirectToolExecutionTransactionRunner(),
                List.of(new FixtureObservabilityConnector())
            );
            return service.execute("verified", request);
        }
    }

    private VerifiedCapability capability(ToolExecutionRequest request) {
        return new VerifiedCapability(
            "capability-durability-test",
            request.actorSubject(),
            request.tenantId(),
            request.projectId(),
            request.incidentId(),
            request.runId(),
            Set.of("observability:metrics.query:1.0"),
            Set.of(request.resource()),
            Set.of("operator:read"),
            1,
            65_536,
            "policy-test",
            NOW.plusSeconds(300)
        );
    }

    private ToolExecutionRequest request() {
        return new ToolExecutionRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "operator-001",
            "observability",
            "metrics.query",
            "1.0",
            "prometheus:synthetic/opsmind-api",
            Map.of(
                "service", "opsmind-api",
                "metric", "http_errors_total",
                "max_points", 3
            ),
            NOW.plusSeconds(4),
            new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }

    private enum FailurePoint {
        CLAIM,
        COMPLETE
    }

    private static final class ThrowingReceiptStore implements ExecutionReceiptStore {

        private final FailurePoint failurePoint;

        private ThrowingReceiptStore(FailurePoint failurePoint) {
            this.failurePoint = failurePoint;
        }

        @Override
        public Claim claim(ToolExecutionRequest request, String requestDigest) {
            if (failurePoint == FailurePoint.CLAIM) {
                throw new IllegalStateException("receipt claim unavailable");
            }
            return Claim.claimed(new Lease(
                request.executionId(),
                requestDigest,
                UUID.randomUUID()
            ));
        }

        @Override
        public void complete(Lease lease, ToolExecutionResponse response) {
            if (failurePoint == FailurePoint.COMPLETE) {
                throw new IllegalStateException("receipt completion unavailable");
            }
        }

        @Override
        public void abandon(Lease lease) {
            // No durable state in this classification test.
        }
    }
}
