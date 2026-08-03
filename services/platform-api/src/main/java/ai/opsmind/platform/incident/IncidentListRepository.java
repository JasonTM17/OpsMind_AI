package ai.opsmind.platform.incident;

import java.util.List;
import java.util.UUID;

interface IncidentListRepository {

    List<IncidentSummary> list(
        UUID organizationId,
        UUID projectId,
        IncidentStatus status,
        IncidentListPageToken.Cursor after,
        int limit
    );
}
