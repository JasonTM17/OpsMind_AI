package ai.opsmind.platform.evidence.artifact;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.identity.OpsMindPrincipal;
import ai.opsmind.platform.investigation.application.InvestigationRunStore;
import ai.opsmind.platform.investigation.domain.InvestigationCommand;
import ai.opsmind.platform.investigation.domain.InvestigationStateMachine;
import ai.opsmind.platform.investigation.integration.InvestigationAiRuntimeClient;
import ai.opsmind.platform.investigation.integration.InvestigationToolGatewayClient;
import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE4C_ARTIFACT_DB_INTEGRATION", matches = "true")
@SpringBootTest(properties = {
    "opsmind.investigation.enabled=true", "opsmind.investigation.store=postgres"
})
class EvidenceArtifactMetadataPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired private InvestigationRunStore investigationStore;
    @Autowired private EvidenceArtifactMetadataService artifactService;
    @MockitoBean private InvestigationAiRuntimeClient aiRuntimeClient;
    @MockitoBean private InvestigationToolGatewayClient toolGatewayClient;

    private JdbcTemplate admin;
    private UUID incidentId;

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required("SPRING_DATASOURCE_URL"));
        properties.add("spring.datasource.username", () -> required("POSTGRES_APP_USER"));
        properties.add("spring.datasource.password", () -> required("POSTGRES_APP_PASSWORD"));
        properties.add("spring.flyway.enabled", () -> "false");
        properties.add("opsmind.persistence.enabled", () -> "true");
    }

    @BeforeEach
    void seedAuthorizedIncident() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        admin = new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
        incidentId = UUID.randomUUID();
        admin.update(
            "INSERT INTO incidents (id, organization_id, project_id, title, description, severity, "
                + "status, created_by, updated_by, created_at, updated_at, version) VALUES "
                + "(?, ?, ?, 'Artifact metadata', 'Durable artifact control-plane test', 'SEV2', "
                + "'OPEN', ?, ?, ?, ?, 0)",
            incidentId, TENANT_A, PROJECT_A, USER_A, USER_A, Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    @Test
    void createsOnePendingArtifactWithAnExactAuditAndRejectsReplayDrift() {
        UUID runId = createRun();
        EvidenceArtifactCreateCommand command = command(runId, "v1");

        EvidenceArtifactMetadata first = artifactService.create(
            principal("phase3-operator-a"), TENANT_A, PROJECT_A, incidentId, command
        );
        EvidenceArtifactMetadata replay = artifactService.create(
            principal("phase3-operator-a"), TENANT_A, PROJECT_A, incidentId, command
        );

        assertThat(replay).isEqualTo(first);
        assertThat(first.lifecycleState()).isEqualTo(EvidenceArtifactLifecycleState.PENDING_UPLOAD);
        assertThat(count("SELECT count(*) FROM evidence_artifacts WHERE organization_id = ?", TENANT_A))
            .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM evidence_artifact_events WHERE organization_id = ?", TENANT_A))
            .isEqualTo(1);
        assertThat(count(
            "SELECT count(*) FROM audit_events WHERE schema_version = 'evidence-artifact-audit-v1'"
        )).isEqualTo(1);
        assertThat(admin.queryForObject(
            "SELECT payload::text FROM audit_events WHERE event_id = ?", String.class,
            EvidenceArtifactIdentity.initialEventId(TENANT_A, first.artifactId())
        )).doesNotContain("storageKey", "credential", "objectUrl", "raw");

        assertThatThrownBy(() -> artifactService.create(
            principal("phase3-operator-a"), TENANT_A, PROJECT_A, incidentId, command(runId, "v2")
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.idempotency-conflict"));
    }

    @Test
    void hidesPendingMetadataAndRejectsCrossTenantAndDirectMutation() {
        UUID runId = createRun();
        EvidenceArtifactMetadata artifact = artifactService.create(
            principal("phase3-operator-a"), TENANT_A, PROJECT_A, incidentId, command(runId, "v1")
        );
        assertThatThrownBy(() -> artifactService.requireReadableMetadata(
            principal("phase3-operator-a"), TENANT_A, PROJECT_A, incidentId, artifact.artifactId()
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.not-found"));
        assertThatThrownBy(() -> artifactService.create(
            principal("phase3-operator-b"), TENANT_B, PROJECT_B, incidentId, command(runId, "v1")
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("resource.not-found"));
        grantAnalyzeAccessToSecondTenantAOperator();
        assertThatThrownBy(() -> artifactService.create(
            principal("phase3-operator-b"), TENANT_A, PROJECT_A, incidentId, command(runId, "v1")
        )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
            assertThat(exception.code()).isEqualTo("evidence-artifact.not-found"));
        assertSqlState(() -> admin.update(
            "UPDATE evidence_artifacts SET lifecycle_state = 'AVAILABLE' WHERE organization_id = ?",
            TENANT_A
        ), "42501");
        assertSqlState(() -> admin.update(
            "DELETE FROM evidence_artifacts WHERE organization_id = ?", TENANT_A
        ), "42501");
        assertSqlState(() -> admin.execute(
            "TRUNCATE TABLE evidence_artifact_upload_attempts, "
                + "evidence_artifacts, evidence_artifact_events"
        ), "42501");
    }

    private UUID createRun() {
        UUID runId = UUID.randomUUID();
        InvestigationCommand.Start start = new InvestigationCommand.Start(
            runId, TENANT_A, PROJECT_A, incidentId, USER_A,
            new InvestigationCommand.Budget(2, 0, 1, 100), NOW, NOW.plusSeconds(120)
        );
        investigationStore.create(InvestigationStateMachine.start(start));
        return runId;
    }

    private EvidenceArtifactCreateCommand command(UUID runId, String sourceVersion) {
        return new EvidenceArtifactCreateCommand(
            UUID.fromString("77777777-7777-4777-8777-777777777777"), runId,
            "metric", "prometheus:synthetic/opsmind-api", sourceVersion, "redacted-metrics",
            EvidenceArtifactDigest.parse("sha256:" + "d".repeat(64)), 2_048
        );
    }

    private void grantAnalyzeAccessToSecondTenantAOperator() {
        admin.update("""
            INSERT INTO organization_memberships (organization_id, user_id, role)
            VALUES (?, ?, 'SRE')
            ON CONFLICT (organization_id, user_id) DO UPDATE
                SET role = EXCLUDED.role, status = 'active'
            """, TENANT_A, USER_B);
        admin.update("""
            INSERT INTO project_memberships (organization_id, project_id, user_id, role)
            VALUES (?, ?, ?, 'SRE')
            ON CONFLICT (project_id, user_id) DO UPDATE
                SET role = EXCLUDED.role, status = 'active'
            """, TENANT_A, PROJECT_A, USER_B);
    }

    private int count(String sql, Object... arguments) {
        return admin.queryForObject(sql, Integer.class, arguments);
    }

    private void assertSqlState(Runnable operation, String expected) {
        assertThatThrownBy(operation::run).satisfies(error -> {
            String actual = null;
            for (Throwable current = error; current != null; current = current.getCause()) {
                if (current instanceof SQLException sql) actual = sql.getSQLState();
            }
            assertThat(actual).isEqualTo(expected);
        });
    }

    private OpsMindPrincipal principal(String subject) {
        return new OpsMindPrincipal(
            URI.create("https://idp.example.test/opsmind"), subject, null, null,
            Set.of("incident:analyze")
        );
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
