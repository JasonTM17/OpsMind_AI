package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_B;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import ai.opsmind.platform.testing.PostgresIntegrationEnvironment;
import ai.opsmind.platform.testing.PostgresTenantFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("persistence")
@EnabledIfEnvironmentVariable(named = "OPSMIND_PHASE4_DB_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncidentListHttpPersistenceIntegrationTest {

    private static final String TOKEN_A = "phase4-list-token-a";
    private static final String TOKEN_B = "phase4-list-token-b";
    private static final Instant TIED_TIME = Instant.parse("2030-01-01T00:00:00Z");

    @LocalServerPort
    private int port;

    @MockitoBean(name = "oidcJwtDecoder")
    private JwtDecoder jwtDecoder;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
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
    void seedAndConfigureTokens() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        admin = new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
        when(jwtDecoder.decode(TOKEN_A)).thenReturn(token(TOKEN_A, "phase3-operator-a"));
        when(jwtDecoder.decode(TOKEN_B)).thenReturn(token(TOKEN_B, "phase3-operator-b"));
    }

    @Test
    void authorizedStableTraversalIsTenantScopedFilteredAndSideEffectFree() throws Exception {
        List<UUID> openIds = List.of(
            uuid("90000000-0000-4000-8000-000000000004"),
            uuid("90000000-0000-4000-8000-000000000003")
        );
        List<UUID> investigatingIds = List.of(
            uuid("90000000-0000-4000-8000-000000000002"),
            uuid("90000000-0000-4000-8000-000000000001")
        );
        for (UUID id : openIds) seedIncident(id, TENANT_A, PROJECT_A, "OPEN", TIED_TIME);
        for (UUID id : investigatingIds) {
            seedIncident(id, TENANT_A, PROJECT_A, "INVESTIGATING", TIED_TIME);
        }
        UUID foreignId = uuid("90000000-0000-4000-8000-000000000099");
        seedIncident(foreignId, TENANT_B, PROJECT_B, "OPEN", TIED_TIME.plusSeconds(1));
        Map<String, Long> before = sideEffectCounts();

        JsonNode first = successfulPage(collectionPath(TENANT_A, PROJECT_A) + "?pageSize=2", TOKEN_A);
        assertExactSummaryShape(first.path("items"));
        assertThat(ids(first)).containsExactly(openIds.get(0), openIds.get(1));
        assertThat(first.path("hasMore").booleanValue()).isTrue();
        String cursor = first.path("nextPageToken").stringValue();

        JsonNode second = successfulPage(
            collectionPath(TENANT_A, PROJECT_A) + "?pageSize=2&pageToken=" + cursor,
            TOKEN_A
        );
        assertThat(ids(second)).containsExactly(investigatingIds.get(0), investigatingIds.get(1));
        assertThat(second.path("hasMore").booleanValue()).isFalse();
        assertThat(second.has("nextPageToken")).isFalse();
        Set<UUID> traversal = new LinkedHashSet<>(ids(first));
        traversal.addAll(ids(second));
        assertThat(traversal).containsExactlyElementsOf(List.of(
            openIds.get(0), openIds.get(1), investigatingIds.get(0), investigatingIds.get(1)
        ));
        assertThat(traversal).doesNotContain(foreignId);

        JsonNode filtered = successfulPage(
            collectionPath(TENANT_A, PROJECT_A) + "?status=INVESTIGATING&pageSize=25",
            TOKEN_A
        );
        assertThat(ids(filtered)).containsExactlyElementsOf(investigatingIds);

        HttpResponse<String> crossTenant = get(
            collectionPath(TENANT_B, PROJECT_B), TOKEN_A
        );
        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(crossTenant.body()).doesNotContain(foreignId.toString());

        HttpResponse<String> filterMismatch = get(
            collectionPath(TENANT_A, PROJECT_A)
                + "?status=OPEN&pageToken=" + cursor,
            TOKEN_A
        );
        assertThat(filterMismatch.statusCode()).isEqualTo(400);
        assertThat(filterMismatch.body()).contains("pagination.invalid-token");
        assertThat(sideEffectCounts()).isEqualTo(before);

        UUID newlyUpdated = uuid("90000000-0000-4000-8000-000000000098");
        seedIncident(newlyUpdated, TENANT_A, PROJECT_A, "OPEN", TIED_TIME.plusSeconds(2));
        Map<String, Long> afterConcurrentWrite = sideEffectCounts();
        JsonNode refreshed = successfulPage(
            collectionPath(TENANT_A, PROJECT_A) + "?pageSize=2", TOKEN_A
        );
        assertThat(ids(refreshed).get(0)).isEqualTo(newlyUpdated);
        assertThat(ids(second)).doesNotContain(newlyUpdated);
        assertThat(sideEffectCounts()).isEqualTo(afterConcurrentWrite);
    }

    private JsonNode successfulPage(String path, String token) throws Exception {
        HttpResponse<String> response = get(path, token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        return jsonMapper.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void seedIncident(
        UUID incidentId,
        UUID organizationId,
        UUID projectId,
        String status,
        Instant updatedAt
    ) {
        UUID actor = organizationId.equals(TENANT_A)
            ? USER_A
            : PostgresTenantFixtures.USER_B;
        admin.update(
            "INSERT INTO incidents (id, organization_id, project_id, title, description, "
                + "severity, status, created_by, updated_by, created_at, updated_at, version) "
                + "VALUES (?, ?, ?, ?, ?, 'SEV1', 'OPEN', ?, ?, ?, ?, 0) "
                + "ON CONFLICT (id) DO NOTHING",
            incidentId,
            organizationId,
            projectId,
            "Incident " + incidentId,
            "List pagination fixture",
            actor,
            actor,
            java.sql.Timestamp.from(updatedAt),
            java.sql.Timestamp.from(updatedAt)
        );
        if (!"OPEN".equals(status)) {
            admin.update(
                "UPDATE incidents SET status = ?, updated_by = ?, updated_at = ?, version = 1 "
                    + "WHERE id = ? AND organization_id = ? AND project_id = ?",
                status,
                actor,
                java.sql.Timestamp.from(updatedAt),
                incidentId,
                organizationId,
                projectId
            );
        }
    }

    private Map<String, Long> sideEffectCounts() {
        return Map.of(
            "incidents", count("incidents"),
            "timeline", count("incident_timeline_events"),
            "audit", count("audit_events"),
            "outbox", count("outbox_events"),
            "idempotency", count("idempotency_records")
        );
    }

    private long count(String table) {
        Long value = admin.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE organization_id = ?",
            Long.class,
            TENANT_A
        );
        return value == null ? 0 : value;
    }

    private void assertExactSummaryShape(JsonNode items) {
        Set<String> expected = Set.of("id", "title", "severity", "status", "updatedAt", "version");
        for (JsonNode item : items) {
            Set<String> fields = new LinkedHashSet<>();
            for (String fieldName : item.properties().keySet()) {
                fields.add(fieldName);
            }
            assertThat(fields).isEqualTo(expected);
        }
    }

    private List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
            ids.add(UUID.fromString(item.path("id").stringValue()));
        }
        return ids;
    }

    private String collectionPath(UUID organizationId, UUID projectId) {
        return "/api/v1/organizations/" + organizationId + "/projects/" + projectId + "/incidents";
    }

    private Jwt token(String value, String subject) {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        return Jwt.withTokenValue(value).header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind").subject(subject)
            .audience(List.of("opsmind-platform-api")).issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300)).claim("scope", "incident:read")
            .claim("amr", List.of("mfa")).build();
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }
}
