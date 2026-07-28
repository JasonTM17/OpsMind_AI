package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.opsmind.platform.incident.AuthorizedIncidentAnalysisEvidence;
import ai.opsmind.platform.incident.IncidentSeverity;
import ai.opsmind.platform.incident.IncidentStatus;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.messaging.OutboxLease;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.json.JsonMapper;

class InvestigationWorkflowStartDispatcherTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void temporalRpcRunsOutsideTransactionAndSuccessfulAckUsesLease() {
        InvestigationWorkflowDispatchTransactions transactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease lease = lease(1);
        when(transactions.claim(
            eq(lease.event().organizationId()), any(), eq(NOW), any()
        )).thenReturn(List.of(lease));
        when(transactions.leaseIsLive(lease)).thenReturn(true);
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
        verify(transactions).acknowledgeStarted(
            lease, "temporal-run-1", NOW
        );
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
            eq(retryLease.event().organizationId()), any(), eq(NOW), any()
        )).thenReturn(List.of(retryLease));
        when(retryTransactions.leaseIsLive(retryLease)).thenReturn(true);
        when(retryTransactions.releaseRetry(
            retryLease, "workflow.temporal-unavailable", NOW, NOW.plusSeconds(1)
        )).thenReturn(true);

        assertThat(dispatcher(retryTransactions, unavailable).dispatchTenant(
            retryLease.event().organizationId()
        )).isOne();
        verify(retryTransactions).releaseRetry(
            retryLease, "workflow.temporal-unavailable", NOW, NOW.plusSeconds(1)
        );

        InvestigationWorkflowDispatchTransactions exhaustedTransactions =
            mock(InvestigationWorkflowDispatchTransactions.class);
        OutboxLease exhaustedLease = lease(8);
        when(exhaustedTransactions.claim(
            eq(exhaustedLease.event().organizationId()), any(), eq(NOW), any()
        )).thenReturn(List.of(exhaustedLease));
        when(exhaustedTransactions.leaseIsLive(exhaustedLease)).thenReturn(true);

        assertThat(dispatcher(exhaustedTransactions, unavailable).dispatchTenant(
            exhaustedLease.event().organizationId()
        )).isOne();
        verify(exhaustedTransactions).reject(
            exhaustedLease, "workflow.retry-attempts-exhausted", NOW
        );
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
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private OutboxLease lease(int attempt) {
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        InvestigationCommand.Start command = command();
        var prepared = new InvestigationWorkflowStartEnvelopeFactory(mapper).prepare(
            command, authorized(), workflowProperties()
        );
        return new OutboxLease(
            prepared.event(), UUID.randomUUID(), NOW.plusSeconds(30), attempt
        );
    }

    private InvestigationCommand.Start command() {
        return new InvestigationCommand.Start(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            new InvestigationCommand.Budget(4, 2, 10, 1_000),
            NOW, NOW.plusSeconds(600)
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

    private InvestigationWorkflowStarterProperties properties() {
        return new InvestigationWorkflowStarterProperties(
            true, Duration.ofSeconds(1), Duration.ofSeconds(30),
            Duration.ofSeconds(5), Duration.ofHours(1), Duration.ofSeconds(1),
            Duration.ofMinutes(1), 8, 25, 10
        );
    }
}
