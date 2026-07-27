package ai.opsmind.platform.incident;

import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.INCIDENT_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.ORGANIZATION_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.PROJECT_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class IncidentActivityTimelinePlanAssertions {

    private IncidentActivityTimelinePlanAssertions() {
    }

    static void validate(String json, Bound incidentBound, Bound investigationBound) {
        List<JsonNode> nodes = planNodes(json);
        assertThat(nodes)
            .extracting(node -> node.path("Node Type").asText())
            .doesNotContain(
                "Seq Scan", "Bitmap Heap Scan", "Bitmap Index Scan", "Materialize"
            );
        assertThat(nodes.stream()
            .filter(node -> node.path("Node Type").asText().equals("Limit")))
            .hasSizeGreaterThanOrEqualTo(3);
        assertThat(nodes).allSatisfy(node -> {
            assertThat(node.has("Actual Rows")).isTrue();
            double loops = node.path("Actual Loops").asDouble(1);
            assertThat(loops).isGreaterThan(0);
            assertThat(node.path("Actual Rows").asDouble() * loops)
                .as("loop-weighted rows for %s", node.path("Node Type").asText())
                .isLessThanOrEqualTo(200);
            assertThat(node.path("Rows Removed by Filter").asDouble() * loops)
                .as("loop-weighted filtered rows for %s", node.path("Node Type").asText())
                .isLessThanOrEqualTo(200);
            assertThat(node.path("Workers Planned").asInt()).isZero();
            assertThat(node.path("Workers").size()).isZero();
        });

        assertIndex(
            nodes,
            "incident_timeline_activity_order_idx",
            "incident_timeline_events",
            incidentBound
        );
        assertIndex(
            nodes,
            "investigation_run_events_activity_order_idx",
            "investigation_run_events",
            investigationBound
        );
    }

    private static void assertIndex(
        List<JsonNode> nodes,
        String indexName,
        String relationName,
        Bound bound
    ) {
        JsonNode index = nodes.stream()
            .filter(node -> indexName.equals(node.path("Index Name").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing V009 index: " + indexName));
        assertThat(index.path("Node Type").asText()).isIn("Index Scan", "Index Only Scan");
        assertThat(index.path("Relation Name").asText()).isEqualTo(relationName);
        assertThat(index.path("Actual Loops").asDouble()).isGreaterThan(0);
        assertThat(index.path("Actual Rows").asDouble()).isBetween(1.0, 100.0);
        String condition = index.path("Index Cond").asText();
        assertThat(condition)
            .contains(
                "organization_id", ORGANIZATION_ID.toString(),
                "project_id", PROJECT_ID.toString(),
                "incident_id", INCIDENT_ID.toString()
            );
        if (bound != Bound.NONE) assertThat(condition).contains("occurred_at");
        if (bound == Bound.TIME_AND_EVENT) assertThat(condition).contains("event_id");
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
            throw new AssertionError("V009 EXPLAIN did not return valid JSON.", exception);
        }
    }

    private static void collect(JsonNode node, List<JsonNode> nodes) {
        nodes.add(node);
        for (JsonNode child : node.path("Plans")) collect(child, nodes);
    }

    enum Bound {
        NONE,
        TIME,
        TIME_AND_EVENT
    }
}
