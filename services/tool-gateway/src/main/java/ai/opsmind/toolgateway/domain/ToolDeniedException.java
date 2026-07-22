package ai.opsmind.toolgateway.domain;

public final class ToolDeniedException extends RuntimeException {

    private final DenialCode code;

    public ToolDeniedException(DenialCode code, String message) {
        super(message);
        this.code = code;
    }

    public ToolDeniedException(DenialCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public DenialCode code() {
        return code;
    }
}
