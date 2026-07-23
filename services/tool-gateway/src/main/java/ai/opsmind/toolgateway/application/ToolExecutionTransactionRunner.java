package ai.opsmind.toolgateway.application;

import java.util.function.Supplier;

public interface ToolExecutionTransactionRunner {

    <T> T required(Supplier<T> operation);
}
