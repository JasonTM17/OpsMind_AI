package ai.opsmind.toolgateway.connectors.prometheus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import ai.opsmind.toolgateway.application.ToolManifest;
import ai.opsmind.toolgateway.connectors.ConnectorEvidence;
import ai.opsmind.toolgateway.connectors.ToolConnector;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

public final class PrometheusObservabilityConnector implements ToolConnector {

    private static final String CONNECTOR_ID = "prometheus-read-only";
    private static final String CONNECTOR_VERSION = "prometheus-http-api@1";

    private final PrometheusConnectorProperties properties;
    private final PrometheusExchange exchange;
    private final PrometheusResponseParser parser;
    private final PrometheusQueryCatalog queries;
    private final Clock clock;

    PrometheusObservabilityConnector(
        PrometheusConnectorProperties properties,
        PrometheusExchange exchange,
        PrometheusResponseParser parser,
        PrometheusQueryCatalog queries,
        Clock clock
    ) {
        this.properties = properties;
        this.exchange = exchange;
        this.parser = parser;
        this.queries = queries;
        this.clock = clock;
    }

    @Override
    public String id() {
        return CONNECTOR_ID;
    }

    @Override
    public boolean available() {
        return exchange.ready(
            properties.egressTarget().endsWith("/")
                ? java.net.URI.create(properties.egressTarget() + "-/ready")
                : java.net.URI.create(properties.egressTarget() + "/-/ready"),
            Duration.ofSeconds(1)
        );
    }

    @Override
    public ConnectorEvidence execute(ToolExecutionRequest request, ToolManifest manifest) {
        validateManifest(manifest);
        PrometheusQueryCatalog.Selection selection = queries.require(request);
        Instant windowEnd = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant windowStart = windowEnd.minus(properties.queryWindow());
        Duration timeout = boundedTimeout(request.deadlineAt());
        byte[] body = exchange.getJson(
            PrometheusQueryUri.build(
                properties.queryRangeEndpoint(),
                selection.promql(),
                windowStart,
                windowEnd,
                properties.queryStep()
            ),
            timeout
        );
        return new ConnectorEvidence(
            "prometheus",
            request.resource(),
            // Keep the envelope internally ordered. The query window is
            // second-truncated before the HTTP call, so using a later clock
            // instant here can make observed_at fall outside window_end.
            windowEnd,
            windowStart,
            windowEnd,
            CONNECTOR_VERSION,
            "source-attested",
            parser.parse(body, selection, windowStart, windowEnd)
        );
    }

    private Duration boundedTimeout(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new ToolDeniedException(
                DenialCode.DEADLINE_EXPIRED,
                "Prometheus request deadline is expired."
            );
        }
        return remaining.compareTo(properties.requestTimeout()) < 0
            ? remaining : properties.requestTimeout();
    }

    private void validateManifest(ToolManifest manifest) {
        if (!CONNECTOR_ID.equals(manifest.connectorId())
            || !"prometheus-read-only".equals(manifest.credentialProfile())
            || !Set.of(properties.egressTarget()).equals(manifest.egressTargets())) {
            throw new IllegalStateException("Prometheus connector manifest binding is invalid.");
        }
    }
}
