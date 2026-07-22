package ai.opsmind.toolgateway.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.opsmind.toolgateway.connectors.ConnectorEvidence;
import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.EvidenceEnvelope;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class EvidenceNormalizer {

    private static final java.util.regex.Pattern SENSITIVE_KEY = java.util.regex.Pattern.compile(
        "(?i).*(authorization|api[-_]?key|token|secret|password|credential|cookie).*"
    );

    private final ObjectMapper objectMapper;
    private final int maximumResultBytes;

    public EvidenceNormalizer(ObjectMapper objectMapper, GatewaySettings settings) {
        this.objectMapper = objectMapper;
        this.maximumResultBytes = settings.maximumResultBytes();
    }

    public EvidenceEnvelope normalize(
        ConnectorEvidence raw,
        ToolManifest manifest,
        ToolExecutionRequest request
    ) {
        validateMetadata(raw, request);
        inspectValue(raw.content(), new StructureBudget(maximumResultBytes), 0);
        int[] redactedFields = {0};
        Map<String, Object> redacted = redactMap(raw.content(), redactedFields);
        byte[] completeBytes = serialize(redacted);
        String digest = RequestDigester.sha256(completeBytes);
        if (completeBytes.length > request.resultBudget().maxBytes()
            || completeBytes.length > maximumResultBytes) {
            throw new ToolDeniedException(
                DenialCode.RESULT_OVERSIZE,
                "Connector evidence requires the unavailable durable artifact path."
            );
        }
        return new EvidenceEnvelope(
            raw.source(),
            raw.targetIdentity(),
            raw.observedAt(),
            raw.windowStart(),
            raw.windowEnd(),
            raw.connectorVersion(),
            manifest.manifestVersion(),
            raw.trustClass(),
            digest,
            redacted,
            redactedFields[0],
            false,
            null
        );
    }

    private void validateMetadata(ConnectorEvidence raw, ToolExecutionRequest request) {
        if (raw == null || !request.resource().equals(raw.targetIdentity())
            || !safeMetadata(raw.source(), 128) || !safeMetadata(raw.connectorVersion(), 128)
            || !java.util.Set.of("synthetic", "source-attested", "derived").contains(raw.trustClass())
            || raw.observedAt() == null || raw.windowStart() == null || raw.windowEnd() == null
            || raw.windowEnd().isBefore(raw.windowStart())) {
            throw new ToolDeniedException(
                DenialCode.CONNECTOR_FAILED,
                "Connector evidence metadata is invalid or outside the delegated resource."
            );
        }
    }

    private boolean safeMetadata(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
            && value.matches("[A-Za-z0-9][A-Za-z0-9_.:@/-]*")
            && SensitiveValueRedactor.redact(value).equals(value);
    }

    private void inspectValue(Object value, StructureBudget budget, int depth) {
        if (depth > 16 || !budget.acceptNode()) rejectOversize();
        if (value == null || value instanceof Boolean || value instanceof Number) return;
        if (value instanceof String text) {
            if (!budget.acceptBytes(text.getBytes(StandardCharsets.UTF_8).length)) rejectOversize();
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.length() > 128
                    || !budget.acceptBytes(key.getBytes(StandardCharsets.UTF_8).length)) {
                    rejectOversize();
                }
                inspectValue(entry.getValue(), budget, depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) inspectValue(item, budget, depth + 1);
            return;
        }
        throw new ToolDeniedException(
            DenialCode.CONNECTOR_FAILED,
            "Connector evidence contains an unsupported value type."
        );
    }

    private void rejectOversize() {
        throw new ToolDeniedException(
            DenialCode.RESULT_OVERSIZE,
            "Connector evidence exceeds the bounded inline result contract."
        );
    }

    private Map<String, Object> redactMap(Map<String, Object> source, int[] count) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (SENSITIVE_KEY.matcher(key).matches()) {
                result.put(key, "[REDACTED]");
                count[0]++;
            }
            else {
                result.put(key, redactValue(value, count));
            }
        });
        return Map.copyOf(result);
    }

    private Object redactValue(Object value, int[] count) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> strings = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> strings.put(String.valueOf(key), nestedValue));
            return redactMap(strings, count);
        }
        if (value instanceof List<?> list) {
            List<Object> redacted = new ArrayList<>(list.size());
            list.forEach(item -> redacted.add(redactValue(item, count)));
            return List.copyOf(redacted);
        }
        if (value instanceof String string) {
            String replaced = SensitiveValueRedactor.redact(string);
            if (!replaced.equals(string)) count[0]++;
            return replaced;
        }
        return value;
    }

    private byte[] serialize(Map<String, Object> content) {
        try {
            return objectMapper.writeValueAsBytes(CanonicalJsonValue.normalize(content));
        }
        catch (JacksonException exception) {
            throw new IllegalStateException("Connector evidence is not serializable.", exception);
        }
    }

    private static final class StructureBudget {

        private static final int MAXIMUM_NODES = 4_096;

        private final int maximumBytes;
        private int nodes;
        private int bytes;

        private StructureBudget(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        private boolean acceptNode() {
            nodes++;
            return nodes <= MAXIMUM_NODES;
        }

        private boolean acceptBytes(int count) {
            if (count < 0 || bytes > maximumBytes - count) return false;
            bytes += count;
            return true;
        }
    }
}
