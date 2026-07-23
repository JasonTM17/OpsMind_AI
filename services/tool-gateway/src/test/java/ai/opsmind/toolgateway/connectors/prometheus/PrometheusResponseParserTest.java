package ai.opsmind.toolgateway.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class PrometheusResponseParserTest {

    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2030-01-01T00:02:00Z");
    private static final String SUCCESS = """
        {
          "status": "success",
          "data": {
            "resultType": "matrix",
            "result": [{
              "metric": {
                "__name__": "opsmind:http_request_duration_seconds:synthetic",
                "service": "opsmind-api"
              },
              "values": [
                [1893456000, "0.10"],
                [1893456060, "0.42"],
                [1893456120, "0.31"]
              ]
            }]
          }
        }
        """;

    private final PrometheusResponseParser parser = new PrometheusResponseParser(
        JsonMapper.builder().findAndAddModules().build(),
        1,
        10
    );

    @Test
    void parsesExactMatrixAndKeepsMostRecentRequestedPoints() {
        Map<String, Object> content = parser.parse(
            bytes(SUCCESS),
            selection(2),
            START,
            END
        );

        assertThat(content.get("service")).isEqualTo("opsmind-api");
        assertThat(content.get("metric")).isEqualTo("http_request_duration_seconds");
        assertThat(content.get("series_count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points =
            (List<Map<String, Object>>) content.get("points");
        assertThat(points).hasSize(2);
        assertThat(points.getFirst().get("timestamp"))
            .isEqualTo("2030-01-01T00:01:00Z");
        assertThat(points.getFirst().get("value").toString()).isEqualTo("0.42");
    }

    @Test
    void acceptsEmptySuccessfulMatrixAsAbsenceEvidence() {
        String empty = """
            {
              "status": "success",
              "data": {"resultType": "matrix", "result": []}
            }
            """;

        assertThat(parser.parse(bytes(empty), selection(3), START, END))
            .containsEntry("series_count", 0)
            .containsEntry("points", List.of());
    }

    @Test
    void rejectsWarningsUnknownLabelsNonFiniteValuesAndTrailingJson() {
        List<String> invalidBodies = List.of(
            SUCCESS.replace("\"data\": {", "\"warnings\": [\"partial\"], \"data\": {"),
            SUCCESS.replace(
                "\"service\": \"opsmind-api\"",
                "\"service\": \"opsmind-api\", \"instance\": \"untrusted:9090\""
            ),
            SUCCESS.replace("\"0.42\"", "\"NaN\""),
            SUCCESS + "{}"
        );

        for (String invalid : invalidBodies) {
            assertThatThrownBy(() -> parser.parse(bytes(invalid), selection(3), START, END))
                .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                    assertThat(exception.code()).isEqualTo(DenialCode.CONNECTOR_FAILED));
        }
    }

    private PrometheusQueryCatalog.Selection selection(int maximumPoints) {
        return new PrometheusQueryCatalog.Selection(
            "opsmind-api",
            "http_request_duration_seconds",
            "opsmind:http_request_duration_seconds:synthetic",
            "server-owned-query",
            maximumPoints
        );
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
