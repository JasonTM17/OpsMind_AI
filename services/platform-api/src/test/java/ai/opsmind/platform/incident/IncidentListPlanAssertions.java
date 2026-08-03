package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class IncidentListPlanAssertions {

    private IncidentListPlanAssertions() {
    }

    static void validate(String json, String expectedIndex, boolean filtered, boolean cursorBound) {
        List<JsonNode> nodes = planNodes(json);
        assertThat(nodes)
            .extracting(node -> node.path("Node Type").asText())
            .doesNotContain("Seq Scan", "Bitmap Heap Scan", "Bitmap Index Scan", "Sort");

        JsonNode index = nodes.stream()
            .filter(node -> expectedIndex.equals(node.path("Index Name").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing V016 index: " + expectedIndex));
        assertThat(index.path("Node Type").asText()).isIn("Index Scan", "Index Only Scan");
        assertThat(index.path("Relation Name").asText()).isEqualTo("incidents");
        assertThat(index.path("Actual Loops").asDouble()).isGreaterThan(0);
        assertThat(index.path("Actual Rows").asDouble()).isBetween(1.0, 26.0);
        assertThat(index.path("Rows Removed by Filter").asDouble()).isLessThanOrEqualTo(26.0);
        String condition = index.path("Index Cond").asText();
        assertThat(condition).contains("organization_id", "project_id");
        if (filtered) assertThat(condition).contains("status");
        if (cursorBound) assertThat(condition).contains("updated_at", "id");
    }

    private static List<JsonNode> planNodes(String json) {
        try {
            JsonNode root = JsonMapper.builder().build().readTree(json);
            JsonNode plan = root.get(0).path("Plan");
            assertThat(plan.isMissingNode()).isFalse();
            List<JsonNode> nodes = new ArrayList<>();
            collect(plan, nodes);
            return nodes;
        }
        catch (JacksonException exception) {
            throw new AssertionError("V016 EXPLAIN did not return valid JSON.", exception);
        }
    }

    private static void collect(JsonNode node, List<JsonNode> nodes) {
        nodes.add(node);
        for (JsonNode child : node.path("Plans")) collect(child, nodes);
    }
}
