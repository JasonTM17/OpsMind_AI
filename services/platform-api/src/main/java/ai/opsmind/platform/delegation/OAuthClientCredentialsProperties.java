package ai.opsmind.platform.delegation;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.tool-gateway.workload")
public record OAuthClientCredentialsProperties(
    boolean enabled,
    URI issuer,
    URI tokenEndpoint,
    boolean allowLocalCleartext,
    String audience,
    String clientId,
    String clientSecret,
    String scope,
    Duration connectTimeout,
    Duration requestTimeout,
    Duration refreshSkew,
    Duration maximumTokenLifetime,
    int maximumResponseBodyBytes
) {
    private static final Pattern TOKEN_VALUE = Pattern.compile("[A-Za-z0-9._:/-]{1,255}");
    private static final Pattern SCOPES = Pattern.compile(
        "[A-Za-z0-9._:/-]+(?: [A-Za-z0-9._:/-]+)*"
    );

    public OAuthClientCredentialsProperties {
        audience = trim(audience);
        clientId = trim(clientId);
        scope = trim(scope);
        clientSecret = clientSecret == null ? "" : clientSecret;
        connectTimeout = defaultDuration(connectTimeout, Duration.ofSeconds(2));
        requestTimeout = defaultDuration(requestTimeout, Duration.ofSeconds(5));
        refreshSkew = defaultDuration(refreshSkew, Duration.ofSeconds(30));
        maximumTokenLifetime = defaultDuration(maximumTokenLifetime, Duration.ofMinutes(5));
        maximumResponseBodyBytes = maximumResponseBodyBytes == 0 ? 16_384 : maximumResponseBodyBytes;
    }

    public void validateEnabled() {
        if (!enabled) throw new IllegalStateException("Tool workload authentication is disabled.");
        if (!validUri(issuer, allowLocalCleartext) || placeholderHost(issuer.getHost())
            || !validUri(tokenEndpoint, allowLocalCleartext)
            || placeholderHost(tokenEndpoint.getHost()) || !sameOrigin(issuer, tokenEndpoint)) {
            throw new IllegalStateException("Workload issuer and token endpoint are invalid.");
        }
        if (!TOKEN_VALUE.matcher(audience).matches() || !TOKEN_VALUE.matcher(clientId).matches()
            || clientSecret.isBlank() || clientSecret.length() > 4_096
            || clientSecret.indexOf('\r') >= 0 || clientSecret.indexOf('\n') >= 0
            || !SCOPES.matcher(scope).matches()) {
            throw new IllegalStateException("Workload client identity configuration is invalid.");
        }
        if (!between(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(5))
            || !between(requestTimeout, Duration.ofMillis(100), Duration.ofSeconds(30))
            || !between(refreshSkew, Duration.ofSeconds(5), Duration.ofMinutes(2))
            || !between(maximumTokenLifetime, Duration.ofSeconds(30), Duration.ofMinutes(10))
            || refreshSkew.compareTo(maximumTokenLifetime) >= 0
            || maximumResponseBodyBytes < 1_024 || maximumResponseBodyBytes > 65_536) {
            throw new IllegalStateException("Workload token bounds are invalid.");
        }
    }

    @Override
    public String toString() {
        return "OAuthClientCredentialsProperties[credentials=redacted]";
    }

    String canonicalIssuer() {
        String value = issuer.toString();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private boolean validUri(URI value, boolean localCleartext) {
        if (value == null || !value.isAbsolute() || value.getHost() == null
            || value.getRawUserInfo() != null || value.getRawQuery() != null
            || value.getRawFragment() != null) return false;
        if ("https".equalsIgnoreCase(value.getScheme())) return true;
        return localCleartext && "http".equalsIgnoreCase(value.getScheme()) && localHost(value.getHost());
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
            && left.getHost().equalsIgnoreCase(right.getHost())
            && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI value) {
        if (value.getPort() >= 0) return value.getPort();
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    private boolean localHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("127.0.0.1") || value.equals("::1");
    }

    private boolean placeholderHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("invalid.example") || value.endsWith(".invalid.example");
    }

    private boolean between(Duration value, Duration minimum, Duration maximum) {
        return value != null && !value.isNegative() && !value.isZero()
            && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
