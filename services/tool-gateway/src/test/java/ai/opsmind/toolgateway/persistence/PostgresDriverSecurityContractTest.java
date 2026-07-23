package ai.opsmind.toolgateway.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

class PostgresDriverSecurityContractTest {

    @Test
    void usesApprovedPatchedPostgresDriver() {
        Package driverPackage = Driver.class.getPackage();

        assertThat(driverPackage.getImplementationVersion()).isEqualTo("42.7.13");
    }
}
