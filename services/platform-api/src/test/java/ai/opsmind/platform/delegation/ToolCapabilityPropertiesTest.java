package ai.opsmind.platform.delegation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class ToolCapabilityPropertiesTest {

    @Test
    void rejectsDisabledOrPlaceholderConfigurationBeforeKeyAccess() {
        assertThatThrownBy(() -> properties(false, URI.create("https://platform.example.test"))
            .validateEnabled()).hasMessageContaining("disabled");
        assertThatThrownBy(() -> properties(true, URI.create("https://platform.invalid.example"))
            .validateEnabled()).hasMessageContaining("routable HTTPS");
    }

    @Test
    void rejectsRelativePrivateKeyPath() {
        ToolCapabilityProperties properties = new ToolCapabilityProperties(
            true, URI.create("https://platform.example.test"), "opsmind-tool-gateway",
            "opsmind-platform-api", "tool-key", Path.of("relative.pem"), Duration.ofMinutes(2)
        );
        assertThatThrownBy(properties::validateEnabled).hasMessageContaining("absolute");
    }

    private ToolCapabilityProperties properties(boolean enabled, URI issuer) {
        return new ToolCapabilityProperties(
            enabled, issuer, "opsmind-tool-gateway", "opsmind-platform-api", "tool-key",
            Path.of("C:/secret-mount/tool-capability.pem").toAbsolutePath(), Duration.ofMinutes(2)
        );
    }
}
