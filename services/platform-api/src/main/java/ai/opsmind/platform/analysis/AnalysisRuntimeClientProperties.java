package ai.opsmind.platform.analysis;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.ai-runtime.client")
public record AnalysisRuntimeClientProperties(
    boolean enabled,
    URI endpoint,
    boolean allowLocalCleartext,
    Duration connectTimeout,
    Duration requestTimeout,
    int maxResponseBodyBytes
) {
    private static final String ANALYSIS_PATH = "/api/v1/analysis";

    public AnalysisRuntimeClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        maxResponseBodyBytes = maxResponseBodyBytes == 0 ? 1_048_576 : maxResponseBodyBytes;
    }

    public void validateEnabled() {
        if (!enabled) {
            throw new IllegalStateException("AI Runtime client is disabled.");
        }
        if (endpoint == null || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
            || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
            || !ANALYSIS_PATH.equals(endpoint.getPath()) || placeholderHost(endpoint.getHost())
            || !("https".equalsIgnoreCase(endpoint.getScheme()) || loopbackHttp(endpoint)
                || localServiceHttp(endpoint))) {
            throw new IllegalStateException(
                "AI Runtime endpoint must be routable HTTPS or exact loopback HTTP."
            );
        }
        if (!between(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(5))
            || !between(requestTimeout, Duration.ofMillis(100), Duration.ofMinutes(5))) {
            throw new IllegalStateException("AI Runtime client timeouts are outside policy.");
        }
        if (maxResponseBodyBytes < 1_024 || maxResponseBodyBytes > 1_048_576) {
            throw new IllegalStateException("AI Runtime response limit is outside policy.");
        }
    }

    private boolean between(Duration value, Duration minimum, Duration maximum) {
        return value != null && !value.isNegative() && !value.isZero()
            && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }

    private boolean loopbackHttp(URI value) {
        if (!"http".equalsIgnoreCase(value.getScheme())) {
            return false;
        }
        String host = value.getHost().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private boolean localServiceHttp(URI value) {
        return allowLocalCleartext
            && "http".equalsIgnoreCase(value.getScheme())
            && value.getHost().matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    }

    private boolean placeholderHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("invalid.example") || normalized.endsWith(".invalid.example");
    }
}
