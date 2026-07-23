package ai.opsmind.toolgateway.connectors.prometheus;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

final class PrometheusQueryUri {

    private PrometheusQueryUri() { }

    static URI build(
        URI endpoint,
        String promql,
        Instant start,
        Instant end,
        Duration step
    ) {
        String query = "query=" + encode(promql)
            + "&start=" + start.getEpochSecond()
            + "&end=" + end.getEpochSecond()
            + "&step=" + step.toSeconds();
        return URI.create(endpoint + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
