package ai.opsmind.platform.incident;

import java.util.UUID;
import java.util.regex.Pattern;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;

final class IncidentCommandValidator {

    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");

    private IncidentCommandValidator() {
    }

    static CreateIncidentRequest normalize(CreateIncidentRequest request) {
        if (request == null || request.severity() == null) {
            throw invalidRequest();
        }
        String title = required(request.title(), 160);
        String summary = required(request.summary(), 4000);
        String reason = required(request.reason(), 1000);
        return new CreateIncidentRequest(title, summary, request.severity(), reason);
    }

    static TransitionIncidentRequest normalize(TransitionIncidentRequest request) {
        if (request == null || request.targetStatus() == null) {
            throw invalidRequest();
        }
        return new TransitionIncidentRequest(
            request.targetStatus(),
            required(request.reason(), 1000),
            optional(request.rootCause(), 8000),
            optional(request.resolutionSummary(), 8000)
        );
    }

    static void requireResourceIds(UUID organizationId, UUID projectId, UUID incidentId) {
        requireCollectionIds(organizationId, projectId);
        if (incidentId == null) {
            throw invalidRequest();
        }
    }

    static void requireCollectionIds(UUID organizationId, UUID projectId) {
        if (organizationId == null || projectId == null) {
            throw invalidRequest();
        }
    }

    static String normalizeTrace(String externalTraceId) {
        if (externalTraceId == null) {
            return null;
        }
        if (!SAFE_TRACE_ID.matcher(externalTraceId).matches()) {
            throw invalidRequest();
        }
        return externalTraceId;
    }

    static void requirePageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw invalidRequest();
        }
    }

    private static String required(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw invalidRequest();
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw invalidRequest();
        }
        return normalized;
    }

    private static String optional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw invalidRequest();
        }
        return normalized;
    }

    private static PlatformProblemException invalidRequest() {
        return new PlatformProblemException(
            HttpStatus.BAD_REQUEST,
            "request.validation-failed",
            "The request did not satisfy the API contract."
        );
    }
}
