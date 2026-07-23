package ai.opsmind.platform.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RsaToolCapabilityTokenIssuerTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String DIGEST =
        "45e795204343e4e5dc30decd9aa73b80966abe6ba900062df09f9cd81b84a1d0";
    private KeyPair keyPair;

    @BeforeEach
    void createKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void issuesTheStrictGatewayFixtureClaimShape() throws Exception {
        String token = issuer().issue(grant(NOW.plusSeconds(120)));
        Jwt jwt = decoder().decode(token);
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256")
            .containsEntry("kid", "tool-key-2026-07").containsEntry("typ", "JWT");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://platform.invalid.example");
        assertThat(jwt.getAudience()).containsExactly("opsmind-tool-gateway");
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo("delegated_capability");
        assertThat(jwt.getClaimAsString("request_digest")).isEqualTo(DIGEST);
        assertThat(jwt.getClaimAsStringList("actions"))
            .containsExactly("observability:metrics.query:1.0");
        assertThat(jwt.getClaimAsString("jti")).hasSize(32);
        assertThat(jwt.getClaimAsString("nonce")).hasSize(32)
            .isNotEqualTo(jwt.getClaimAsString("jti"));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        JsonNode fixture = mapper.readTree(Files.readAllBytes(Path.of(
            "..", "..", "packages", "contracts", "fixtures", "tool-gateway",
            "delegated-tool-capability-claims-v1.valid.json"
        )));
        assertThat(claims.propertyNames()).containsExactlyInAnyOrderElementsOf(fixture.propertyNames());
        for (String field : Set.of("iss", "sub", "aud", "iat", "exp", "azp", "token_use",
            "org_id", "project_id", "incident_id", "run_id", "actions", "resources", "roles",
            "max_calls", "max_bytes", "request_digest", "policy_version")) {
            assertThat(claims.get(field)).as(field).isEqualTo(fixture.get(field));
        }
    }

    @Test
    void rejectsDeadlineBeyondOneUseLifetime() {
        assertThatThrownBy(() -> issuer().issue(grant(NOW.plusSeconds(121))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deadline");
    }

    @Test
    void rejectsExecutableScopeOrDigestDriftAtGrantBoundary() {
        assertThatThrownBy(() -> new ToolCapabilityGrant(
            "operator-001", uuid(1), uuid(2), uuid(3), uuid(4), "untrusted",
            "prometheus:synthetic/opsmind-api", Set.of("operator:read"), 65_536,
            "sha256:" + DIGEST, "policy-fixture-v1", NOW.plusSeconds(60)
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid");
    }

    private RsaToolCapabilityTokenIssuer issuer() {
        return new RsaToolCapabilityTokenIssuer(
            keyPair.getPrivate(), "tool-key-2026-07",
            URI.create("https://platform.invalid.example/"), "opsmind-tool-gateway",
            "opsmind-platform-api", Duration.ofMinutes(2), Clock.fixed(NOW, ZoneOffset.UTC),
            new SecureRandom(), new ObjectMapper()
        );
    }

    private NimbusJwtDecoder decoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256).build();
    }

    private ToolCapabilityGrant grant(Instant deadline) {
        return new ToolCapabilityGrant(
            "operator-001", uuid(1), uuid(2), uuid(3), uuid(4),
            "observability:metrics.query:1.0", "prometheus:synthetic/opsmind-api",
            Set.of("operator:read"), 65_536, DIGEST, "policy-fixture-v1", deadline
        );
    }

    private UUID uuid(int value) {
        String digit = Integer.toString(value);
        return UUID.fromString(digit.repeat(8) + "-" + digit.repeat(4) + "-4"
            + digit.repeat(3) + "-8" + digit.repeat(3) + "-" + digit.repeat(12));
    }
}
