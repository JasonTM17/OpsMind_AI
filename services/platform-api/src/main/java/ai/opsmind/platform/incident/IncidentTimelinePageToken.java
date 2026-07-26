package ai.opsmind.platform.incident;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class IncidentTimelinePageToken {

    private static final String LEGACY_VERSION_PREFIX = "v1:";
    private static final String ACTIVITY_VERSION_PREFIX = "v2:";
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MAX_EPOCH_MICROS = 253_402_300_799_999_999L;

    Long decode(String token, UUID expectedIncidentId) {
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
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
        String value = LEGACY_VERSION_PREFIX + incidentId + ":" + incidentVersion;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    ActivityCursor decodeActivity(String token, UUID expectedIncidentId) {
        if (expectedIncidentId == null) {
            throw invalidToken();
        }
        if (token == null) {
            return null;
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw invalidToken();
        }
        if (token.isBlank()) {
            throw invalidToken();
        }

        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token);
            String canonicalToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            if (!canonicalToken.equals(token)) {
                throw new IllegalArgumentException("Non-canonical page token.");
            }

            String decoded = decodeCanonicalUtf8(tokenBytes);
            String[] fields = decoded.split(":", -1);
            if (fields.length != 5 || !"v2".equals(fields[0])) {
                throw new IllegalArgumentException("Invalid activity token shape.");
            }

            UUID incidentId = parseCanonicalUuid(fields[1]);
            if (!expectedIncidentId.equals(incidentId)) {
                throw new IllegalArgumentException("Activity token belongs to another incident.");
            }

            long epochMicros = Long.parseLong(fields[2]);
            int sourceRank = Integer.parseInt(fields[3]);
            if (!Long.toString(epochMicros).equals(fields[2])
                || !Integer.toString(sourceRank).equals(fields[3])
                || epochMicros < 0
                || epochMicros > MAX_EPOCH_MICROS
                || !isSourceRank(sourceRank)) {
                throw new IllegalArgumentException("Activity token values are invalid.");
            }

            UUID eventId = parseCanonicalUuid(fields[4]);
            Instant occurredAt = Instant.ofEpochSecond(
                epochMicros / MICROS_PER_SECOND,
                (epochMicros % MICROS_PER_SECOND) * 1_000L
            );
            return new ActivityCursor(occurredAt, sourceRank, eventId);
        }
        catch (CharacterCodingException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    String encodeActivity(
        UUID incidentId,
        Instant occurredAt,
        int sourceRank,
        UUID eventId
    ) {
        if (incidentId == null
            || eventId == null
            || !isCanonicalActivityTime(occurredAt)
            || !isSourceRank(sourceRank)) {
            throw new IllegalArgumentException("Activity timeline cursor is invalid.");
        }

        long epochMicros;
        try {
            epochMicros = Math.addExact(
                Math.multiplyExact(occurredAt.getEpochSecond(), MICROS_PER_SECOND),
                occurredAt.getNano() / 1_000L
            );
        }
        catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Activity timeline cursor is invalid.", exception);
        }

        String value = ACTIVITY_VERSION_PREFIX
            + incidentId
            + ":"
            + epochMicros
            + ":"
            + sourceRank
            + ":"
            + eventId;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCanonicalUtf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString();
    }

    private UUID parseCanonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("UUID is not canonical.");
        }
        return parsed;
    }

    private boolean isCanonicalActivityTime(Instant occurredAt) {
        if (occurredAt == null || occurredAt.getNano() % 1_000 != 0) {
            return false;
        }
        long epochSecond = occurredAt.getEpochSecond();
        return epochSecond >= 0 && epochSecond <= MAX_EPOCH_MICROS / MICROS_PER_SECOND;
    }

    private boolean isSourceRank(int sourceRank) {
        return sourceRank == 0 || sourceRank == 1;
    }

    private PlatformProblemException invalidToken() {
        return new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            "pagination.invalid-token",
            "The page token is invalid or expired."
        );
    }

    record ActivityCursor(Instant occurredAt, int sourceRank, UUID eventId) {
    }
}
