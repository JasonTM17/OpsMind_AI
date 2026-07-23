package ai.opsmind.platform.investigation.integration;

import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.NOW;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.catalog;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.context;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.evidence;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.intent;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.mapper;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.properties;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.successResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpInvestigationToolGatewayBoundaryTest {

    @Test
    void rejectsEveryIdentityProvenanceAndContentMismatch() throws Exception {
        List<Consumer<Map<String, Object>>> mutations = List.of(
            response -> response.put("execution_id", UUID.randomUUID().toString()),
            response -> response.put("request_digest", "0".repeat(64)),
            response -> response.put("manifest_version", "other@1"),
            response -> response.put("source_provenance", "other/source@1"),
            response -> response.remove("audit_event_id"),
            response -> evidence(response).put("target_identity", "prometheus:synthetic/other"),
            response -> evidence(response).put("content_digest", "0".repeat(64)),
            response -> response.put("unexpected", true)
        );
        for (Consumer<Map<String, Object>> mutation : mutations) assertInvalid(mutation);
    }

    @Test
    void rejectsTruncatedArtifactAndOverBudgetLists() throws Exception {
        assertInvalid(response -> {
            response.put("truncated", true);
            evidence(response).put("truncated", true);
            evidence(response).put("artifact_reference", "artifact://evidence/1");
        });
        assertInvalid(response -> evidence(response).put(
            "content", Map.of("points", java.util.Collections.nCopies(11, Map.of("value", 1)))
        ));
    }

    @Test
    void oversizedResponseIsCancelledAndNeverRetried() throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange ->
            ToolGatewayHttpTestServer.respond(
                exchange, 200, "application/json", "x".repeat(1_025)
            ))) {
            assertThatThrownBy(() -> client(server, objectMapper, catalog, 1_024)
                .execute(intent(catalog), context()))
                .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                    assertThat(exception.code()).isEqualTo("dependency.tool-gateway-invalid-response"));
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void rejectsUnexpectedSuccessMediaTypeWithoutRetry() throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            ToolGatewayHttpTestServer.respond(
                exchange, 200, "text/plain",
                objectMapper.writeValueAsBytes(successResponse(objectMapper, requestBody, false))
            );
        })) {
            assertThatThrownBy(() -> client(server, objectMapper, catalog, 65_536)
                .execute(intent(catalog), context()))
                .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                    assertThat(exception.code()).isEqualTo("dependency.tool-gateway-invalid-response"));
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void ambiguousTransportFailureHasNoClientRetryPath() {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        AtomicInteger sends = new AtomicInteger();
        ToolGatewayHttpExchange exchange = (request, timeout) -> {
            sends.incrementAndGet();
            throw new PlatformProblemException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "dependency.tool-gateway-unavailable",
                "The Tool Gateway is temporarily unavailable."
            );
        };
        HttpInvestigationToolGatewayClient client = new HttpInvestigationToolGatewayClient(
            properties(java.net.URI.create(
                "http://127.0.0.1:8081/internal/v1/tools/execute"
            ), 65_536), () -> "workload.jwt.token", ignored -> "capability.jwt.token",
            catalog, new EvidenceContentCanonicalizer(objectMapper), objectMapper,
            Duration.ofMinutes(2), Clock.fixed(NOW, ZoneOffset.UTC), exchange
        );

        assertThatThrownBy(() -> client.execute(intent(catalog), context()))
            .isInstanceOf(PlatformProblemException.class);
        assertThat(sends).hasValue(1);
    }

    private void assertInvalid(Consumer<Map<String, Object>> mutation) throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Map<String, Object> response = successResponse(objectMapper, requestBody, false);
            mutation.accept(response);
            ToolGatewayHttpTestServer.respond(
                exchange, 200, "application/json", objectMapper.writeValueAsBytes(response)
            );
        })) {
            assertThatThrownBy(() -> client(server, objectMapper, catalog, 65_536)
                .execute(intent(catalog), context()))
                .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                    assertThat(exception.code()).isEqualTo("dependency.tool-gateway-invalid-response"));
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    private HttpInvestigationToolGatewayClient client(
        ToolGatewayHttpTestServer server,
        ObjectMapper objectMapper,
        InvestigationToolIntentCatalog catalog,
        int responseLimit
    ) {
        return new HttpInvestigationToolGatewayClient(
            properties(server.endpoint(), responseLimit), () -> "workload.jwt.token",
            ignored -> "capability.jwt.token", catalog,
            new EvidenceContentCanonicalizer(objectMapper), objectMapper, Duration.ofMinutes(2),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
