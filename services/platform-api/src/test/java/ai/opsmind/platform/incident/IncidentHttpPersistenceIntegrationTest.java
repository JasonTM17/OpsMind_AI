package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

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
class IncidentHttpPersistenceIntegrationTest {

    private static final UUID ACTOR_C = UUID.fromString("c4000001-4444-4444-8444-444444444444");
    private static final String TOKEN_A = "phase4-token-a";
    private static final String TOKEN_B = "phase4-token-b";
    private static final String TOKEN_C = "phase4-token-c";

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean(name = "oidcJwtDecoder")
    private JwtDecoder jwtDecoder;

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

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
    void seedAndConfigureVerifiedTokens() throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        PostgresTenantFixtures.seed(environment);
        seedSecondTenantAActor(environment);
        when(jwtDecoder.decode(TOKEN_A)).thenReturn(token(TOKEN_A, "phase3-operator-a",
            "incident:read incident:write"));
        when(jwtDecoder.decode(TOKEN_B)).thenReturn(token(TOKEN_B, "phase3-operator-b",
            "incident:read incident:write"));
        when(jwtDecoder.decode(TOKEN_C)).thenReturn(token(TOKEN_C, "phase4-operator-c",
            "incident:read incident:write"));
    }

    @Test
    void routeSecurityMembershipRlsReplayAndActorIsolationStayJoined() throws Exception {
        String key = "http-create-" + UUID.randomUUID();
        HttpResponse<String> created = send("POST", collectionPath(), TOKEN_A, key, null, createBody());
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("etag")).contains("\"0\"");
        assertThat(created.headers().firstValue("x-operation-id")).isPresent();
        String incidentId = jsonMapper.readTree(created.body()).get("id").stringValue();

        HttpResponse<String> replay = send("POST", collectionPath(), TOKEN_A, key, null, createBody());
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(created.body());
        assertThat(replay.headers().firstValue("x-operation-id"))
            .isEqualTo(created.headers().firstValue("x-operation-id"));

        HttpResponse<String> actorMismatch = send(
            "POST", collectionPath(), TOKEN_C, key, null, createBody()
        );
        assertThat(actorMismatch.statusCode()).isEqualTo(409);
        assertThat(actorMismatch.body()).contains("idempotency.request-mismatch");

        HttpResponse<String> detail = send(
            "GET", collectionPath() + "/" + incidentId, TOKEN_A, null, null, null
        );
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.headers().firstValue("etag")).contains("\"0\"");

        HttpResponse<String> timeline = send(
            "GET", collectionPath() + "/" + incidentId + "/timeline", TOKEN_A, null, null, null
        );
        JsonNode timelineBody = jsonMapper.readTree(timeline.body());
        assertThat(timeline.statusCode()).isEqualTo(200);
        assertThat(timelineBody.get("items").size()).isEqualTo(1);
        assertThat(timelineBody.has("nextPageToken")).isFalse();

        HttpResponse<String> crossTenant = send(
            "GET", collectionPath() + "/" + incidentId, TOKEN_B, null, null, null
        );
        assertThat(crossTenant.statusCode()).isEqualTo(404);
        assertThat(crossTenant.body()).doesNotContain(incidentId);
    }

    @Test
    void concurrentHttpTransitionsProduceOneWinnerAndOneLogicalEvent() throws Exception {
        HttpResponse<String> created = send(
            "POST", collectionPath(), TOKEN_A, "race-create-" + UUID.randomUUID(), null, createBody()
        );
        String incidentId = jsonMapper.readTree(created.body()).get("id").stringValue();
        String transitionPath = collectionPath() + "/" + incidentId + "/transitions";
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return send("POST", transitionPath, TOKEN_A, "race-a-" + UUID.randomUUID(),
                    "\"0\"", transitionBody());
            });
            var second = executor.submit(() -> {
                start.await();
                return send("POST", transitionPath, TOKEN_A, "race-b-" + UUID.randomUUID(),
                    "\"0\"", transitionBody());
            });
            start.countDown();
            List<Integer> statuses = List.of(first.get().statusCode(), second.get().statusCode());
            assertThat(statuses).containsExactlyInAnyOrder(200, 412);
        }

        JsonNode timeline = jsonMapper.readTree(send(
            "GET", transitionPath.replace("/transitions", "/timeline"), TOKEN_A, null, null, null
        ).body());
        assertThat(timeline.get("items").size()).isEqualTo(2);
        assertThat(timeline.get("items").get(1).get("incidentVersion").longValue()).isEqualTo(1L);
    }

    private HttpResponse<String> send(
        String method,
        String path,
        String token,
        String idempotencyKey,
        String ifMatch,
        String body
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String collectionPath() {
        return "/api/v1/organizations/" + TENANT_A + "/projects/" + PROJECT_A + "/incidents";
    }

    private String createBody() {
        return "{\"title\":\"API unavailable\",\"summary\":\"5xx spike\","
            + "\"severity\":\"SEV1\",\"reason\":\"alert\"}";
    }

    private String transitionBody() {
        return "{\"targetStatus\":\"INVESTIGATING\",\"reason\":\"triage\"}";
    }

    private Jwt token(String value, String subject, String scope) {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        return Jwt.withTokenValue(value).header("alg", "RS256")
            .issuer("https://idp.example.test/opsmind").subject(subject)
            .audience(List.of("opsmind-platform-api")).issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(300)).claim("scope", scope)
            .claim("amr", List.of("mfa")).build();
    }

    private static void seedSecondTenantAActor(PostgresIntegrationEnvironment environment) {
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
        admin.update("INSERT INTO platform_users (id, issuer, subject, display_name) VALUES "
            + "(?, 'https://idp.example.test/opsmind', 'phase4-operator-c', 'Phase 4 Operator C') "
            + "ON CONFLICT (id) DO UPDATE SET status = 'active'", ACTOR_C);
        admin.update("INSERT INTO organization_memberships (organization_id, user_id, role) "
            + "VALUES (?, ?, 'SRE') ON CONFLICT (organization_id, user_id) DO UPDATE "
            + "SET role = 'SRE', status = 'active'", TENANT_A, ACTOR_C);
        admin.update("INSERT INTO project_memberships (organization_id, project_id, user_id, role) "
            + "VALUES (?, ?, ?, 'SRE') ON CONFLICT (project_id, user_id) DO UPDATE "
            + "SET role = 'SRE', status = 'active'", TENANT_A, PROJECT_A, ACTOR_C);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required.");
        return value;
    }
}
