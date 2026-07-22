package ai.opsmind.toolgateway.application;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import ai.opsmind.toolgateway.config.GatewaySettings;
import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;
import ai.opsmind.toolgateway.domain.ToolExecutionRequest;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class PolicyEvaluator {

    private static final Pattern SAFE_SELECTOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Set<String> FORBIDDEN_ARGUMENTS = Set.of(
        "url", "uri", "path", "command", "shell", "sql", "query", "verb", "method"
    );

    private final ObjectMapper objectMapper;
    private final GatewaySettings settings;
    private final Clock clock;

    public PolicyEvaluator(ObjectMapper objectMapper, GatewaySettings settings, Clock clock) {
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.clock = clock;
    }

    public void evaluate(
        ToolExecutionRequest request,
        ToolManifest manifest,
        VerifiedCapability capability
    ) {
        validateRequiredFields(request);
        if (!request.deadlineAt().isAfter(clock.instant())) {
            deny(DenialCode.DEADLINE_EXPIRED, "Tool execution deadline is expired.");
        }
        if (request.deadlineAt().isAfter(capability.expiresAt())
            || request.deadlineAt().isAfter(clock.instant().plus(manifest.maximumDuration()))) {
            deny(
                DenialCode.DEADLINE_OUTSIDE_CAPABILITY,
                "Tool execution deadline exceeds the signed or manifest bound."
            );
        }
        if (!capability.roles().contains(manifest.requiredRole())
            || !request.resource().startsWith(manifest.resourcePrefix())) {
            deny(DenialCode.CAPABILITY_SCOPE_MISMATCH, "Tool resource is outside delegated policy.");
        }
        if (request.resultBudget().maxBytes() < 1
            || request.resultBudget().maxBytes() > manifest.maximumBytes()
            || request.resultBudget().maxBytes() > settings.maximumResultBytes()
            || request.resultBudget().maxItems() < 1
            || request.resultBudget().maxItems() > manifest.maximumItems()) {
            deny(DenialCode.RESULT_OVERSIZE, "Requested result budget exceeds the manifest bound.");
        }
        if (!manifest.allowedArgumentNames().containsAll(request.arguments().keySet())
            || request.arguments().keySet().stream().map(String::toLowerCase)
                .anyMatch(FORBIDDEN_ARGUMENTS::contains)) {
            deny(DenialCode.ARGUMENTS_INVALID, "Tool arguments are not allowed by the manifest.");
        }
        validateFixtureArguments(request);
        validateResourceBoundArguments(request, manifest);
        try {
            if (objectMapper.writeValueAsBytes(request).length > settings.maximumRequestBytes()) {
                deny(DenialCode.REQUEST_OVERSIZE, "Tool execution request exceeds the byte bound.");
            }
        }
        catch (JacksonException exception) {
            throw new ToolDeniedException(DenialCode.REQUEST_INVALID, "Tool execution request is invalid.", exception);
        }
    }

    private void validateResourceBoundArguments(ToolExecutionRequest request, ToolManifest manifest) {
        Object service = request.arguments().get("service");
        String resource = request.resource();
        String prefix = manifest.resourcePrefix();
        String target = resource.startsWith(prefix) ? resource.substring(prefix.length()) : "";
        if (!SAFE_SELECTOR.matcher(target).matches() || (service != null && !service.equals(target))) {
            deny(
                DenialCode.CAPABILITY_SCOPE_MISMATCH,
                "Tool selector does not match the delegated resource."
            );
        }
    }

    private void validateFixtureArguments(ToolExecutionRequest request) {
        for (String name : java.util.List.of("service", "metric")) {
            Object value = request.arguments().get(name);
            if (value != null && (!(value instanceof String selector)
                || selector.length() > 128 || !SAFE_SELECTOR.matcher(selector).matches())) {
                deny(DenialCode.ARGUMENTS_INVALID, "Tool selector is invalid.");
            }
        }
        Object maximumPoints = request.arguments().get("max_points");
        if (maximumPoints != null) {
            if (!(maximumPoints instanceof Byte || maximumPoints instanceof Short
                || maximumPoints instanceof Integer || maximumPoints instanceof Long)) {
                deny(DenialCode.ARGUMENTS_INVALID, "Tool item bound must be an integer.");
            }
            long value = ((Number) maximumPoints).longValue();
            if (value < 1 || value > request.resultBudget().maxItems()) {
                deny(DenialCode.ARGUMENTS_INVALID, "Tool item bound is outside the request budget.");
            }
        }
        if (request.arguments().values().stream().anyMatch(value ->
            value == null || value instanceof Map<?, ?> || value instanceof java.util.Collection<?>)) {
            deny(DenialCode.ARGUMENTS_INVALID, "Nested tool arguments are prohibited.");
        }
    }

    private void validateRequiredFields(ToolExecutionRequest request) {
        if (request.executionId() == null || request.tenantId() == null || request.projectId() == null
            || request.incidentId() == null || request.runId() == null || blank(request.actorSubject())
            || blank(request.tool()) || blank(request.action()) || blank(request.schemaVersion())
            || blank(request.resource()) || request.arguments() == null
            || request.deadlineAt() == null || request.resultBudget() == null) {
            deny(DenialCode.REQUEST_INVALID, "Tool execution request is incomplete.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void deny(DenialCode code, String message) {
        throw new ToolDeniedException(code, message);
    }
}
