package ai.opsmind.platform.incident;

import java.util.UUID;

import ai.opsmind.platform.identity.OpsMindPrincipal;

interface IncidentAccessRepository {

    IncidentActor requireAccess(
        OpsMindPrincipal principal,
        UUID organizationId,
        UUID projectId,
        IncidentAccessMode mode
    );
}
