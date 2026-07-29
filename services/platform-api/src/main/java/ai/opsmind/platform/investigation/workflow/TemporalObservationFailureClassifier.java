package ai.opsmind.platform.investigation.workflow;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.temporal.common.converter.DataConverterException;

final class TemporalObservationFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 16;

    private TemporalObservationFailureClassifier() {
    }

    static InvestigationWorkflowObservation describe(Throwable failure) {
        Optional<Status.Code> status = findStatus(failure);
        if (status.filter(code -> code == Status.Code.NOT_FOUND).isPresent()) {
            return InvestigationWorkflowObservation.absent();
        }
        return classify(
            failure, status, "workflow.reconciliation-description-malformed"
        );
    }

    static InvestigationWorkflowObservation history(Throwable failure) {
        Optional<Status.Code> status = findStatus(failure);
        if (status.filter(code -> code == Status.Code.NOT_FOUND).isPresent()) {
            return InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-history-disappeared"
            );
        }
        return classify(failure, status, "workflow.reconciliation-history-malformed");
    }

    private static InvestigationWorkflowObservation classify(
        Throwable failure,
        Optional<Status.Code> status,
        String blockedCode
    ) {
        if (status.filter(code ->
            code == Status.Code.PERMISSION_DENIED
                || code == Status.Code.UNAUTHENTICATED
        ).isPresent()) {
            return InvestigationWorkflowObservation.blocked(
                "workflow.reconciliation-permission-denied"
            );
        }
        if (status.filter(TemporalObservationFailureClassifier::retryable).isPresent()) {
            return InvestigationWorkflowObservation.retry(
                retryCode(status.orElseThrow())
            );
        }
        String statuslessCode = statuslessRetryCode(failure);
        if (status.isEmpty() && statuslessCode != null) {
            return InvestigationWorkflowObservation.retry(statuslessCode);
        }
        return InvestigationWorkflowObservation.blocked(blockedCode);
    }

    private static Optional<Status.Code> findStatus(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof StatusRuntimeException status) {
                return Optional.of(status.getStatus().getCode());
            }
            if (current instanceof StatusException status) {
                return Optional.of(status.getStatus().getCode());
            }
            current = next(current);
        }
        return Optional.empty();
    }

    private static String statuslessRetryCode(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof DataConverterException) {
                return null;
            }
            if (current instanceof TimeoutException) {
                return "workflow.temporal-timeout";
            }
            if (current instanceof IOException) {
                return "workflow.temporal-io-failure";
            }
            current = next(current);
        }
        return null;
    }

    private static Throwable next(Throwable current) {
        return current.getCause() == current ? null : current.getCause();
    }

    private static boolean retryable(Status.Code code) {
        return switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED,
                UNKNOWN, INTERNAL, CANCELLED -> true;
            default -> false;
        };
    }

    private static String retryCode(Status.Code code) {
        return switch (code) {
            case UNAVAILABLE -> "workflow.temporal-unavailable";
            case DEADLINE_EXCEEDED -> "workflow.temporal-deadline-exceeded";
            case RESOURCE_EXHAUSTED -> "workflow.temporal-resource-exhausted";
            case ABORTED -> "workflow.temporal-aborted";
            case UNKNOWN -> "workflow.temporal-unknown";
            case INTERNAL -> "workflow.temporal-internal";
            case CANCELLED -> "workflow.temporal-cancelled";
            default -> throw new IllegalArgumentException(
                "Temporal observation status is not retryable."
            );
        };
    }
}
