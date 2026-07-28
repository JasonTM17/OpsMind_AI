package ai.opsmind.platform.investigation.workflow;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    prefix = "opsmind.investigation.workflow-starter",
    name = "enabled",
    havingValue = "true"
)
public final class InvestigationWorkflowStartTenantScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    public InvestigationWorkflowStartTenantScheduler(
        @Qualifier("dispatcherJdbcTemplate") JdbcTemplate jdbcTemplate,
        @Qualifier("dispatcherTransactionManager") PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<UUID> listReadyTenants(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Workflow starter tenant limit is invalid.");
        }
        return List.copyOf(Objects.requireNonNull(transactions.execute(status ->
            jdbcTemplate.query(
                "SELECT organization_id "
                    + "FROM public.opsmind_list_investigation_workflow_start_tenants(?)",
                (resultSet, rowNumber) ->
                    resultSet.getObject("organization_id", UUID.class),
                limit
            )
        )));
    }
}
