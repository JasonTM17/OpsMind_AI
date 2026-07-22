package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:05:00Z");
    private static final PlatformSecurityProperties PROPERTIES = new PlatformSecurityProperties(
        "oidc",
        "opsmind-platform-api",
        URI.create("https://idp.example.test/opsmind"),
        "mfa",
        Duration.ofMinutes(5),
        Duration.ofSeconds(60),
        Duration.ofSeconds(1)
    );
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void configuredClockSkewControlsExpirationBoundary() {
        var validators = SecurityConfiguration.oidcValidators(PROPERTIES, clock);

        var withinSkew = validators.validate(token(
            "https://idp.example.test/opsmind",
            NOW.minusSeconds(300),
            NOW.minusSeconds(59)
        ));
        var beyondSkew = validators.validate(token(
            "https://idp.example.test/opsmind",
            NOW.minusSeconds(300),
            NOW.minusSeconds(61)
        ));

        assertThat(withinSkew.hasErrors()).isFalse();
        assertThat(beyondSkew.hasErrors()).isTrue();
    }

    @Test
    void composedValidatorsRejectWrongIssuer() {
        var result = SecurityConfiguration.oidcValidators(PROPERTIES, clock).validate(token(
            "https://other-idp.example.test/opsmind",
            NOW.minusSeconds(1),
            NOW.plusSeconds(299)
        ));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void composedValidatorsPreserveJwtTypeAndCertificateBindingChecks() {
        var validators = SecurityConfiguration.oidcValidators(PROPERTIES, clock);
        var wrongType = validators.validate(token(
            "https://idp.example.test/opsmind",
            NOW.minusSeconds(1),
            NOW.plusSeconds(299),
            "at+jwt",
            false
        ));
        var certificateBoundWithoutCertificate = validators.validate(token(
            "https://idp.example.test/opsmind",
            NOW.minusSeconds(1),
            NOW.plusSeconds(299),
            "JWT",
            true
        ));

        assertThat(wrongType.hasErrors()).isTrue();
        assertThat(certificateBoundWithoutCertificate.hasErrors()).isTrue();
    }

    private Jwt token(String issuer, Instant issuedAt, Instant expiresAt) {
        return token(issuer, issuedAt, expiresAt, "JWT", false);
    }

    private Jwt token(
        String issuer,
        Instant issuedAt,
        Instant expiresAt,
        String type,
        boolean certificateBound
    ) {
        var builder = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .header("typ", type)
            .issuer(issuer)
            .subject("operator-001")
            .audience(List.of("opsmind-platform-api"))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("amr", List.of("pwd", "mfa"));
        if (certificateBound) {
            builder.claim("cnf", Map.of("x5t#S256", "synthetic-thumbprint"));
        }
        return builder.build();
    }
}
