package ai.opsmind.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtPrincipalMapperTest {

    @Test
    void mapsIdentityAndScopesButDoesNotTreatTenantClaimAsAuthority() {
        Jwt jwt = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .audience(List.of("opsmind-platform-api"))
            .issuedAt(Instant.parse("2030-01-01T00:00:00Z"))
            .expiresAt(Instant.parse("2030-01-01T00:05:00Z"))
            .claim("name", "Synthetic Operator")
            .claim("email", "operator@example.test")
            .claim("scope", "incident:read project:read")
            .claim("scp", List.of("evidence:read"))
            .claim("org_id", "99999999-9999-4999-8999-999999999999")
            .build();

        OpsMindPrincipal principal = new JwtPrincipalMapper().map(jwt);

        assertThat(principal.subject()).isEqualTo("operator-001");
        assertThat(principal.scopes()).containsExactlyInAnyOrder(
            "incident:read",
            "project:read",
            "evidence:read"
        );
        assertThat(OpsMindPrincipal.class.getRecordComponents())
            .extracting(component -> component.getName())
            .doesNotContain("organizationId", "tenant");
    }

    @Test
    void rejectsIssuerWithUnsafeUriComponents() {
        Jwt jwt = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("http://idp.example.test/opsmind")
            .subject("operator-001")
            .build();

        assertThatThrownBy(() -> new JwtPrincipalMapper().map(jwt))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsOversizedOrMalformedScopeClaims() {
        Jwt oversized = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .claim("scope", "x".repeat(129))
            .build();
        Jwt malformed = Jwt.withTokenValue("synthetic")
            .header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind")
            .subject("operator-001")
            .claim("scp", java.util.List.of("incident:read", 42))
            .build();

        assertThatThrownBy(() -> new JwtPrincipalMapper().map(oversized))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtPrincipalMapper().map(malformed))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
