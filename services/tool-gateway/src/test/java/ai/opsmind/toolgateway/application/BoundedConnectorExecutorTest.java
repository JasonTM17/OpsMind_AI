package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.toolgateway.connectors.ConnectorEvidence;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class BoundedConnectorExecutorTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void cancelsConnectorWhenSignedDeadlineElapses() {
        ToolExecutionRequest request = new ToolExecutionRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "operator-001", "observability", "metrics.query", "1.0",
            "prometheus:synthetic/opsmind-api", Map.of("service", "opsmind-api"),
            NOW.plusMillis(20), new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
        ToolManifest manifest = new ToolManifestResourceLoader(
            JsonMapper.builder().findAndAddModules().build()
        ).loadFixtureRegistry().require(request);
        ToolConnector slowConnector = new ToolConnector() {
            @Override
            public String id() {
                return "slow-test";
            }

            @Override
            public ConnectorEvidence execute(ToolExecutionRequest toolRequest, ToolManifest toolManifest) {
                try {
                    Thread.sleep(5_000);
                    throw new AssertionError("Connector cancellation did not interrupt the virtual thread.");
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Connector was cancelled.", exception);
                }
            }
        };

        try (BoundedConnectorExecutor executor = new BoundedConnectorExecutor(
            Clock.fixed(NOW, ZoneOffset.UTC)
        )) {
            assertThatThrownBy(() -> executor.execute(
                () -> slowConnector.execute(request, manifest),
                request,
                manifest
            ))
                .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.CONNECTOR_TIMEOUT));
        }
    }
}
