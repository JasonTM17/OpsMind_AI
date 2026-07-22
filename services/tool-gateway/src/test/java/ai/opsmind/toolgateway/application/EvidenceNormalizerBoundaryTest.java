package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.connectors.ConnectorEvidence;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class EvidenceNormalizerBoundaryTest {

    private EvidenceNormalizer normalizer;
    private ToolManifest manifest;
    private ToolExecutionRequest request;

    @BeforeEach
    void setUp() {
        GatewaySettings settings = new GatewaySettings(
            URI.create("https://platform.invalid.example"), "opsmind-tool-gateway",
            "opsmind-platform-api", null, URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload", null, Duration.ofMinutes(5), 65_536, 262_144
        );
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        normalizer = new EvidenceNormalizer(mapper, settings);
        request = new ToolExecutionRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "operator-001", "observability", "metrics.query", "1.0",
            "prometheus:synthetic/opsmind-api", Map.of("service", "opsmind-api"),
            Instant.parse("2030-01-01T00:00:04Z"),
            new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
        manifest = new ToolManifestResourceLoader(mapper).loadFixtureRegistry().require(request);
    }

    @Test
    void rejectsUnrecoverableOversizedInlineEvidence() {
        assertDenied(evidence(request.resource(), Map.of("message", "x".repeat(5_000))), DenialCode.RESULT_OVERSIZE);
    }

    @Test
    void rejectsConnectorMetadataOutsideDelegatedResource() {
        assertDenied(
            evidence("prometheus:synthetic/other-service", Map.of("value", 1)),
            DenialCode.CONNECTOR_FAILED
        );
    }

    @Test
    void rejectsSecretShapedConnectorMetadata() {
        ConnectorEvidence unsafe = new ConnectorEvidence(
            "fixture-prometheus", request.resource(), Instant.parse("2030-01-01T00:03:00Z"),
            Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:03:00Z"),
            "sk-" + "fixture-diagnostic-canary-1234", "synthetic", Map.of("value", 1)
        );
        assertDenied(unsafe, DenialCode.CONNECTOR_FAILED);
    }

    private void assertDenied(ConnectorEvidence evidence, DenialCode expectedCode) {
        assertThatThrownBy(() -> normalizer.normalize(evidence, manifest, request))
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(expectedCode));
    }

    private ConnectorEvidence evidence(String target, Map<String, Object> content) {
        return new ConnectorEvidence(
            "fixture-prometheus", target, Instant.parse("2030-01-01T00:03:00Z"),
            Instant.parse("2030-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:03:00Z"),
            "fixture-observability@1", "synthetic", content
        );
    }
}
