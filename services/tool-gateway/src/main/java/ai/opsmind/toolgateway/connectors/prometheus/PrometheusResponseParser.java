package ai.opsmind.toolgateway.connectors.prometheus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class PrometheusResponseParser {

    private static final Pattern FINITE_DECIMAL = Pattern.compile(
        "-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?"
    );
    private static final Set<String> METRIC_LABELS = Set.of("__name__", "service");

    private final ObjectReader reader;
    private final int maximumSeries;
    private final int maximumPoints;

    PrometheusResponseParser(
        ObjectMapper objectMapper,
        int maximumSeries,
        int maximumPoints
    ) {
        reader = objectMapper.readerFor(PrometheusApiResponse.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.maximumSeries = maximumSeries;
        this.maximumPoints = maximumPoints;
    }

    Map<String, Object> parse(
        byte[] body,
        PrometheusQueryCatalog.Selection selection,
        Instant windowStart,
        Instant windowEnd
    ) {
        PrometheusApiResponse response = read(body);
        validateEnvelope(response);
        List<PrometheusApiResponse.Series> series = response.data().result();
        if (series.size() > maximumSeries) invalid("Prometheus returned too many series.");
        if (series.isEmpty()) {
            return content(selection, List.of(), 0);
        }
        PrometheusApiResponse.Series only = series.getFirst();
        validateLabels(only.metric(), selection);
        if (only.values() == null || only.values().size() > maximumPoints) {
            invalid("Prometheus returned too many points.");
        }
        List<Map<String, Object>> points = parsePoints(
            only.values(),
            windowStart,
            windowEnd
        );
        int requested = Math.min(selection.maximumPoints(), maximumPoints);
        if (points.size() > requested) {
            points = List.copyOf(points.subList(points.size() - requested, points.size()));
        }
        return content(selection, points, 1);
    }

    private PrometheusApiResponse read(byte[] body) {
        if (body == null || body.length == 0) invalid("Prometheus response body is empty.");
        try {
            return reader.readValue(body);
        }
        catch (JacksonException exception) {
            throw invalid("Prometheus response JSON is invalid.", exception);
        }
    }

    private void validateEnvelope(PrometheusApiResponse response) {
        if (response == null || !"success".equals(response.status()) || response.data() == null
            || !"matrix".equals(response.data().resultType())
            || response.data().result() == null
            || present(response.errorType()) || present(response.error())
            || nonempty(response.warnings()) || nonempty(response.infos())) {
            invalid("Prometheus response envelope is invalid or incomplete.");
        }
    }

    private void validateLabels(
        Map<String, String> labels,
        PrometheusQueryCatalog.Selection selection
    ) {
        if (labels == null || !labels.keySet().equals(METRIC_LABELS)
            || !selection.expectedSeriesName().equals(labels.get("__name__"))
            || !selection.service().equals(labels.get("service"))) {
            invalid("Prometheus returned an unexpected series identity.");
        }
    }

    private List<Map<String, Object>> parsePoints(
        List<List<Object>> values,
        Instant windowStart,
        Instant windowEnd
    ) {
        List<Map<String, Object>> points = new ArrayList<>(values.size());
        Instant previous = null;
        for (List<Object> sample : values) {
            if (sample == null || sample.size() != 2 || !(sample.get(0) instanceof Number)) {
                invalid("Prometheus returned an invalid sample.");
            }
            Instant timestamp = timestamp((Number) sample.get(0));
            if (timestamp.isBefore(windowStart) || timestamp.isAfter(windowEnd)
                || (previous != null && !timestamp.isAfter(previous))) {
                invalid("Prometheus sample timestamps are outside the requested window.");
            }
            BigDecimal value = sampleValue(sample.get(1));
            points.add(Map.of("timestamp", timestamp.toString(), "value", value));
            previous = timestamp;
        }
        return List.copyOf(points);
    }

    private Instant timestamp(Number raw) {
        try {
            BigDecimal decimal = new BigDecimal(raw.toString()).stripTrailingZeros();
            if (decimal.signum() < 0 || decimal.scale() > 9) {
                invalid("Prometheus sample timestamp precision is invalid.");
            }
            BigDecimal seconds = decimal.setScale(0, RoundingMode.DOWN);
            int nanos = decimal.subtract(seconds).movePointRight(9).intValueExact();
            return Instant.ofEpochSecond(seconds.longValueExact(), nanos);
        }
        catch (ArithmeticException exception) {
            throw invalid("Prometheus sample timestamp is invalid.", exception);
        }
    }

    private BigDecimal sampleValue(Object raw) {
        if (!(raw instanceof String text) || text.length() > 64
            || !FINITE_DECIMAL.matcher(text).matches()) {
            invalid("Prometheus sample value is not a finite decimal.");
        }
        try {
            return new BigDecimal((String) raw).stripTrailingZeros();
        }
        catch (NumberFormatException exception) {
            throw invalid("Prometheus sample value is invalid.", exception);
        }
    }

    private Map<String, Object> content(
        PrometheusQueryCatalog.Selection selection,
        List<Map<String, Object>> points,
        int seriesCount
    ) {
        return Map.of(
            "service", selection.service(),
            "metric", selection.metric(),
            "series_count", seriesCount,
            "points", points
        );
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private boolean nonempty(List<String> values) {
        return values != null && !values.isEmpty();
    }

    private void invalid(String message) {
        throw invalid(message, null);
    }

    private ToolDeniedException invalid(String message, Throwable cause) {
        return new ToolDeniedException(DenialCode.CONNECTOR_FAILED, message, cause);
    }

}
