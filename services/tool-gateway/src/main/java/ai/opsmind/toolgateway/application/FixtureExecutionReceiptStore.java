package ai.opsmind.toolgateway.application;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import ai.opsmind.toolgateway.domain.ToolExecutionResponse;

/** Process-local execution receipts for deterministic fixture tests only. */
public final class FixtureExecutionReceiptStore implements ExecutionReceiptStore {

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Claim claim(UUID executionId, String requestDigest) {
        Entry candidate = new Entry(requestDigest, null);
        Entry current = entries.putIfAbsent(executionId, candidate);
        if (current == null) return Claim.of(ClaimStatus.CLAIMED);
        if (!current.requestDigest().equals(requestDigest)) return Claim.of(ClaimStatus.CONFLICT);
        if (current.response() == null) return Claim.of(ClaimStatus.IN_PROGRESS);
        return new Claim(ClaimStatus.REPLAY, current.response());
    }

    @Override
    public void complete(UUID executionId, String requestDigest, ToolExecutionResponse response) {
        boolean replaced = entries.replace(
            executionId,
            new Entry(requestDigest, null),
            new Entry(requestDigest, response)
        );
        if (!replaced) throw new IllegalStateException("Execution receipt claim was lost.");
    }

    @Override
    public void abandon(UUID executionId, String requestDigest) {
        entries.remove(executionId, new Entry(requestDigest, null));
    }

    private record Entry(String requestDigest, ToolExecutionResponse response) { }
}
