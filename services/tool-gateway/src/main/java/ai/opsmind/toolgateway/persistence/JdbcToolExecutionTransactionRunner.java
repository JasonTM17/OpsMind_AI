package ai.opsmind.toolgateway.persistence;

import java.util.function.Supplier;

import ai.opsmind.toolgateway.application.ToolExecutionTransactionRunner;

import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcToolExecutionTransactionRunner
    implements ToolExecutionTransactionRunner {

    private final TransactionTemplate transactions;

    public JdbcToolExecutionTransactionRunner(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public <T> T required(Supplier<T> operation) {
        T result = transactions.execute(status -> operation.get());
        if (result == null) throw new IllegalStateException("Tool transaction returned no result.");
        return result;
    }
}
