package ai.opsmind.toolgateway.application;

import java.util.function.Supplier;

/** Non-persistent runner used only by fixture and fail-closed profiles. */
public final class DirectToolExecutionTransactionRunner implements ToolExecutionTransactionRunner {

    @Override
    public <T> T required(Supplier<T> operation) {
        return operation.get();
    }
}
