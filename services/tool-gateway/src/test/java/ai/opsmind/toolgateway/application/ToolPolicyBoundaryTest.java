package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ToolPolicyBoundaryTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private PolicyEvaluator policy;
    private ToolManifest manifest;

    @BeforeEach
    void setUp() {
        GatewaySettings settings = new GatewaySettings(
            URI.create("https://platform.invalid.example"), "opsmind-tool-gateway",
            "opsmind-platform-api", null, URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload", null, Duration.ofMinutes(5), 65_536, 262_144
        );
        JsonMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
        policy = new PolicyEvaluator(
            objectMapper,
            settings,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        ToolExecutionRequest request = request(
            "opsmind-api",
            "prometheus:synthetic/opsmind-api",
            NOW.plusSeconds(4)
        );
        manifest = new ToolManifestResourceLoader(objectMapper).loadFixtureRegistry().require(request);
    }

    @Test
    void rejectsSelectorThatTargetsAResourceDifferentFromCapability() {
        ToolExecutionRequest request = request(
            "other-service",
            "prometheus:synthetic/opsmind-api",
            NOW.plusSeconds(4)
        );

        assertDenied(request, NOW.plusSeconds(30), DenialCode.CAPABILITY_SCOPE_MISMATCH);
    }

    @Test
    void rejectsDeadlineBeyondManifestOrCapability() {
        ToolExecutionRequest request = request(
            "opsmind-api",
            "prometheus:synthetic/opsmind-api",
            NOW.plusSeconds(10)
        );

        assertDenied(request, NOW.plusSeconds(30), DenialCode.DEADLINE_OUTSIDE_CAPABILITY);
    }

    private void assertDenied(
        ToolExecutionRequest request,
        Instant capabilityExpiry,
        DenialCode expectedCode
    ) {
        assertThatThrownBy(() -> policy.evaluate(request, manifest, capability(request, capabilityExpiry)))
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private VerifiedCapability capability(ToolExecutionRequest request, Instant expiresAt) {
        return new VerifiedCapability(
            "capability-test", request.actorSubject(), request.tenantId(), request.projectId(),
            request.incidentId(), request.runId(), Set.of("observability:metrics.query:1.0"),
            Set.of(request.resource()), Set.of("operator:read"), 1, 65_536,
            "policy-test", expiresAt
        );
    }

    private ToolExecutionRequest request(String service, String resource, Instant deadline) {
        return new ToolExecutionRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "operator-001", "observability", "metrics.query", "1.0",
            resource, Map.of("service", service), deadline,
            new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }
}
