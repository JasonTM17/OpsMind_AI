package ai.opsmind.toolgateway.application;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ai.opsmind.toolgateway.domain.ToolExecutionResponse;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

/** Process-local execution receipts for deterministic fixture tests only. */
public final class FixtureExecutionReceiptStore implements ExecutionReceiptStore {

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Claim claim(ToolExecutionRequest request, String requestDigest) {
        UUID token = UUID.randomUUID();
        Entry candidate = new Entry(requestDigest, token, null);
        Entry current = entries.putIfAbsent(request.executionId(), candidate);
        if (current == null) {
            return Claim.claimed(new Lease(request.executionId(), requestDigest, token));
        }
        if (!current.requestDigest().equals(requestDigest)) return Claim.of(ClaimStatus.CONFLICT);
        if (current.response() == null) return Claim.of(ClaimStatus.IN_PROGRESS);
        return new Claim(ClaimStatus.REPLAY, current.response(), null);
    }

    @Override
    public void complete(Lease lease, ToolExecutionResponse response) {
        boolean replaced = entries.replace(
            lease.executionId(),
            new Entry(lease.requestDigest(), lease.token(), null),
            new Entry(lease.requestDigest(), null, response)
        );
        if (!replaced) throw new IllegalStateException("Execution receipt claim was lost.");
    }

    @Override
    public void abandon(Lease lease) {
        entries.remove(
            lease.executionId(),
            new Entry(lease.requestDigest(), lease.token(), null)
        );
    }

    private record Entry(String requestDigest, UUID leaseToken, ToolExecutionResponse response) { }
}
