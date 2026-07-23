package ai.opsmind.platform.delegation;

import java.net.URI;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.ObjectMapper;

public final class RsaToolCapabilityTokenIssuer implements ToolCapabilityTokenIssuer {

    private static final Pattern TOKEN_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final URI issuer;
    private final String audience;
    private final String authorizedParty;
    private final Duration maximumLifetime;
    private final Clock clock;
    private final RsaJwtSigner signer;

    public RsaToolCapabilityTokenIssuer(
        PrivateKey privateKey,
        String keyId,
        URI issuer,
        String audience,
        String authorizedParty,
        Duration maximumLifetime,
        Clock clock,
        SecureRandom random,
        ObjectMapper objectMapper
    ) {
        if (issuer == null || !"https".equalsIgnoreCase(issuer.getScheme())
            || issuer.getHost() == null || issuer.getRawUserInfo() != null
            || issuer.getRawQuery() != null || issuer.getRawFragment() != null) {
            throw new IllegalArgumentException("Tool capability issuer is invalid.");
        }
        if (!validTokenValue(audience) || !validTokenValue(authorizedParty)) {
            throw new IllegalArgumentException("Tool capability audience configuration is invalid.");
        }
        if (maximumLifetime == null || maximumLifetime.compareTo(Duration.ofSeconds(30)) < 0
            || maximumLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Tool capability lifetime policy is invalid.");
        }
        this.issuer = canonicalIssuer(issuer);
        this.audience = audience;
        this.authorizedParty = authorizedParty;
        this.maximumLifetime = maximumLifetime;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signer = new RsaJwtSigner(privateKey, keyId, random, objectMapper);
    }

    @Override
    public String issue(ToolCapabilityGrant grant) {
        Instant issuedAt = clock.instant();
        long expirationSecond = ceilingEpochSecond(grant.deadlineAt());
        if (!grant.deadlineAt().isAfter(issuedAt)
            || grant.deadlineAt().isAfter(issuedAt.plus(maximumLifetime))
            || expirationSecond - issuedAt.getEpochSecond() > maximumLifetime.toSeconds()) {
            throw new IllegalArgumentException("Tool deadline exceeds capability lifetime policy.");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.toString());
        claims.put("sub", grant.subject());
        claims.put("aud", List.of(audience));
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expirationSecond);
        claims.put("jti", signer.randomIdentifier());
        claims.put("azp", authorizedParty);
        claims.put("token_use", "delegated_capability");
        claims.put("org_id", grant.organizationId().toString());
        claims.put("project_id", grant.projectId().toString());
        claims.put("incident_id", grant.incidentId().toString());
        claims.put("run_id", grant.runId().toString());
        claims.put("actions", List.of(grant.action()));
        claims.put("resources", List.of(grant.resource()));
        claims.put("roles", grant.roles().stream().sorted().toList());
        claims.put("max_calls", 1);
        claims.put("max_bytes", grant.maximumBytes());
        claims.put("request_digest", grant.requestDigest());
        claims.put("nonce", signer.randomIdentifier());
        claims.put("policy_version", grant.policyVersion());
        return signer.sign(claims);
    }

    private boolean validTokenValue(String value) {
        return value != null && TOKEN_VALUE.matcher(value).matches();
    }

    private long ceilingEpochSecond(Instant value) {
        return value.getEpochSecond() + (value.getNano() == 0 ? 0 : 1);
    }

    private URI canonicalIssuer(URI value) {
        String normalized = value.toString();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return URI.create(normalized);
    }
}
