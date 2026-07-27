package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE7_DB_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncidentActivityTimelineHttpIntegrationTest {

    private static final String TOKEN_A = "activity-token-a";
    private static final String TOKEN_B = "activity-token-b";
    private static final UUID SAME_ORG_PROJECT =
        UUID.fromString("aaaaaaa2-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String ACTIVITY_MEDIA_TYPE =
        "application/vnd.opsmind.incident-activity-timeline.v1+json";

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean(name = "oidcJwtDecoder")
    private JwtDecoder jwtDecoder;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private JdbcTemplate admin;
    private TransactionTemplate adminTransactions;

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
    void seedFixturesAndTokens() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        );
        admin = new JdbcTemplate(dataSource);
        adminTransactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        admin.update(
            "INSERT INTO projects (id, organization_id, slug, name) VALUES (?, ?, 'project-a2', "
                + "'Phase 7 Project A2') ON CONFLICT (id) DO NOTHING",
            SAME_ORG_PROJECT, TENANT_A
        );
        admin.update(
            "INSERT INTO project_memberships (organization_id, project_id, user_id, role) "
                + "VALUES (?, ?, ?, 'SRE') ON CONFLICT (project_id, user_id) DO UPDATE "
                + "SET role = 'SRE', status = 'active'",
            TENANT_A, SAME_ORG_PROJECT, USER_A
        );
        whenDecoded(TOKEN_A, "phase3-operator-a", "incident:read incident:write incident:analyze");
        whenDecoded(TOKEN_B, "phase3-operator-b", "incident:read incident:analyze");
    }

    @Test
    void vendorOrdersTiesAndSeesLateTiedInvestigationAppend() throws Exception {
        String sentinel = "secret-free-text-" + UUID.randomUUID();
        HttpResponse<String> created = send(
            "POST", collectionPath(TENANT_A, PROJECT_A), TOKEN_A,
            "activity-create-" + UUID.randomUUID(), null,
            "{\"title\":\"API unavailable\",\"summary\":\"5xx spike\","
                + "\"severity\":\"SEV1\",\"reason\":\"" + sentinel + "\"}",
            null
        );
        assertThat(created.statusCode()).isEqualTo(201);
        UUID incidentId = UUID.fromString(jsonMapper.readTree(created.body()).get("id").stringValue());

        HttpResponse<String> transitioned = send(
            "POST", collectionPath(TENANT_A, PROJECT_A) + "/" + incidentId + "/transitions",
            TOKEN_A, "activity-transition-" + UUID.randomUUID(), "\"0\"",
            "{\"targetStatus\":\"INVESTIGATING\",\"reason\":\"triage\"}", null
        );
        assertThat(transitioned.statusCode()).isEqualTo(200);

        HttpResponse<String> first = sendActivity(
            incidentPath(TENANT_A, PROJECT_A, incidentId), TOKEN_A, 1, null
        );
        assertThat(first.statusCode())
            .as("unexpected first activity response: %s", first.body())
            .isEqualTo(200);
        String cursor = jsonMapper.readTree(first.body()).get("nextPageToken").stringValue();
        JsonNode firstItem = jsonMapper.readTree(first.body()).get("items").get(0);
        assertThat(first.headers().firstValue("content-type").orElse(""))
            .startsWith(ACTIVITY_MEDIA_TYPE);
        assertThat(first.headers().firstValue("vary")).contains("Accept");
        assertThat(first.headers().firstValue("cache-control")).contains("no-store");
        assertThat(firstItem.get("source").stringValue()).isEqualTo("INCIDENT");
        assertThat(first.body()).doesNotContain(sentinel, "triage");

        Instant tiedAt = admin.queryForObject(
            "SELECT occurred_at FROM incident_timeline_events WHERE incident_id = ? "
                + "AND incident_version = 0",
            Timestamp.class,
            incidentId
        ).toInstant();
        UUID runId = UUID.randomUUID();
        seedRunStarted(runId, TENANT_A, PROJECT_A, incidentId, USER_A, tiedAt);
        UUID backdatedRunId = UUID.randomUUID();
        seedRunStarted(
            backdatedRunId,
            TENANT_A,
            PROJECT_A,
            incidentId,
            USER_A,
            tiedAt.minusSeconds(1)
        );

        HttpResponse<String> continuation = send(
            "GET", incidentPath(TENANT_A, PROJECT_A, incidentId), TOKEN_A, null, null, null, cursor
        );
        assertThat(continuation.statusCode()).isEqualTo(200);
        JsonNode secondItem = jsonMapper.readTree(continuation.body()).get("items").get(0);
        assertThat(secondItem.get("source").stringValue()).isEqualTo("INVESTIGATION");
        assertThat(secondItem.get("investigationRunId").stringValue()).isEqualTo(runId.toString());
        assertThat(continuation.body()).doesNotContain(
            sentinel, "budget", "details", backdatedRunId.toString()
        );

        HttpResponse<String> freshTraversal = sendActivity(
            incidentPath(TENANT_A, PROJECT_A, incidentId), TOKEN_A, 1, null
        );
        assertThat(freshTraversal.statusCode()).isEqualTo(200);
        assertThat(freshTraversal.body()).contains(backdatedRunId.toString());
    }

    @Test
    void vendorRouteMaintainsHiddenDenialAcrossTenantProjectIncidentAndCursorScopes() throws Exception {
        HttpResponse<String> created = send(
            "POST", collectionPath(TENANT_A, PROJECT_A), TOKEN_A,
            "activity-scope-create-" + UUID.randomUUID(), null,
            "{\"title\":\"Scoped incident\",\"summary\":\"bounded\","
                + "\"severity\":\"SEV2\",\"reason\":\"authorized\"}",
            null
        );
        UUID incidentId = UUID.fromString(jsonMapper.readTree(created.body()).get("id").stringValue());

        assertThat(send(
            "GET", incidentPath(TENANT_B, PROJECT_B, incidentId), TOKEN_B, null, null, null, null
        ).statusCode()).isEqualTo(404);
        assertThat(send(
            "GET", incidentPath(TENANT_A, SAME_ORG_PROJECT, incidentId), TOKEN_A,
            null, null, null, null
        ).statusCode()).isEqualTo(404);
        assertThat(send(
            "GET", incidentPath(TENANT_A, PROJECT_A, UUID.randomUUID()), TOKEN_A,
            null, null, null, null
        ).statusCode()).isEqualTo(404);

        String foreignCursor = new IncidentTimelinePageToken().encodeActivity(
            UUID.randomUUID(), Instant.parse("2030-01-01T00:00:00Z"), 0, UUID.randomUUID()
        );
        assertThat(send(
            "GET", incidentPath(TENANT_A, PROJECT_A, incidentId), TOKEN_A,
            null, null, null, foreignCursor
        ).statusCode()).isEqualTo(400);
    }

    @Test
    void unionQueryExcludesSameTenantRowsFromOtherProjectsAndIncidents() throws Exception {
        UUID targetIncident = createIncident(TENANT_A, PROJECT_A, TOKEN_A, "target");
        UUID otherIncident = createIncident(TENANT_A, PROJECT_A, TOKEN_A, "other-incident");
        UUID otherProjectIncident = createIncident(
            TENANT_A, SAME_ORG_PROJECT, TOKEN_A, "other-project"
        );

        Instant targetTime = incidentCreatedAt(targetIncident);
        UUID targetRun = UUID.randomUUID();
        seedRunStarted(
            targetRun, TENANT_A, PROJECT_A, targetIncident, USER_A, targetTime
        );
        UUID otherIncidentRun = UUID.randomUUID();
        UUID otherIncidentRunEvent = seedRunStarted(
            otherIncidentRun,
            TENANT_A,
            PROJECT_A,
            otherIncident,
            USER_A,
            targetTime.minusSeconds(2)
        );
        UUID otherProjectRun = UUID.randomUUID();
        UUID otherProjectRunEvent = seedRunStarted(
            otherProjectRun,
            TENANT_A,
            SAME_ORG_PROJECT,
            otherProjectIncident,
            USER_A,
            targetTime.minusSeconds(1)
        );
        UUID otherIncidentEvent = incidentCreatedEventId(otherIncident);
        UUID otherProjectEvent = incidentCreatedEventId(otherProjectIncident);

        HttpResponse<String> response = sendActivity(
            incidentPath(TENANT_A, PROJECT_A, targetIncident), TOKEN_A, 100, null
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(targetRun.toString());
        assertThat(response.body()).doesNotContain(
            otherIncident.toString(),
            otherProjectIncident.toString(),
            otherIncidentRun.toString(),
            otherProjectRun.toString(),
            otherIncidentRunEvent.toString(),
            otherProjectRunEvent.toString(),
            otherIncidentEvent.toString(),
            otherProjectEvent.toString()
        );
    }

    private UUID createIncident(
        UUID organizationId,
        UUID projectId,
        String token,
        String label
    ) throws Exception {
        HttpResponse<String> response = send(
            "POST",
            collectionPath(organizationId, projectId),
            token,
            "activity-" + label + "-" + UUID.randomUUID(),
            null,
            "{\"title\":\"" + label + "\",\"summary\":\"bounded\","
                + "\"severity\":\"SEV2\",\"reason\":\"fixture\"}",
            null
        );
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(jsonMapper.readTree(response.body()).get("id").stringValue());
    }

    private Instant incidentCreatedAt(UUID incidentId) {
        return admin.queryForObject(
            "SELECT occurred_at FROM incident_timeline_events WHERE incident_id = ? "
                + "AND incident_version = 0",
            Timestamp.class,
            incidentId
        ).toInstant();
    }

    private UUID incidentCreatedEventId(UUID incidentId) {
        return admin.queryForObject(
            "SELECT event_id FROM incident_timeline_events WHERE incident_id = ? "
                + "AND incident_version = 0",
            UUID.class,
            incidentId
        );
    }

    private UUID seedRunStarted(
        UUID runId,
        UUID organizationId,
        UUID projectId,
        UUID incidentId,
        UUID actorId,
        Instant occurredAt
    ) {
        Instant deadline = occurredAt.plusSeconds(300);
        UUID eventId = UUID.randomUUID();
        String occurred = occurredAt.toString();
        String payload = String.format(
            "{\"eventId\":\"%s\",\"organizationId\":\"%s\",\"projectId\":\"%s\","
                + "\"incidentId\":\"%s\",\"runId\":\"%s\",\"sequenceNo\":1,"
                + "\"eventType\":\"RUN_STARTED\",\"actorId\":\"%s\",\"occurredAt\":\"%s\","
                + "\"details\":{\"runId\":\"%s\",\"incidentId\":\"%s\","
                + "\"budget\":{\"maxRounds\":3,\"maxToolCalls\":3,\"maxEvidenceItems\":10,"
                + "\"maxTokens\":1000},\"occurredAt\":\"%s\"}}",
            eventId, organizationId, projectId, incidentId, runId, actorId, occurred,
            runId, incidentId, occurred
        );
        adminTransactions.executeWithoutResult(status -> {
            admin.update(
                "INSERT INTO investigation_runs (run_id, organization_id, project_id, incident_id, "
                    + "actor_id, status, max_rounds, max_tool_calls, max_evidence_items, max_tokens, "
                    + "event_count, started_at, deadline_at) VALUES (?, ?, ?, ?, ?, 'CREATED', "
                    + "3, 3, 10, 1000, 1, ?, ?)",
                runId, organizationId, projectId, incidentId, actorId,
                Timestamp.from(occurredAt), Timestamp.from(deadline)
            );
            admin.update(
                "INSERT INTO investigation_run_events (event_id, organization_id, project_id, "
                    + "incident_id, run_id, sequence_no, event_type, actor_id, occurred_at, payload) "
                    + "VALUES (?, ?, ?, ?, ?, 1, 'RUN_STARTED', ?, ?, CAST(? AS jsonb))",
                eventId, organizationId, projectId, incidentId, runId, actorId,
                Timestamp.from(occurredAt), payload
            );
        });
        return eventId;
    }

    private HttpResponse<String> sendActivity(
        String path,
        String token,
        int pageSize,
        String pageToken
    ) throws Exception {
        String query = "?pageSize=" + pageSize
            + (pageToken == null ? "" : "&pageToken=" + pageToken);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path + query))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .header("Accept", ACTIVITY_MEDIA_TYPE)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(
        String method,
        String path,
        String token,
        String idempotencyKey,
        String ifMatch,
        String body,
        String pageToken
    ) throws Exception {
        String query = method.equals("GET")
            ? "?pageSize=1" + (pageToken == null ? "" : "&pageToken=" + pageToken)
            : "";
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path + query))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (method.equals("GET")) request.header("Accept", ACTIVITY_MEDIA_TYPE);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        else {
            request.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String collectionPath(UUID organizationId, UUID projectId) {
        return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/incidents";
    }

    private String incidentPath(UUID organizationId, UUID projectId, UUID incidentId) {
        return collectionPath(organizationId, projectId) + "/" + incidentId + "/timeline";
    }

    private void whenDecoded(String token, String subject, String scope) {
        org.mockito.Mockito.when(jwtDecoder.decode(token)).thenReturn(
            Jwt.withTokenValue(token).header("alg", "RS256")
                .issuer("https://idp.example.test/opsmind").subject(subject)
                .audience(List.of("opsmind-platform-api"))
                .issuedAt(Instant.parse("2030-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:05:00Z"))
                .claim("scope", scope).claim("amr", List.of("mfa")).build()
        );
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
