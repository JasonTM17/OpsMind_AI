package ai.opsmind.platform.incident;

import static ai.opsmind.platform.testing.PostgresTenantFixtures.PROJECT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.TENANT_A;
import static ai.opsmind.platform.testing.PostgresTenantFixtures.USER_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void resolutionClosureReplayAndTerminalFailuresPreserveOneDurableLifecycle() throws Exception {
        int idempotencyBefore = tenantIdempotencyCount();
        HttpResponse<String> created = send(
            "POST", collectionPath(), TOKEN_A, "closure-create-" + UUID.randomUUID(), null, createBody()
        );
        String incidentId = jsonMapper.readTree(created.body()).get("id").stringValue();
        String transitionPath = collectionPath() + "/" + incidentId + "/transitions";

        assertTransition(transitionPath, "\"0\"", "closure-investigate-", transitionBody(),
            "INVESTIGATING", 1);
        assertTransition(transitionPath, "\"1\"", "closure-resolve-", resolvedBody(),
            "RESOLVED", 2);

        String closureKey = "closure-close-" + UUID.randomUUID();
        HttpResponse<String> closed = send(
            "POST", transitionPath, TOKEN_A, closureKey, "\"2\"", closedBody()
        );
        assertThat(closed.statusCode()).isEqualTo(200);
        assertThat(closed.headers().firstValue("etag")).contains("\"3\"");
        JsonNode closedJson = jsonMapper.readTree(closed.body());
        assertThat(closedJson.get("status").stringValue()).isEqualTo("CLOSED");
        assertThat(closedJson.get("rootCause").stringValue()).isEqualTo("dependency saturation");
        assertThat(closedJson.get("resolutionSummary").stringValue()).isEqualTo("capacity restored");

        DurableCounts committed = durableCounts(UUID.fromString(incidentId));
        assertThat(committed).isEqualTo(new DurableCounts(4, 4, 4, idempotencyBefore + 4));
        assertEventLinkageAndSequence(UUID.fromString(incidentId));
        HttpResponse<String> replay = send(
            "POST", transitionPath, TOKEN_A, closureKey, "\"2\"", closedBody()
        );
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(closed.body());
        assertThat(replay.headers().firstValue("etag")).isEqualTo(closed.headers().firstValue("etag"));
        assertThat(replay.headers().firstValue("x-operation-id"))
            .isEqualTo(closed.headers().firstValue("x-operation-id"));
        assertThat(durableCounts(UUID.fromString(incidentId))).isEqualTo(committed);

        HttpResponse<String> stale = send(
            "POST", transitionPath, TOKEN_A, "closure-stale-" + UUID.randomUUID(),
            "\"2\"", transitionBody()
        );
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(durableCounts(UUID.fromString(incidentId))).isEqualTo(committed);

        for (IncidentStatus target : IncidentStatus.values()) {
            HttpResponse<String> terminal = send(
                "POST", transitionPath, TOKEN_A, "closure-terminal-" + UUID.randomUUID(),
                "\"3\"", terminalTransitionBody(target)
            );
            assertThat(terminal.statusCode()).as("CLOSED -> %s", target).isEqualTo(409);
            assertThat(terminal.body()).contains("incident.transition-not-allowed");
            assertThat(durableCounts(UUID.fromString(incidentId))).isEqualTo(committed);
        }

        HttpResponse<String> detailResponse = send(
            "GET", collectionPath() + "/" + incidentId, TOKEN_A, null, null, null
        );
        assertThat(detailResponse.statusCode()).isEqualTo(200);
        JsonNode detail = jsonMapper.readTree(detailResponse.body());
        assertThat(detail.get("status").stringValue()).isEqualTo("CLOSED");
        assertThat(detail.get("rootCause").stringValue()).isEqualTo("dependency saturation");
        assertThat(detail.get("resolutionSummary").stringValue()).isEqualTo("capacity restored");

        JsonNode timeline = jsonMapper.readTree(send(
            "GET", collectionPath() + "/" + incidentId + "/timeline", TOKEN_A, null, null, null
        ).body());
        assertThat(timeline.get("items").size()).isEqualTo(4);
        JsonNode closureEvent = timeline.get("items").get(3);
        assertThat(closureEvent.get("toStatus").stringValue()).isEqualTo("CLOSED");
        assertThat(closureEvent.get("rootCause").stringValue()).isEqualTo("dependency saturation");
        assertThat(closureEvent.get("resolutionSummary").stringValue()).isEqualTo("capacity restored");
    }

    @Test
    void metadataPatchAssignClearReplayAndHiddenOrderingStayAtomic() throws Exception {
        int idempotencyBefore = tenantIdempotencyCount();
        HttpResponse<String> created = send(
            "POST", collectionPath(), TOKEN_A, "patch-create-" + UUID.randomUUID(), null, createBody()
        );
        String incidentId = jsonMapper.readTree(created.body()).get("id").stringValue();
        String incidentPath = collectionPath() + "/" + incidentId;
        String assignKey = "patch-assign-" + UUID.randomUUID();
        String assignBody = "{\"ownerId\":\"" + ACTOR_C
            + "\",\"reason\":\"Primary on-call accepted\"}";

        HttpResponse<String> assigned = send(
            "PATCH", incidentPath, TOKEN_A, assignKey, "\"0\"", assignBody
        );
        assertThat(assigned.statusCode()).isEqualTo(200);
        assertThat(assigned.headers().firstValue("etag")).contains("\"1\"");
        assertThat(jsonMapper.readTree(assigned.body()).get("ownerId").stringValue())
            .isEqualTo(ACTOR_C.toString());
        DurableCounts assignedCounts = durableCounts(UUID.fromString(incidentId));
        assertThat(assignedCounts).isEqualTo(new DurableCounts(2, 2, 2, idempotencyBefore + 2));

        HttpResponse<String> replay = send(
            "PATCH", incidentPath, TOKEN_A, assignKey, "\"0\"", assignBody
        );
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(assigned.body());
        assertThat(replay.headers().firstValue("x-operation-id"))
            .isEqualTo(assigned.headers().firstValue("x-operation-id"));
        assertThat(durableCounts(UUID.fromString(incidentId))).isEqualTo(assignedCounts);

        UUID ineligibleOwner = UUID.randomUUID();
        HttpResponse<String> ineligible = send(
            "PATCH", incidentPath, TOKEN_A, "patch-ineligible-" + UUID.randomUUID(), "\"1\"",
            "{\"ownerId\":\"" + ineligibleOwner + "\",\"reason\":\"Invalid owner\"}"
        );
        assertThat(ineligible.statusCode()).isEqualTo(422);
        assertThat(ineligible.body()).contains("incident.owner-ineligible");
        assertThat(durableCounts(UUID.fromString(incidentId))).isEqualTo(assignedCounts);

        HttpResponse<String> foreign = send(
            "PATCH", incidentPath, TOKEN_A, "patch-foreign-" + UUID.randomUUID(), "\"1\"",
            "{\"ownerId\":\"" + USER_B + "\",\"reason\":\"Foreign owner\"}"
        );
        assertThat(foreign.statusCode()).isEqualTo(422);
        assertThat(foreign.body()).contains("incident.owner-ineligible");
        assertThat(durableCounts(UUID.fromString(incidentId))).isEqualTo(assignedCounts);

        assertInactiveOwnerRejectedWithoutEffects(
            incidentPath,
            incidentId,
            assignedCounts,
            "UPDATE organization_memberships SET status = ? "
                + "WHERE organization_id = ? AND user_id = ?",
            "active"
        );
        assertInactiveOwnerRejectedWithoutEffects(
            incidentPath,
            incidentId,
            assignedCounts,
            "UPDATE platform_users SET status = ? WHERE id = ?",
            "active"
        );

        HttpResponse<String> hidden = send(
            "PATCH", collectionPath() + "/" + UUID.randomUUID(), TOKEN_A,
            "patch-hidden-" + UUID.randomUUID(), "\"0\"",
            "{\"ownerId\":\"" + ineligibleOwner + "\",\"reason\":\"Hidden target\"}"
        );
        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(hidden.body()).doesNotContain(ineligibleOwner.toString());

        HttpResponse<String> cleared = send(
            "PATCH", incidentPath, TOKEN_A, "patch-clear-" + UUID.randomUUID(), "\"1\"",
            "{\"ownerId\":null,\"reason\":\"Returned to queue\"}"
        );
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(cleared.headers().firstValue("etag")).contains("\"2\"");
        assertThat(jsonMapper.readTree(cleared.body()).has("ownerId")).isFalse();

        JsonNode timeline = jsonMapper.readTree(send(
            "GET", incidentPath + "/timeline", TOKEN_A, null, null, null
        ).body());
        assertThat(timeline.get("items").size()).isEqualTo(3);
        assertThat(timeline.get("items").get(1).get("eventType").stringValue())
            .isEqualTo("INCIDENT_METADATA_PATCHED");
        assertThat(timeline.get("items").get(1).get("metadata").get("ownerId").stringValue())
            .isEqualTo(ACTOR_C.toString());
        assertThat(timeline.get("items").get(2).get("metadata").get("ownerId").isNull()).isTrue();
    }

    @Test
    void concurrentSameVersionMetadataPatchesCommitExactlyOneWinner() throws Exception {
        int idempotencyBefore = tenantIdempotencyCount();
        HttpResponse<String> created = send(
            "POST", collectionPath(), TOKEN_A, "patch-race-create-" + UUID.randomUUID(),
            null, createBody()
        );
        String incidentId = jsonMapper.readTree(created.body()).get("id").stringValue();
        String incidentPath = collectionPath() + "/" + incidentId;
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = executor.submit(() -> {
                start.await();
                return send(
                    "PATCH", incidentPath, TOKEN_A, "patch-race-a-" + UUID.randomUUID(), "\"0\"",
                    "{\"title\":\"Winner A\",\"reason\":\"Concurrent correction A\"}"
                );
            });
            Future<HttpResponse<String>> second = executor.submit(() -> {
                start.await();
                return send(
                    "PATCH", incidentPath, TOKEN_A, "patch-race-b-" + UUID.randomUUID(), "\"0\"",
                    "{\"title\":\"Winner B\",\"reason\":\"Concurrent correction B\"}"
                );
            });
            start.countDown();
            List<Integer> statuses = java.util.stream.Stream.of(
                first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)
            ).map(HttpResponse::statusCode).sorted().toList();
            assertThat(statuses).containsExactly(200, 412);
        }

        assertThat(durableCounts(UUID.fromString(incidentId)))
            .isEqualTo(new DurableCounts(2, 2, 2, idempotencyBefore + 2));
        JsonNode detail = jsonMapper.readTree(send(
            "GET", incidentPath, TOKEN_A, null, null, null
        ).body());
        assertThat(detail.get("version").intValue()).isEqualTo(1);
        assertThat(detail.get("title").stringValue()).isIn("Winner A", "Winner B");
    }

    @Test
    void eligibleOwnerRowsStayLockedUntilTheAssignmentTransactionEnds() throws Exception {
        assertOwnerEligibilityLockBlocks(
            "UPDATE organization_memberships SET status = 'suspended' "
                + "WHERE organization_id = ? AND user_id = ?"
        );
        assertOwnerEligibilityLockBlocks(
            "UPDATE platform_users SET status = 'suspended' WHERE id = ?",
            false
        );
    }

    private void assertOwnerEligibilityLockBlocks(String updateSql) throws Exception {
        assertOwnerEligibilityLockBlocks(updateSql, true);
    }

    private void assertOwnerEligibilityLockBlocks(String updateSql, boolean tenantScoped)
        throws Exception {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        try (
            Connection appConnection = java.sql.DriverManager.getConnection(
                environment.jdbcUrl(), environment.appUser(), environment.appPassword()
            );
            PreparedStatement eligibility = appConnection.prepareStatement(
                "SELECT public.opsmind_lock_eligible_incident_owner(?, ?)"
            );
            var executor = Executors.newSingleThreadExecutor()
        ) {
            appConnection.setAutoCommit(false);
            eligibility.setObject(1, TENANT_A);
            eligibility.setObject(2, ACTOR_C);
            try (var result = eligibility.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }

            Future<String> update = executor.submit(() -> {
                try (
                    Connection adminConnection = java.sql.DriverManager.getConnection(
                        environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
                    );
                    PreparedStatement statement = adminConnection.prepareStatement(updateSql)
                ) {
                    adminConnection.setAutoCommit(false);
                    try (var lockTimeout = adminConnection.createStatement()) {
                        lockTimeout.execute("SET LOCAL lock_timeout = '250ms'");
                    }
                    int parameter = 1;
                    if (tenantScoped) statement.setObject(parameter++, TENANT_A);
                    statement.setObject(parameter, ACTOR_C);
                    statement.executeUpdate();
                    return "unexpected-update";
                }
                catch (SQLException exception) {
                    return exception.getSQLState();
                }
            });
            assertThat(Objects.requireNonNull(update.get(5, TimeUnit.SECONDS)))
                .isEqualTo("55P03");
            appConnection.rollback();
        }
    }

    private void assertInactiveOwnerRejectedWithoutEffects(
        String incidentPath,
        String incidentId,
        DurableCounts expectedCounts,
        String statusSql,
        String restoredStatus
    ) throws Exception {
        JdbcTemplate admin = adminJdbc();
        boolean tenantScoped = statusSql.contains("organization_memberships");
        updateOwnerStatus(admin, statusSql, "suspended", tenantScoped);
        try {
            HttpResponse<String> response = send(
                "PATCH", incidentPath, TOKEN_A, "patch-inactive-" + UUID.randomUUID(), "\"1\"",
                "{\"ownerId\":\"" + ACTOR_C + "\",\"reason\":\"Inactive owner\"}"
            );
            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.body()).contains("incident.owner-ineligible");
            assertThat(durableCounts(UUID.fromString(incidentId))).isEqualTo(expectedCounts);
        }
        finally {
            updateOwnerStatus(admin, statusSql, restoredStatus, tenantScoped);
        }
    }

    private void updateOwnerStatus(
        JdbcTemplate admin,
        String sql,
        String status,
        boolean tenantScoped
    ) {
        if (tenantScoped) admin.update(sql, status, TENANT_A, ACTOR_C);
        else admin.update(sql, status, ACTOR_C);
    }

    private void assertTransition(
        String path,
        String ifMatch,
        String keyPrefix,
        String body,
        String expectedStatus,
        int expectedVersion
    ) throws Exception {
        HttpResponse<String> response = send(
            "POST", path, TOKEN_A, keyPrefix + UUID.randomUUID(), ifMatch, body
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("etag")).contains("\"" + expectedVersion + "\"");
        assertThat(jsonMapper.readTree(response.body()).get("status").stringValue())
            .isEqualTo(expectedStatus);
    }

    private DurableCounts durableCounts(UUID incidentId) {
        JdbcTemplate admin = adminJdbc();
        return new DurableCounts(
            count(admin, "incident_timeline_events", "incident_id", incidentId),
            count(admin, "audit_events", "resource_id", incidentId.toString()),
            count(admin, "outbox_events", "aggregate_id", incidentId),
            admin.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE organization_id = ?",
                Integer.class,
                TENANT_A
            )
        );
    }

    private int tenantIdempotencyCount() {
        return adminJdbc().queryForObject(
            "SELECT count(*) FROM idempotency_records WHERE organization_id = ?",
            Integer.class,
            TENANT_A
        );
    }

    private void assertEventLinkageAndSequence(UUID incidentId) {
        JdbcTemplate admin = adminJdbc();
        List<UUID> timelineEvents = admin.queryForList(
            "SELECT event_id FROM incident_timeline_events WHERE incident_id = ? ORDER BY incident_version",
            UUID.class,
            incidentId
        );
        List<UUID> auditEvents = admin.queryForList(
            "SELECT event_id FROM audit_events WHERE resource_id = ? ORDER BY tenant_sequence_no",
            UUID.class,
            incidentId.toString()
        );
        List<UUID> outboxEvents = admin.queryForList(
            "SELECT event_id FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence",
            UUID.class,
            incidentId
        );
        assertThat(auditEvents).containsExactlyElementsOf(timelineEvents);
        assertThat(outboxEvents).containsExactlyElementsOf(timelineEvents);
        assertThat(admin.queryForList(
            "SELECT aggregate_sequence FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence",
            Long.class,
            incidentId
        )).containsExactly(1L, 2L, 3L, 4L);
    }

    private JdbcTemplate adminJdbc() {
        PostgresIntegrationEnvironment environment = PostgresIntegrationEnvironment.fromProcess();
        return new JdbcTemplate(new DriverManagerDataSource(
            environment.jdbcUrl(), environment.adminUser(), environment.adminPassword()
        ));
    }

    private int count(JdbcTemplate jdbc, String table, String column, Object value) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
            Integer.class,
            value
        );
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
        else request.header(
                "Content-Type",
                "PATCH".equals(method) ? "application/merge-patch+json" : "application/json"
            )
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

    private String resolvedBody() {
        return "{\"targetStatus\":\"RESOLVED\",\"reason\":\"mitigation verified\","
            + "\"rootCause\":\"dependency saturation\","
            + "\"resolutionSummary\":\"capacity restored\"}";
    }

    private String closedBody() {
        return "{\"targetStatus\":\"CLOSED\",\"reason\":\"post-recovery checks passed\"}";
    }

    private String terminalTransitionBody(IncidentStatus target) {
        if (target == IncidentStatus.RESOLVED) {
            return "{\"targetStatus\":\"RESOLVED\",\"reason\":\"invalid terminal retry\","
                + "\"rootCause\":\"unchanged\",\"resolutionSummary\":\"unchanged\"}";
        }
        return "{\"targetStatus\":\"" + target + "\",\"reason\":\"invalid terminal retry\"}";
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

    private record DurableCounts(int timeline, int audit, int outbox, int idempotency) {
    }
}
