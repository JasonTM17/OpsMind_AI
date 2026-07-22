package ai.opsmind.platform.delegation;

public final class CapabilityValidationException extends RuntimeException {

    private final String code;

    public CapabilityValidationException(String code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
