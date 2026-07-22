package ai.opsmind.platform.identity;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.security")
public record PlatformSecurityProperties(
    String mode,
    String audience,
    URI issuerUri,
    String requiredAmr,
    Duration maximumTokenLifetime,
    Duration clockSkew,
    Duration jwksRefreshMinimumInterval
) {

    private static final Pattern ASSURANCE_VALUE = Pattern.compile("[A-Za-z0-9._:/-]{1,128}");

    public PlatformSecurityProperties {
        mode = mode == null || mode.isBlank() ? "fail-closed" : mode.trim();
        audience = audience == null || audience.isBlank() ? "opsmind-platform-api" : audience.trim();
        requiredAmr = requiredAmr == null || requiredAmr.isBlank() ? "mfa" : requiredAmr.trim();
        maximumTokenLifetime = maximumTokenLifetime == null
            ? Duration.ofMinutes(5)
            : maximumTokenLifetime;
        clockSkew = clockSkew == null ? Duration.ofSeconds(60) : clockSkew;
        jwksRefreshMinimumInterval = jwksRefreshMinimumInterval == null
            ? Duration.ofSeconds(1)
            : jwksRefreshMinimumInterval;
    }

    public void validateOidcMode() {
        if (!"oidc".equals(mode)) {
            throw new IllegalStateException("OIDC settings were requested while security mode is not oidc.");
        }
        if (issuerUri == null || !"https".equalsIgnoreCase(issuerUri.getScheme())) {
            throw new IllegalStateException("OIDC issuer must be an absolute HTTPS URI.");
        }
        String host = issuerUri.getHost();
        if (host == null || host.equalsIgnoreCase("invalid.example")
            || host.toLowerCase(java.util.Locale.ROOT).endsWith(".invalid.example")) {
            throw new IllegalStateException("OIDC mode cannot use the non-routable placeholder issuer.");
        }
        if (issuerUri.getRawUserInfo() != null || issuerUri.getRawQuery() != null
            || issuerUri.getRawFragment() != null) {
            throw new IllegalStateException("OIDC issuer cannot contain userinfo, query, or fragment components.");
        }
        if (audience.length() > 255) {
            throw new IllegalStateException("OIDC audience exceeds the contract limit.");
        }
        if (!ASSURANCE_VALUE.matcher(requiredAmr).matches()) {
            throw new IllegalStateException("OIDC required AMR value is invalid.");
        }
        if (maximumTokenLifetime.compareTo(Duration.ofMinutes(1)) < 0
            || maximumTokenLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("OIDC maximum token lifetime must be between one and five minutes.");
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalStateException("OIDC clock skew must be between zero and 60 seconds.");
        }
        if (jwksRefreshMinimumInterval.compareTo(Duration.ofMillis(100)) < 0
            || jwksRefreshMinimumInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalStateException(
                "OIDC JWKS refresh minimum interval must be between 100 milliseconds and one minute."
            );
        }
    }
}
