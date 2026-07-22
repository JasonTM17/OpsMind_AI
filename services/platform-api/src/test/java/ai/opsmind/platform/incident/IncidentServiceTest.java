package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.audit.AuditEvent;
import ai.opsmind.platform.audit.AuditRepository;
import ai.opsmind.platform.common.api.IdempotencyKey;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.messaging.EventEnvelope;
import ai.opsmind.platform.messaging.OutboxRepository;
import ai.opsmind.platform.persistence.IdempotencyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class IncidentServiceTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTOR_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID INCIDENT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OPERATION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID EVENT_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final IncidentAccessRepository access = mock(IncidentAccessRepository.class);
    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IdempotencyRepository idempotency = mock(IdempotencyRepository.class);
    private final IncidentDomainEventAppender events = mock(IncidentDomainEventAppender.class);
    private final IncidentJsonCodec json = mock(IncidentJsonCodec.class);
    private final IncidentRuntimeValues runtime = mock(IncidentRuntimeValues.class);
    private final IncidentMutationService service = new IncidentMutationService(
        transactionManager, access, incidents, idempotency, events, json, runtime
    );

    @BeforeEach
    void configureTransaction() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenReturn(transactionStatus);
        when(access.requireAccess(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID), eq(IncidentAccessMode.MUTATE)))
            .thenReturn(new IncidentActor(ACTOR_ID, "SRE", "SRE"));
        when(json.incidentBody(any())).thenReturn("{\"status\":\"OPEN\",\"version\":0}");
        when(json.cache(any())).thenReturn("{\"responseBody\":\"cached\"}");
    }

    @Test
    void createOrdersAllDurableEffectsAndCompletesIdempotencyBeforeCommit() {
        when(idempotency.claim(eq(ORGANIZATION_ID), eq(ACTOR_ID), any(), any(byte[].class)))
            .thenReturn(IdempotencyRepository.IdempotencyClaim.acquired());
        when(runtime.now()).thenReturn(NOW);
        when(runtime.newId()).thenReturn(INCIDENT_ID, OPERATION_ID, EVENT_ID);

        IncidentOperationResult result = service.create(
            principal("incident:write"), ORGANIZATION_ID, PROJECT_ID,
            new IdempotencyKey("create-001"), request(), "trace_12345678"
        );

        assertThat(result.responseStatus()).isEqualTo(201);
        assertThat(result.location()).isEqualTo(URI.create(
            "/api/v1/organizations/" + ORGANIZATION_ID + "/projects/" + PROJECT_ID
                + "/incidents/" + INCIDENT_ID
        ));
        assertThat(result.etag()).isEqualTo("\"0\"");
        assertThat(result.operationId()).isEqualTo(OPERATION_ID);

        InOrder order = inOrder(access, idempotency, incidents, events);
        order.verify(access).requireAccess(any(), eq(ORGANIZATION_ID), eq(PROJECT_ID),
            eq(IncidentAccessMode.MUTATE));
        order.verify(idempotency).claim(eq(ORGANIZATION_ID), eq(ACTOR_ID), any(), any(byte[].class));
        order.verify(incidents).insert(any(IncidentSnapshot.class));
        order.verify(events).append(any(IncidentTimelineEvent.class), eq("trace_12345678"));
        order.verify(idempotency).complete(
            eq(ORGANIZATION_ID), eq(ACTOR_ID), any(), any(byte[].class), eq(201), anyString()
        );
        verify(transactionManager).commit(transactionStatus);

        ArgumentCaptor<IncidentTimelineEvent> event = ArgumentCaptor.forClass(IncidentTimelineEvent.class);
        verify(events).append(event.capture(), eq("trace_12345678"));
        assertThat(event.getValue().eventId()).isEqualTo(EVENT_ID);
        assertThat(event.getValue().operationId()).isEqualTo(OPERATION_ID);
        assertThat(event.getValue().incidentVersion()).isZero();
    }

    @Test
    void exactReplayReturnsCachedSemanticsWithoutGeneratingOrAppendingEffects() {
        var replayed = new IncidentOperationResult(
            201, "{\"version\":0}", URI.create("/incidents/" + INCIDENT_ID), "\"0\"", OPERATION_ID
        );
        when(idempotency.claim(eq(ORGANIZATION_ID), eq(ACTOR_ID), any(), any(byte[].class)))
            .thenReturn(IdempotencyRepository.IdempotencyClaim.replay(201, "{\"cached\":true}"));
        when(json.replay(201, "{\"cached\":true}")).thenReturn(replayed);

        IncidentOperationResult result = service.create(
            principal("incident:write"), ORGANIZATION_ID, PROJECT_ID,
            new IdempotencyKey("create-replay"), request(), null
        );

        assertThat(result).isEqualTo(replayed);
        verify(incidents, never()).insert(any());
        verify(events, never()).append(any(), any());
        verify(idempotency, never()).complete(any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(runtime, never()).newId();
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void appendFailureRollsBackAndDoesNotCompleteCachedResponse() {
        when(idempotency.claim(eq(ORGANIZATION_ID), eq(ACTOR_ID), any(), any(byte[].class)))
            .thenReturn(IdempotencyRepository.IdempotencyClaim.acquired());
        when(runtime.now()).thenReturn(NOW);
        when(runtime.newId()).thenReturn(INCIDENT_ID, OPERATION_ID, EVENT_ID);
        org.mockito.Mockito.doThrow(new PlatformProblemException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "audit.persistence-unavailable",
            "Audit persistence is temporarily unavailable."
        )).when(events).append(any(), any());

        assertThatThrownBy(() -> service.create(
            principal("incident:write"), ORGANIZATION_ID, PROJECT_ID,
            new IdempotencyKey("create-rollback"), request(), null
        )).isInstanceOf(PlatformProblemException.class);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
        verify(idempotency, never()).complete(any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    void transactionInfrastructureFailureMapsToServiceUnavailable() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
            .thenThrow(new CannotCreateTransactionException("database offline"));

        assertThatThrownBy(() -> service.create(
            principal("incident:write"), ORGANIZATION_ID, PROJECT_ID,
            new IdempotencyKey("create-transaction-failure"), request(), null
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exception.code()).isEqualTo("incident.transaction-unavailable");
        });

        verify(access, never()).requireAccess(any(), any(), any(), any());
    }

    @Test
    void staleVersionRollsBackBeforeTransitionOrEvent() {
        when(idempotency.claim(eq(ORGANIZATION_ID), eq(ACTOR_ID), any(), any(byte[].class)))
            .thenReturn(IdempotencyRepository.IdempotencyClaim.acquired());
        when(incidents.findForUpdate(ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID))
            .thenReturn(java.util.Optional.of(snapshot(1, IncidentStatus.OPEN)));

        assertThatThrownBy(() -> service.transition(
            principal("incident:write"), ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID,
            new IdempotencyKey("transition-stale"), 0,
            new TransitionIncidentRequest(IncidentStatus.INVESTIGATING, "triage", null, null),
            null
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            {
                assertThat(exception.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
                assertThat(exception.code()).isEqualTo("request.if-match-stale");
            });

        verify(incidents, never()).transition(any(), any(), any(),
            org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any());
        verify(events, never()).append(any(), any());
        verify(transactionManager).rollback(transactionStatus);
    }

    @Test
    void scopeAndDatabaseRolePoliciesFailClosed() {
        assertThatThrownBy(() -> service.create(
            principal("incident:read"), ORGANIZATION_ID, PROJECT_ID,
            new IdempotencyKey("scope-denied"), request(), null
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("authorization.scope-required"));
        verify(transactionManager, never()).getTransaction(any());

        for (String role : Set.of("ADMIN", "SRE")) {
            IncidentRolePolicy.requireAllowed(role, role, IncidentAccessMode.MUTATE);
        }
        for (String role : Set.of("ADMIN", "SRE", "DEVELOPER", "SECURITY_REVIEWER", "VIEWER")) {
            IncidentRolePolicy.requireAllowed(role, role, IncidentAccessMode.READ);
        }
        for (String role : Set.of("DEVELOPER", "SECURITY_REVIEWER", "VIEWER", "AI_AGENT")) {
            assertThatThrownBy(() -> IncidentRolePolicy.requireAllowed(
                role, role, IncidentAccessMode.MUTATE
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        assertThatThrownBy(() -> IncidentRolePolicy.requireAllowed(
            "ADMIN", "VIEWER", IncidentAccessMode.MUTATE
        )).isInstanceOf(PlatformProblemException.class);
        assertThatThrownBy(() -> IncidentRolePolicy.requireAllowed(
            "AI_AGENT", "AI_AGENT", IncidentAccessMode.READ
        )).isInstanceOf(PlatformProblemException.class);
    }

    @Test
    void domainEventUsesSharedIdentityPayloadAndVersionBasedOutboxSequence() {
        IncidentTimelineRepository timelineRepository = mock(IncidentTimelineRepository.class);
        AuditRepository auditRepository = mock(AuditRepository.class);
        OutboxRepository outboxRepository = mock(OutboxRepository.class);
        IncidentJsonCodec codec = mock(IncidentJsonCodec.class);
        IncidentDomainEventAppender appender = new IncidentDomainEventAppender(
            timelineRepository, auditRepository, outboxRepository, codec
        );
        IncidentTimelineEvent event = new IncidentTimelineEvent(
            EVENT_ID, ORGANIZATION_ID, PROJECT_ID, INCIDENT_ID, 2,
            IncidentTimelineEvent.STATUS_TRANSITIONED, ACTOR_ID, OPERATION_ID, NOW,
            "mitigation complete", IncidentStatus.MITIGATING, IncidentStatus.RESOLVED,
            "root", "resolution"
        );
        byte[] digest = new byte[32];
        when(codec.timelinePayload(event)).thenReturn("{\"eventType\":\"INCIDENT_STATUS_TRANSITIONED\"}");
        when(codec.payloadDigest(anyString())).thenReturn(digest);

        appender.append(event, "trace_12345678");

        ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
        ArgumentCaptor<EventEnvelope> outbox = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(auditRepository).append(audit.capture());
        verify(outboxRepository).append(outbox.capture());
        assertThat(audit.getValue().eventId()).isEqualTo(EVENT_ID);
        assertThat(audit.getValue().operationId()).isEqualTo(OPERATION_ID);
        assertThat(audit.getValue().schemaVersion()).isEqualTo(AuditEvent.INCIDENT_SCHEMA_VERSION);
        assertThat(outbox.getValue().eventId()).isEqualTo(EVENT_ID);
        assertThat(outbox.getValue().correlationId()).isEqualTo(OPERATION_ID);
        assertThat(outbox.getValue().aggregateSequence()).isEqualTo(3);
        assertThat(outbox.getValue().payloadJson()).isEqualTo(audit.getValue().payloadJson());
    }

    private CreateIncidentRequest request() {
        return new CreateIncidentRequest("API unavailable", "5xx spike", IncidentSeverity.SEV1, "alert");
    }

    private OpsMindPrincipal principal(String scope) {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "operator-001",
            null,
            null,
            Set.of(scope)
        );
    }

    private IncidentSnapshot snapshot(long version, IncidentStatus status) {
        return new IncidentSnapshot(
            INCIDENT_ID, ORGANIZATION_ID, PROJECT_ID, "API unavailable", "5xx spike",
            IncidentSeverity.SEV1, status, null, null, ACTOR_ID, ACTOR_ID, NOW, NOW, version
        );
    }
}
