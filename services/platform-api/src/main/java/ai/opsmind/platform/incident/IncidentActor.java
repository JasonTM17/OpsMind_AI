package ai.opsmind.platform.incident;

import java.util.UUID;

record IncidentActor(UUID id, String organizationRole, String projectRole) {
}
