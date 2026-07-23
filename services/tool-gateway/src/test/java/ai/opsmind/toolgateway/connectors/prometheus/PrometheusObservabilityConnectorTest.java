package ai.opsmind.toolgateway.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import ai.opsmind.toolgateway.application.ToolManifest;
import ai.opsmind.toolgateway.application.ToolManifestResourceLoader;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class PrometheusObservabilityConnectorTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:02:00Z");
    private static final String RESPONSE = """
        {"status":"success","data":{"resultType":"matrix","result":[{
          "metric":{"__name__":"opsmind:http_request_duration_seconds:synthetic",
                    "service":"opsmind-api"},
          "values":[[1893456000,"0.10"],[1893456060,"0.42"],[1893456120,"0.31"]]
        }]}}
        """;

    @Test
    void emitsServerOwnedQueryAndSourceAttestedEvidence() {
        PrometheusConnectorProperties properties = properties();
        CapturingExchange exchange = new CapturingExchange(bytes(RESPONSE));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        ToolManifest manifest = new ToolManifestResourceLoader(mapper)
            .loadPrometheusRegistry(properties.egressTarget())
            .require(request("http_request_duration_seconds"));
        var connector = new PrometheusObservabilityConnector(
            properties,
            exchange,
            new PrometheusResponseParser(mapper, 1, 10),
            new PrometheusQueryCatalog(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        var evidence = connector.execute(request("http_request_duration_seconds"), manifest);

        assertThat(exchange.endpoint.get().getPath()).isEqualTo("/api/v1/query_range");
        assertThat(exchange.endpoint.get().getRawQuery())
            .contains(
                "query=opsmind%3Ahttp_request_duration_seconds%3Asynthetic%7Bservice%3D%22opsmind-api%22%7D",
                "start=1893456000",
                "end=1893456120",
                "step=60"
            );
        assertThat(evidence.source()).isEqualTo("prometheus");
        assertThat(evidence.targetIdentity()).isEqualTo("prometheus:synthetic/opsmind-api");
        assertThat(evidence.trustClass()).isEqualTo("source-attested");
        assertThat(evidence.content()).containsEntry("series_count", 1);
        assertThat(connector.available()).isTrue();
        assertThat(exchange.readinessEndpoint.get().getPath()).isEqualTo("/-/ready");
    }

    @Test
    void rejectsMetricThatHasNoServerOwnedTemplateBeforeHttp() {
        PrometheusConnectorProperties properties = properties();
        CapturingExchange exchange = new CapturingExchange(bytes(RESPONSE));
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        ToolExecutionRequest request = request("caller_supplied_promql");
        ToolManifest manifest = new ToolManifestResourceLoader(mapper)
            .loadPrometheusRegistry(properties.egressTarget())
            .require(request);
        var connector = new PrometheusObservabilityConnector(
            properties,
            exchange,
            new PrometheusResponseParser(mapper, 1, 10),
            new PrometheusQueryCatalog(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> connector.execute(request, manifest))
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.ARGUMENTS_INVALID));
        assertThat(exchange.endpoint.get()).isNull();
    }

    private PrometheusConnectorProperties properties() {
        return new PrometheusConnectorProperties(
            true, URI.create("https://prometheus.opsmind.example"), false,
            Duration.ofSeconds(2), Duration.ofSeconds(4), 65_536,
            1, 10, Duration.ofMinutes(2), Duration.ofMinutes(1)
        );
    }

    private ToolExecutionRequest request(String metric) {
        return new ToolExecutionRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "operator-test", "observability", "metrics.query", "1.0",
            "prometheus:synthetic/opsmind-api",
            Map.of("service", "opsmind-api", "metric", metric, "max_points", 3),
            NOW.plusSeconds(4), new ToolExecutionRequest.ResultBudget(65_536, 10)
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class CapturingExchange implements PrometheusExchange {
        private final byte[] response;
        private final AtomicReference<URI> endpoint = new AtomicReference<>();
        private final AtomicReference<URI> readinessEndpoint = new AtomicReference<>();

        private CapturingExchange(byte[] response) {
            this.response = response;
        }

        @Override
        public byte[] getJson(URI target, Duration timeout) {
            endpoint.set(target);
            return response;
        }

        @Override
        public boolean ready(URI target, Duration timeout) {
            readinessEndpoint.set(target);
            return true;
        }
    }
}
