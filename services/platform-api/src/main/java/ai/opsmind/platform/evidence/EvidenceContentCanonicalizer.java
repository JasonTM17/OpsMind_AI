package ai.opsmind.platform.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Canonicalizes the bounded, already-redacted inline evidence contract. */
@Component
public final class EvidenceContentCanonicalizer {

    public static final int MAXIMUM_BYTES = 65_536;
    private static final int MAXIMUM_DEPTH = 16;
    private static final int MAXIMUM_NODES = 4_096;
    private static final String REDACTED = "[REDACTED]";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
        "(?i).*(authorization|api[-_]?key|token|secret|password|credential|cookie).*"
    );
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
        "(?i).*(bearer\\s+[a-z0-9._~+/=-]{8,}|"
            + "(?:api[-_]?key|token|secret|password|credential|cookie)\\s*[:=]\\s*\\S{4,}).*"
    );

    private final ObjectMapper objectMapper;

    public EvidenceContentCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalEvidenceContent canonicalize(Map<String, Object> content) {
        if (content == null) throw invalid("Evidence content must be a JSON object.");
        StructureBudget budget = new StructureBudget();
        Object normalized = normalize(content, budget, 0, null);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(normalized);
            if (bytes.length > MAXIMUM_BYTES) {
                throw invalid("Evidence content exceeds the inline byte limit.");
            }
            return new CanonicalEvidenceContent(
                new String(bytes, StandardCharsets.UTF_8), digest(bytes), bytes.length
            );
        }
        catch (JacksonException exception) {
            throw new IllegalArgumentException("Evidence content is not serializable.", exception);
        }
    }

    public CanonicalEvidenceContent verify(String canonicalJson, String expectedDigest) {
        if (canonicalJson == null || expectedDigest == null) {
            throw invalid("Canonical evidence content and digest are required.");
        }
        try {
            CanonicalEvidenceContent verified = canonicalize(objectMapper.readValue(canonicalJson, OBJECT));
            if (!canonicalJson.equals(verified.json())
                || !MessageDigest.isEqual(
                    expectedDigest.getBytes(StandardCharsets.US_ASCII),
                    verified.digest().getBytes(StandardCharsets.US_ASCII)
                )) {
                throw invalid("Evidence content does not match its canonical digest.");
            }
            return verified;
        }
        catch (JacksonException exception) {
            throw new IllegalArgumentException("Canonical evidence content is invalid JSON.", exception);
        }
    }

    private Object normalize(Object value, StructureBudget budget, int depth, String key) {
        if (depth > MAXIMUM_DEPTH || !budget.acceptNode()) {
            throw invalid("Evidence content exceeds the structural limit.");
        }
        if (key != null && SENSITIVE_KEY.matcher(key).matches() && !REDACTED.equals(value)) {
            throw invalid("Sensitive evidence fields must already be redacted.");
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            if ((value instanceof Double doubleValue && !Double.isFinite(doubleValue))
                || (value instanceof Float floatValue && !Float.isFinite(floatValue))) {
                throw invalid("Evidence content contains a non-finite number.");
            }
            return value;
        }
        if (value instanceof String text) {
            if (text.length() > MAXIMUM_BYTES || SENSITIVE_VALUE.matcher(text).matches()) {
                throw invalid("Evidence content contains an unsafe string value.");
            }
            return text;
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String childKey)
                    || childKey.isBlank() || childKey.length() > 128
                    || childKey.chars().anyMatch(Character::isISOControl)) {
                    throw invalid("Evidence content contains an unsafe object key.");
                }
                ordered.put(childKey, normalize(entry.getValue(), budget, depth + 1, childKey));
            }
            return ordered;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) normalized.add(normalize(item, budget, depth + 1, null));
            return List.copyOf(normalized);
        }
        throw invalid("Evidence content contains an unsupported value type.");
    }

    private String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public record CanonicalEvidenceContent(String json, String digest, int byteCount) { }

    private static final class StructureBudget {
        private int nodes;

        private boolean acceptNode() {
            nodes++;
            return nodes <= MAXIMUM_NODES;
        }
    }
}
