package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class OidcAccessTokenValidatorTest {

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
    private final OidcAccessTokenValidator validator = new OidcAccessTokenValidator(
        PROPERTIES,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void acceptsBoundedMfaAccessToken() {
        var result = validator.validate(token(
            List.of("opsmind-platform-api"),
            NOW.minusSeconds(1),
            NOW.plusSeconds(299),
            List.of("pwd", "mfa")
        ));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongAudienceMissingMfaAndMalformedAmr() {
        var wrongAudience = validator.validate(token(
            List.of("other-api"),
            NOW.minusSeconds(1),
            NOW.plusSeconds(299),
            List.of("pwd")
        ));
        var malformedAmr = validator.validate(token(
            List.of("opsmind-platform-api"),
            NOW.minusSeconds(1),
            NOW.plusSeconds(299),
            "mfa"
        ));

        assertThat(wrongAudience.getErrors())
            .extracting(error -> error.getDescription())
            .contains("Access token audience is not accepted.")
            .contains("Access token does not prove the required authentication method.");
        assertThat(malformedAmr.getErrors())
            .extracting(error -> error.getDescription())
            .contains("Access token AMR claim is invalid.");
    }

    @Test
    void rejectsOverlongLifetimeAndFutureIssuedAt() {
        var overlong = validator.validate(token(
            List.of("opsmind-platform-api"),
            NOW.minusSeconds(1),
            NOW.plusSeconds(300),
            List.of("mfa")
        ));
        var future = validator.validate(token(
            List.of("opsmind-platform-api"),
            NOW.plusSeconds(61),
            NOW.plusSeconds(361),
            List.of("mfa")
        ));

        assertThat(overlong.getErrors())
            .extracting(error -> error.getDescription())
            .contains("Access token lifetime exceeds the accepted maximum.");
        assertThat(future.getErrors())
            .extracting(error -> error.getDescription())
            .contains("Access token issued-at time is in the future.");
    }

    private Jwt token(
        List<String> audience,
        Instant issuedAt,
        Instant expiresAt,
        Object amr
    ) {
        return Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .audience(audience)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("amr", amr)
            .build();
    }
}
