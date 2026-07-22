package ai.opsmind.platform.delegation;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.tenancy.TenantScope;

import org.springframework.security.oauth2.jwt.Jwt;

public final class DelegatedCapabilityValidator {

    private static final Duration MAX_LIFETIME = Duration.ofMinutes(10);

    private final Clock clock;
    private final NonceReplayGuard nonceReplayGuard;
    private final URI expectedIssuer;

    public DelegatedCapabilityValidator(
        Clock clock,
        NonceReplayGuard nonceReplayGuard,
        URI expectedIssuer
    ) {
        this.clock = clock;
        this.nonceReplayGuard = nonceReplayGuard;
        if (expectedIssuer == null || !expectedIssuer.isAbsolute()
            || !"https".equalsIgnoreCase(expectedIssuer.getScheme())
            || expectedIssuer.getRawUserInfo() != null || expectedIssuer.getRawQuery() != null
            || expectedIssuer.getRawFragment() != null) {
            throw new IllegalArgumentException("Delegated capability issuer is required.");
        }
        this.expectedIssuer = expectedIssuer;
    }

    public DelegatedCapability validate(
        Jwt jwt,
        String expectedAudience,
        UUID expectedOrganizationId,
        String requestedAction,
        String requestedResource
    ) {
        if (jwt == null || expectedAudience == null || expectedAudience.isBlank()
            || expectedOrganizationId == null || requestedAction == null || requestedAction.isBlank()
            || requestedResource == null || requestedResource.isBlank()) {
            throw invalid("capability.request-invalid", "The delegated capability request is invalid.");
        }
        Instant now = clock.instant();
        Instant issuedAt = requiredInstant(jwt.getIssuedAt(), "capability.issued-at-missing");
        Instant expiresAt = requiredInstant(jwt.getExpiresAt(), "capability.expiry-missing");
        if (expiresAt.isBefore(now) || !expiresAt.isAfter(issuedAt)) {
            throw invalid("capability.expired", "The delegated capability is expired.");
        }
        if (issuedAt.isAfter(now.plusSeconds(30)) || Duration.between(issuedAt, expiresAt).compareTo(MAX_LIFETIME) > 0) {
            throw invalid("capability.lifetime-invalid", "The delegated capability lifetime is invalid.");
        }
        URI actualIssuer = actualIssuer(jwt);
        if (!expectedIssuer.equals(actualIssuer)) {
            throw invalid("capability.issuer-mismatch", "The delegated capability issuer is invalid.");
        }
        if (jwt.getAudience() == null || !jwt.getAudience().contains(expectedAudience)) {
            throw invalid("capability.audience-mismatch", "The delegated capability audience is invalid.");
        }

        DelegatedCapability capability;
        try {
            capability = toCapability(jwt, expectedAudience, actualIssuer, issuedAt, expiresAt);
        }
        catch (CapabilityValidationException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw invalid("capability.claim-invalid", "The delegated capability claim is invalid.");
        }
        if (!capability.tenant().organizationId().equals(expectedOrganizationId)
            || !capability.allowsAction(requestedAction)
            || !capability.allowsResource(requestedResource)) {
            throw invalid("capability.scope-denied", "The delegated capability scope is insufficient.");
        }
        if (!nonceReplayGuard.reserve(capability.nonce(), capability.expiresAt())) {
            throw invalid("capability.replayed", "The delegated capability nonce has already been used.");
        }
        return capability;
    }

    private DelegatedCapability toCapability(
        Jwt jwt,
        String expectedAudience,
        URI actualIssuer,
        Instant issuedAt,
        Instant expiresAt
    ) {
        String organizationId = requiredString(jwt, "org_id", "capability.tenant-missing");
        UUID tenantId;
        try {
            tenantId = UUID.fromString(organizationId);
        }
        catch (IllegalArgumentException exception) {
            throw invalid("capability.tenant-invalid", "The delegated capability tenant is invalid.");
        }
        String subject = requiredString(jwt, "sub", "capability.subject-missing", 255);
        String audience = expectedAudience;
        String nonce = requiredString(jwt, "nonce", "capability.nonce-missing");
        String policyVersion = requiredString(jwt, "policy_version", "capability.policy-missing", 64);
        List<String> resources = claimStringList(jwt, "resources");
        Set<String> actions = Set.copyOf(claimStringList(jwt, "actions"));
        Number maxCallsValue = jwt.getClaim("max_calls");
        Number maxBytesValue = jwt.getClaim("max_bytes");
        if (maxCallsValue == null || maxBytesValue == null) {
            throw invalid("capability.budget-missing", "The delegated capability budget is missing.");
        }
        int maxCalls = Math.toIntExact(integralValue(maxCallsValue));
        long maxBytes = integralValue(maxBytesValue);
        return new DelegatedCapability(
            actualIssuer,
            subject,
            audience,
            new TenantScope(tenantId, null, null, Set.of()),
            optionalUuid(jwt.getClaimAsString("incident_id")),
            resources,
            actions,
            new DelegatedCapability.CapabilityBudget(maxCalls, maxBytes),
            nonce,
            issuedAt,
            expiresAt,
            policyVersion
        );
    }

    private List<String> claimStringList(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        if (value instanceof List<?> list && list.size() <= 100
            && list.stream().allMatch(String.class::isInstance)) {
            List<String> strings = list.stream().map(String.class::cast).toList();
            if (strings.stream().allMatch(item -> !item.isBlank() && item.length() <= 256)) {
                return strings;
            }
        }
        throw invalid("capability.claim-invalid", "The delegated capability claim is invalid.");
    }

    private String requiredString(Jwt jwt, String name, String code) {
        return requiredString(jwt, name, code, 256);
    }

    private String requiredString(Jwt jwt, String name, String code, int maximumLength) {
        String value = jwt.getClaimAsString(name);
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw invalid(code, "The delegated capability claim is missing or invalid.");
        }
        return value.trim();
    }

    private Instant requiredInstant(Instant value, String code) {
        if (value == null) throw invalid(code, "The delegated capability timestamp is missing.");
        return value;
    }

    private URI actualIssuer(Jwt jwt) {
        if (jwt.getIssuer() == null) {
            throw invalid("capability.issuer-missing", "The delegated capability issuer is missing.");
        }
        try {
            URI issuer = URI.create(jwt.getIssuer().toString());
            if (!issuer.isAbsolute() || !"https".equalsIgnoreCase(issuer.getScheme())
                || issuer.getHost() == null || issuer.getRawUserInfo() != null
                || issuer.getRawQuery() != null || issuer.getRawFragment() != null) {
                throw invalid("capability.issuer-mismatch", "The delegated capability issuer is invalid.");
            }
            return issuer;
        }
        catch (IllegalArgumentException exception) {
            throw invalid("capability.issuer-mismatch", "The delegated capability issuer is invalid.");
        }
    }

    private UUID optionalUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException exception) {
            throw invalid("capability.incident-invalid", "The delegated capability incident is invalid.");
        }
    }

    private long integralValue(Number value) {
        try {
            return new BigDecimal(value.toString()).longValueExact();
        }
        catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("capability.budget-invalid", "The delegated capability budget is invalid.");
        }
    }

    private CapabilityValidationException invalid(String code, String message) {
        return new CapabilityValidationException(code, message);
    }
}
