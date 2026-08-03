package ai.opsmind.platform.incident;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentListPage(
    List<IncidentSummary> items,
    int pageSize,
    String nextPageToken,
    boolean hasMore
) {
    public IncidentListPage {
        items = List.copyOf(items);
    }
}
