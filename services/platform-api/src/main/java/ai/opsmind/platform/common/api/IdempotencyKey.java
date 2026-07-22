package ai.opsmind.platform.common.api;

/**
 * Validated idempotency key from the HTTP boundary. Keys are intentionally
 * opaque; they are never interpreted as a UUID or used as a log field.
 */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 128;

    public IdempotencyKey {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH
            || !isVisibleAscii(value) || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Idempotency key is invalid.");
        }
    }

    public static IdempotencyKey parse(String rawValue) {
        try {
            return new IdempotencyKey(rawValue);
        }
        catch (IllegalArgumentException exception) {
            throw new PlatformProblemException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "request.idempotency-key-invalid",
                "The Idempotency-Key header is invalid."
            );
        }
    }

    private static boolean isVisibleAscii(String value) {
        return value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }
}
