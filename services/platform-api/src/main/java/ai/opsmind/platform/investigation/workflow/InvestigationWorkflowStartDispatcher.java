package ai.opsmind.platform.investigation.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.investigation.workflow.InvestigationWorkflowStartEventCodec.DecodedStart;
import ai.opsmind.platform.messaging.OutboxLease;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-starter",
    name = "enabled",
    havingValue = "true"
)
public final class InvestigationWorkflowStartDispatcher {

    private final InvestigationWorkflowDispatchTransactions transactions;
    private final InvestigationWorkflowStartEventCodec eventCodec;
    private final InvestigationWorkflowClient workflowClient;
    private final InvestigationWorkflowStarterProperties properties;
    private final Clock clock;

    public InvestigationWorkflowStartDispatcher(
        InvestigationWorkflowDispatchTransactions transactions,
        InvestigationWorkflowStartEventCodec eventCodec,
        InvestigationWorkflowClient workflowClient,
        InvestigationWorkflowStarterProperties properties,
        Clock clock
    ) {
        this.transactions = transactions;
        this.eventCodec = eventCodec;
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.clock = clock;
    }

    public int dispatchTenant(UUID organizationId) {
        properties.validate();
        Instant claimTime = Instant.now(clock);
        List<OutboxLease> leases = transactions.claim(
            organizationId, UUID.randomUUID(), claimTime, properties
        );
        int handled = 0;
        for (OutboxLease lease : leases) {
            if (process(lease)) handled++;
        }
        return handled;
    }

    private boolean process(OutboxLease lease) {
        DecodedStart decoded;
        try {
            decoded = eventCodec.decode(lease.event());
        }
        catch (InvestigationWorkflowStartException invalid) {
            return rejectIfLive(lease, invalid.code());
        }
        if (!transactions.leaseIsLive(lease)) return false;
        requireNoDatabaseTransaction();
        try {
            InvestigationWorkflowClient.StartResult result = workflowClient.start(
                decoded.request(), decoded.startPayloadDigest()
            );
            transactions.acknowledgeStarted(
                lease, result.temporalRunId(), Instant.now(clock)
            );
            return true;
        }
        catch (InvestigationWorkflowStartException exception) {
            return handleFailure(lease, decoded.request(), exception);
        }
        catch (RuntimeException unexpected) {
            return handleFailure(
                lease,
                decoded.request(),
                InvestigationWorkflowStartException.retryable(
                    "workflow.starter-internal", unexpected
                )
            );
        }
    }

    private boolean handleFailure(
        OutboxLease lease,
        InvestigationWorkflowStartRequest request,
        InvestigationWorkflowStartException exception
    ) {
        Instant failedAt = Instant.now(clock);
        Instant retryAt = failedAt.plus(properties.retryDelay(lease.attempt()));
        Instant ageLimit = lease.event().occurredAt().plus(properties.maximumAge());
        Instant deadlineLimit = request.deadlineAt().minus(properties.rpcSafetyMargin());
        boolean retry = exception.retryable()
            && lease.attempt() < properties.maximumAttempts()
            && retryAt.isBefore(ageLimit)
            && retryAt.isBefore(deadlineLimit);
        if (retry) {
            return transactions.releaseRetry(
                lease, exception.code(), failedAt, retryAt
            );
        }
        transactions.reject(
            lease,
            terminalCode(exception, lease, failedAt, ageLimit),
            failedAt
        );
        return true;
    }

    private String terminalCode(
        InvestigationWorkflowStartException exception,
        OutboxLease lease,
        Instant failedAt,
        Instant ageLimit
    ) {
        if (!exception.retryable()) return exception.code();
        if (lease.attempt() >= properties.maximumAttempts()) {
            return "workflow.retry-attempts-exhausted";
        }
        if (!failedAt.isBefore(ageLimit)) return "workflow.retry-age-exhausted";
        return "workflow.deadline-exhausted";
    }

    private boolean rejectIfLive(OutboxLease lease, String errorCode) {
        if (!transactions.leaseIsLive(lease)) return false;
        transactions.reject(lease, errorCode, Instant.now(clock));
        return true;
    }

    private void requireNoDatabaseTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Temporal RPC cannot run inside a database transaction.");
        }
    }
}
