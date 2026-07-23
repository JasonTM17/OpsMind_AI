package ai.opsmind.platform.investigation.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class ToolGatewayClientPropertiesTest {

    @Test
    void permitsRoutableHttpsAndExplicitLocalCleartextOnly() {
        properties(URI.create("https://gateway.opsmind.example/internal/v1/tools/execute"), false)
            .validateEnabled();
        properties(URI.create("http://127.0.0.1:8081/internal/v1/tools/execute"), true)
            .validateEnabled();

        assertThatThrownBy(() -> properties(
            URI.create("http://gateway.example/internal/v1/tools/execute"), false
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties(
            URI.create("https://gateway.opsmind.example/internal/v1/other"), false
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> properties(
            URI.create("https://tool-gateway.invalid.example/internal/v1/tools/execute"), false
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);
    }

    private ToolGatewayClientProperties properties(URI endpoint, boolean localCleartext) {
        return new ToolGatewayClientProperties(
            true, endpoint, localCleartext, Duration.ofSeconds(1), Duration.ofSeconds(10), 65_536
        );
    }
}
