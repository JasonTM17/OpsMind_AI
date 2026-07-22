package ai.opsmind.platform.incident;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;

public final class IncidentStateMachine {

    private static final Map<IncidentStatus, Set<IncidentStatus>> TRANSITIONS = transitions();

    private IncidentStateMachine() {
    }

    public static ResolutionFields apply(
        IncidentStatus current,
        IncidentStatus target,
        String currentRootCause,
        String currentResolutionSummary,
        String requestedRootCause,
        String requestedResolutionSummary
    ) {
        if (current == null || target == null || !TRANSITIONS.get(current).contains(target)) {
            throw new PlatformProblemException(
                HttpStatus.CONFLICT,
                "incident.transition-not-allowed",
                "The requested incident transition is not allowed from the current status."
            );
        }
        if (target == IncidentStatus.RESOLVED) {
            if (isBlank(requestedRootCause) || isBlank(requestedResolutionSummary)) {
                throw invalidResolution();
            }
            return new ResolutionFields(requestedRootCause.trim(), requestedResolutionSummary.trim());
        }
        if (!isBlank(requestedRootCause) || !isBlank(requestedResolutionSummary)) {
            throw new PlatformProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "incident.resolution-not-applicable",
                "Resolution fields are accepted only when transitioning to RESOLVED."
            );
        }
        if (current == IncidentStatus.RESOLVED && target == IncidentStatus.INVESTIGATING) {
            return new ResolutionFields(null, null);
        }
        return new ResolutionFields(currentRootCause, currentResolutionSummary);
    }

    public static boolean allows(IncidentStatus current, IncidentStatus target) {
        return current != null && target != null && TRANSITIONS.get(current).contains(target);
    }

    private static Map<IncidentStatus, Set<IncidentStatus>> transitions() {
        Map<IncidentStatus, Set<IncidentStatus>> values = new EnumMap<>(IncidentStatus.class);
        values.put(IncidentStatus.OPEN, Set.of(IncidentStatus.INVESTIGATING));
        values.put(
            IncidentStatus.INVESTIGATING,
            Set.of(IncidentStatus.AWAITING_APPROVAL, IncidentStatus.MITIGATING, IncidentStatus.RESOLVED)
        );
        values.put(
            IncidentStatus.AWAITING_APPROVAL,
            Set.of(IncidentStatus.INVESTIGATING, IncidentStatus.MITIGATING)
        );
        values.put(
            IncidentStatus.MITIGATING,
            Set.of(IncidentStatus.INVESTIGATING, IncidentStatus.RESOLVED)
        );
        values.put(IncidentStatus.RESOLVED, Set.of(IncidentStatus.INVESTIGATING, IncidentStatus.CLOSED));
        values.put(IncidentStatus.CLOSED, Set.of());
        return Map.copyOf(values);
    }

    private static PlatformProblemException invalidResolution() {
        return new PlatformProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "incident.resolution-required",
            "Root cause and resolution summary are required when resolving an incident."
        );
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ResolutionFields(String rootCause, String resolutionSummary) {
    }
}
