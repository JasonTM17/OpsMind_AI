package ai.opsmind.platform.common.api;

import org.springframework.http.HttpStatus;

/** Strict HTTP If-Match and version-update conventions for mutable resources. */
public final class OptimisticConcurrency {

    private OptimisticConcurrency() {
    }

    public static long requireIfMatch(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new PlatformProblemException(
                HttpStatus.PRECONDITION_REQUIRED,
                "request.if-match-required",
                "If-Match is required for this mutable resource."
            );
        }
        if (headerValue.length() < 3 || headerValue.charAt(0) != '"'
            || headerValue.charAt(headerValue.length() - 1) != '"'
            || headerValue.indexOf(',') >= 0
            || headerValue.startsWith("W/")) {
            throw invalidIfMatch();
        }
        String version = headerValue.substring(1, headerValue.length() - 1);
        if (version.isBlank() || (version.length() > 1 && version.charAt(0) == '+')
            || (version.length() > 1 && version.charAt(0) == '-')) {
            throw invalidIfMatch();
        }
        try {
            long parsed = Long.parseLong(version);
            if (parsed < 0) {
                throw invalidIfMatch();
            }
            return parsed;
        }
        catch (NumberFormatException exception) {
            throw invalidIfMatch();
        }
    }

    public static String etag(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("Resource version cannot be negative.");
        }
        return "\"" + version + "\"";
    }

    public static void requireExactlyOneUpdated(int updatedRows) {
        if (updatedRows != 1) {
            throw new PlatformProblemException(
                HttpStatus.CONFLICT,
                "resource.version-conflict",
                "The resource changed before this operation was applied."
            );
        }
    }

    public static void requireCurrentVersion(long currentVersion, long expectedVersion) {
        if (currentVersion != expectedVersion) {
            throw new PlatformProblemException(
                HttpStatus.PRECONDITION_FAILED,
                "request.if-match-stale",
                "If-Match does not equal the current resource version."
            );
        }
    }

    private static PlatformProblemException invalidIfMatch() {
        return new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            "request.if-match-invalid",
            "If-Match must contain one strong non-negative resource version."
        );
    }
}
