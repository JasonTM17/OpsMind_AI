package ai.opsmind.toolgateway.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class Rs256JwksDelegatedCapabilityVerifierTest {

    private static final Instant NOW = Instant.now().plusSeconds(5);
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private JwtEncoder encoder;
    private JwtDecoder decoder;
    private GatewaySettings settings;
    private Rs256JwksDelegatedCapabilityVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID("tool-test-key")
            .build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
        settings = new GatewaySettings(
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway",
            "opsmind-platform-api",
            null,
            URI.create("https://platform.invalid.example"),
            "opsmind-tool-gateway-workload",
            null,
            Duration.ofMinutes(5),
            65_536,
            262_144
        );
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        verifier = new Rs256JwksDelegatedCapabilityVerifier(
            decoder,
            new FixtureNonceReplayStore(clock),
            settings,
            clock
        );
    }

    @Test
    void verifiesRs256CapabilityAndExactBodyBinding() {
        VerifiedCapability verified = verifier.verify(token("nonce-012345678901"), request("operator-001"));

        assertThat(verified.tenantId()).isEqualTo(TENANT_ID);
        assertThat(verified.actions()).containsExactly("observability:metrics.query:1.0");
    }

    @Test
    void rejectsForgedBodyScopeBeforeNonceClaim() {
        assertThatThrownBy(() -> verifier.verify(token("nonce-forged-0123456"), request("forged-actor")))
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.CAPABILITY_SCOPE_MISMATCH));

        assertThat(verifier.verify(token("nonce-forged-0123456"), request("operator-001")))
            .isNotNull();
    }

    @Test
    void rejectsNonceReplay() {
        String token = token("nonce-replay-0123456");
        verifier.verify(token, request("operator-001"));

        assertThatThrownBy(() -> verifier.verify(token, request("operator-001")))
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.CAPABILITY_REPLAYED));
    }

    @Test
    void reportsReplayProtectionOutageAsUnavailable() {
        Rs256JwksDelegatedCapabilityVerifier unavailableVerifier =
            new Rs256JwksDelegatedCapabilityVerifier(
                decoder,
                new FailClosedNonceReplayStore(),
                settings,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );

        assertThatThrownBy(() -> unavailableVerifier.verify(
            token("nonce-unavailable-1234"),
            request("operator-001")
        )).isInstanceOfSatisfying(ToolDeniedException.class, exception ->
            assertThat(exception.code()).isEqualTo(DenialCode.CAPABILITY_UNAVAILABLE));
    }

    private String token(String nonce) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("https://platform.invalid.example")
            .subject("operator-001")
            .audience(List.of("opsmind-tool-gateway"))
            .issuedAt(NOW.minusSeconds(5))
            .expiresAt(NOW.plusSeconds(120))
            .id("capability-test-001")
            .claim("azp", "opsmind-platform-api")
            .claim("token_use", "delegated_capability")
            .claim("org_id", TENANT_ID.toString())
            .claim("project_id", PROJECT_ID.toString())
            .claim("incident_id", INCIDENT_ID.toString())
            .claim("run_id", RUN_ID.toString())
            .claim("actions", List.of("observability:metrics.query:1.0"))
            .claim("resources", List.of("prometheus:synthetic/opsmind-api"))
            .claim("roles", List.of("operator:read"))
            .claim("max_calls", 1)
            .claim("max_bytes", 65_536)
            .claim("nonce", nonce)
            .claim("policy_version", "policy-test")
            .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("tool-test-key").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private ToolExecutionRequest request(String actor) {
        return new ToolExecutionRequest(
            UUID.randomUUID(),
            TENANT_ID,
            PROJECT_ID,
            INCIDENT_ID,
            RUN_ID,
            actor,
            "observability",
            "metrics.query",
            "1.0",
            "prometheus:synthetic/opsmind-api",
            Map.of("service", "opsmind-api"),
            NOW.plusSeconds(30),
            new ToolExecutionRequest.ResultBudget(4_096, 10)
        );
    }
}
