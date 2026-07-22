package ai.opsmind.platform.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class OidcAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final String INVALID_TOKEN = "invalid_token";

    private final PlatformSecurityProperties properties;
    private final Clock clock;

    public OidcAccessTokenValidator(PlatformSecurityProperties properties, Clock clock) {
        if (properties == null || clock == null) {
            throw new IllegalArgumentException("OIDC token policy and clock are required.");
        }
        properties.validateOidcMode();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token == null) {
            return failure("Access token is missing.");
        }
        List<OAuth2Error> errors = new ArrayList<>();
        validateIdentityAndAudience(token, errors);
        validateLifetime(token, errors);
        validateMfa(token, errors);
        return errors.isEmpty()
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(errors);
    }

    private void validateIdentityAndAudience(Jwt token, List<OAuth2Error> errors) {
        if (token.getSubject() == null || token.getSubject().isBlank()
            || token.getSubject().length() > 255) {
            errors.add(error("Access token subject is invalid."));
        }
        if (token.getAudience() == null || !token.getAudience().contains(properties.audience())) {
            errors.add(error("Access token audience is not accepted."));
        }
    }

    private void validateLifetime(Jwt token, List<OAuth2Error> errors) {
        Instant issuedAt = token.getIssuedAt();
        Instant expiresAt = token.getExpiresAt();
        if (issuedAt == null || expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            errors.add(error("Access token lifetime claims are invalid."));
            return;
        }
        if (issuedAt.isAfter(clock.instant().plus(properties.clockSkew()))) {
            errors.add(error("Access token issued-at time is in the future."));
        }
        Duration lifetime = Duration.between(issuedAt, expiresAt);
        if (lifetime.compareTo(properties.maximumTokenLifetime()) > 0) {
            errors.add(error("Access token lifetime exceeds the accepted maximum."));
        }
    }

    private void validateMfa(Jwt token, List<OAuth2Error> errors) {
        Object rawAmr = token.getClaims().get("amr");
        if (!(rawAmr instanceof Collection<?> values)
            || values.isEmpty() || values.size() > 20
            || values.stream().anyMatch(value -> !(value instanceof String text)
                || text.isBlank() || text.length() > 128)) {
            errors.add(error("Access token AMR claim is invalid."));
            return;
        }
        if (values.stream().noneMatch(properties.requiredAmr()::equals)) {
            errors.add(error("Access token does not prove the required authentication method."));
        }
    }

    private OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(error(description));
    }

    private OAuth2Error error(String description) {
        return new OAuth2Error(INVALID_TOKEN, description, null);
    }
}
