package ai.opsmind.toolgateway.application;

import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;

public final class FailClosedExecutionReceiptStore implements ExecutionReceiptStore {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Claim claim(ToolExecutionRequest request, String requestDigest) {
        return Claim.of(ClaimStatus.UNAVAILABLE);
    }

    @Override
    public void complete(Lease lease, ToolExecutionResponse response) {
        throw new IllegalStateException("Durable execution receipt storage is unavailable.");
    }

    @Override
    public void abandon(Lease lease) {
        // No claim was accepted by the unavailable store.
    }
}
