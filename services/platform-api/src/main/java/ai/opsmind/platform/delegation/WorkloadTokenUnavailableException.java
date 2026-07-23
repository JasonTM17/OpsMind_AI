package ai.opsmind.platform.delegation;

public final class WorkloadTokenUnavailableException extends RuntimeException {

    public WorkloadTokenUnavailableException(String message) {
        super(message);
    }

    public WorkloadTokenUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
