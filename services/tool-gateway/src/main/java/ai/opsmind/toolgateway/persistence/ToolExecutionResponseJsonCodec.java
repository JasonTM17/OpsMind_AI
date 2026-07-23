package ai.opsmind.toolgateway.persistence;

import java.nio.charset.StandardCharsets;

import ai.opsmind.toolgateway.domain.ToolExecutionResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

final class ToolExecutionResponseJsonCodec {

    private final ObjectMapper objectMapper;
    private final ObjectReader reader;
    private final int maximumBytes;

    ToolExecutionResponseJsonCodec(ObjectMapper objectMapper, int maximumBytes) {
        this.objectMapper = objectMapper;
        this.reader = objectMapper.readerFor(ToolExecutionResponse.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.maximumBytes = maximumBytes;
    }

    String write(ToolExecutionResponse response) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(response);
            if (bytes.length == 0 || bytes.length > maximumBytes) throw invalid(null);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (JacksonException exception) {
            throw invalid(exception);
        }
    }

    ToolExecutionResponse read(String json) {
        if (json == null) throw invalid(null);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maximumBytes) throw invalid(null);
        try {
            return reader.readValue(bytes);
        }
        catch (JacksonException exception) {
            throw invalid(exception);
        }
    }

    private IllegalStateException invalid(Throwable cause) {
        return new IllegalStateException("Stored Tool Gateway response is invalid.", cause);
    }
}
