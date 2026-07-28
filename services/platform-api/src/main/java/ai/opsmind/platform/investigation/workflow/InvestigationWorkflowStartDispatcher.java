package ai.opsmind.platform.investigation.workflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
    private final InvestigationTemporalClientProperties clientProperties;
    private final Clock clock;

    public InvestigationWorkflowStartDispatcher(
        InvestigationWorkflowDispatchTransactions transactions,
        InvestigationWorkflowStartEventCodec eventCodec,
        InvestigationWorkflowClient workflowClient,
        InvestigationWorkflowStarterProperties properties,
        InvestigationTemporalClientProperties clientProperties,
        Clock clock
    ) {
        this.transactions = transactions;
        this.eventCodec = eventCodec;
        this.workflowClient = workflowClient;
        this.properties = properties;
        this.clientProperties = clientProperties;
        this.clock = clock;
    }

    public int dispatchTenant(UUID organizationId) {
        Duration requiredRpcWindow = properties.requiredRpcWindow(clientProperties.rpcTimeout());
        Instant claimTime = Instant.now(clock);
        return transactions.claim(
            organizationId, UUID.randomUUID(), claimTime, properties
        ).filter(lease -> process(lease, requiredRpcWindow)).map(lease -> 1).orElse(0);
    }

    public int terminalizeUnclaimedIneligibleStarts(int limit) {
        properties.validate();
        return transactions.terminalizeUnclaimedIneligible(limit);
    }

    private boolean process(OutboxLease lease, Duration requiredRpcWindow) {
        DecodedStart decoded;
        try {
            decoded = eventCodec.decode(lease.event());
        }
        catch (InvestigationWorkflowStartException invalid) {
            return transactions.reject(lease, invalid.code()).handled();
        }
        InvestigationWorkflowDispatchPreflightDecision preflight = transactions.preflight(
            lease, requiredRpcWindow
        );
        if (preflight == InvestigationWorkflowDispatchPreflightDecision.LEASE_LOST) {
            return false;
        }
        if (preflight.retryWithoutRpc()) {
            return handleFailure(
                lease,
                decoded.request(),
                InvestigationWorkflowStartException.retryable(preflight.code(), null)
            );
        }
        if (preflight.rejectWithoutRpc()) {
            return transactions.reject(lease, preflight.code()).handled();
        }
        requireNoDatabaseTransaction();
        try {
            InvestigationWorkflowClient.StartResult result = workflowClient.start(
                decoded.request(), decoded.startPayloadDigest()
            );
            return transactions.acknowledgeStarted(
                lease, result.temporalRunId()
            ).handled();
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
        Duration retryDelay = properties.retryDelay(lease.attempt());
        Instant retryAt = failedAt.plus(retryDelay);
        Instant ageLimit = lease.event().occurredAt().plus(properties.maximumAge());
        Instant deadlineLimit = request.deadlineAt().minus(properties.rpcSafetyMargin());
        boolean retry = exception.retryable()
            && lease.attempt() < properties.maximumAttempts()
            && retryAt.isBefore(ageLimit)
            && retryAt.isBefore(deadlineLimit);
        if (retry) {
            return transactions.releaseRetry(
                lease, exception.code(), retryDelay
            ).handled();
        }
        return transactions.reject(
            lease,
            terminalCode(exception, lease, failedAt, ageLimit)
        ).handled();
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

    private void requireNoDatabaseTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Temporal RPC cannot run inside a database transaction.");
        }
    }
}
