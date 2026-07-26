package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;

class IncidentTimelinePageTokenTest {

    private static final UUID INCIDENT_ID =
        UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_ID =
        UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant OCCURRED_AT =
        Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final String LEGACY_TOKEN =
        "djE6MzMzMzMzMzMtMzMzMy00MzMzLTgzMzMtMzMzMzMzMzMzMzMzOjc";
    private static final String ACTIVITY_TOKEN =
        "djI6MzMzMzMzMzMtMzMzMy00MzMzLTgzMzMtMzMzMzMzMzMzMzMzOjE4OTM0NTYwMDAxMjM0NTY6M"
            + "To1NTU1NTU1NS01NTU1LTQ1NTUtODU1NS01NTU1NTU1NTU1NTU";

    private final IncidentTimelinePageToken codec = new IncidentTimelinePageToken();

    @Test
    void preservesLegacyTokenBytesAndRoundTrip() {
        assertThat(codec.encode(INCIDENT_ID, 7)).isEqualTo(LEGACY_TOKEN);
        assertThat(codec.decode(LEGACY_TOKEN, INCIDENT_ID)).isEqualTo(7L);
    }

    @Test
    void roundTripsCanonicalActivityCursorBytes() {
        String encoded = codec.encodeActivity(INCIDENT_ID, OCCURRED_AT, 1, EVENT_ID);

        assertThat(encoded).isEqualTo(ACTIVITY_TOKEN);
        assertThat(codec.decodeActivity(encoded, INCIDENT_ID))
            .isEqualTo(new IncidentTimelinePageToken.ActivityCursor(OCCURRED_AT, 1, EVENT_ID));
        assertThat(codec.decodeActivity(null, INCIDENT_ID)).isNull();
    }

    @Test
    void rejectsMalformedOrForeignActivityTokens() {
        for (String token : new String[] {
            activityToken("v3:" + INCIDENT_ID + ":1893456000123456:1:" + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID + ":1893456000123456:1"),
            activityToken("v2:33333333-3333-4333-8333-333333333334:1893456000123456:1:"
                + EVENT_ID),
            activityToken("v2:33333333-3333-4333-8333-33333333333:1893456000123456:1:"
                + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID + ":-1:1:" + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID + ":01893456000123456:1:" + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID + ":+1893456000123456:1:" + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID + ":253402300800000000:1:" + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID + ":1893456000123456:01:" + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID + ":1893456000123456:2:" + EVENT_ID),
            activityToken("v2:" + INCIDENT_ID
                + ":1893456000123456:1:AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
            ACTIVITY_TOKEN + "=",
            "",
            " ",
            "x".repeat(513),
            " ".repeat(513),
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xff}),
        }) {
            assertInvalid(token);
        }
        assertInvalidForIncident(ACTIVITY_TOKEN, null);
    }

    @Test
    void refusesToEncodeNonCanonicalActivityCursorValues() {
        assertThatThrownBy(() ->
            codec.encodeActivity(INCIDENT_ID, OCCURRED_AT.plusNanos(1), 1, EVENT_ID)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            codec.encodeActivity(INCIDENT_ID, OCCURRED_AT, 2, EVENT_ID)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            codec.encodeActivity(null, OCCURRED_AT, 1, EVENT_ID)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            codec.encodeActivity(
                INCIDENT_ID,
                Instant.ofEpochSecond(253_402_300_800L),
                1,
                EVENT_ID
            )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalid(String token) {
        assertInvalidForIncident(token, INCIDENT_ID);
    }

    private void assertInvalidForIncident(String token, UUID incidentId) {
        assertThatThrownBy(() -> codec.decodeActivity(token, incidentId))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("pagination.invalid-token");
            });
    }

    private String activityToken(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
