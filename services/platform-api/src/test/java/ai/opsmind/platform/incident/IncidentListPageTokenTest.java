package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;

import org.junit.jupiter.api.Test;

class IncidentListPageTokenTest {

    private static final UUID ORGANIZATION_ID =
        UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PROJECT_ID =
        UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID INCIDENT_ID =
        UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant UPDATED_AT = Instant.parse("2030-01-01T00:00:00.123456Z");

    private final IncidentListPageToken codec = new IncidentListPageToken();

    @Test
    void roundTripsFilteredAndUnfilteredCanonicalCursors() {
        for (IncidentStatus status : new IncidentStatus[] {null, IncidentStatus.INVESTIGATING}) {
            String encoded = codec.encode(
                ORGANIZATION_ID, PROJECT_ID, status, UPDATED_AT, INCIDENT_ID
            );
            IncidentListPageToken.Claims claims = codec.parse(encoded);

            assertThat(codec.bind(claims, ORGANIZATION_ID, PROJECT_ID, status))
                .isEqualTo(new IncidentListPageToken.Cursor(UPDATED_AT, INCIDENT_ID));
            assertThat(encoded).doesNotContain("=");
        }
        assertThat(codec.parse(null)).isNull();
        assertThat(codec.bind(null, ORGANIZATION_ID, PROJECT_ID, null)).isNull();
    }

    @Test
    void parsesForgeableSeekBoundaryButBindsContextOnlyAfterAuthorization() {
        UUID arbitraryIncidentId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        String encoded = codec.encode(
            ORGANIZATION_ID,
            PROJECT_ID,
            IncidentStatus.OPEN,
            Instant.parse("2029-01-01T00:00:00Z"),
            arbitraryIncidentId
        );

        IncidentListPageToken.Claims claims = codec.parse(encoded);

        assertThat(codec.bind(claims, ORGANIZATION_ID, PROJECT_ID, IncidentStatus.OPEN))
            .isEqualTo(new IncidentListPageToken.Cursor(
                Instant.parse("2029-01-01T00:00:00Z"), arbitraryIncidentId
            ));
        assertInvalidBinding(claims, UUID.randomUUID(), PROJECT_ID, IncidentStatus.OPEN);
        assertInvalidBinding(claims, ORGANIZATION_ID, UUID.randomUUID(), IncidentStatus.OPEN);
        assertInvalidBinding(claims, ORGANIZATION_ID, PROJECT_ID, IncidentStatus.CLOSED);
    }

    @Test
    void rejectsMalformedNonCanonicalAndSubstitutedFields() {
        String valid = codec.encode(
            ORGANIZATION_ID, PROJECT_ID, null, UPDATED_AT, INCIDENT_ID
        );
        for (String token : new String[] {
            "",
            " ",
            "x".repeat(513),
            valid + "=",
            valid.substring(0, valid.length() - 1),
            encoded("v2:" + ORGANIZATION_ID + ":" + PROJECT_ID
                + ":*:1893456000123456:updated-desc-id-desc-v1:" + INCIDENT_ID),
            encoded("v1:" + ORGANIZATION_ID + ":" + PROJECT_ID
                + ":SEV1:1893456000123456:updated-desc-id-desc-v1:" + INCIDENT_ID),
            encoded("v1:" + ORGANIZATION_ID + ":" + PROJECT_ID
                + ":*:01893456000123456:updated-desc-id-desc-v1:" + INCIDENT_ID),
            encoded("v1:" + ORGANIZATION_ID + ":" + PROJECT_ID
                + ":*:-1:updated-desc-id-desc-v1:" + INCIDENT_ID),
            encoded("v1:" + ORGANIZATION_ID + ":" + PROJECT_ID
                + ":*:1893456000123456:updated-asc-id-asc-v1:" + INCIDENT_ID),
            encoded("v1:AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA:" + PROJECT_ID
                + ":*:1893456000123456:updated-desc-id-desc-v1:" + INCIDENT_ID),
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[] {(byte) 0xff}),
            encoded("v1:" + ORGANIZATION_ID + ":" + PROJECT_ID
                + ":*:1893456000123456:updated-desc-id-desc-v1:" + INCIDENT_ID + ":extra"),
        }) {
            assertInvalidToken(token);
        }
    }

    @Test
    void refusesToEncodeNonCanonicalTimesOrMissingScope() {
        assertThatThrownBy(() -> codec.encode(
            ORGANIZATION_ID, PROJECT_ID, null, UPDATED_AT.plusNanos(1), INCIDENT_ID
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(
            null, PROJECT_ID, null, UPDATED_AT, INCIDENT_ID
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(
            ORGANIZATION_ID, PROJECT_ID, null,
            Instant.ofEpochSecond(253_402_300_800L), INCIDENT_ID
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalidToken(String token) {
        assertThatThrownBy(() -> codec.parse(token))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("pagination.invalid-token")
            );
    }

    private void assertInvalidBinding(
        IncidentListPageToken.Claims claims,
        UUID organizationId,
        UUID projectId,
        IncidentStatus status
    ) {
        assertThatThrownBy(() -> codec.bind(claims, organizationId, projectId, status))
            .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("pagination.invalid-token")
            );
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
