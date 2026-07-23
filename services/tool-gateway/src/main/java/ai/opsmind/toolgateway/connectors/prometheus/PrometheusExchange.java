package ai.opsmind.toolgateway.connectors.prometheus;

import java.net.URI;
import java.time.Duration;

interface PrometheusExchange {

    byte[] getJson(URI endpoint, Duration timeout);

    boolean ready(URI endpoint, Duration timeout);
}
