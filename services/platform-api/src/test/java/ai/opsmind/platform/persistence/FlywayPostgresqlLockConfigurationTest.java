package ai.opsmind.platform.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.flyway.autoconfigure.FlywayProperties;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class FlywayPostgresqlLockConfigurationTest {

    @Test
    void concurrentIndexMigrationsUseSessionLevelFlywayLock() throws IOException {
        var environment = new StandardEnvironment();
        var sources = new YamlPropertySourceLoader().load(
            "persistence",
            new ClassPathResource("application-persistence.yaml")
        );
        sources.forEach(environment.getPropertySources()::addLast);

        FlywayProperties properties = Binder.get(environment)
            .bind("spring.flyway", FlywayProperties.class)
            .orElseThrow(() -> new IllegalStateException("Flyway configuration is missing."));

        assertThat(properties.getPostgresql().getTransactionalLock()).isFalse();
    }
}
