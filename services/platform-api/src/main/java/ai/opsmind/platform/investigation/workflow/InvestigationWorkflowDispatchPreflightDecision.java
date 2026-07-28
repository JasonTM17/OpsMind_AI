package ai.opsmind.platform.investigation.workflow;

import java.util.Arrays;

enum InvestigationWorkflowDispatchPreflightDecision {
    ALLOW("workflow.preflight-allowed", false, false),
    AMBIGUOUS_RETRY_ALLOWED("workflow.ambiguous-retry-allowed", false, false),
    LEASE_LOST("workflow.lease-lost", false, false),
    RECONCILIATION_REQUIRED("workflow.reconciliation-required", false, false),
    AUTHORIZATION_REVOKED("workflow.authorization-revoked", true, false),
    DEADLINE_EXHAUSTED("workflow.deadline-exhausted", true, false),
    LEASE_WINDOW_EXHAUSTED("workflow.lease-window-exhausted", false, true),
    DISPATCHER_INELIGIBLE("workflow.dispatcher-ineligible", true, false);

    private final String code;
    private final boolean rejectWithoutRpc;
    private final boolean retryWithoutRpc;

    InvestigationWorkflowDispatchPreflightDecision(
        String code,
        boolean rejectWithoutRpc,
        boolean retryWithoutRpc
    ) {
        this.code = code;
        this.rejectWithoutRpc = rejectWithoutRpc;
        this.retryWithoutRpc = retryWithoutRpc;
    }

    String code() {
        return code;
    }

    boolean rejectWithoutRpc() {
        return rejectWithoutRpc;
    }

    boolean retryWithoutRpc() {
        return retryWithoutRpc;
    }

    boolean reconciliationRequired() {
        return this == RECONCILIATION_REQUIRED;
    }

    boolean ambiguousRetryAllowed() {
        return this == AMBIGUOUS_RETRY_ALLOWED;
    }

    static InvestigationWorkflowDispatchPreflightDecision fromCode(String code) {
        return Arrays.stream(values())
            .filter(decision -> decision.code.equals(code))
            .findFirst()
            .orElseThrow(() ->
                new IllegalArgumentException("Unknown workflow preflight decision: " + code)
            );
    }
}
