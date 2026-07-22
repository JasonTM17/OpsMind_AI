package ai.opsmind.platform.analysis;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class RsaAnalysisCapabilityTokenIssuer implements AnalysisCapabilityTokenIssuer {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int NONCE_BYTES = 24;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern AUDIENCE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PrivateKey privateKey;
    private final String keyId;
    private final URI issuer;
    private final String audience;
    private final Duration maximumLifetime;
    private final Clock clock;
    private final SecureRandom random;
    private final ObjectMapper objectMapper;

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
        if (!(privateKey instanceof RSAPrivateKey rsaKey) || rsaKey.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("Capability signing key must be RSA-2048 or stronger.");
        }
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Capability key ID is invalid.");
        }
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
        this.privateKey = privateKey;
        this.keyId = keyId;
        this.issuer = canonicalIssuer(issuer);
        this.audience = audience;
        this.maximumLifetime = maximumLifetime;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("kid", keyId);
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer.toString());
        claims.put("sub", grant.subject());
        claims.put("aud", audience);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expirationSecond);
        claims.put("jti", nonce());
        claims.put("tenant_id", grant.tenantId().toString());
        claims.put("incident_id", grant.incidentId().toString());
        claims.put("run_id", grant.runId().toString());
        claims.put("purpose", grant.purpose());
        claims.put("allowed_data_classes", grant.allowedDataClasses().stream().sorted().toList());
        claims.put("request_digest", grant.requestDigest());

        String signingInput = encode(header) + "." + encode(claims);
        return signingInput + "." + URL_ENCODER.encodeToString(sign(signingInput));
    }

    private String encode(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Capability claims could not be encoded.", exception);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey, random);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        }
        catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Capability signing failed.", exception);
        }
    }

    private String nonce() {
        byte[] value = new byte[NONCE_BYTES];
        random.nextBytes(value);
        return URL_ENCODER.encodeToString(value);
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
