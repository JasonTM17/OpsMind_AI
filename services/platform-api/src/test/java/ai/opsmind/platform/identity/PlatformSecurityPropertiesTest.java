package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class PlatformSecurityPropertiesTest {

    @Test
    void defaultsEnforceFiveMinuteTokensAndOneMinuteClockSkew() {
        var properties = new PlatformSecurityProperties(
            "oidc",
            "opsmind-platform-api",
            URI.create("https://idp.example.test/opsmind"),
            "mfa",
            null,
            null,
            null
        );

        properties.validateOidcMode();

        assertThat(properties.maximumTokenLifetime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.clockSkew()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.jwksRefreshMinimumInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void oidcModeRejectsPlaceholderOrNonHttpsIssuer() {
        assertThatThrownBy(() -> properties(
            URI.create("https://idp.invalid.example/opsmind")
        ).validateOidcMode()).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> properties(
            URI.create("http://idp.example.test/opsmind")
        ).validateOidcMode()).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> properties(
            URI.create("https://issuer.example.test/opsmind?tenant=untrusted")
        ).validateOidcMode()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oidcModeRejectsTokenAndClockBoundsAbovePolicy() {
        assertThatThrownBy(() -> properties(
            URI.create("https://idp.example.test/opsmind"),
            Duration.ofMinutes(6),
            Duration.ofSeconds(60),
            Duration.ofSeconds(1)
        ).validateOidcMode()).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> properties(
            URI.create("https://idp.example.test/opsmind"),
            Duration.ofMinutes(5),
            Duration.ofSeconds(61),
            Duration.ofSeconds(1)
        ).validateOidcMode()).isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> properties(
            URI.create("https://idp.example.test/opsmind"),
            Duration.ofMinutes(5),
            Duration.ofSeconds(60),
            Duration.ofMillis(99)
        ).validateOidcMode()).isInstanceOf(IllegalStateException.class);
    }

    private PlatformSecurityProperties properties(URI issuer) {
        return properties(
            issuer,
            Duration.ofMinutes(5),
            Duration.ofSeconds(60),
            Duration.ofSeconds(1)
        );
    }

    private PlatformSecurityProperties properties(
        URI issuer,
        Duration maximumTokenLifetime,
        Duration clockSkew,
        Duration jwksRefreshMinimumInterval
    ) {
        return new PlatformSecurityProperties(
            "oidc",
            "opsmind-platform-api",
            issuer,
            "mfa",
            maximumTokenLifetime,
            clockSkew,
            jwksRefreshMinimumInterval
        );
    }
}
