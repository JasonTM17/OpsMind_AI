package ai.opsmind.platform.investigation.workflow;

import java.util.Arrays;

enum InvestigationWorkflowDispatchSettlementResult {
    STARTED("workflow.started"),
    RETRY_SCHEDULED("workflow.retry-scheduled"),
    REJECTED("workflow.rejected"),
    LEASE_LOST("workflow.lease-lost");

    private final String code;

    InvestigationWorkflowDispatchSettlementResult(String code) {
        this.code = code;
    }

    static InvestigationWorkflowDispatchSettlementResult fromCode(String code) {
        return Arrays.stream(values())
            .filter(result -> result.code.equals(code))
            .findFirst()
            .orElseThrow(() ->
                new IllegalArgumentException("Unknown workflow settlement result: " + code)
            );
    }

    boolean handled() {
        return this != LEASE_LOST;
    }
}
