package ai.opsmind.toolgateway.connectors.prometheus;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

record PrometheusApiResponse(
    String status,
    Data data,
    @JsonProperty("errorType") String errorType,
    String error,
    List<String> warnings,
    List<String> infos
) {
    record Data(
        @JsonProperty("resultType") String resultType,
        List<Series> result
    ) { }

    record Series(
        Map<String, String> metric,
        List<List<Object>> values
    ) { }
}
