package ai.opsmind.platform.incident;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.audit.AuditRepository;
import ai.opsmind.platform.messaging.EventEnvelope;
import ai.opsmind.platform.messaging.OutboxRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class IncidentDomainEventAppender {

    private final IncidentTimelineRepository timelineRepository;
    private final AuditRepository auditRepository;
    private final OutboxRepository outboxRepository;
    private final IncidentJsonCodec jsonCodec;

    IncidentDomainEventAppender(
        IncidentTimelineRepository timelineRepository,
        AuditRepository auditRepository,
        OutboxRepository outboxRepository,
        IncidentJsonCodec jsonCodec
    ) {
        this.timelineRepository = timelineRepository;
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
        this.jsonCodec = jsonCodec;
    }

    void append(IncidentTimelineEvent event, String externalTraceId) {
        String payloadJson = jsonCodec.timelinePayload(event);
        timelineRepository.append(event, payloadJson, externalTraceId);
        auditRepository.append(new AuditEvent(
            event.eventId(),
            event.organizationId(),
            event.actorId(),
            event.eventType(),
            AuditEvent.INCIDENT_SCHEMA_VERSION,
            "incident",
            event.incidentId().toString(),
            event.operationId(),
            event.occurredAt(),
            payloadJson
        ));
        outboxRepository.append(new EventEnvelope(
            event.eventId(),
            event.organizationId(),
            "incident",
            event.incidentId(),
            event.incidentVersion() + 1,
            event.eventType(),
            "1",
            null,
            event.operationId(),
            event.occurredAt(),
            payloadJson,
            jsonCodec.payloadDigest(payloadJson)
        ));
    }
}
