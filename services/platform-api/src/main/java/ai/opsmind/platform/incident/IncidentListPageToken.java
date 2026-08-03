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
final class IncidentListPageToken {

    private static final String VERSION = "v1";
    private static final String NO_STATUS = "*";
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MAX_EPOCH_MICROS = 253_402_300_799_999_999L;

    Claims parse(String token) {
        if (token == null) {
            return null;
        }
        if (token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw invalidToken();
        }
        try {
            byte[] tokenBytes = Base64.getUrlDecoder().decode(token);
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            if (!canonical.equals(token)) {
                throw new IllegalArgumentException("Non-canonical page token.");
            }
            String[] fields = decodeCanonicalUtf8(tokenBytes).split(":", -1);
            if (fields.length != 7 || !VERSION.equals(fields[0])) {
                throw new IllegalArgumentException("Invalid incident list token shape.");
            }
            UUID organizationId = parseCanonicalUuid(fields[1]);
            UUID projectId = parseCanonicalUuid(fields[2]);
            IncidentStatus status = parseStatus(fields[3]);
            long epochMicros = parseCanonicalLong(fields[4]);
            if (epochMicros < 0 || epochMicros > MAX_EPOCH_MICROS) {
                throw new IllegalArgumentException("Incident list token time is invalid.");
            }
            if (!"updated-desc-id-desc-v1".equals(fields[5])) {
                throw new IllegalArgumentException("Incident list token sort is invalid.");
            }
            UUID incidentId = parseCanonicalUuid(fields[6]);
            Instant updatedAt = Instant.ofEpochSecond(
                epochMicros / MICROS_PER_SECOND,
                (epochMicros % MICROS_PER_SECOND) * 1_000L
            );
            return new Claims(organizationId, projectId, status, updatedAt, incidentId);
        }
        catch (CharacterCodingException | IllegalArgumentException exception) {
            throw invalidToken();
        }
    }

    Cursor bind(
        Claims claims,
        UUID expectedOrganizationId,
        UUID expectedProjectId,
        IncidentStatus expectedStatus
    ) {
        if (claims == null) {
            return null;
        }
        if (!claims.organizationId().equals(expectedOrganizationId)
            || !claims.projectId().equals(expectedProjectId)
            || claims.status() != expectedStatus) {
            throw invalidToken();
        }
        return new Cursor(claims.updatedAt(), claims.incidentId());
    }

    String encode(
        UUID organizationId,
        UUID projectId,
        IncidentStatus status,
        Instant updatedAt,
        UUID incidentId
    ) {
        if (organizationId == null || projectId == null || incidentId == null
            || !isCanonicalTime(updatedAt)) {
            throw new IllegalArgumentException("Incident list cursor is invalid.");
        }
        long epochMicros;
        try {
            epochMicros = Math.addExact(
                Math.multiplyExact(updatedAt.getEpochSecond(), MICROS_PER_SECOND),
                updatedAt.getNano() / 1_000L
            );
        }
        catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Incident list cursor is invalid.", exception);
        }
        String value = VERSION + ":" + organizationId + ":" + projectId + ":"
            + (status == null ? NO_STATUS : status.name()) + ":" + epochMicros
            + ":updated-desc-id-desc-v1:" + incidentId;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private IncidentStatus parseStatus(String value) {
        return NO_STATUS.equals(value) ? null : IncidentStatus.valueOf(value);
    }

    private long parseCanonicalLong(String value) {
        long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value)) {
            throw new IllegalArgumentException("Number is not canonical.");
        }
        return parsed;
    }

    private UUID parseCanonicalUuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new IllegalArgumentException("UUID is not canonical.");
        }
        return parsed;
    }

    private String decodeCanonicalUtf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString();
    }

    private boolean isCanonicalTime(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) {
            return false;
        }
        long epochSecond = value.getEpochSecond();
        return epochSecond >= 0 && epochSecond <= MAX_EPOCH_MICROS / MICROS_PER_SECOND;
    }

    private PlatformProblemException invalidToken() {
        return new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            "pagination.invalid-token",
            "The page token is invalid or expired."
        );
    }

    record Claims(
        UUID organizationId,
        UUID projectId,
        IncidentStatus status,
        Instant updatedAt,
        UUID incidentId
    ) {
    }

    record Cursor(Instant updatedAt, UUID incidentId) {
    }
}
