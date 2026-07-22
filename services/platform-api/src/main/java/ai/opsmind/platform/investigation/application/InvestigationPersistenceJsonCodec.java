package ai.opsmind.platform.investigation.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.analysis.AnalysisRuntimeResponse;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.investigation.domain.InvestigationEvent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
final class InvestigationPersistenceJsonCodec {

    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() { };
    private static final TypeReference<Set<UUID>> UUID_SET = new TypeReference<>() { };
    private static final TypeReference<List<AnalysisRuntimeResponse.ToolIntent>> TOOL_INTENTS =
        new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    InvestigationPersistenceJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JacksonException exception) {
            throw new PlatformProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "investigation.serialization-failed",
                "Investigation state could not be serialized safely.",
                exception
            );
        }
    }

    String eventPayload(
        UUID eventId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        long sequenceNo,
        UUID actorId,
        InvestigationEvent event
    ) {
        return write(new EventEnvelope(
            eventId, organizationId, projectId, incidentId, runId, sequenceNo,
            eventType(event), actorId, occurredAt(event), eventDetails(event)
        ));
    }

    Set<String> readFingerprints(String json) {
        return read(json, STRING_SET, "requested fingerprints");
    }

    Set<UUID> readEvidenceIds(String json) {
        return read(json, UUID_SET, "evidence identifiers");
    }

    List<AnalysisRuntimeResponse.ToolIntent> readToolIntents(String json) {
        return read(json, TOOL_INTENTS, "pending tool intents");
    }

    AnalysisRuntimeResponse readFinalResponse(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, AnalysisRuntimeResponse.class);
        }
        catch (JacksonException | IllegalArgumentException exception) {
            throw invalidState("final analysis", exception);
        }
    }

    String eventType(InvestigationEvent event) {
        return switch (event) {
            case InvestigationEvent.RunStarted ignored -> "RUN_STARTED";
            case InvestigationEvent.AnalysisAccepted ignored -> "ANALYSIS_ACCEPTED";
            case InvestigationEvent.ToolRequested ignored -> "TOOL_REQUESTED";
            case InvestigationEvent.EvidenceAppended ignored -> "EVIDENCE_APPENDED";
            case InvestigationEvent.Completed ignored -> "COMPLETED";
            case InvestigationEvent.Abstained ignored -> "ABSTAINED";
            case InvestigationEvent.BudgetExceeded ignored -> "BUDGET_EXCEEDED";
            case InvestigationEvent.NoProgress ignored -> "NO_PROGRESS";
            case InvestigationEvent.Failed ignored -> "FAILED";
        };
    }

    Instant occurredAt(InvestigationEvent event) {
        return switch (event) {
            case InvestigationEvent.RunStarted value -> value.occurredAt();
            case InvestigationEvent.AnalysisAccepted value -> value.occurredAt();
            case InvestigationEvent.ToolRequested value -> value.occurredAt();
            case InvestigationEvent.EvidenceAppended value -> value.occurredAt();
            case InvestigationEvent.Completed value -> value.occurredAt();
            case InvestigationEvent.Abstained value -> value.occurredAt();
            case InvestigationEvent.BudgetExceeded value -> value.occurredAt();
            case InvestigationEvent.NoProgress value -> value.occurredAt();
            case InvestigationEvent.Failed value -> value.occurredAt();
        };
    }

    private Object eventDetails(InvestigationEvent event) {
        if (event instanceof InvestigationEvent.EvidenceAppended evidence) {
            return new EvidenceAppendedDetails(
                evidence.runId(), evidence.intentId(), evidence.evidenceId(),
                evidence.digest(), evidence.sourceType(), evidence.occurredAt()
            );
        }
        return event;
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            T value = objectMapper.readValue(json, type);
            if (value == null) throw new IllegalArgumentException(field + " are missing.");
            return value;
        }
        catch (JacksonException | IllegalArgumentException exception) {
            throw invalidState(field, exception);
        }
    }

    private PlatformProblemException invalidState(String field, Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "investigation.persistence-invalid",
            "Stored investigation " + field + " are invalid.",
            cause
        );
    }

    private record EventEnvelope(
        UUID eventId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID runId,
        long sequenceNo,
        String eventType,
        UUID actorId,
        Instant occurredAt,
        Object details
    ) { }

    private record EvidenceAppendedDetails(
        UUID runId,
        UUID intentId,
        UUID evidenceId,
        String digest,
        String sourceType,
        Instant occurredAt
    ) { }
}
