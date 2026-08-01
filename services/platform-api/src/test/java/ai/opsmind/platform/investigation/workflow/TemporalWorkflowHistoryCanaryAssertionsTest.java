package ai.opsmind.platform.investigation.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TemporalWorkflowHistoryCanaryAssertionsTest {

    @ParameterizedTest
    @MethodSource("prohibitedHistoryEvents")
    void scannerRejectsCanariesInEveryPersistedHistoryLocation(HistoryEvent event) {
        assertThatThrownBy(() ->
            TemporalWorkflowHistoryCanaryAssertions.assertNoProhibitedContent(List.of(event))
        ).isInstanceOf(AssertionError.class);
    }

    private static Stream<HistoryEvent> prohibitedHistoryEvents() {
        return Stream.of(
            startedWithInput("restart-prompt-canary"),
            startedWithMemo("restart-evidence-canary"),
            startedWithHeader("restart-bearer-token-canary"),
            startedWithSearchAttribute("restart-capability-token-canary"),
            HistoryEvent.newBuilder().setWorkflowExecutionFailedEventAttributes(
                WorkflowExecutionFailedEventAttributes.newBuilder().setFailure(
                    Failure.newBuilder().setMessage("restart-provider-canary")
                )
            ).build(),
            canceledWithDetails("restart-incident-title-canary"),
            canceledWithDetails("restart-summary-canary")
        );
    }

    private static HistoryEvent startedWithInput(String canary) {
        return started(WorkflowExecutionStartedEventAttributes.newBuilder()
            .setInput(payloads(canary)));
    }

    private static HistoryEvent startedWithMemo(String canary) {
        return started(WorkflowExecutionStartedEventAttributes.newBuilder()
            .setMemo(Memo.newBuilder().putFields("bounded", payload(canary))));
    }

    private static HistoryEvent startedWithHeader(String canary) {
        return started(WorkflowExecutionStartedEventAttributes.newBuilder()
            .setHeader(Header.newBuilder().putFields("bounded", payload(canary))));
    }

    private static HistoryEvent startedWithSearchAttribute(String canary) {
        return started(WorkflowExecutionStartedEventAttributes.newBuilder()
            .setSearchAttributes(
                SearchAttributes.newBuilder().putIndexedFields("bounded", payload(canary))
            ));
    }

    private static HistoryEvent started(WorkflowExecutionStartedEventAttributes.Builder attributes) {
        return HistoryEvent.newBuilder()
            .setWorkflowExecutionStartedEventAttributes(attributes)
            .build();
    }

    private static HistoryEvent canceledWithDetails(String canary) {
        return HistoryEvent.newBuilder().setWorkflowExecutionCanceledEventAttributes(
            WorkflowExecutionCanceledEventAttributes.newBuilder().setDetails(payloads(canary))
        ).build();
    }

    private static Payload payload(String content) {
        return Payload.newBuilder().setData(ByteString.copyFromUtf8(content)).build();
    }

    private static Payloads payloads(String content) {
        return Payloads.newBuilder().addPayloads(payload(content)).build();
    }
}
