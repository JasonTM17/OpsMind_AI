package ai.opsmind.toolgateway.application;

import java.util.UUID;

import ai.opsmind.toolgateway.domain.ToolExecutionResponse;

public final class FailClosedExecutionReceiptStore implements ExecutionReceiptStore {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Claim claim(UUID executionId, String requestDigest) {
        return Claim.of(ClaimStatus.UNAVAILABLE);
    }

    @Override
    public void complete(UUID executionId, String requestDigest, ToolExecutionResponse response) {
        throw new IllegalStateException("Durable execution receipt storage is unavailable.");
    }

    @Override
    public void abandon(UUID executionId, String requestDigest) {
        // No claim was accepted by the unavailable store.
    }
}
