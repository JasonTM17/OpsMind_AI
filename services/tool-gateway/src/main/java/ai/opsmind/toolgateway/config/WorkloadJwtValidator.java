package ai.opsmind.toolgateway.config;

import java.util.List;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** Validates the platform workload credential independently from delegated capabilities. */
public final class WorkloadJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(
        "invalid_token",
        "The platform workload token is invalid.",
        null
    );

    private final OAuth2TokenValidator<Jwt> delegate;
    private final String audience;
    private final String scope;

    public WorkloadJwtValidator(GatewaySettings settings) {
        this.delegate = JwtValidators.createDefaultWithIssuer(settings.workloadIssuer().toString());
        this.audience = settings.workloadAudience();
        this.scope = settings.workloadScope();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        OAuth2TokenValidatorResult standard = delegate.validate(jwt);
        if (standard.hasErrors()) return standard;
        if (!List.of(audience).equals(jwt.getAudience())
            || !"workload".equals(jwt.getClaimAsString("token_use"))
            || !scope.equals(jwt.getClaimAsString("scope"))) {
            return OAuth2TokenValidatorResult.failure(INVALID_TOKEN);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
