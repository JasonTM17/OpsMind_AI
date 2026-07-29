package ai.opsmind.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;

class WorkflowReconcilerDataSourcePropertiesTest {

    @Test
    void exactReconcilerLoginIsRequiredAndPasswordIsRedacted() {
        WorkflowReconcilerDataSourceProperties properties = configured();

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.toString())
            .contains("opsmind_workflow_reconciler", "password=<redacted>")
            .doesNotContain("synthetic-secret");
    }

    @Test
    void dispatcherLoginCannotBeReused() {
        WorkflowReconcilerDataSourceProperties properties = configured();
        properties.setUsername("opsmind_dispatcher");

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside policy");
    }

    @Test
    void databaseIdentityRejectsOwnerOrDispatcherEscalation() {
        assertThatThrownBy(() -> new WorkflowReconcilerDatabaseIdentity(
            "opsmind_workflow_reconciler", "opsmind_dispatch_resolver"
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queryTimeoutIsBounded() {
        WorkflowReconcilerDataSourceProperties properties = configured();
        properties.setQueryTimeoutSeconds(31);

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside policy");
    }

    @Test
    void queryAndTransactionTimeoutsUseTheValidatedBound() {
        WorkflowReconcilerDataSourceProperties properties = configured();
        WorkflowReconcilerDataSourceConfiguration configuration =
            new WorkflowReconcilerDataSourceConfiguration();
        DataSource dataSource = mock(DataSource.class);

        assertThat(configuration.workflowReconcilerJdbcTemplate(
            dataSource, properties
        ).getQueryTimeout()).isEqualTo(1);
        assertThat((JdbcTransactionManager)
            configuration.workflowReconcilerTransactionManager(
                dataSource, properties
            )).extracting(JdbcTransactionManager::getDefaultTimeout)
            .isEqualTo(1);
    }

    private WorkflowReconcilerDataSourceProperties configured() {
        WorkflowReconcilerDataSourceProperties properties =
            new WorkflowReconcilerDataSourceProperties();
        properties.setUrl("jdbc:postgresql://db.example.test:5432/opsmind");
        properties.setUsername("opsmind_workflow_reconciler");
        properties.setPassword("synthetic-secret");
        return properties;
    }
}
