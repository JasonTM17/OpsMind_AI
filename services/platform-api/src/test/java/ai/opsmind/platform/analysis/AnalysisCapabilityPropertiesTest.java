package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class AnalysisCapabilityPropertiesTest {

    @Test
    void acceptsBoundedEnabledAsymmetricIssuerConfiguration() {
        AnalysisCapabilityProperties properties = properties(
            URI.create("https://platform.example.test"),
            Duration.ofMinutes(4)
        );

        properties.validateEnabled();

        assertThat(properties.audience()).isEqualTo("opsmind-ai-runtime");
        assertThat(properties.keyId()).isEqualTo("analysis-key-2026-07");
    }

    @Test
    void rejectsPlaceholderIssuerOrExcessiveLifetime() {
        assertThatThrownBy(() -> properties(
            URI.create("https://platform.invalid.example"),
            Duration.ofMinutes(4)
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> properties(
            URI.create("https://platform.example.test"),
            Duration.ofMinutes(6)
        ).validateEnabled()).isInstanceOf(IllegalStateException.class);
    }

    private AnalysisCapabilityProperties properties(URI issuer, Duration maximumLifetime) {
        return new AnalysisCapabilityProperties(
            true,
            issuer,
            "opsmind-ai-runtime",
            "analysis-key-2026-07",
            Path.of("C:/secret-mount/analysis-capability.pem").toAbsolutePath(),
            maximumLifetime
        );
    }
}
