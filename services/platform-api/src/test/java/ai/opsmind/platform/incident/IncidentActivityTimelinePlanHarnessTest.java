package ai.opsmind.platform.incident;

import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.INCIDENT_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.INITIAL_EVENT_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.INITIAL_INVESTIGATION_EVENT_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.INITIAL_INVESTIGATION_OCCURRED_AT;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.INITIAL_OCCURRED_AT;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.ORGANIZATION_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.PROJECT_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelinePlanAssertions.Bound;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class IncidentActivityTimelinePlanHarnessTest {

    private static final int WARMUP_SAMPLES = 50;
    private static final int MEASURED_SAMPLES = 50;
    private static final int PAGE_SIZE = 100;
    private static final java.time.Instant DISTRACTOR_START =
        java.time.Instant.parse("2032-01-01T00:00:00Z");

    @Test
    void provesProductionSqlPlansAndPersistentAppRoleReadLatency() {
        Assumptions.assumeTrue(
            "true".equals(System.getenv("OPSMIND_PHASE4B_PLAN_ENABLED")),
            "The plan harness runs only after V009 in the disposable database gate."
        );

        try (IncidentActivityTimelineEvidenceSupport.Context context =
                 new IncidentActivityTimelineEvidenceSupport.Context("v009-plan")) {
            context.assertRuntimeBoundary();
            context.assertHighCardinalityDistractors();
            JdbcIncidentTimelineRepository repository =
                new JdbcIncidentTimelineRepository(context.appJdbc);
            for (Mode mode : Mode.values()) {
                IncidentActivityTimelineQuery.Prepared query = query(mode);
                IncidentActivityTimelinePlanAssertions.validate(
                    explain(context, query),
                    mode.incidentBound,
                    mode.investigationBound
                );
                Samples samples = measure(context, repository, mode);
                context.persistSamples(
                    "vendor_read_" + mode.metricName,
                    "post_index_warmup",
                    samples.warmup
                );
                context.persistSamples(
                    "vendor_read_" + mode.metricName,
                    "post_index",
                    samples.measured
                );
                assertThat(samples.measured).hasSize(MEASURED_SAMPLES);
                System.out.printf(
                    "V009QueryPlanMode=%s%nVendorReadHarnessP95Ms=%.3f%n"
                        + "V009QueryPlanResult=PASS%n",
                    mode.metricName,
                    IncidentActivityTimelineEvidenceSupport.p95(samples.measured)
                );
            }
        }
    }

    private Samples measure(
        IncidentActivityTimelineEvidenceSupport.Context context,
        JdbcIncidentTimelineRepository repository,
        Mode mode
    ) {
        Samples samples = new Samples();
        for (int sample = 1; sample <= WARMUP_SAMPLES + MEASURED_SAMPLES; sample++) {
            long started = System.nanoTime();
            List<IncidentActivityTimelineEntry> rows = context.transactions.execute(status -> {
                context.tenantContext.apply(
                    IncidentActivityTimelineEvidenceSupport.ORGANIZATION_ID,
                    IncidentActivityTimelineEvidenceSupport.ACTOR_ID
                );
                return repository.listActivity(
                    ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, mode.cursor, PAGE_SIZE
                );
            });
            double duration = IncidentActivityTimelineEvidenceSupport.milliseconds(started);
            assertThat(rows).hasSize(PAGE_SIZE);
            assertThat(rows)
                .allSatisfy(row -> assertThat(row.occurredAt()).isBefore(DISTRACTOR_START));
            if (mode == Mode.INITIAL) {
                assertThat(rows.get(0).eventId()).isEqualTo(INITIAL_EVENT_ID);
            }
            samples.add(sample, duration);
        }
        return samples;
    }

    private String explain(
        IncidentActivityTimelineEvidenceSupport.Context context,
        IncidentActivityTimelineQuery.Prepared query
    ) {
        return context.transactions.execute(status -> {
            context.tenantContext.apply(
                IncidentActivityTimelineEvidenceSupport.ORGANIZATION_ID,
                IncidentActivityTimelineEvidenceSupport.ACTOR_ID
            );
            return context.appJdbc.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query.sql(),
                String.class,
                query.parameters().toArray()
            );
        });
    }

    private IncidentActivityTimelineQuery.Prepared query(Mode mode) {
        return IncidentActivityTimelineQuery.build(
            ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, mode.cursor, PAGE_SIZE
        );
    }

    private enum Mode {
        INITIAL("initial", null, Bound.NONE, Bound.NONE),
        CURSOR_RANK_0(
            "cursor_rank_0",
            new IncidentTimelinePageToken.ActivityCursor(
                INITIAL_OCCURRED_AT, 0, INITIAL_EVENT_ID
            ),
            Bound.TIME_AND_EVENT,
            Bound.TIME
        ),
        CURSOR_RANK_1(
            "cursor_rank_1",
            new IncidentTimelinePageToken.ActivityCursor(
                INITIAL_INVESTIGATION_OCCURRED_AT, 1, INITIAL_INVESTIGATION_EVENT_ID
            ),
            Bound.TIME,
            Bound.TIME_AND_EVENT
        );

        final String metricName;
        final IncidentTimelinePageToken.ActivityCursor cursor;
        final Bound incidentBound;
        final Bound investigationBound;

        Mode(
            String metricName,
            IncidentTimelinePageToken.ActivityCursor cursor,
            Bound incidentBound,
            Bound investigationBound
        ) {
            this.metricName = metricName;
            this.cursor = cursor;
            this.incidentBound = incidentBound;
            this.investigationBound = investigationBound;
        }
    }

    private static final class Samples {
        final List<Double> warmup = new ArrayList<>();
        final List<Double> measured = new ArrayList<>();

        void add(int sample, double duration) {
            (sample <= WARMUP_SAMPLES ? warmup : measured).add(duration);
        }
    }
}
