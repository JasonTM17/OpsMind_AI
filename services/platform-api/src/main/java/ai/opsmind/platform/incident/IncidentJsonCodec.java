package ai.opsmind.platform.incident;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.common.api.RequestDigest;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
final class IncidentJsonCodec {

    private final ObjectMapper objectMapper;

    IncidentJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String incidentBody(IncidentSnapshot incident) {
        return write(IncidentResponse.from(incident));
    }

    String timelinePayload(IncidentTimelineEvent event) {
        return write(event);
    }

    byte[] payloadDigest(String payloadJson) {
        return RequestDigest.sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
    }

    String cache(IncidentOperationResult response) {
        return write(new CachedMutation(
            response.responseBody(),
            response.location() == null ? null : response.location().toString(),
            response.etag(),
            response.operationId()
        ));
    }

    IncidentOperationResult replay(int responseStatus, String cachedJson) {
        try {
            CachedMutation cached = objectMapper.readValue(cachedJson, CachedMutation.class);
            if ((responseStatus != 200 && responseStatus != 201)
                || cached == null || cached.responseBody() == null || cached.etag() == null
                || cached.operationId() == null) {
                throw invalidCache(null);
            }
            URI location = cached.location() == null ? null : URI.create(cached.location());
            if (responseStatus == 201 && location == null) {
                throw invalidCache(null);
            }
            return new IncidentOperationResult(
                responseStatus,
                cached.responseBody(),
                location,
                cached.etag(),
                cached.operationId()
            );
        }
        catch (JacksonException | IllegalArgumentException exception) {
            throw invalidCache(exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JacksonException exception) {
            throw new PlatformProblemException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "incident.serialization-failed",
                "The incident response could not be serialized safely.",
                exception
            );
        }
    }

    private PlatformProblemException invalidCache(Throwable cause) {
        return new PlatformProblemException(
            HttpStatus.CONFLICT,
            "idempotency.record-invalid",
            "The stored idempotency response cannot be replayed safely.",
            cause
        );
    }

    private record CachedMutation(
        String responseBody,
        String location,
        String etag,
        UUID operationId
    ) {
    }
}
