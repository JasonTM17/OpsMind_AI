package ai.opsmind.platform.analysis;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.ai-runtime.capability")
public record AnalysisCapabilityProperties(
    boolean enabled,
    URI issuer,
    String audience,
    String keyId,
    Path privateKeyPath,
    Duration maximumLifetime
) {
    private static final Pattern TOKEN_VALUE = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public AnalysisCapabilityProperties {
        audience = audience == null || audience.isBlank() ? "opsmind-ai-runtime" : audience.trim();
        keyId = keyId == null ? "" : keyId.trim();
        maximumLifetime = maximumLifetime == null ? Duration.ofMinutes(4) : maximumLifetime;
    }

    public void validateEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Analysis capability issuance is disabled.");
        }
        if (issuer == null || !"https".equalsIgnoreCase(issuer.getScheme())
            || issuer.getHost() == null || placeholderHost(issuer.getHost())
            || issuer.getRawUserInfo() != null || issuer.getRawQuery() != null
            || issuer.getRawFragment() != null) {
            throw new IllegalStateException("Analysis capability issuer must be routable HTTPS.");
        }
        if (!TOKEN_VALUE.matcher(audience).matches() || !TOKEN_VALUE.matcher(keyId).matches()) {
            throw new IllegalStateException("Analysis capability audience or key ID is invalid.");
        }
        if (privateKeyPath == null || !privateKeyPath.isAbsolute()) {
            throw new IllegalStateException("Analysis capability private key path must be absolute.");
        }
        if (maximumLifetime.compareTo(Duration.ofSeconds(30)) < 0
            || maximumLifetime.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("Analysis capability lifetime must be 30 seconds to 5 minutes.");
        }
    }

    private boolean placeholderHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("invalid.example") || normalized.endsWith(".invalid.example");
    }
}
