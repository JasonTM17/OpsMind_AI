package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IncidentListPlanAssertionsTest {

    @Test
    void acceptsExecutedBoundedV016IndexPlan() {
        assertThatCode(() -> IncidentListPlanAssertions.validate(
            plan("Index Scan", "incident_list_status_order_idx", "incidents", 25,
                "organization_id = x AND project_id = y AND status = OPEN "
                    + "AND updated_at < now() AND id < z"),
            "incident_list_status_order_idx", true, true
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsSequentialScanAndWrongIndexRelation() {
        assertThatThrownBy(() -> IncidentListPlanAssertions.validate(
            plan("Seq Scan", "incident_list_order_idx", "other_table", 25,
                "organization_id = x AND project_id = y"),
            "incident_list_order_idx", false, false
        )).isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsUnboundedWork() {
        assertThatThrownBy(() -> IncidentListPlanAssertions.validate(
            plan("Index Scan", "incident_list_order_idx", "incidents", 200,
                "organization_id = x AND project_id = y"),
            "incident_list_order_idx", false, false
        )).isInstanceOf(AssertionError.class);
    }

    private String plan(
        String nodeType,
        String index,
        String relation,
        int rows,
        String condition
    ) {
        return """
            [{"Plan":{"Node Type":"Limit","Actual Rows":25,"Actual Loops":1,"Plans":[
              {"Node Type":"%s","Index Name":"%s","Relation Name":"%s",
               "Actual Rows":%d,"Actual Loops":1,"Rows Removed by Filter":0,
               "Index Cond":"%s"}
            ]}}]
            """.formatted(nodeType, index, relation, rows, condition);
    }
}
