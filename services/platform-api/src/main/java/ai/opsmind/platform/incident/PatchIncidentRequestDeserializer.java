package ai.opsmind.platform.incident;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Rejects authority drift and preserves absent-versus-null owner intent. */
final class PatchIncidentRequestDeserializer extends ValueDeserializer<PatchIncidentRequest> {

    private static final String TITLE = "title";
    private static final String SUMMARY = "summary";
    private static final String SEVERITY = "severity";
    private static final String OWNER_ID = "ownerId";
    private static final String REASON = "reason";
    private static final Pattern CANONICAL_UUID = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );
    // JSON Schema patterns use ECMAScript whitespace semantics. Java's Unicode
    // class additionally treats U+0085 as whitespace and omits U+FEFF.
    private static final Pattern JSON_SCHEMA_NON_WHITESPACE = Pattern.compile(
        "[^\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A"
            + "\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    );
    private static final Set<String> ALLOWED_FIELDS = Set.of(
        TITLE, SUMMARY, SEVERITY, OWNER_ID, REASON
    );

    @Override
    public PatchIncidentRequest deserialize(
        JsonParser parser,
        DeserializationContext context
    ) throws JacksonException {
        if (!parser.isExpectedStartObjectToken()) {
            return mismatch(context, "Patch request must be a JSON object.");
        }
        Map<String, JsonNode> request = new HashMap<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token != JsonToken.PROPERTY_NAME) {
                return mismatch(context, "Patch request must contain named properties.");
            }
            String property = parser.currentName();
            if (!ALLOWED_FIELDS.contains(property)) {
                return mismatch(context, "Patch request contains an unknown property.");
            }
            if (request.containsKey(property)) {
                return mismatch(context, "Patch request contains a duplicate property.");
            }
            if (parser.nextToken() == null) {
                return mismatch(context, "Patch request contains an incomplete property.");
            }
            request.put(property, parser.readValueAsTree());
        }

        boolean titlePresent = request.containsKey(TITLE);
        boolean summaryPresent = request.containsKey(SUMMARY);
        boolean severityPresent = request.containsKey(SEVERITY);
        boolean ownerIdPresent = request.containsKey(OWNER_ID);
        if (!titlePresent && !summaryPresent && !severityPresent && !ownerIdPresent) {
            return mismatch(context, "Patch request must change at least one mutable field.");
        }

        return new PatchIncidentRequest(
            optionalNonBlankText(request, TITLE, titlePresent, 160, context),
            titlePresent,
            optionalNonBlankText(request, SUMMARY, summaryPresent, 4000, context),
            summaryPresent,
            severity(request, severityPresent, context),
            severityPresent,
            ownerId(request, ownerIdPresent, context),
            ownerIdPresent,
            requiredNonBlankText(request, REASON, 1000, context)
        );
    }

    private String optionalNonBlankText(
        Map<String, JsonNode> request,
        String property,
        boolean present,
        int maximumLength,
        DeserializationContext context
    ) throws JacksonException {
        if (!present) {
            return null;
        }
        JsonNode value = request.get(property);
        if (value == null || !value.isString()
            || !isBoundedNonBlank(value.stringValue(), maximumLength)) {
            return mismatch(context, property + " must be a bounded non-blank string when present.");
        }
        return value.stringValue();
    }

    private IncidentSeverity severity(
        Map<String, JsonNode> request,
        boolean present,
        DeserializationContext context
    ) throws JacksonException {
        if (!present) {
            return null;
        }
        JsonNode value = request.get(SEVERITY);
        if (value == null || !value.isString()) {
            return mismatch(context, "severity must be a supported string value.");
        }
        try {
            return IncidentSeverity.valueOf(value.stringValue());
        }
        catch (IllegalArgumentException exception) {
            return mismatch(context, "severity must be a supported string value.");
        }
    }

    private UUID ownerId(
        Map<String, JsonNode> request,
        boolean present,
        DeserializationContext context
    ) throws JacksonException {
        if (!present || request.get(OWNER_ID).isNull()) {
            return null;
        }
        JsonNode value = request.get(OWNER_ID);
        if (!value.isString()) {
            return mismatch(context, "ownerId must be a UUID or null.");
        }
        String text = value.stringValue();
        if (!CANONICAL_UUID.matcher(text).matches()) {
            return mismatch(context, "ownerId must be a UUID or null.");
        }
        try {
            UUID parsed = UUID.fromString(text);
            if (!parsed.toString().equalsIgnoreCase(text)) {
                return mismatch(context, "ownerId must be a UUID or null.");
            }
            return parsed;
        }
        catch (IllegalArgumentException exception) {
            return mismatch(context, "ownerId must be a UUID or null.");
        }
    }

    private String requiredNonBlankText(
        Map<String, JsonNode> request,
        String property,
        int maximumLength,
        DeserializationContext context
    ) throws JacksonException {
        JsonNode value = request.get(property);
        if (value == null || !value.isString()
            || !isBoundedNonBlank(value.stringValue(), maximumLength)) {
            return mismatch(context, property + " must be a bounded non-blank string.");
        }
        return value.stringValue();
    }

    private boolean isBoundedNonBlank(String value, int maximumLength) {
        return JSON_SCHEMA_NON_WHITESPACE.matcher(value).find()
            && value.codePointCount(0, value.length()) <= maximumLength;
    }

    private <T> T mismatch(DeserializationContext context, String message)
        throws JacksonException {
        return context.reportInputMismatch(PatchIncidentRequest.class, message);
    }
}
