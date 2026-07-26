package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IncidentActivityTimelinePageTest {

    private static final IncidentActivityTimelineEntry ENTRY =
        new IncidentActivityTimelineEntry(
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            IncidentActivityTimelineEntry.INCIDENT,
            IncidentTimelineEvent.CREATED,
            Instant.parse("2030-01-01T00:00:00.123456Z"),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            1L,
            null,
            null
        );

    @Test
    void copiesItemsAndPinsContinuationInvariants() {
        List<IncidentActivityTimelineEntry> mutableItems = new ArrayList<>(List.of(ENTRY));
        IncidentActivityTimelinePage page =
            new IncidentActivityTimelinePage(mutableItems, 2, "token", true);

        mutableItems.clear();

        assertThat(page.items()).containsExactly(ENTRY);
        assertThatThrownBy(() -> page.items().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new IncidentActivityTimelinePage(List.of(), 2, null, false).items()).isEmpty();
    }

    @Test
    void rejectsInvalidPageShapes() {
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(null, 1, null, false)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(ENTRY), 0, null, false)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(ENTRY), 101, null, false)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(ENTRY, ENTRY), 1, null, false)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(), 1, null, true)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(), 1, "token", false)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(), 1, "x".repeat(513), true)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(), 1, "token=", true)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            new IncidentActivityTimelinePage(List.of(), 1, "token value", true)
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
