package ai.opsmind.toolgateway.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DenialCode {
    CALLER_UNAUTHORIZED("caller.unauthorized"),
    CAPABILITY_INVALID("capability.invalid"),
    CAPABILITY_UNAVAILABLE("capability.verifier-unavailable"),
    CAPABILITY_EXPIRED("capability.expired"),
    CAPABILITY_SCOPE_MISMATCH("capability.scope-mismatch"),
    CAPABILITY_REPLAYED("capability.replayed"),
    REQUEST_INVALID("request.invalid"),
    REQUEST_OVERSIZE("request.oversize"),
    UNKNOWN_ACTION("action.unknown"),
    ACTION_DISABLED("action.disabled"),
    ARGUMENTS_INVALID("arguments.invalid"),
    DEADLINE_EXPIRED("deadline.expired"),
    DEADLINE_OUTSIDE_CAPABILITY("deadline.outside-capability"),
    RESULT_OVERSIZE("result.oversize"),
    EXECUTION_CONFLICT("execution.conflict"),
    EXECUTION_IN_PROGRESS("execution.in-progress"),
    EXECUTION_BACKPRESSURE("execution.backpressure"),
    EXECUTION_STORE_UNAVAILABLE("execution.store-unavailable"),
    AUDIT_UNAVAILABLE("audit.unavailable"),
    CONNECTOR_TIMEOUT("connector.timeout"),
    CONNECTOR_CANCELLED("connector.cancelled"),
    CONNECTOR_FAILED("connector.failed");

    private final String value;

    DenialCode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
