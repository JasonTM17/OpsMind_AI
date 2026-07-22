package ai.opsmind.toolgateway.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class RequestDigester {

    private final ObjectMapper objectMapper;

    public RequestDigester(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String digest(ToolExecutionRequest request) {
        try {
            TreeMap<String, Object> canonical = new TreeMap<>();
            canonical.put("action", request.action());
            canonical.put("actor_subject", request.actorSubject());
            canonical.put("arguments", CanonicalJsonValue.normalize(request.arguments()));
            canonical.put("deadline_at", request.deadlineAt());
            canonical.put("execution_id", request.executionId());
            canonical.put("incident_id", request.incidentId());
            canonical.put("project_id", request.projectId());
            canonical.put("resource", request.resource());
            canonical.put("result_budget", request.resultBudget());
            canonical.put("run_id", request.runId());
            canonical.put("schema_version", request.schemaVersion());
            canonical.put("tenant_id", request.tenantId());
            canonical.put("tool", request.tool());
            return sha256(objectMapper.writeValueAsBytes(canonical));
        }
        catch (JacksonException exception) {
            throw new ToolDeniedException(DenialCode.REQUEST_INVALID, "Tool request is not canonicalizable.", exception);
        }
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static String fallbackDigest(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
