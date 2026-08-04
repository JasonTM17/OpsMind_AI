package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentActivityTimelineEntry(
    UUID eventId,
    String source,
    String eventType,
    Instant occurredAt,
    UUID actorId,
    Long incidentVersion,
    UUID investigationRunId,
    Long investigationSequence
) {
    public static final String INCIDENT = "INCIDENT";
    public static final String INVESTIGATION = "INVESTIGATION";

    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String ANALYSIS_ACCEPTED = "ANALYSIS_ACCEPTED";
    public static final String TOOL_REQUESTED = "TOOL_REQUESTED";
    public static final String EVIDENCE_APPENDED = "EVIDENCE_APPENDED";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABSTAINED = "ABSTAINED";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String NO_PROGRESS = "NO_PROGRESS";
    public static final String FAILED = "FAILED";

    private static final long MAX_EPOCH_SECOND = 253_402_300_799L;
    private static final Set<String> INCIDENT_EVENTS = Set.of(
        IncidentTimelineEvent.CREATED,
        IncidentTimelineEvent.STATUS_TRANSITIONED,
        IncidentTimelineEvent.METADATA_PATCHED
    );
    private static final Set<String> INVESTIGATION_EVENTS = Set.of(
        RUN_STARTED,
        ANALYSIS_ACCEPTED,
        TOOL_REQUESTED,
        EVIDENCE_APPENDED,
        COMPLETED,
        ABSTAINED,
        BUDGET_EXCEEDED,
        NO_PROGRESS,
        FAILED
    );

    public IncidentActivityTimelineEntry {
        if (eventId == null
            || source == null
            || eventType == null
            || actorId == null
            || !isCanonicalTime(occurredAt)) {
            throw invalidEntry();
        }

        if (INCIDENT.equals(source)) {
            if (!INCIDENT_EVENTS.contains(eventType)
                || incidentVersion == null
                || incidentVersion < 0
                || incidentVersion > Integer.MAX_VALUE
                || investigationRunId != null
                || investigationSequence != null) {
                throw invalidEntry();
            }
        }
        else if (INVESTIGATION.equals(source)) {
            if (!INVESTIGATION_EVENTS.contains(eventType)
                || incidentVersion != null
                || investigationRunId == null
                || investigationSequence == null
                || investigationSequence < 1) {
                throw invalidEntry();
            }
        }
        else {
            throw invalidEntry();
        }
    }

    private static boolean isCanonicalTime(Instant occurredAt) {
        if (occurredAt == null || occurredAt.getNano() % 1_000 != 0) {
            return false;
        }
        long epochSecond = occurredAt.getEpochSecond();
        return epochSecond >= 0 && epochSecond <= MAX_EPOCH_SECOND;
    }

    private static IllegalArgumentException invalidEntry() {
        return new IllegalArgumentException("Incident activity timeline entry is invalid.");
    }
}
