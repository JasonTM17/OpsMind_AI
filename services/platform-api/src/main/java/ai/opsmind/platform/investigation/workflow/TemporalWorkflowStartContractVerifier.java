package ai.opsmind.platform.investigation.workflow;

import java.util.Optional;

import io.temporal.api.common.v1.Memo;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;

final class TemporalWorkflowStartContractVerifier {

    private static final String MEMO_KEY = "opsmind_start_payload_digest";

    private final DataConverter dataConverter;

    TemporalWorkflowStartContractVerifier(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    DescriptionVerification verifyDescription(
        InvestigationWorkflowStartRequest expected,
        String expectedDigest,
        DescribeWorkflowExecutionResponse response
    ) {
        if (response == null || !response.hasWorkflowExecutionInfo()) {
            return DescriptionVerification.blocked();
        }
        WorkflowExecutionInfo info = response.getWorkflowExecutionInfo();
        if (!info.hasExecution() || !info.hasType() || info.getFirstRunId().isBlank()) {
            return DescriptionVerification.blocked();
        }
        if (!expected.workflowId().equals(info.getExecution().getWorkflowId())
            || !expected.workflowType().equals(info.getType().getName())
            || !expected.taskQueue().equals(info.getTaskQueue())) {
            return DescriptionVerification.mismatch();
        }
        Verification memo = verifyMemo(info.hasMemo() ? info.getMemo() : null, expectedDigest);
        return switch (memo) {
            case MATCH -> DescriptionVerification.match(info.getFirstRunId());
            case MISMATCH -> DescriptionVerification.mismatch();
            case BLOCKED -> DescriptionVerification.blocked();
        };
    }

    InvestigationWorkflowObservation verifyHistory(
        InvestigationWorkflowStartRequest expected,
        String expectedDigest,
        String firstRunId,
        HistoryEvent event
    ) {
        if (event == null
            || event.getEventId() != 1
            || !event.hasWorkflowExecutionStartedEventAttributes()) {
            return blockedHistory();
        }
        WorkflowExecutionStartedEventAttributes attributes =
            event.getWorkflowExecutionStartedEventAttributes();
        if (!complete(attributes)) {
            return blockedHistory();
        }
        if (!expected.workflowId().equals(attributes.getWorkflowId())
            || !expected.workflowType().equals(attributes.getWorkflowType().getName())
            || !expected.taskQueue().equals(attributes.getTaskQueue().getName())
            || !firstRunId.equals(attributes.getFirstExecutionRunId())
            || !firstRunId.equals(attributes.getOriginalExecutionRunId())) {
            return InvestigationWorkflowObservation.mismatch();
        }
        Verification memo = verifyMemo(attributes.getMemo(), expectedDigest);
        if (memo == Verification.MISMATCH) {
            return InvestigationWorkflowObservation.mismatch();
        }
        if (memo == Verification.BLOCKED) {
            return blockedHistory();
        }
        try {
            InvestigationWorkflowStartRequest actual = dataConverter.fromPayloads(
                0,
                Optional.of(attributes.getInput()),
                InvestigationWorkflowStartRequest.class,
                InvestigationWorkflowStartRequest.class
            );
            return expected.equals(actual)
                ? InvestigationWorkflowObservation.match(firstRunId)
                : InvestigationWorkflowObservation.mismatch();
        }
        catch (DataConverterException | IllegalArgumentException failure) {
            return InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-decode-failed"
            );
        }
    }

    private Verification verifyMemo(Memo memo, String expectedDigest) {
        if (memo == null || !memo.containsFields(MEMO_KEY)) {
            return Verification.MISMATCH;
        }
        try {
            String actual = dataConverter.fromPayload(
                memo.getFieldsOrThrow(MEMO_KEY), String.class, String.class
            );
            return expectedDigest.equals(actual) ? Verification.MATCH : Verification.MISMATCH;
        }
        catch (DataConverterException | IllegalArgumentException failure) {
            return Verification.BLOCKED;
        }
    }

    private static boolean complete(WorkflowExecutionStartedEventAttributes attributes) {
        return attributes.hasWorkflowType()
            && attributes.hasTaskQueue()
            && attributes.hasInput()
            && attributes.hasMemo()
            && !attributes.getWorkflowId().isBlank()
            && !attributes.getFirstExecutionRunId().isBlank()
            && !attributes.getOriginalExecutionRunId().isBlank();
    }

    private static InvestigationWorkflowObservation blockedHistory() {
        return InvestigationWorkflowObservation.blocked(
            "workflow.reconciliation-history-malformed"
        );
    }

    private enum Verification {
        MATCH,
        MISMATCH,
        BLOCKED
    }

    record DescriptionVerification(
        InvestigationWorkflowObservation observation,
        String firstRunId
    ) {
        static DescriptionVerification match(String firstRunId) {
            return new DescriptionVerification(null, firstRunId);
        }

        static DescriptionVerification mismatch() {
            return new DescriptionVerification(
                InvestigationWorkflowObservation.mismatch(), null
            );
        }

        static DescriptionVerification blocked() {
            return new DescriptionVerification(
                InvestigationWorkflowObservation.blocked(
                    "workflow.reconciliation-description-malformed"
                ),
                null
            );
        }

        boolean matched() {
            return firstRunId != null;
        }
    }
}
