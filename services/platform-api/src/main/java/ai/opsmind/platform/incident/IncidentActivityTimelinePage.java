package ai.opsmind.platform.incident;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentActivityTimelinePage(
    List<IncidentActivityTimelineEntry> items,
    int pageSize,
    String nextPageToken,
    boolean hasMore
) {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TOKEN_LENGTH = 512;
    private static final Pattern PAGE_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    public IncidentActivityTimelinePage {
        if (items == null
            || items.stream().anyMatch(Objects::isNull)
            || pageSize < 1
            || pageSize > MAX_PAGE_SIZE
            || items.size() > pageSize
            || hasMore != (nextPageToken != null)
            || (nextPageToken != null
                && (nextPageToken.length() > MAX_TOKEN_LENGTH
                    || !PAGE_TOKEN_PATTERN.matcher(nextPageToken).matches()))) {
            throw new IllegalArgumentException("Incident activity timeline page is invalid.");
        }
        items = List.copyOf(items);
    }
}
