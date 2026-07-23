package ai.opsmind.platform.investigation.integration;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opsmind.tool-gateway.client")
public record ToolGatewayClientProperties(
    boolean enabled,
    URI endpoint,
    boolean allowLocalCleartext,
    Duration connectTimeout,
    Duration requestTimeout,
    int maximumResponseBodyBytes
) {
    private static final String EXECUTION_PATH = "/internal/v1/tools/execute";

    public ToolGatewayClientProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
        maximumResponseBodyBytes = maximumResponseBodyBytes == 0 ? 131_072 : maximumResponseBodyBytes;
    }

    public void validateEnabled() {
        if (!enabled) throw new IllegalStateException("Tool Gateway client is disabled.");
        if (endpoint == null || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
            || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
            || !EXECUTION_PATH.equals(endpoint.getPath()) || placeholderHost(endpoint.getHost())
            || !("https".equalsIgnoreCase(endpoint.getScheme()) || localHttp(endpoint))) {
            throw new IllegalStateException("Tool Gateway endpoint is outside transport policy.");
        }
        if (!between(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(5))
            || !between(requestTimeout, Duration.ofMillis(100), Duration.ofSeconds(30))
            || maximumResponseBodyBytes < 1_024 || maximumResponseBodyBytes > 1_048_576) {
            throw new IllegalStateException("Tool Gateway client bounds are outside policy.");
        }
    }

    private boolean localHttp(URI value) {
        if (!allowLocalCleartext || !"http".equalsIgnoreCase(value.getScheme())) return false;
        String host = value.getHost().toLowerCase(Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
            || host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    }

    private boolean placeholderHost(String host) {
        String value = host.toLowerCase(Locale.ROOT);
        return value.equals("invalid.example") || value.endsWith(".invalid.example");
    }

    private boolean between(Duration value, Duration minimum, Duration maximum) {
        return value != null && !value.isNegative() && !value.isZero()
            && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
