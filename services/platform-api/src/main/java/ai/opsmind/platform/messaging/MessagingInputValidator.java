package ai.opsmind.platform.messaging;

import java.util.UUID;
import java.util.regex.Pattern;

final class MessagingInputValidator {

    private static final Pattern CONSUMER_PATTERN = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"
    );
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile(
        "[a-z0-9][a-z0-9._-]{0,159}"
    );

    private MessagingInputValidator() {
    }

    static void requireIdentity(UUID organizationId, UUID eventId) {
        if (organizationId == null || eventId == null) {
            throw new IllegalArgumentException("Organization and event identity are required.");
        }
    }

    static String requireConsumer(String consumer) {
        if (consumer == null || !CONSUMER_PATTERN.matcher(consumer).matches()) {
            throw new IllegalArgumentException("Inbox consumer is invalid.");
        }
        return consumer;
    }

    static String requireErrorCode(String errorCode) {
        if (errorCode == null || !ERROR_CODE_PATTERN.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("Messaging error code is invalid.");
        }
        return errorCode;
    }
}
