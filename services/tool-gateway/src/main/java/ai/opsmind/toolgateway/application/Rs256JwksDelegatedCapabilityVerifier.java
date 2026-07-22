package ai.opsmind.toolgateway.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

public final class Rs256JwksDelegatedCapabilityVerifier implements DelegatedCapabilityVerifier {

    private final JwtDecoder jwtDecoder;
    private final NonceReplayStore nonceReplayStore;
    private final GatewaySettings settings;
    private final Clock clock;

    public Rs256JwksDelegatedCapabilityVerifier(
        JwtDecoder jwtDecoder,
        NonceReplayStore nonceReplayStore,
        GatewaySettings settings,
        Clock clock
    ) {
        this.jwtDecoder = jwtDecoder;
        this.nonceReplayStore = nonceReplayStore;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public VerifiedCapability verify(String token, ToolExecutionRequest request) {
        if (token == null || token.isBlank() || token.length() > 16_384 || request == null) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability is invalid.");
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        }
        catch (JwtException | IllegalArgumentException exception) {
            throw new ToolDeniedException(
                DenialCode.CAPABILITY_INVALID,
                "Delegated capability is invalid.",
                exception
            );
        }

        validateStandardClaims(jwt);
        VerifiedCapability capability = claims(jwt);
        validateBodyBinding(capability, request);
        String nonce = requiredString(jwt, "nonce", 128);
        boolean nonceClaimed;
        try {
            nonceClaimed = nonceReplayStore.claim(nonce, capability.expiresAt());
        }
        catch (RuntimeException exception) {
            throw new ToolDeniedException(
                DenialCode.CAPABILITY_UNAVAILABLE,
                "Delegated capability replay protection is unavailable.",
                exception
            );
        }
        if (!nonceClaimed) {
            throw denied(DenialCode.CAPABILITY_REPLAYED, "Delegated capability was already consumed.");
        }
        return capability;
    }

    private void validateStandardClaims(Jwt jwt) {
        Object algorithm = jwt.getHeaders().get("alg");
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        Instant now = clock.instant();
        if (!"RS256".equals(String.valueOf(algorithm))) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability algorithm is invalid.");
        }
        if (jwt.getIssuer() == null
            || !settings.capabilityIssuer().toString().equals(jwt.getIssuer().toString())) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability issuer is invalid.");
        }
        if (jwt.getAudience() == null
            || !List.of(settings.capabilityAudience()).equals(jwt.getAudience())) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability audience is invalid.");
        }
        if (!settings.platformCallerId().equals(jwt.getClaimAsString("azp"))) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability authorized party is invalid.");
        }
        if (!"delegated_capability".equals(jwt.getClaimAsString("token_use"))) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability token use is invalid.");
        }
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(now)
            || !expiresAt.isAfter(issuedAt)) {
            throw denied(DenialCode.CAPABILITY_EXPIRED, "Delegated capability is expired.");
        }
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (issuedAt.isAfter(now.plusSeconds(30))
            || lifetime.compareTo(settings.maximumCapabilityLifetime()) > 0) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability lifetime is invalid.");
        }
    }

    private VerifiedCapability claims(Jwt jwt) {
        try {
            int maximumCalls = Math.toIntExact(integralClaim(jwt, "max_calls"));
            long maximumBytes = integralClaim(jwt, "max_bytes");
            if (maximumCalls != 1 || maximumBytes < 1
                || maximumBytes > settings.maximumResultBytes()) {
                throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability budget is invalid.");
            }
            return new VerifiedCapability(
                requiredString(jwt, "jti", 128),
                requiredString(jwt, "sub", 255),
                requiredUuid(jwt, "org_id"),
                requiredUuid(jwt, "project_id"),
                requiredUuid(jwt, "incident_id"),
                requiredUuid(jwt, "run_id"),
                stringSet(jwt, "actions", 32),
                stringSet(jwt, "resources", 100),
                stringSet(jwt, "roles", 32),
                maximumCalls,
                maximumBytes,
                requiredString(jwt, "policy_version", 64),
                jwt.getExpiresAt()
            );
        }
        catch (ToolDeniedException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw new ToolDeniedException(
                DenialCode.CAPABILITY_INVALID,
                "Delegated capability claims are invalid.",
                exception
            );
        }
    }

    private void validateBodyBinding(VerifiedCapability capability, ToolExecutionRequest request) {
        if (!capability.subject().equals(request.actorSubject())
            || !capability.tenantId().equals(request.tenantId())
            || !capability.projectId().equals(request.projectId())
            || !capability.incidentId().equals(request.incidentId())
            || !capability.runId().equals(request.runId())
            || !capability.actions().equals(Set.of(canonicalAction(request)))
            || !capability.resources().equals(Set.of(request.resource()))
            || request.resultBudget() == null
            || request.resultBudget().maxBytes() > capability.maximumBytes()) {
            throw denied(
                DenialCode.CAPABILITY_SCOPE_MISMATCH,
                "Request scope does not match the delegated capability."
            );
        }
    }

    private String canonicalAction(ToolExecutionRequest request) {
        return request.tool() + ":" + request.action() + ":" + request.schemaVersion();
    }

    private String requiredString(Jwt jwt, String name, int maximumLength) {
        String value = jwt.getClaimAsString(name);
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability claim is invalid.");
        }
        return value;
    }

    private UUID requiredUuid(Jwt jwt, String name) {
        return UUID.fromString(requiredString(jwt, name, 64));
    }

    private Set<String> stringSet(Jwt jwt, String name, int maximumItems) {
        Object value = jwt.getClaims().get(name);
        if (!(value instanceof Collection<?> values) || values.isEmpty()
            || values.size() > maximumItems || values.stream().anyMatch(item ->
                !(item instanceof String string) || string.isBlank() || string.length() > 256)) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability claim is invalid.");
        }
        List<String> strings = values.stream().map(String.class::cast).toList();
        Set<String> uniqueValues = Set.copyOf(strings);
        if (uniqueValues.size() != strings.size()) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability claim is ambiguous.");
        }
        return uniqueValues;
    }

    private long integralClaim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        if (!(value instanceof Number number)) {
            throw denied(DenialCode.CAPABILITY_INVALID, "Delegated capability budget is invalid.");
        }
        return new BigDecimal(number.toString()).longValueExact();
    }

    private ToolDeniedException denied(DenialCode code, String message) {
        return new ToolDeniedException(code, message);
    }
}
