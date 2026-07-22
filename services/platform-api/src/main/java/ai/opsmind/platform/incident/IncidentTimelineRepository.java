package ai.opsmind.platform.incident;

import java.util.List;
import java.util.UUID;

interface IncidentTimelineRepository {

    void append(IncidentTimelineEvent event, String payloadJson, String externalTraceId);

    List<IncidentTimelineEvent> list(
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        Long afterIncidentVersion,
        int limit
    );
}
