package ai.opsmind.platform.incident;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentTimelinePage(
    List<IncidentTimelineEvent> items,
    int pageSize,
    String nextPageToken,
    boolean hasMore
) {
    public IncidentTimelinePage {
        items = List.copyOf(items);
    }
}
