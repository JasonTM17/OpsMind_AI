package ai.opsmind.platform.incident;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class IncidentTimelinePageToken {

    private static final String VERSION_PREFIX = "v1:";

    Long decode(String token, UUID expectedIncidentId) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() > 512) {
            throw invalidToken();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] fields = decoded.split(":", -1);
            if (fields.length != 3 || !"v1".equals(fields[0])
                || !expectedIncidentId.equals(UUID.fromString(fields[1]))) {
                throw invalidToken();
            }
            long incidentVersion = Long.parseLong(fields[2]);
            if (incidentVersion < 0) {
                throw invalidToken();
            }
            return incidentVersion;
        }
        catch (IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    String encode(UUID incidentId, long incidentVersion) {
        if (incidentId == null || incidentVersion < 0) {
            throw new IllegalArgumentException("Timeline cursor is invalid.");
        }
        String value = VERSION_PREFIX + incidentId + ":" + incidentVersion;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private PlatformProblemException invalidToken() {
        return new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            "pagination.invalid-token",
            "The page token is invalid or expired."
        );
    }
}
