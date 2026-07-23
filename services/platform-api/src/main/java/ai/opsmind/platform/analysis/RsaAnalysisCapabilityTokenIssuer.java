package ai.opsmind.platform.analysis;

import java.net.URI;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import ai.opsmind.platform.delegation.RsaJwtSigner;

import tools.jackson.databind.ObjectMapper;

public final class RsaAnalysisCapabilityTokenIssuer implements AnalysisCapabilityTokenIssuer {

    private static final Pattern AUDIENCE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final URI issuer;
    private final String audience;
    private final Duration maximumLifetime;
    private final Clock clock;
    private final RsaJwtSigner signer;

    public RsaAnalysisCapabilityTokenIssuer(
        PrivateKey privateKey,
        String keyId,
        URI issuer,
        String audience,
        Duration maximumLifetime,
        Clock clock,
        SecureRandom random,
        ObjectMapper objectMapper
    ) {
        if (issuer == null || !"https".equalsIgnoreCase(issuer.getScheme())
            || issuer.getHost() == null || issuer.getRawUserInfo() != null
            || issuer.getRawQuery() != null || issuer.getRawFragment() != null) {
            throw new IllegalArgumentException("Capability issuer is invalid.");
        }
        if (audience == null || !AUDIENCE.matcher(audience).matches()) {
            throw new IllegalArgumentException("Capability audience is invalid.");
        }
        if (maximumLifetime == null || maximumLifetime.compareTo(Duration.ofSeconds(30)) < 0
            || maximumLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Capability lifetime policy is invalid.");
        }
        this.issuer = canonicalIssuer(issuer);
        this.audience = audience;
        this.maximumLifetime = maximumLifetime;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signer = new RsaJwtSigner(privateKey, keyId, random, objectMapper);
    }

    @Override
    public String issue(AnalysisCapabilityGrant grant) {
        Instant issuedAt = clock.instant();
        long expirationSecond = ceilingEpochSecond(grant.deadlineAt());
        if (!grant.deadlineAt().isAfter(issuedAt)
            || grant.deadlineAt().isAfter(issuedAt.plus(maximumLifetime))
            || expirationSecond - issuedAt.getEpochSecond() > maximumLifetime.toSeconds()) {
            throw new IllegalArgumentException("Analysis deadline exceeds capability lifetime policy.");
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.toString());
        claims.put("sub", grant.subject());
        claims.put("aud", audience);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expirationSecond);
        claims.put("jti", signer.randomIdentifier());
        claims.put("tenant_id", grant.tenantId().toString());
        claims.put("incident_id", grant.incidentId().toString());
        claims.put("run_id", grant.runId().toString());
        claims.put("purpose", grant.purpose());
        claims.put("allowed_data_classes", grant.allowedDataClasses().stream().sorted().toList());
        claims.put("request_digest", grant.requestDigest());

        return signer.sign(claims);
    }

    private long ceilingEpochSecond(Instant value) {
        return value.getEpochSecond() + (value.getNano() == 0 ? 0 : 1);
    }

    private URI canonicalIssuer(URI value) {
        String normalized = value.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }
}
