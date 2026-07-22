package ai.opsmind.platform.incident;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface IncidentRepository {

    void insert(IncidentSnapshot incident);

    Optional<IncidentSnapshot> find(UUID organizationId, UUID projectId, UUID incidentId);

    Optional<IncidentSnapshot> findForUpdate(UUID organizationId, UUID projectId, UUID incidentId);

    IncidentSnapshot transition(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        long expectedVersion,
        IncidentStatus targetStatus,
        String rootCause,
        String resolutionSummary,
        UUID actorId,
        Instant occurredAt
    );

}
