package ai.opsmind.toolgateway.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;

import tools.jackson.databind.ObjectMapper;

public final class GatewayProblemWriter {

    private final ObjectMapper objectMapper;

    public GatewayProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int status, String code, String title) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:opsmind:problem:" + code);
        body.put("title", title);
        body.put("status", status);
        body.put("code", code);
        body.put("instance", "urn:opsmind:error:" + UUID.randomUUID());
        objectMapper.writeValue(response.getOutputStream(), body);
        response.flushBuffer();
    }
}
