package ai.opsmind.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresDriverSecurityContractTest {

    @Test
    void resolvesDriverOutsideChannelBindingDowngradeAdvisoryRange() throws Exception {
        Package driverPackage = Class.forName("org.postgresql.Driver").getPackage();

        assertThat(driverPackage.getImplementationVersion()).isEqualTo("42.7.13");
    }
}
