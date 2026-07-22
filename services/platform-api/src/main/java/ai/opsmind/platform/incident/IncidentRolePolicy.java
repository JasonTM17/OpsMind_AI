package ai.opsmind.platform.incident;

import java.util.Set;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.springframework.http.HttpStatus;

final class IncidentRolePolicy {

    private static final Set<String> READ_ROLES = Set.of(
        "ADMIN",
        "SRE",
        "DEVELOPER",
        "SECURITY_REVIEWER",
        "VIEWER"
    );
    private static final Set<String> MUTATE_ROLES = Set.of("ADMIN", "SRE");
    private static final Set<String> ANALYZE_ROLES = Set.of("ADMIN", "SRE");

    private IncidentRolePolicy() {
    }

    static void requireAllowed(String organizationRole, String projectRole, IncidentAccessMode mode) {
        Set<String> allowed = switch (mode) {
            case READ -> READ_ROLES;
            case ANALYZE -> ANALYZE_ROLES;
            case MUTATE -> MUTATE_ROLES;
        };
        if (!allowed.contains(organizationRole) || !allowed.contains(projectRole)) {
            throw hiddenDenial();
        }
    }

    static PlatformProblemException hiddenDenial() {
        return new PlatformProblemException(
            HttpStatus.NOT_FOUND,
            "resource.not-found",
            "The resource does not exist or is not visible to this principal."
        );
    }
}
