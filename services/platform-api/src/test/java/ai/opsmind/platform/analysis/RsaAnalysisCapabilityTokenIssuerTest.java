package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class RsaAnalysisCapabilityTokenIssuerTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final URI ISSUER = URI.create("https://platform.example.test");
    private static final String AUDIENCE = "opsmind-ai-runtime";
    private KeyPair keyPair;

    @BeforeEach
    void createKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void issuesStrictRs256CapabilityBoundToExactAnalysisRequest() {
        RsaAnalysisCapabilityTokenIssuer issuer = issuer();

        String token = issuer.issue(grant(NOW.plusSeconds(60)));

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.getHeaders()).containsEntry("kid", "analysis-key-2026-07");
        assertThat(jwt.getHeaders()).containsEntry("typ", "JWT");
        assertThat(jwt.getIssuer().toString()).isEqualTo(ISSUER.toString());
        assertThat(jwt.getSubject()).isEqualTo("operator:verified-subject");
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getClaimAsString("request_digest")).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(jwt.getClaimAsString("jti")).hasSize(32);
        assertThat(jwt.getClaimAsStringList("allowed_data_classes"))
            .containsExactly("redacted_metrics");
    }

    @Test
    void rejectsDeadlineBeyondMaximumCapabilityLifetime() {
        assertThatThrownBy(() -> issuer().issue(grant(NOW.plus(Duration.ofMinutes(4)).plusSeconds(1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deadline");
    }

    @Test
    void rejectsFractionalDeadlineWhoseNumericDateWouldExceedLifetime() {
        Instant fractionalNow = NOW.plusMillis(500);
        assertThatThrownBy(() -> issuer(fractionalNow).issue(grant(
            fractionalNow.plus(Duration.ofMinutes(4)).minusNanos(1)
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deadline");
    }

    @Test
    void emitsOnlyTheStrictCrossServiceHeaderAndClaimSet() throws Exception {
        String token = issuer().issue(grant(NOW.plusSeconds(60)));
        String[] segments = token.split("\\.");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode header = mapper.readTree(java.util.Base64.getUrlDecoder().decode(segments[0]));
        JsonNode claims = mapper.readTree(java.util.Base64.getUrlDecoder().decode(segments[1]));

        assertThat(header.propertyNames()).containsExactlyInAnyOrder("alg", "kid", "typ");
        assertThat(claims.propertyNames()).containsExactlyInAnyOrder(
            "iss", "sub", "aud", "iat", "exp", "jti", "tenant_id", "incident_id",
            "run_id", "purpose", "allowed_data_classes", "request_digest"
        );
        assertThat(claims.get("aud").isString()).isTrue();
        assertThat(claims.get("iat").isIntegralNumber()).isTrue();
        assertThat(claims.get("exp").isIntegralNumber()).isTrue();
    }

    @Test
    void rejectsWeakSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair weak = generator.generateKeyPair();

        assertThatThrownBy(() -> new RsaAnalysisCapabilityTokenIssuer(
            weak.getPrivate(),
            "analysis-key-2026-07",
            ISSUER,
            AUDIENCE,
            Duration.ofMinutes(4),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SecureRandom(),
            new ObjectMapper()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RSA-2048");
    }

    @Test
    void canonicalizesTrailingIssuerSlashForCrossLanguageVerification() {
        RsaAnalysisCapabilityTokenIssuer issuer = new RsaAnalysisCapabilityTokenIssuer(
            keyPair.getPrivate(),
            "analysis-key-2026-07",
            URI.create(ISSUER + "/"),
            AUDIENCE,
            Duration.ofMinutes(4),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SecureRandom(),
            new ObjectMapper()
        );

        String token = issuer.issue(grant(NOW.plusSeconds(60)));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
        assertThat(decoder.decode(token).getIssuer().toString()).isEqualTo(ISSUER.toString());
    }

    private RsaAnalysisCapabilityTokenIssuer issuer() {
        return issuer(NOW);
    }

    private RsaAnalysisCapabilityTokenIssuer issuer(Instant instant) {
        return new RsaAnalysisCapabilityTokenIssuer(
            keyPair.getPrivate(),
            "analysis-key-2026-07",
            ISSUER,
            AUDIENCE,
            Duration.ofMinutes(4),
            Clock.fixed(instant, ZoneOffset.UTC),
            new SecureRandom(),
            new ObjectMapper()
        );
    }

    private AnalysisCapabilityGrant grant(Instant deadlineAt) {
        return new AnalysisCapabilityGrant(
            "operator:verified-subject",
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "incident_investigation",
            Set.of("redacted_metrics"),
            "sha256:" + "a".repeat(64),
            deadlineAt
        );
    }
}
