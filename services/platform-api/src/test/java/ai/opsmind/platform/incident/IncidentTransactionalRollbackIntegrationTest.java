package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.IdempotencyKey;
import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE4_DB_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncidentTransactionalRollbackIntegrationTest {

    private static final UUID INCIDENT_ID = UUID.fromString("4b000001-4444-4444-8444-444444444444");
    private static final UUID OPERATION_ID = UUID.fromString("4b100001-4444-4444-8444-444444444444");
    private static final UUID EVENT_ID = UUID.fromString("4b200001-4444-4444-8444-444444444444");
    private static final UUID BASELINE_AGGREGATE =
        UUID.fromString("4b300001-4444-4444-8444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2030-01-01T00:00:00Z");
    private static final String IDEMPOTENCY_KEY = "rollback-outbox-conflict-001";

    @Autowired
    private IncidentMutationService mutations;

    @MockitoBean
    private IncidentRuntimeValues runtimeValues;

    @MockitoBean(name = "oidcJwtDecoder")
    private JwtDecoder jwtDecoder;

    private JdbcTemplate admin;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> requiredEnvironment("SPRING_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> requiredEnvironment("POSTGRES_APP_USER"));
        properties.add("spring.datasource.password", () -> requiredEnvironment("POSTGRES_APP_PASSWORD"));
        properties.add("spring.flyway.enabled", () -> "false");
        properties.add("opsmind.persistence.enabled", () -> "true");
        properties.add("opsmind.security.mode", () -> "oidc");
        properties.add("opsmind.security.issuer-uri", () -> "https://idp.example.test/opsmind");
        properties.add("opsmind.security.audience", () -> "opsmind-platform-api");
    }

    @BeforeEach
    void seedConflictAndDeterministicRuntime() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        admin = new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        admin.update(
            "INSERT INTO outbox_events (event_id, organization_id, aggregate_type, aggregate_id, "
                + "aggregate_sequence, event_type, schema_version, correlation_id, occurred_at, "
                + "payload, payload_bytes, payload_digest) VALUES (?, ?, 'rollback-probe', ?, 1, "
                + "'ROLLBACK_PROBE', '1', ?, ?, '{}'::jsonb, ?, ?) ON CONFLICT (event_id) DO NOTHING",
            EVENT_ID,
            TENANT_A,
            BASELINE_AGGREGATE,
            OPERATION_ID,
            Timestamp.from(OCCURRED_AT),
            payload,
            MessageDigest.getInstance("SHA-256").digest(payload)
        );
        when(runtimeValues.now()).thenReturn(OCCURRED_AT);
        when(runtimeValues.newId()).thenReturn(INCIDENT_ID, OPERATION_ID, EVENT_ID);
    }

    @Test
    void outboxDatabaseFailureRollsBackWholeIncidentTransaction() {
        assertThatThrownBy(() -> mutations.create(
            principal(),
            TENANT_A,
            PROJECT_A,
            new IdempotencyKey(IDEMPOTENCY_KEY),
            new CreateIncidentRequest(
                "Rollback probe", "Verify atomic incident persistence.", IncidentSeverity.SEV2, "test"
            ),
            "rollback_trace_001"
        )).isInstanceOf(PlatformProblemException.class)
            .satisfies(exception -> assertThat(((PlatformProblemException) exception).code())
                .isEqualTo("event.duplicate-conflict"));

        assertThat(count("SELECT count(*) FROM incidents WHERE id = ?", INCIDENT_ID)).isZero();
        assertThat(count("SELECT count(*) FROM incident_timeline_events WHERE event_id = ?", EVENT_ID))
            .isZero();
        assertThat(count("SELECT count(*) FROM audit_events WHERE event_id = ?", EVENT_ID)).isZero();
        assertThat(count(
            "SELECT count(*) FROM idempotency_records WHERE organization_id = ? AND idempotency_key = ?",
            TENANT_A,
            IDEMPOTENCY_KEY
        )).isZero();
        assertThat(count(
            "SELECT count(*) FROM outbox_events WHERE event_id = ? AND aggregate_type = 'rollback-probe'",
            EVENT_ID
        )).isEqualTo(1);
    }

    private int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private OpsMindPrincipal principal() {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"),
            "phase3-operator-a",
            "Phase 3 Operator A",
            null,
            Set.of("incident:write")
        );
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }
}
