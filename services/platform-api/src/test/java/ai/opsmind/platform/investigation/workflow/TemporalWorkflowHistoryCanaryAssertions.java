package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import io.temporal.api.history.v1.HistoryEvent;

final class TemporalWorkflowHistoryCanaryAssertions {

    private static final List<String> PROHIBITED_CANARIES = List.of(
        "restart-prompt-canary",
        "restart-evidence-canary",
        "restart-bearer-token-canary",
        "restart-capability-token-canary",
        "restart-provider-canary",
        "restart-incident-title-canary",
        "restart-summary-canary",
        "prompt",
        "evidence_body",
        "bearer_token",
        "api_key",
        "provider_request",
        "capability_token",
        "title",
        "summary"
    );

    private TemporalWorkflowHistoryCanaryAssertions() {
    }

    static void assertNoProhibitedContent(List<HistoryEvent> history) {
        history.forEach(event -> scanMessage(event, value -> {
            String normalized = value.toLowerCase(Locale.ROOT);
            assertThat(PROHIBITED_CANARIES).noneMatch(normalized::contains);
        }));
    }

    private static void scanMessage(Message message, Consumer<String> assertion) {
        message.getAllFields().forEach((field, value) -> scanField(field, value, assertion));
    }

    private static void scanField(
        FieldDescriptor field,
        Object value,
        Consumer<String> assertion
    ) {
        if (field.isRepeated()) {
            ((List<?>) value).forEach(item -> scanValue(item, assertion));
        }
        else {
            scanValue(value, assertion);
        }
    }

    private static void scanValue(Object value, Consumer<String> assertion) {
        if (value instanceof Message nested) {
            scanMessage(nested, assertion);
        }
        else if (value instanceof ByteString bytes) {
            assertion.accept(bytes.toStringUtf8());
        }
        else if (value instanceof String text) {
            assertion.accept(text);
        }
    }
}
