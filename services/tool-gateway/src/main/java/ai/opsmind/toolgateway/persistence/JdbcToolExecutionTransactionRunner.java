package ai.opsmind.toolgateway.persistence;

import java.util.function.Supplier;

import ai.opsmind.toolgateway.application.TenantProjectScope;
import ai.opsmind.toolgateway.application.ToolExecutionTransactionRunner;

import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcToolExecutionTransactionRunner
    implements ToolExecutionTransactionRunner {

    private final TransactionTemplate transactions;
    private final GatewayTenantContextSql tenantContext;

    public JdbcToolExecutionTransactionRunner(
        TransactionTemplate transactions,
        GatewayTenantContextSql tenantContext
    ) {
        this.transactions = transactions;
        this.tenantContext = tenantContext;
    }

    @Override
    public <T> T required(TenantProjectScope scope, Supplier<T> operation) {
        if (scope == null || operation == null) {
            throw new IllegalArgumentException("Scoped tool transaction is incomplete.");
        }
        T result = transactions.execute(status -> {
            tenantContext.apply(scope);
            return operation.get();
        });
        if (result == null) throw new IllegalStateException("Tool transaction returned no result.");
        return result;
    }
}
