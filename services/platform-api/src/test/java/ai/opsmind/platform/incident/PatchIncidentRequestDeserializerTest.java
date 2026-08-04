package ai.opsmind.platform.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class PatchIncidentRequestDeserializerTest {

    private static final UUID OWNER_ID = UUID.fromString(
        "44444444-4444-4444-8444-444444444444"
    );

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void distinguishesAbsentOwnerFromExplicitClear() throws Exception {
        PatchIncidentRequest unchanged = mapper.readValue(
            "{\"title\":\"Updated\",\"reason\":\"Operator correction\"}",
            PatchIncidentRequest.class
        );
        PatchIncidentRequest cleared = mapper.readValue(
            "{\"ownerId\":null,\"reason\":\"Returned to queue\"}",
            PatchIncidentRequest.class
        );

        assertThat(unchanged.hasOwnerId()).isFalse();
        assertThat(cleared.hasOwnerId()).isTrue();
        assertThat(cleared.ownerId()).isNull();
    }

    @Test
    void readsAssignmentAndAllMutableFields() throws Exception {
        PatchIncidentRequest request = mapper.readValue("""
            {
              "title": "Checkout errors",
              "summary": "Elevated synthetic failures",
              "severity": "SEV1",
              "ownerId": "44444444-4444-4444-8444-444444444444",
              "reason": "Primary on-call accepted ownership"
            }
            """, PatchIncidentRequest.class);

        assertThat(request.hasTitle()).isTrue();
        assertThat(request.hasSummary()).isTrue();
        assertThat(request.hasSeverity()).isTrue();
        assertThat(request.hasOwnerId()).isTrue();
        assertThat(request.ownerId()).isEqualTo(OWNER_ID);
    }

    @Test
    void appliesJsonSchemaUnicodeWhitespaceAndCodePointBounds() throws Exception {
        String astralBoundary = "\uD83D\uDE80".repeat(160);
        PatchIncidentRequest request = mapper.readValue(
            "{\"title\":\"" + astralBoundary + "\",\"reason\":\"Unicode boundary\"}",
            PatchIncidentRequest.class
        );

        assertThat(request.title().codePointCount(0, request.title().length())).isEqualTo(160);
        assertThat(IncidentCommandValidator.normalize(request).title()).isEqualTo(astralBoundary);
        assertRejected("{\"title\":\"\u00A0\",\"reason\":\"Whitespace title\"}");
        assertRejected("{\"title\":\"Updated\",\"reason\":\"\u00A0\"}");
        assertRejected("{\"title\":\"\uFEFF\",\"reason\":\"BOM title\"}");
        assertRejected("{\"title\":\"Updated\",\"reason\":\"\uFEFF\"}");
        assertRejected(
            "{\"title\":\"" + "\uD83D\uDE80".repeat(161)
                + "\",\"reason\":\"Unicode overflow\"}"
        );

        PatchIncidentRequest ecmaNonWhitespace = mapper.readValue(
            "{\"title\":\"\u0085\",\"reason\":\"ECMAScript non-whitespace\"}",
            PatchIncidentRequest.class
        );
        assertThat(ecmaNonWhitespace.title()).isEqualTo("\u0085");
        assertThat(IncidentCommandValidator.normalize(ecmaNonWhitespace).title()).isEqualTo("\u0085");
    }

    @Test
    void rejectsEmptyUnknownAndMalformedFields() {
        assertRejected("{\"reason\":\"No mutation\"}");
        assertRejected("{\"status\":\"CLOSED\",\"reason\":\"Authority drift\"}");
        assertRejected("{\"ownerId\":\"invalid\",\"reason\":\"Bad owner\"}");
        assertRejected("{\"ownerId\":\"1-1-1-1-1\",\"reason\":\"Short owner\"}");
        assertRejected("{\"title\":null,\"reason\":\"Null metadata\"}");
        assertRejected("{\"title\":\"   \",\"reason\":\"Blank title\"}");
        assertRejected("{\"title\":\"Updated\",\"reason\":\"   \"}");
        assertRejected("{\"title\":\"" + "x".repeat(161) + "\",\"reason\":\"Long title\"}");
        assertRejected("{\"summary\":\"" + "x".repeat(4001) + "\",\"reason\":\"Long summary\"}");
        assertRejected("{\"title\":\"Updated\",\"reason\":\"" + "x".repeat(1001) + "\"}");
        assertRejected("{\"ownerId\":null,\"ownerId\":\"" + OWNER_ID + "\",\"reason\":\"Duplicate\"}");
        assertRejected("{\"title\":\"Updated\",\"reason\":\"First\",\"reason\":\"Second\"}");
        assertRejected("[]");
    }

    private void assertRejected(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, PatchIncidentRequest.class))
            .isInstanceOf(JacksonException.class);
    }
}
