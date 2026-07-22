package ai.opsmind.platform.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class DelegatedCapabilityValidatorTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void validatesAudienceScopeBudgetAndReservesNonce() {
        RecordingNonceGuard nonceGuard = new RecordingNonceGuard();
        DelegatedCapabilityValidator validator = new DelegatedCapabilityValidator(
            Clock.fixed(NOW, ZoneOffset.UTC),
            nonceGuard,
            URI.create("https://platform.example.test")
        );

        DelegatedCapability capability = validator.validate(
            validJwt(),
            "opsmind-tool-gateway",
            ORGANIZATION_ID,
            "metrics.query",
            "prometheus:synthetic/opsmind-api"
        );

        assertThat(capability.subject()).isEqualTo("operator-001");
        assertThat(capability.budget().maxCalls()).isEqualTo(5);
        assertThat(nonceGuard.reserved).isEqualTo("nonce-012345678901");
    }

    @Test
    void rejectsReplayAndOutOfScopeCalls() {
        DelegatedCapabilityValidator validator = new DelegatedCapabilityValidator(
            Clock.fixed(NOW, ZoneOffset.UTC),
            (nonce, expiresAt) -> false,
            URI.create("https://platform.example.test")
        );

        assertThatThrownBy(() -> validator.validate(
            validJwt(),
            "opsmind-tool-gateway",
            ORGANIZATION_ID,
            "metrics.query",
            "prometheus:synthetic/opsmind-api"
        )).isInstanceOfSatisfying(CapabilityValidationException.class, exception ->
            assertThat(exception.code()).isEqualTo("capability.replayed"));

        assertThatThrownBy(() -> validator.validate(
            validJwt(),
            "opsmind-tool-gateway",
            ORGANIZATION_ID,
            "write.execute",
            "prometheus:synthetic/opsmind-api"
        )).isInstanceOfSatisfying(CapabilityValidationException.class, exception ->
            assertThat(exception.code()).isEqualTo("capability.scope-denied"));
    }

    @Test
    void rejectsLifetimeBeyondBound() {
        Jwt jwt = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://platform.example.test")
            .subject("operator-001")
            .audience(List.of("opsmind-tool-gateway"))
            .issuedAt(NOW)
            .expiresAt(NOW.plusSeconds(601))
            .claim("org_id", ORGANIZATION_ID.toString())
            .claim("actions", List.of("metrics.query"))
            .claim("resources", List.of("prometheus:synthetic/opsmind-api"))
            .claim("max_calls", 5)
            .claim("max_bytes", 200000L)
            .claim("nonce", "nonce-012345678901")
            .claim("policy_version", "policy-2026.07")
            .build();
        DelegatedCapabilityValidator validator = new DelegatedCapabilityValidator(
            Clock.fixed(NOW, ZoneOffset.UTC),
            (nonce, expiresAt) -> true,
            URI.create("https://platform.example.test")
        );

        assertThatThrownBy(() -> validator.validate(
            jwt,
            "opsmind-tool-gateway",
            ORGANIZATION_ID,
            "metrics.query",
            "prometheus:synthetic/opsmind-api"
        )).isInstanceOfSatisfying(CapabilityValidationException.class, exception ->
            assertThat(exception.code()).isEqualTo("capability.lifetime-invalid"));
    }

    private Jwt validJwt() {
        return Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://platform.example.test")
            .subject("operator-001")
            .audience(List.of("opsmind-tool-gateway"))
            .issuedAt(NOW.minusSeconds(5))
            .expiresAt(NOW.plusSeconds(300))
            .claim("org_id", ORGANIZATION_ID.toString())
            .claim("actions", List.of("metrics.query"))
            .claim("resources", List.of("prometheus:synthetic/opsmind-api"))
            .claim("max_calls", 5)
            .claim("max_bytes", 200000L)
            .claim("nonce", "nonce-012345678901")
            .claim("policy_version", "policy-2026.07")
            .build();
    }

    private static final class RecordingNonceGuard implements NonceReplayGuard {
        private String reserved;

        @Override
        public boolean reserve(String nonce, Instant expiresAt) {
            reserved = nonce;
            return true;
        }
    }
}
