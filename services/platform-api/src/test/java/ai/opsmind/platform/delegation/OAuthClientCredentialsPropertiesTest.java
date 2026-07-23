package ai.opsmind.platform.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class OAuthClientCredentialsPropertiesTest {

    @Test
    void failsClosedBeforeNetworkForDisabledPlaceholderOrCrossOriginConfiguration() {
        assertThatThrownBy(() -> properties(false, issuer(), endpoint()).validateEnabled())
            .hasMessageContaining("disabled");
        assertThatThrownBy(() -> properties(
            true,
            URI.create("https://idp.invalid.example/issuer"),
            URI.create("https://idp.invalid.example/token")
        ).validateEnabled()).hasMessageContaining("invalid");
        assertThatThrownBy(() -> properties(
            true, issuer(), URI.create("https://other.example.test/token")
        ).validateEnabled()).hasMessageContaining("invalid");
    }

    @Test
    void neverRendersTheClientSecret() {
        OAuthClientCredentialsProperties properties = properties(true, issuer(), endpoint());
        assertThat(properties.toString()).doesNotContain("secret-value").contains("redacted");
    }

    @Test
    void rejectsAnEnabledClientWithoutInjectedSecret() {
        OAuthClientCredentialsProperties properties = new OAuthClientCredentialsProperties(
            true, issuer(), endpoint(), false, "opsmind-tool-gateway-workload",
            "platform-client", "", "tool.execute", Duration.ofSeconds(1),
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5), 16_384
        );
        assertThatThrownBy(properties::validateEnabled).hasMessageContaining("identity");
    }

    private OAuthClientCredentialsProperties properties(boolean enabled, URI issuer, URI endpoint) {
        return new OAuthClientCredentialsProperties(
            enabled, issuer, endpoint, false, "opsmind-tool-gateway-workload",
            "platform-client", "secret-value", "tool.execute", Duration.ofSeconds(1),
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5), 16_384
        );
    }

    private URI issuer() {
        return URI.create("https://idp.example.test/issuer");
    }

    private URI endpoint() {
        return URI.create("https://idp.example.test/oauth/token");
    }
}
