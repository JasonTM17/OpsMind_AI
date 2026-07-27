package ai.opsmind.platform.incident;

import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.ACTOR_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.ORGANIZATION_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.PROJECT_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class IncidentActivityTimelineAppendBenchmarkHarnessTest {

    private static final int WARMUP_SAMPLES = 50;
    private static final int MEASURED_SAMPLES = 250;

    @Test
    void measuresCommittedAppRoleWritesOnOnePooledConnection() {
        String phase = System.getenv("OPSMIND_PHASE4B_APPEND_PHASE");
        Assumptions.assumeTrue(
            "pre_index".equals(phase) || "post_index".equals(phase),
            "The append harness runs only in the disposable V009 gate."
        );

        try (IncidentActivityTimelineEvidenceSupport.Context context =
                 new IncidentActivityTimelineEvidenceSupport.Context("v009-append-" + phase)) {
            context.assertRuntimeBoundary();
            JdbcIncidentRepository incidents = new JdbcIncidentRepository(context.appJdbc);
            JdbcIncidentTimelineRepository timeline =
                new JdbcIncidentTimelineRepository(context.appJdbc);
            ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
            IncidentJsonCodec codec = new IncidentJsonCodec(mapper);
            Samples incidentSamples = new Samples();
            Samples investigationSamples = new Samples();

            for (int sample = 1; sample <= WARMUP_SAMPLES + MEASURED_SAMPLES; sample++) {
                incidentSamples.add(sample, appendIncident(context, incidents, timeline, codec));
                investigationSamples.add(
                    sample, appendInvestigation(context, incidents, mapper)
                );
            }

            persist(context, "incident_append", phase, incidentSamples);
            persist(context, "investigation_append", phase, investigationSamples);
            assertThat(incidentSamples.measured()).hasSize(MEASURED_SAMPLES);
            assertThat(investigationSamples.measured()).hasSize(MEASURED_SAMPLES);
            System.out.printf(
                "V009AppendPhase=%s%nIncidentAppendHarnessP95Ms=%.3f%n"
                    + "InvestigationAppendHarnessP95Ms=%.3f%nV009AppendHarness=PASS%n",
                phase,
                IncidentActivityTimelineEvidenceSupport.p95(incidentSamples.measured()),
                IncidentActivityTimelineEvidenceSupport.p95(investigationSamples.measured())
            );
        }
    }

    private double appendIncident(
        IncidentActivityTimelineEvidenceSupport.Context context,
        JdbcIncidentRepository incidents,
        JdbcIncidentTimelineRepository timeline,
        IncidentJsonCodec codec
    ) {
        UUID incidentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Instant occurredAt = IncidentActivityTimelineEvidenceSupport.now();
        IncidentSnapshot incident = incident(incidentId, occurredAt);
        IncidentTimelineEvent event = new IncidentTimelineEvent(
            eventId, ORGANIZATION_ID, PROJECT_ID, incidentId, 0,
            IncidentTimelineEvent.CREATED, ACTOR_ID, operationId, occurredAt,
            "V009 committed app-role append", null, IncidentStatus.OPEN, null, null
        );
        String payload = codec.timelinePayload(event);

        context.transactions.executeWithoutResult(status -> {
            context.tenantContext.apply(ORGANIZATION_ID, ACTOR_ID);
            incidents.insert(incident);
        });
        long started = System.nanoTime();
        context.transactions.executeWithoutResult(status -> {
            context.tenantContext.apply(ORGANIZATION_ID, ACTOR_ID);
            timeline.append(event, payload, null);
        });
        return IncidentActivityTimelineEvidenceSupport.milliseconds(started);
    }

    private double appendInvestigation(
        IncidentActivityTimelineEvidenceSupport.Context context,
        JdbcIncidentRepository incidents,
        ObjectMapper mapper
    ) {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID startedEventId = UUID.randomUUID();
        Instant startedAt = IncidentActivityTimelineEvidenceSupport.now();
        String startedPayload = IncidentActivityTimelineInvestigationFixture.runStarted(
            mapper, incidentId, runId, startedEventId, startedAt
        );

        context.transactions.executeWithoutResult(status -> {
            context.tenantContext.apply(ORGANIZATION_ID, ACTOR_ID);
            incidents.insert(incident(incidentId, startedAt));
            assertThat(context.appJdbc.update(
                "INSERT INTO investigation_runs (run_id, organization_id, project_id, "
                    + "incident_id, actor_id, status, max_rounds, max_tool_calls, "
                    + "max_evidence_items, max_tokens, event_count, started_at, deadline_at) "
                    + "VALUES (?, ?, ?, ?, ?, 'CREATED', 1, 0, 1, 1, 1, ?, ?)",
                runId, ORGANIZATION_ID, PROJECT_ID, incidentId, ACTOR_ID,
                Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(300))
            )).isEqualTo(1);
            assertThat(context.appJdbc.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, 1, 'RUN_STARTED', ?, ?, CAST(? AS jsonb))",
                startedEventId, ORGANIZATION_ID, PROJECT_ID, incidentId, runId, ACTOR_ID,
                Timestamp.from(startedAt), startedPayload
            )).isEqualTo(1);
        });
        UUID terminalEventId = UUID.randomUUID();
        Instant endedAt = IncidentActivityTimelineEvidenceSupport.after(startedAt);
        String reason = "V009 committed investigation append";
        String terminalPayload = IncidentActivityTimelineInvestigationFixture.terminal(
            mapper, incidentId, runId, terminalEventId, endedAt, reason
        );
        long started = System.nanoTime();
        context.transactions.executeWithoutResult(status -> {
            context.tenantContext.apply(ORGANIZATION_ID, ACTOR_ID);
            assertThat(context.appJdbc.update(
                "UPDATE investigation_runs SET status = 'ABSTAINED', revision = 1, "
                    + "event_count = 2, terminal_reason = ?, ended_at = ? "
                    + "WHERE organization_id = ? AND run_id = ?",
                reason, Timestamp.from(endedAt), ORGANIZATION_ID, runId
            )).isEqualTo(1);
            assertThat(context.appJdbc.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, 2, 'ABSTAINED', ?, ?, CAST(? AS jsonb))",
                terminalEventId, ORGANIZATION_ID, PROJECT_ID, incidentId, runId, ACTOR_ID,
                Timestamp.from(endedAt), terminalPayload
            )).isEqualTo(1);
        });
        return IncidentActivityTimelineEvidenceSupport.milliseconds(started);
    }

    private IncidentSnapshot incident(UUID incidentId, Instant occurredAt) {
        return new IncidentSnapshot(
            incidentId, ORGANIZATION_ID, PROJECT_ID,
            "V009 append benchmark", "Committed app-role write benchmark.",
            IncidentSeverity.SEV3, IncidentStatus.OPEN, null, null,
            ACTOR_ID, ACTOR_ID, occurredAt, occurredAt, 0
        );
    }

    private void persist(
        IncidentActivityTimelineEvidenceSupport.Context context,
        String kind,
        String phase,
        Samples samples
    ) {
        context.persistSamples(kind, phase + "_warmup", samples.warmup());
        context.persistSamples(kind, phase, samples.measured());
    }

    private record Samples(List<Double> warmup, List<Double> measured) {
        Samples() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        void add(int sample, double duration) {
            (sample <= WARMUP_SAMPLES ? warmup : measured).add(duration);
        }
    }
}
