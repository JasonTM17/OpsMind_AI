package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IncidentActivityTimelineEntryTest {

    private static final UUID EVENT_ID =
        UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ACTOR_ID =
        UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID RUN_ID =
        UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant OCCURRED_AT =
        Instant.parse("2030-01-01T00:00:00.123456Z");

    @Test
    void acceptsOnlyClosedSourceSpecificShapes() {
        IncidentActivityTimelineEntry incident = new IncidentActivityTimelineEntry(
            EVENT_ID,
            IncidentActivityTimelineEntry.INCIDENT,
            IncidentTimelineEvent.CREATED,
            OCCURRED_AT,
            ACTOR_ID,
            0L,
            null,
            null
        );
        IncidentActivityTimelineEntry investigation = new IncidentActivityTimelineEntry(
            EVENT_ID,
            IncidentActivityTimelineEntry.INVESTIGATION,
            IncidentActivityTimelineEntry.ANALYSIS_ACCEPTED,
            OCCURRED_AT,
            ACTOR_ID,
            null,
            RUN_ID,
            2L
        );
        IncidentActivityTimelineEntry metadataPatch = new IncidentActivityTimelineEntry(
            EVENT_ID,
            IncidentActivityTimelineEntry.INCIDENT,
            IncidentTimelineEvent.METADATA_PATCHED,
            OCCURRED_AT,
            ACTOR_ID,
            1L,
            null,
            null
        );

        assertThat(incident.incidentVersion()).isZero();
        assertThat(metadataPatch.incidentVersion()).isOne();
        assertThat(investigation.investigationRunId()).isEqualTo(RUN_ID);
        assertThat(investigation.investigationSequence()).isEqualTo(2L);
    }

    @Test
    void rejectsCrossSourceFieldsUnknownEventsAndNonCanonicalTime() {
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INCIDENT, IncidentTimelineEvent.CREATED,
            OCCURRED_AT, ACTOR_ID, 0L, RUN_ID, null,
        });
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INVESTIGATION,
            IncidentTimelineEvent.STATUS_TRANSITIONED,
            OCCURRED_AT, ACTOR_ID, null, RUN_ID, 1L,
        });
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INVESTIGATION, "UNKNOWN",
            OCCURRED_AT, ACTOR_ID, null, RUN_ID, 1L,
        });
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INVESTIGATION,
            IncidentActivityTimelineEntry.RUN_STARTED, OCCURRED_AT.plusNanos(1),
            ACTOR_ID, null, RUN_ID, 1L,
        });
    }

    @Test
    void rejectsMissingOrOutOfRangeSourceSpecificValues() {
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INCIDENT, IncidentTimelineEvent.CREATED,
            OCCURRED_AT, ACTOR_ID, -1L, null, null,
        });
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INCIDENT, IncidentTimelineEvent.CREATED,
            OCCURRED_AT, ACTOR_ID, (long) Integer.MAX_VALUE + 1L, null, null,
        });
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INVESTIGATION,
            IncidentActivityTimelineEntry.RUN_STARTED,
            OCCURRED_AT, ACTOR_ID, null, RUN_ID, 0L,
        });
        assertInvalid(new Object[] {
            null, IncidentActivityTimelineEntry.INVESTIGATION,
            IncidentActivityTimelineEntry.RUN_STARTED,
            OCCURRED_AT, ACTOR_ID, null, RUN_ID, 1L,
        });
        assertInvalid(new Object[] {
            EVENT_ID, "OTHER", IncidentActivityTimelineEntry.RUN_STARTED,
            OCCURRED_AT, ACTOR_ID, null, RUN_ID, 1L,
        });
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INCIDENT, IncidentTimelineEvent.CREATED,
            Instant.parse("1969-12-31T23:59:59.999999Z"),
            ACTOR_ID, 0L, null, null,
        });
        assertInvalid(new Object[] {
            EVENT_ID, IncidentActivityTimelineEntry.INCIDENT, IncidentTimelineEvent.CREATED,
            Instant.ofEpochSecond(253_402_300_800L),
            ACTOR_ID, 0L, null, null,
        });
    }

    private void assertInvalid(Object[] values) {
        assertThatThrownBy(() -> new IncidentActivityTimelineEntry(
            (UUID) values[0],
            (String) values[1],
            (String) values[2],
            (Instant) values[3],
            (UUID) values[4],
            (Long) values[5],
            (UUID) values[6],
            (Long) values[7]
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
