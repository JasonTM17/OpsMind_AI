package ai.opsmind.toolgateway.application;

import java.util.UUID;

import ai.opsmind.toolgateway.domain.ToolExecutionResponse;

public interface ExecutionReceiptStore {

    default boolean available() {
        return true;
    }

    Claim claim(UUID executionId, String requestDigest);

    void complete(UUID executionId, String requestDigest, ToolExecutionResponse response);

    void abandon(UUID executionId, String requestDigest);

    enum ClaimStatus {
        CLAIMED,
        REPLAY,
        CONFLICT,
        IN_PROGRESS,
        UNAVAILABLE
    }

    record Claim(ClaimStatus status, ToolExecutionResponse response) {
        public static Claim of(ClaimStatus status) {
            return new Claim(status, null);
        }
    }
}
