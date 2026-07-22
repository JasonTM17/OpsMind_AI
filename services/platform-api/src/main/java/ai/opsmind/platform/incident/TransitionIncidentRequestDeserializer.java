package ai.opsmind.platform.incident;

import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/** Enforces the conditional transition request schema at the JSON boundary. */
final class TransitionIncidentRequestDeserializer
    extends ValueDeserializer<TransitionIncidentRequest> {

    private static final String TARGET_STATUS = "targetStatus";
    private static final String REASON = "reason";
    private static final String ROOT_CAUSE = "rootCause";
    private static final String RESOLUTION_SUMMARY = "resolutionSummary";
    private static final Set<String> ALLOWED_FIELDS = Set.of(
        TARGET_STATUS,
        REASON,
        ROOT_CAUSE,
        RESOLUTION_SUMMARY
    );

    @Override
    public TransitionIncidentRequest deserialize(
        JsonParser parser,
        DeserializationContext context
    ) throws JacksonException {
        JsonNode request = parser.readValueAsTree();
        if (request == null || !request.isObject()) {
            return mismatch(context, "Transition request must be a JSON object.");
        }
        for (String property : request.propertyNames()) {
            if (!ALLOWED_FIELDS.contains(property)) {
                return mismatch(context, "Transition request contains an unknown property.");
            }
        }

        IncidentStatus targetStatus = targetStatus(request, context);
        String reason = optionalText(request, REASON, context);
        boolean hasRootCause = request.has(ROOT_CAUSE);
        boolean hasResolutionSummary = request.has(RESOLUTION_SUMMARY);

        if (targetStatus != IncidentStatus.RESOLVED) {
            if (hasRootCause || hasResolutionSummary) {
                return mismatch(
                    context,
                    "Resolution fields are allowed only when targetStatus is RESOLVED."
                );
            }
            return new TransitionIncidentRequest(targetStatus, reason, null, null);
        }

        String rootCause = requiredNonBlankText(request, ROOT_CAUSE, context);
        String resolutionSummary = requiredNonBlankText(request, RESOLUTION_SUMMARY, context);
        return new TransitionIncidentRequest(targetStatus, reason, rootCause, resolutionSummary);
    }

    private IncidentStatus targetStatus(JsonNode request, DeserializationContext context)
        throws JacksonException {
        JsonNode value = request.get(TARGET_STATUS);
        if (value == null || !value.isString()) {
            return mismatch(context, "targetStatus must be a supported string value.");
        }
        try {
            return IncidentStatus.valueOf(value.stringValue());
        }
        catch (IllegalArgumentException exception) {
            return mismatch(context, "targetStatus must be a supported string value.");
        }
    }

    private String optionalText(
        JsonNode request,
        String property,
        DeserializationContext context
    ) throws JacksonException {
        JsonNode value = request.get(property);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            return mismatch(context, property + " must be a string.");
        }
        return value.stringValue();
    }

    private String requiredNonBlankText(
        JsonNode request,
        String property,
        DeserializationContext context
    ) throws JacksonException {
        JsonNode value = request.get(property);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            return mismatch(context, property + " must be a non-blank string when resolving.");
        }
        return value.stringValue();
    }

    private <T> T mismatch(DeserializationContext context, String message)
        throws JacksonException {
        return context.reportInputMismatch(TransitionIncidentRequest.class, message);
    }
}
