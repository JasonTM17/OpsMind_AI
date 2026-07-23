package ai.opsmind.toolgateway.connectors.prometheus;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opsmind.tool-gateway.prometheus")
public record PrometheusConnectorProperties(
    boolean enabled,
    URI baseUri,
    boolean allowInternalCleartext,
    Duration connectTimeout,
    Duration requestTimeout,
    int maximumResponseBytes,
    int maximumSeries,
    int maximumPoints,
    Duration queryWindow,
    Duration queryStep
) {
    public PrometheusConnectorProperties {
        baseUri = baseUri == null
            ? URI.create("https://prometheus.invalid.example") : baseUri;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(4) : requestTimeout;
        maximumResponseBytes = maximumResponseBytes == 0 ? 65_536 : maximumResponseBytes;
        maximumSeries = maximumSeries == 0 ? 1 : maximumSeries;
        maximumPoints = maximumPoints == 0 ? 10 : maximumPoints;
        queryWindow = queryWindow == null ? Duration.ofMinutes(2) : queryWindow;
        queryStep = queryStep == null ? Duration.ofMinutes(1) : queryStep;
        if (enabled) validate(
            baseUri, allowInternalCleartext, connectTimeout, requestTimeout,
            maximumResponseBytes, maximumSeries, maximumPoints, queryWindow, queryStep
        );
    }

    public String egressTarget() {
        try {
            return new URI(
                baseUri.getScheme(),
                null,
                baseUri.getHost(),
                baseUri.getPort(),
                null,
                null,
                null
            ).toString();
        }
        catch (URISyntaxException exception) {
            throw new IllegalStateException("Prometheus base URI cannot be normalized.", exception);
        }
    }

    public URI queryRangeEndpoint() {
        return URI.create(egressTarget() + "/api/v1/query_range");
    }

    private static void validate(
        URI baseUri,
        boolean allowInternalCleartext,
        Duration connectTimeout,
        Duration requestTimeout,
        int maximumResponseBytes,
        int maximumSeries,
        int maximumPoints,
        Duration queryWindow,
        Duration queryStep
    ) {
        String host = baseUri.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        String scheme = baseUri.getScheme() == null
            ? "" : baseUri.getScheme().toLowerCase(Locale.ROOT);
        boolean internalHttp = "http".equals(scheme)
            && allowInternalCleartext
            && host != null
            && (normalizedHost.endsWith(".opsmind.internal") || loopback(host));
        if (!baseUri.isAbsolute() || host == null || baseUri.getRawUserInfo() != null
            || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null
            || (baseUri.getPath() != null && !baseUri.getPath().isEmpty()
                && !"/".equals(baseUri.getPath()))
            || (!"https".equals(scheme) && !internalHttp)
            || ("https".equals(scheme) && loopback(host))
            || normalizedHost.endsWith(".invalid.example")) {
            throw new IllegalArgumentException("Prometheus base URI is unsafe.");
        }
        if (outside(connectTimeout, Duration.ofMillis(100), Duration.ofSeconds(5))
            || outside(requestTimeout, Duration.ofMillis(250), Duration.ofSeconds(10))
            || maximumResponseBytes < 1_024 || maximumResponseBytes > 262_144
            || maximumSeries != 1
            || maximumPoints < 1 || maximumPoints > 100
            || outside(queryWindow, Duration.ofMinutes(1), Duration.ofMinutes(15))
            || outside(queryStep, Duration.ofSeconds(1), queryWindow)
            || queryWindow.dividedBy(queryStep) + 1 > maximumPoints) {
            throw new IllegalArgumentException("Prometheus connector bounds are unsafe.");
        }
    }

    private static boolean outside(Duration value, Duration minimum, Duration maximum) {
        return value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0;
    }

    private static boolean loopback(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1")
            || normalized.equals("::1") || normalized.startsWith("127.");
    }
}
