package ai.opsmind.platform.investigation.workflow;

public final class InvestigationWorkflowStartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;
    private final boolean outcomeUncertain;

    private InvestigationWorkflowStartException(
        String code,
        boolean retryable,
        boolean outcomeUncertain,
        Throwable cause
    ) {
        super(code, cause);
        this.code = code;
        this.retryable = retryable;
        this.outcomeUncertain = outcomeUncertain;
    }

    public static InvestigationWorkflowStartException retryable(String code, Throwable cause) {
        return new InvestigationWorkflowStartException(code, true, false, cause);
    }

    public static InvestigationWorkflowStartException outcomeUncertain(
        String code,
        Throwable cause
    ) {
        return new InvestigationWorkflowStartException(code, true, true, cause);
    }

    public static InvestigationWorkflowStartException permanent(String code, Throwable cause) {
        return new InvestigationWorkflowStartException(code, false, false, cause);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean outcomeUncertain() {
        return outcomeUncertain;
    }
}
