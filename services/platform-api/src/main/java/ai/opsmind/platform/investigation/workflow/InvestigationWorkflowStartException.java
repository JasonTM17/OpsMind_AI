package ai.opsmind.platform.investigation.workflow;

public final class InvestigationWorkflowStartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;

    private InvestigationWorkflowStartException(String code, boolean retryable, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public static InvestigationWorkflowStartException retryable(String code, Throwable cause) {
        return new InvestigationWorkflowStartException(code, true, cause);
    }

    public static InvestigationWorkflowStartException permanent(String code, Throwable cause) {
        return new InvestigationWorkflowStartException(code, false, cause);
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
