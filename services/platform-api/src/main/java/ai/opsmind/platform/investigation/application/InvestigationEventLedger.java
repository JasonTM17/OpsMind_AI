package ai.opsmind.platform.investigation.application;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.audit.AuditRepository;
import ai.opsmind.platform.investigation.domain.InvestigationEvent;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Profile("persistence")
@ConditionalOnProperty(prefix = "opsmind.investigation", name = "store", havingValue = "postgres")
@ConditionalOnProperty(prefix = "opsmind.persistence", name = "enabled", havingValue = "true")
final class InvestigationEventLedger {

    private final JdbcTemplate jdbcTemplate;
    private final AuditRepository auditRepository;
    private final InvestigationPersistenceJsonCodec jsonCodec;

    InvestigationEventLedger(
        JdbcTemplate jdbcTemplate,
        AuditRepository auditRepository,
        InvestigationPersistenceJsonCodec jsonCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditRepository = auditRepository;
        this.jsonCodec = jsonCodec;
    }

    void append(
        InvestigationStateMachine.State state,
        List<InvestigationEvent> events,
        long firstSequence
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Investigation event append requires a transaction.");
        }
        long sequence = firstSequence;
        for (InvestigationEvent event : events) {
            UUID eventId = eventId(state.organizationId(), state.runId(), sequence);
            String eventType = jsonCodec.eventType(event);
            String payload = jsonCodec.eventPayload(
                eventId, state.organizationId(), state.projectId(), state.incidentId(),
                state.runId(), sequence, state.actorId(), event
            );
            jdbcTemplate.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                eventId, state.organizationId(), state.projectId(), state.incidentId(), state.runId(),
                sequence, eventType, state.actorId(), Timestamp.from(jsonCodec.occurredAt(event)), payload
            );
            auditRepository.append(new AuditEvent(
                eventId, state.organizationId(), state.actorId(), eventType,
                AuditEvent.INVESTIGATION_SCHEMA_VERSION, "investigation_run",
                state.runId().toString(), state.runId(), jsonCodec.occurredAt(event), payload
            ));
            sequence++;
        }
    }

    private UUID eventId(UUID organizationId, UUID runId, long sequence) {
        return UUID.nameUUIDFromBytes(
            ("investigation:" + organizationId + ":" + runId + ":" + sequence)
                .getBytes(StandardCharsets.UTF_8)
        );
    }
}
