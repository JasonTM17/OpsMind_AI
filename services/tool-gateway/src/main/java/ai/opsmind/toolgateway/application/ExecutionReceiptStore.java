package ai.opsmind.toolgateway.application;

import java.util.UUID;

import ai.opsmind.toolgateway.domain.ToolExecutionRequest;
import ai.opsmind.toolgateway.domain.ToolExecutionResponse;

public interface ExecutionReceiptStore {

    default boolean available() {
        return true;
    }

    Claim claim(ToolExecutionRequest request, String requestDigest);

    void complete(Lease lease, ToolExecutionResponse response);

    void abandon(Lease lease);

    enum ClaimStatus {
        CLAIMED,
        REPLAY,
        CONFLICT,
        IN_PROGRESS,
        UNAVAILABLE
    }

    record Lease(UUID executionId, String requestDigest, UUID token) {
        public Lease {
            if (executionId == null || requestDigest == null || token == null) {
                throw new IllegalArgumentException("Execution receipt lease is incomplete.");
            }
        }
    }

    record Claim(ClaimStatus status, ToolExecutionResponse response, Lease lease) {
        public Claim {
            if (status == null
                || (status == ClaimStatus.CLAIMED) != (lease != null)
                || (status == ClaimStatus.REPLAY) != (response != null)) {
                throw new IllegalArgumentException("Execution receipt claim is inconsistent.");
            }
        }

        public static Claim of(ClaimStatus status) {
            return new Claim(status, null, null);
        }

        public static Claim claimed(Lease lease) {
            return new Claim(ClaimStatus.CLAIMED, null, lease);
        }
    }
}
