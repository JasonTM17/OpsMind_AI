package ai.opsmind.toolgateway.connectors.prometheus;

import java.util.Map;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

final class PrometheusQueryCatalog {

    private static final Map<QueryKey, QueryTemplate> QUERIES = Map.of(
        new QueryKey("opsmind-api", "http_request_duration_seconds"),
        new QueryTemplate(
            "opsmind:http_request_duration_seconds:synthetic",
            "opsmind:http_request_duration_seconds:synthetic{service=\"opsmind-api\"}"
        ),
        new QueryKey("opsmind-api", "http_errors_total"),
        new QueryTemplate(
            "opsmind:http_errors_total:synthetic",
            "opsmind:http_errors_total:synthetic{service=\"opsmind-api\"}"
        )
    );

    Selection require(ToolExecutionRequest request) {
        Object serviceValue = request.arguments().get("service");
        Object metricValue = request.arguments().get("metric");
        if (!(serviceValue instanceof String service)
            || !(metricValue instanceof String metric)) {
            throw denied("Prometheus service and metric selectors are required.");
        }
        QueryTemplate template = QUERIES.get(new QueryKey(service, metric));
        if (template == null) {
            throw denied("Prometheus selector is not registered.");
        }
        int maximumPoints = request.resultBudget().maxItems();
        Object requested = request.arguments().get("max_points");
        if (requested instanceof Number number) {
            maximumPoints = Math.min(maximumPoints, number.intValue());
        }
        return new Selection(
            service,
            metric,
            template.seriesName(),
            template.promql(),
            maximumPoints
        );
    }

    private ToolDeniedException denied(String message) {
        return new ToolDeniedException(DenialCode.ARGUMENTS_INVALID, message);
    }

    record Selection(
        String service,
        String metric,
        String expectedSeriesName,
        String promql,
        int maximumPoints
    ) { }

    private record QueryKey(String service, String metric) { }

    private record QueryTemplate(String seriesName, String promql) { }
}
