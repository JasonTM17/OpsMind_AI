package ai.opsmind.toolgateway.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.toolgateway.application.ToolManifestRegistry;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("prometheus")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "opsmind.tool-gateway.prometheus.enabled=true",
        "opsmind.tool-gateway.prometheus.base-uri=https://prometheus.opsmind.example"
    }
)
class PrometheusProfileConfigurationTest {

    @Autowired
    private ToolManifestRegistry manifests;

    @Autowired
    private List<ToolConnector> connectors;

    @Test
    void activatesExactlyOneLiveManifestAndConnector() {
        assertThat(manifests.require(request()).connectorId())
            .isEqualTo("prometheus-read-only");
        assertThat(connectors).extracting(ToolConnector::id)
            .containsExactly("prometheus-read-only");
    }

    private ToolExecutionRequest request() {
        return new ToolExecutionRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "operator-test", "observability", "metrics.query", "1.0",
            "prometheus:synthetic/opsmind-api",
            Map.of(
                "service", "opsmind-api",
                "metric", "http_request_duration_seconds",
                "max_points", 3
            ),
            Instant.now().plusSeconds(4),
            new ToolExecutionRequest.ResultBudget(65_536, 10)
        );
    }
}
