package ai.opsmind.platform.investigation.workflow;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowServiceException;
import io.temporal.common.converter.DataConverterException;

final class TemporalTransportFailureClassifier {

    private static final int MAX_EXCEPTION_CAUSE_DEPTH = 16;

    private TemporalTransportFailureClassifier() {
    }

    static InvestigationWorkflowStartException map(
        Throwable exception,
        String permanentCode
    ) {
        Optional<Status.Code> statusCode = findStatusCode(exception);
        if (statusCode.filter(TemporalTransportFailureClassifier::isRetryable)
            .isPresent() || isStatuslessTransportFailure(exception, statusCode)) {
            return InvestigationWorkflowStartException.retryable(
                "workflow.temporal-unavailable", exception
            );
        }
        return InvestigationWorkflowStartException.permanent(
            permanentCode, exception
        );
    }

    private static Optional<Status.Code> findStatusCode(Throwable exception) {
        Throwable current = exception;
        for (
            int depth = 0;
            current != null && depth < MAX_EXCEPTION_CAUSE_DEPTH;
            depth++
        ) {
            if (current instanceof StatusRuntimeException status) {
                return Optional.of(status.getStatus().getCode());
            }
            if (current instanceof StatusException status) {
                return Optional.of(status.getStatus().getCode());
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return Optional.empty();
    }

    private static boolean isRetryable(Status.Code code) {
        return code == Status.Code.UNAVAILABLE
            || code == Status.Code.DEADLINE_EXCEEDED
            || code == Status.Code.RESOURCE_EXHAUSTED
            || code == Status.Code.ABORTED
            || code == Status.Code.UNKNOWN
            || code == Status.Code.INTERNAL
            || code == Status.Code.CANCELLED;
    }

    private static boolean isStatuslessTransportFailure(
        Throwable exception,
        Optional<Status.Code> statusCode
    ) {
        if (statusCode.isPresent()
            || !(exception instanceof WorkflowServiceException)) {
            return false;
        }
        Throwable current = exception.getCause();
        for (
            int depth = 0;
            current != null && depth < MAX_EXCEPTION_CAUSE_DEPTH;
            depth++
        ) {
            if (current instanceof DataConverterException) {
                return false;
            }
            if (current instanceof IOException || current instanceof TimeoutException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
