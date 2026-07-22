package ai.opsmind.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Component
public final class EventPayloadIntegrity {

    private static final int MAX_PAYLOAD_BYTES = 1_048_576;

    private final ObjectMapper objectMapper;
    private final ObjectReader singleValueReader;

    public EventPayloadIntegrity(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.singleValueReader = objectMapper.reader()
            .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Verifies the exact UTF-8 payload bytes represented by the envelope. Event
     * producers must serialize once, hash those bytes, and reuse that JSON.
     */
    public void verify(EventEnvelope event) {
        byte[] payloadBytes = event.payloadJson().getBytes(StandardCharsets.UTF_8);
        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Event payload exceeds the bounded size limit.");
        }
        JsonNode payload = parsePayload(event.payloadJson());
        if (!payload.isObject()) {
            throw new IllegalArgumentException("Event payload must be a JSON object.");
        }

        byte[] expectedDigest = sha256(payloadBytes);
        if (!MessageDigest.isEqual(expectedDigest, event.payloadDigest())) {
            throw new IllegalArgumentException("Event payload digest does not match the payload bytes.");
        }
    }

    private JsonNode parsePayload(String payloadJson) {
        try (JsonParser parser = objectMapper.createParser(payloadJson)) {
            JsonNode payload = singleValueReader.readTree(parser);
            if (payload == null) {
                throw new IllegalArgumentException("Event payload must contain JSON.");
            }
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Event payload must contain exactly one JSON value.");
            }
            return payload;
        }
        catch (JacksonException exception) {
            throw new IllegalArgumentException("Event payload must contain valid JSON.", exception);
        }
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime.", exception);
        }
    }
}
