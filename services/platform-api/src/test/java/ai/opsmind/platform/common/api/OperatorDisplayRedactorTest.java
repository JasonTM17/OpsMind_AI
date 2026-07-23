package ai.opsmind.platform.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OperatorDisplayRedactorTest {

    private final OperatorDisplayRedactor redactor = new OperatorDisplayRedactor();

    @Test
    void normalizesAndRedactsOneChangedLeafWithoutLeakingMatchCount() {
        String bearer = "opaque-" + "credential".repeat(4);
        String input = "Contact ops@example.test\u202E; Authorization: Bearer " + bearer
            + "\nquery: rate(private_metric[5m])"
            + "\nhttps://internal.example.test/path?token=value";

        OperatorDisplayRedactor.Redaction result = redactor.redact(input, "withheld");

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.value())
            .contains("[REDACTED_EMAIL]", "[REDACTED_SECRET]")
            .doesNotContain("ops@example.test", bearer, "private_metric", "internal.example.test");
    }

    @Test
    void preservesNullSemanticsAndIsIdempotentForMarkers() {
        assertThat(redactor.redact(null, "not recorded"))
            .isEqualTo(new OperatorDisplayRedactor.Redaction("not recorded", 0));
        assertThat(redactor.redact("[REDACTED_SECRET]", "not recorded"))
            .isEqualTo(new OperatorDisplayRedactor.Redaction("[REDACTED_SECRET]", 0));
    }

    @Test
    void compatibilityNormalizationClosesFullwidthLabelBypass() {
        String input = "ａｕｔｈｏｒｉｚａｔｉｏｎ: Bearer " + "x".repeat(40);

        OperatorDisplayRedactor.Redaction result = redactor.redact(input, "withheld");

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.value()).isEqualTo("[REDACTED_SECRET]");
    }
}
