package ai.opsmind.platform.incident;

import static ai.opsmind.platform.incident.IncidentActivityTimelinePlanAssertions.Bound;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IncidentActivityTimelinePlanAssertionsTest {

    @Test
    void acceptsExecutedBoundedPlansForBothExactIndexes() {
        assertThatCode(() -> IncidentActivityTimelinePlanAssertions.validate(
            plan(indexNode(
                    "incident_timeline_activity_order_idx",
                    "incident_timeline_events",
                    100,
                    1,
                    condition(true)
                ),
                indexNode(
                    "investigation_run_events_activity_order_idx",
                    "investigation_run_events",
                    100,
                    1,
                    condition(false)
                )),
            Bound.TIME_AND_EVENT,
            Bound.TIME
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsNamedIndexesThatWereNotExecutedOrBound() {
        assertThatThrownBy(() -> IncidentActivityTimelinePlanAssertions.validate(
            plan(indexNode(
                    "incident_timeline_activity_order_idx",
                    "incident_timeline_events",
                    100,
                    0,
                    ""
                ),
                indexNode(
                    "investigation_run_events_activity_order_idx",
                    "investigation_run_events",
                    100,
                    0,
                    ""
                )),
            Bound.TIME_AND_EVENT,
            Bound.TIME
        )).isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsSequentialScanEvenWhenNamedIndexesAlsoAppear() {
        String withSequentialScan = plan(
            indexNode(
                "incident_timeline_activity_order_idx",
                "incident_timeline_events",
                100,
                1,
                condition(true)
            ),
            indexNode(
                "investigation_run_events_activity_order_idx",
                "investigation_run_events",
                100,
                1,
                condition(false)
            )
        ).replace(
            "{\"Node Type\":\"Limit\",\"Actual Rows\":100,\"Plans\":[",
            "{\"Node Type\":\"Seq Scan\",\"Actual Rows\":1},"
                + "{\"Node Type\":\"Limit\",\"Actual Rows\":100,\"Plans\":["
        );
        assertThatThrownBy(() -> IncidentActivityTimelinePlanAssertions.validate(
            withSequentialScan,
            Bound.TIME_AND_EVENT,
            Bound.TIME
        )).isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsRightIndexNameOnWrongRelation() {
        assertThatThrownBy(() -> IncidentActivityTimelinePlanAssertions.validate(
            plan(indexNode(
                    "incident_timeline_activity_order_idx",
                    "unrelated_events",
                    100,
                    1,
                    condition(true)
                ),
                indexNode(
                    "investigation_run_events_activity_order_idx",
                    "investigation_run_events",
                    100,
                    1,
                    condition(false)
                )),
            Bound.TIME_AND_EVENT,
            Bound.TIME
        )).isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsLoopWeightedWorkAboveBranchLimit() {
        assertThatThrownBy(() -> IncidentActivityTimelinePlanAssertions.validate(
            plan(indexNode(
                    "incident_timeline_activity_order_idx",
                    "incident_timeline_events",
                    100,
                    3,
                    condition(true)
                ),
                indexNode(
                    "investigation_run_events_activity_order_idx",
                    "investigation_run_events",
                    100,
                    1,
                    condition(false)
                )),
            Bound.TIME_AND_EVENT,
            Bound.TIME
        )).isInstanceOf(AssertionError.class);
    }

    private String plan(String incidentIndex, String investigationIndex) {
        return """
            [{"Plan":{"Node Type":"Limit","Actual Rows":100,"Plans":[
              {"Node Type":"Append","Actual Rows":200,"Plans":[
                {"Node Type":"Limit","Actual Rows":100,"Plans":[%s]},
                {"Node Type":"Limit","Actual Rows":100,"Plans":[%s]}
              ]}
            ]}}]
            """.formatted(incidentIndex, investigationIndex);
    }

    private String indexNode(
        String name,
        String relation,
        int rows,
        int loops,
        String condition
    ) {
        return """
            {"Node Type":"Index Scan","Index Name":"%s","Relation Name":"%s",
             "Actual Rows":%d,"Actual Loops":%d,"Index Cond":"%s"}
            """.formatted(name, relation, rows, loops, condition);
    }

    private String condition(boolean includeEvent) {
        return "(organization_id = '70000000-0000-4000-8000-000000000001'::uuid) "
            + "AND (project_id = '70000000-0000-4000-8000-000000000003'::uuid) "
            + "AND (incident_id = '70000000-0000-4000-8000-000000000014'::uuid) "
            + "AND (occurred_at > '2031-01-01 00:00:00+00'::timestamptz)"
            + (includeEvent ? " AND (event_id > '70000000-0000-4000-8000-000000000015')" : "");
    }
}
