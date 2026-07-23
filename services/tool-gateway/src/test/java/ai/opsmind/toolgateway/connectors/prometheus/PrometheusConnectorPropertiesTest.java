package ai.opsmind.toolgateway.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class PrometheusConnectorPropertiesTest {

    @Test
    void acceptsHttpsAndExplicitInternalCleartextOrigins() {
        var https = properties("https://prometheus.opsmind.example", false);
        var internal = properties("http://prometheus.opsmind.internal:9090", true);

        assertThat(https.egressTarget()).isEqualTo("https://prometheus.opsmind.example");
        assertThat(https.queryRangeEndpoint().getPath()).isEqualTo("/api/v1/query_range");
        assertThat(internal.egressTarget())
            .isEqualTo("http://prometheus.opsmind.internal:9090");
    }

    @Test
    void rejectsImplicitCleartextLoopbackPlaceholdersAndUnsafeBounds() {
        assertThatThrownBy(() -> properties("http://prometheus.opsmind.internal:9090", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("https://127.0.0.1:9090", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("https://prometheus.invalid.example", false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrometheusConnectorProperties(
            true,
            URI.create("https://prometheus.opsmind.example"),
            false,
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            65_536,
            2,
            10,
            Duration.ofMinutes(2),
            Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrometheusConnectorProperties(
            true,
            URI.create("https://prometheus.opsmind.example"),
            false,
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            65_536,
            1,
            2,
            Duration.ofMinutes(2),
            Duration.ofMinutes(1)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private PrometheusConnectorProperties properties(String uri, boolean allowCleartext) {
        return new PrometheusConnectorProperties(
            true,
            URI.create(uri),
            allowCleartext,
            Duration.ofSeconds(2),
            Duration.ofSeconds(4),
            65_536,
            1,
            10,
            Duration.ofMinutes(2),
            Duration.ofMinutes(1)
        );
    }
}
