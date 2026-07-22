package ai.opsmind.platform.investigation.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InvestigationPersistenceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues(
            "spring.profiles.active=persistence",
            "opsmind.persistence.enabled=false",
            "opsmind.investigation.store=postgres"
        )
        .withUserConfiguration(
            InvestigationEventLedger.class,
            JdbcInvestigationRunStore.class
        );

    @Test
    void migrationOnlyStartupDoesNotCreateInvestigationPersistenceBeans() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(InvestigationEventLedger.class);
            assertThat(context).doesNotHaveBean(JdbcInvestigationRunStore.class);
        });
    }
}
