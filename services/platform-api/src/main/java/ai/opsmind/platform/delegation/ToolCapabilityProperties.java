package ai.opsmind.platform.delegation;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.tool-gateway.capability")
public record ToolCapabilityProperties(
    boolean enabled,
    URI issuer,
    String audience,
    String authorizedParty,
    String keyId,
    Path privateKeyPath,
    Duration maximumLifetime
) {
    private static final Pattern TOKEN_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public ToolCapabilityProperties {
        audience = blankDefault(audience, "opsmind-tool-gateway");
        authorizedParty = blankDefault(authorizedParty, "opsmind-platform-api");
        keyId = keyId == null ? "" : keyId.trim();
        maximumLifetime = maximumLifetime == null ? Duration.ofMinutes(2) : maximumLifetime;
    }

    public void validateEnabled() {
        if (!enabled) throw new IllegalStateException("Tool capability issuance is disabled.");
        if (issuer == null || !"https".equalsIgnoreCase(issuer.getScheme())
            || issuer.getHost() == null || placeholderHost(issuer.getHost())
            || issuer.getRawUserInfo() != null || issuer.getRawQuery() != null
            || issuer.getRawFragment() != null) {
            throw new IllegalStateException("Tool capability issuer must be routable HTTPS.");
        }
        if (!TOKEN_VALUE.matcher(audience).matches()
            || !TOKEN_VALUE.matcher(authorizedParty).matches()
            || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalStateException("Tool capability identity configuration is invalid.");
        }
        if (privateKeyPath == null || !privateKeyPath.isAbsolute()) {
            throw new IllegalStateException("Tool capability private key path must be absolute.");
        }
        if (maximumLifetime.compareTo(Duration.ofSeconds(30)) < 0
            || maximumLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("Tool capability lifetime must be 30 seconds to 5 minutes.");
        }
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean placeholderHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("invalid.example") || normalized.endsWith(".invalid.example");
    }
}
