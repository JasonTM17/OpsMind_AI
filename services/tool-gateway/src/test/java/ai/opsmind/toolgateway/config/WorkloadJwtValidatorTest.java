package ai.opsmind.toolgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class WorkloadJwtValidatorTest {

    private WorkloadJwtValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WorkloadJwtValidator(new GatewaySettings(
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway",
            "opsmind-platform-api",
            null,
            URI.create("https://identity.invalid.example"),
            "opsmind-tool-gateway-workload",
            null,
            Duration.ofMinutes(5),
            65_536,
            262_144
        ));
    }

    @Test
    void acceptsOnlyDedicatedPlatformWorkloadTokens() {
        assertThat(validator.validate(token(
            "https://identity.invalid.example",
            "opsmind-tool-gateway-workload",
            "workload"
        )).hasErrors()).isFalse();
    }

    @Test
    void rejectsDelegatedCapabilityAsBearerCredential() {
        assertThat(validator.validate(token(
            "https://identity.invalid.example",
            "opsmind-tool-gateway",
            "delegated_capability"
        )).hasErrors()).isTrue();
    }

    private Jwt token(String issuer, String audience, String tokenUse) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .issuer(issuer)
            .subject("opsmind-platform-api")
            .audience(List.of(audience))
            .issuedAt(now.minusSeconds(5))
            .expiresAt(now.plusSeconds(60))
            .claim("token_use", tokenUse)
            .build();
    }
}
