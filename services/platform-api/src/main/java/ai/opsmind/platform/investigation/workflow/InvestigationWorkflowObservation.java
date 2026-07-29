package ai.opsmind.platform.investigation.workflow;

public record InvestigationWorkflowObservation(
    Outcome outcome,
    String firstRunId,
    String safeCode
) {
    private static final String SAFE_CODE_PATTERN =
        "[a-z0-9][a-z0-9._-]{0,127}";

    public enum Outcome {
        MATCH,
        ABSENT,
        MISMATCH,
        RETRY,
        BLOCKED
    }

    public InvestigationWorkflowObservation {
        if (outcome == null) {
            throw new IllegalArgumentException("Workflow observation outcome is invalid.");
        }
        boolean match = outcome == Outcome.MATCH;
        boolean validFirstRunId = firstRunId != null
            && !firstRunId.isBlank()
            && firstRunId.length() <= 255;
        if ((match && !validFirstRunId) || (!match && firstRunId != null)) {
            throw new IllegalArgumentException("Only a matching observation has a first run ID.");
        }
        if ((match && safeCode != null)
            || (!match && (
                safeCode == null || !safeCode.matches(SAFE_CODE_PATTERN)
            ))) {
            throw new IllegalArgumentException(
                "Only a matching observation omits the settlement code."
            );
        }
    }

    public static InvestigationWorkflowObservation match(String firstRunId) {
        return new InvestigationWorkflowObservation(Outcome.MATCH, firstRunId, null);
    }

    public static InvestigationWorkflowObservation absent() {
        return new InvestigationWorkflowObservation(
            Outcome.ABSENT, null, "workflow.temporal-start-not-found"
        );
    }

    public static InvestigationWorkflowObservation mismatch() {
        return new InvestigationWorkflowObservation(
            Outcome.MISMATCH, null, "workflow.existing-contract-mismatch"
        );
    }

    public static InvestigationWorkflowObservation retry(String safeCode) {
        return new InvestigationWorkflowObservation(Outcome.RETRY, null, safeCode);
    }

    public static InvestigationWorkflowObservation blocked(String safeCode) {
        return new InvestigationWorkflowObservation(Outcome.BLOCKED, null, safeCode);
    }
}
