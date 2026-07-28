package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.opsmind.platform.common.api.RequestDigest;
import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.messaging.EventEnvelope;
import ai.opsmind.platform.messaging.OutboxLease;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

class InvestigationWorkflowStartDispatcherTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void temporalRpcRunsOutsideTransactionAndSuccessfulSettlementUsesLease() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = lease(1);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.ALLOW);
        when(transactions.acknowledgeStarted(lease, "temporal-run-1"))
            .thenReturn(InvestigationWorkflowDispatchSettlementResult.STARTED);
        AtomicBoolean calledOutsideTransaction = new AtomicBoolean();
        InvestigationWorkflowClient client = (request, digest) -> {
            calledOutsideTransaction.set(
                !TransactionSynchronizationManager.isActualTransactionActive()
            );
            return new InvestigationWorkflowClient.StartResult("temporal-run-1", false);
        };

        int handled = dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        );

        assertThat(handled).isOne();
        assertThat(calledOutsideTransaction).isTrue();
        verify(transactions).acknowledgeStarted(lease, "temporal-run-1");
    }

    @Test
    void retryableFailureReleasesBelowCeilingAndRejectsAtCeiling() {
        InvestigationWorkflowClient unavailable = (request, digest) -> {
            throw InvestigationWorkflowStartException.retryable(
                "workflow.temporal-unavailable", null
            );
        };
        InvestigationWorkflowDispatchTransactions retryTransactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease retryLease = lease(1);
        when(retryTransactions.claim(
            eq(retryLease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(retryLease));
        when(retryTransactions.preflight(
            retryLease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.ALLOW);
        when(retryTransactions.releaseRetry(
            retryLease, "workflow.temporal-unavailable", Duration.ofSeconds(1)
        )).thenReturn(InvestigationWorkflowDispatchSettlementResult.RETRY_SCHEDULED);

        assertThat(dispatcher(retryTransactions, unavailable).dispatchTenant(
            retryLease.event().organizationId()
        )).isOne();
        verify(retryTransactions).releaseRetry(
            retryLease, "workflow.temporal-unavailable", Duration.ofSeconds(1)
        );

        InvestigationWorkflowDispatchTransactions exhaustedTransactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease exhaustedLease = lease(8);
        when(exhaustedTransactions.claim(
            eq(exhaustedLease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(exhaustedLease));
        when(exhaustedTransactions.preflight(
            exhaustedLease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.ALLOW);
        when(exhaustedTransactions.reject(
            exhaustedLease, "workflow.retry-attempts-exhausted"
        )).thenReturn(InvestigationWorkflowDispatchSettlementResult.REJECTED);

        assertThat(dispatcher(exhaustedTransactions, unavailable).dispatchTenant(
            exhaustedLease.event().organizationId()
        )).isOne();
        verify(exhaustedTransactions).reject(
            exhaustedLease, "workflow.retry-attempts-exhausted"
        );
    }

    @Test
    void finalOutcomeUncertainAttemptParksForReconciliation() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = lease(8, NOW.plusMillis(1));
        InvestigationWorkflowClient client = (request, digest) -> {
            throw new RuntimeException("transport outcome is unknown");
        };
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.ALLOW);
        when(transactions.releaseRetry(
            lease,
            "workflow.reconciliation-required",
            Duration.ofMinutes(1)
        )).thenReturn(InvestigationWorkflowDispatchSettlementResult.RETRY_SCHEDULED);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        )).isOne();

        verify(transactions).releaseRetry(
            lease,
            "workflow.reconciliation-required",
            Duration.ofMinutes(1)
        );
        verify(transactions, never()).reject(any(), any());
    }

    @Test
    void ambiguousRetryWithinBudgetReachesDeterministicTemporalReconciliation() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = lease(2);
        InvestigationWorkflowClient client = mock(InvestigationWorkflowClient.class);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(
            InvestigationWorkflowDispatchPreflightDecision.AMBIGUOUS_RETRY_ALLOWED
        );
        when(client.start(any(), any())).thenReturn(
            new InvestigationWorkflowClient.StartResult("temporal-run-reconciled", true)
        );
        when(transactions.acknowledgeStarted(lease, "temporal-run-reconciled"))
            .thenReturn(InvestigationWorkflowDispatchSettlementResult.STARTED);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        )).isOne();

        verify(client).start(any(), any());
        verify(transactions).acknowledgeStarted(lease, "temporal-run-reconciled");
        verify(transactions, never()).releaseRetry(any(), any(), any());
        verify(transactions, never()).reject(any(), any());
    }

    @Test
    void corruptPayloadUsesWorkflowSettlementRejectWithoutCallingTemporal() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease corruptLease = corruptPayloadDigest(lease(1));
        InvestigationWorkflowClient client = mock(InvestigationWorkflowClient.class);
        when(transactions.claim(
            eq(corruptLease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(corruptLease));
        when(transactions.preflight(
            corruptLease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.ALLOW);
        when(transactions.reject(corruptLease, "workflow.event-payload-invalid"))
            .thenReturn(InvestigationWorkflowDispatchSettlementResult.REJECTED);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            corruptLease.event().organizationId()
        )).isOne();

        verifyNoInteractions(client);
        var settlementOrder = inOrder(transactions);
        settlementOrder.verify(transactions).preflight(corruptLease, requiredRpcWindow());
        settlementOrder.verify(transactions).reject(
            corruptLease, "workflow.event-payload-invalid"
        );
        verify(transactions, never()).releaseRetry(any(), any(), any());
    }

    @Test
    void preflightTerminalDecisionRejectsWithoutInvokingWorkflowClient() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = lease(1);
        InvestigationWorkflowClient client = mock(InvestigationWorkflowClient.class);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.DISPATCHER_INELIGIBLE);
        when(transactions.reject(lease, "workflow.dispatcher-ineligible"))
            .thenReturn(InvestigationWorkflowDispatchSettlementResult.REJECTED);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        )).isOne();

        verifyNoInteractions(client);
        verify(transactions).reject(lease, "workflow.dispatcher-ineligible");
    }

    @Test
    void shortLeaseWindowReleasesWithoutInvokingWorkflowClient() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = lease(1);
        InvestigationWorkflowClient client = mock(InvestigationWorkflowClient.class);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.LEASE_WINDOW_EXHAUSTED);
        when(transactions.releaseRetry(
            lease, "workflow.lease-window-exhausted", Duration.ofSeconds(1)
        )).thenReturn(InvestigationWorkflowDispatchSettlementResult.RETRY_SCHEDULED);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        )).isOne();

        verifyNoInteractions(client);
        verify(transactions).releaseRetry(
            lease, "workflow.lease-window-exhausted", Duration.ofSeconds(1)
        );
    }

    @Test
    void reconciliationRequiredParksUndecodablePayloadWithoutInvokingWorkflowClient() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = undecodablePayload(lease(8));
        InvestigationWorkflowClient client = mock(InvestigationWorkflowClient.class);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.RECONCILIATION_REQUIRED);
        when(transactions.releaseRetry(
            lease,
            "workflow.reconciliation-required",
            Duration.ofMinutes(1)
        )).thenReturn(InvestigationWorkflowDispatchSettlementResult.RETRY_SCHEDULED);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        )).isOne();

        verifyNoInteractions(client);
        verify(transactions).releaseRetry(
            lease,
            "workflow.reconciliation-required",
            Duration.ofMinutes(1)
        );
        verify(transactions, never()).reject(any(), any());
    }

    @Test
    void exhaustedAmbiguousRetryParksUndecodablePayloadForReconciliation() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = undecodablePayload(lease(9));
        InvestigationWorkflowClient client = mock(InvestigationWorkflowClient.class);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(
            InvestigationWorkflowDispatchPreflightDecision.AMBIGUOUS_RETRY_ALLOWED
        );
        when(transactions.releaseRetry(
            lease,
            "workflow.reconciliation-required",
            Duration.ofMinutes(1)
        )).thenReturn(InvestigationWorkflowDispatchSettlementResult.RETRY_SCHEDULED);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        )).isOne();

        verifyNoInteractions(client);
        verify(transactions).releaseRetry(
            lease,
            "workflow.reconciliation-required",
            Duration.ofMinutes(1)
        );
        verify(transactions, never()).reject(any(), any());
    }

    @Test
    void stalePreflightSkipsWorkflowClientAndLeaseMutation() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = lease(1);
        InvestigationWorkflowClient client = mock(InvestigationWorkflowClient.class);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), any()
        )).thenReturn(Optional.of(lease));
        when(transactions.preflight(
            lease, requiredRpcWindow()
        )).thenReturn(InvestigationWorkflowDispatchPreflightDecision.LEASE_LOST);

        assertThat(dispatcher(transactions, client).dispatchTenant(
            lease.event().organizationId()
        )).isZero();

        verifyNoInteractions(client);
    }

    @Test
    void terminalizerUsesTheBoundedConfiguredLimit() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        when(transactions.terminalizeUnclaimedIneligible(25)).thenReturn(3);

        assertThat(dispatcher(transactions, mock(InvestigationWorkflowClient.class))
            .terminalizeUnclaimedIneligibleStarts(25)).isEqualTo(3);

        verify(transactions).terminalizeUnclaimedIneligible(25);
    }

    private InvestigationWorkflowStartDispatcher dispatcher(
        InvestigationWorkflowDispatchTransactions transactions,
        InvestigationWorkflowClient client
    ) {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        return new InvestigationWorkflowStartDispatcher(
            transactions,
            new InvestigationWorkflowStartEventCodec(mapper),
            client,
            properties(),
            temporalProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private OutboxLease lease(int attempt) {
        return lease(attempt, NOW.plusSeconds(600));
    }

    private OutboxLease lease(int attempt, Instant deadlineAt) {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        InvestigationCommand.Start command = command(deadlineAt);
        var prepared = new InvestigationWorkflowStartEnvelopeFactory(mapper).prepare(
            command, authorized(), workflowProperties()
        );
        return new OutboxLease(
            prepared.event(), UUID.randomUUID(), NOW.plusSeconds(30), attempt
        );
    }

    private OutboxLease corruptPayloadDigest(OutboxLease originalLease) {
        EventEnvelope event = originalLease.event();
        byte[] corruptDigest = event.payloadDigest();
        corruptDigest[0] ^= 0x01;
        EventEnvelope corruptEvent = new EventEnvelope(
            event.eventId(),
            event.organizationId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateSequence(),
            event.eventType(),
            event.schemaVersion(),
            event.causationId(),
            event.correlationId(),
            event.occurredAt(),
            event.payloadJson(),
            corruptDigest
        );
        return new OutboxLease(
            corruptEvent,
            originalLease.leaseToken(),
            originalLease.leaseExpiresAt(),
            originalLease.attempt()
        );
    }

    private OutboxLease undecodablePayload(OutboxLease originalLease) {
        EventEnvelope event = originalLease.event();
        String invalidPayload = "{";
        EventEnvelope invalidEvent = new EventEnvelope(
            event.eventId(),
            event.organizationId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateSequence(),
            event.eventType(),
            event.schemaVersion(),
            event.causationId(),
            event.correlationId(),
            event.occurredAt(),
            invalidPayload,
            RequestDigest.sha256(invalidPayload.getBytes(StandardCharsets.UTF_8))
        );
        return new OutboxLease(
            invalidEvent,
            originalLease.leaseToken(),
            originalLease.leaseExpiresAt(),
            originalLease.attempt()
        );
    }

    private InvestigationCommand.Start command() {
        return command(NOW.plusSeconds(600));
    }

    private InvestigationCommand.Start command(Instant deadlineAt) {
        return new InvestigationCommand.Start(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            new InvestigationCommand.Budget(4, 2, 10, 1_000),
            NOW, deadlineAt
        );
    }

    private AuthorizedIncidentAnalysisEvidence authorized() {
        var command = command();
        return new AuthorizedIncidentAnalysisEvidence(
            command.organizationId(), command.projectId(), command.incidentId(), command.actorId(),
            "Sensitive", "Sensitive", IncidentSeverity.SEV1,
            IncidentStatus.INVESTIGATING, null, null, 7
        );
    }

    private InvestigationWorkflowProperties workflowProperties() {
        return new InvestigationWorkflowProperties(
            "temporal-test", "default", "opsmind-investigation-v1", "investigation-test"
        );
    }

    private InvestigationTemporalClientProperties temporalProperties() {
        return new InvestigationTemporalClientProperties(
            "temporal-test", "127.0.0.1:7233", false, true,
            Duration.ofSeconds(5), "test-worker", "test-build"
        );
    }

    private Duration requiredRpcWindow() {
        return properties().requiredRpcWindow(temporalProperties().rpcTimeout());
    }

    private InvestigationWorkflowStarterProperties properties() {
        return new InvestigationWorkflowStarterProperties(
            true, Duration.ofSeconds(1), Duration.ofSeconds(30),
            Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(1),
            Duration.ofMinutes(1), 8, 25, 1
        );
    }
}
