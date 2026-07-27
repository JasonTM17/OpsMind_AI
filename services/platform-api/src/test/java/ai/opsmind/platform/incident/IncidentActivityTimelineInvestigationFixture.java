package ai.opsmind.platform.incident;

import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.ACTOR_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.ORGANIZATION_ID;
import static ai.opsmind.platform.incident.IncidentActivityTimelineEvidenceSupport.PROJECT_ID;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class IncidentActivityTimelineInvestigationFixture {

    private IncidentActivityTimelineInvestigationFixture() {
    }

    static String runStarted(
        ObjectMapper mapper,
        UUID incidentId,
        UUID runId,
        UUID eventId,
        Instant occurredAt
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId);
        details.put("incidentId", incidentId);
        details.put("budget", Map.of(
            "maxRounds", 1,
            "maxToolCalls", 0,
            "maxEvidenceItems", 1,
            "maxTokens", 1
        ));
        details.put("occurredAt", occurredAt);
        return encode(mapper, payload(
            incidentId, runId, eventId, 1, "RUN_STARTED", occurredAt, details
        ));
    }

    static String terminal(
        ObjectMapper mapper,
        UUID incidentId,
        UUID runId,
        UUID eventId,
        Instant occurredAt,
        String reason
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", runId);
        details.put("reason", reason);
        details.put("occurredAt", occurredAt);
        return encode(mapper, payload(
            incidentId, runId, eventId, 2, "ABSTAINED", occurredAt, details
        ));
    }

    private static Map<String, Object> payload(
        UUID incidentId,
        UUID runId,
        UUID eventId,
        int sequenceNo,
        String eventType,
        Instant occurredAt,
        Map<String, Object> details
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("organizationId", ORGANIZATION_ID);
        payload.put("projectId", PROJECT_ID);
        payload.put("incidentId", incidentId);
        payload.put("runId", runId);
        payload.put("sequenceNo", sequenceNo);
        payload.put("eventType", eventType);
        payload.put("actorId", ACTOR_ID);
        payload.put("occurredAt", occurredAt);
        payload.put("details", details);
        return payload;
    }

    private static String encode(ObjectMapper mapper, Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        }
        catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to encode V009 investigation fixture.",
                exception
            );
        }
    }
}
