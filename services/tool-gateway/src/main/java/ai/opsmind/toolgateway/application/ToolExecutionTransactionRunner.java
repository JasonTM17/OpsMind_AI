package ai.opsmind.toolgateway.application;

import java.util.function.Supplier;

public interface ToolExecutionTransactionRunner {

    <T> T required(TenantProjectScope scope, Supplier<T> operation);
}
