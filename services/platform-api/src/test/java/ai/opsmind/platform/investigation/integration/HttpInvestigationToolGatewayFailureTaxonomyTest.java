package ai.opsmind.platform.investigation.integration;

import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.NOW;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.catalog;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.context;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.intent;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.mapper;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.properties;
import static ai.opsmind.platform.investigation.integration.ToolGatewayClientTestSupport.successResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;

import ai.opsmind.platform.common.api.PlatformProblemException;
import ai.opsmind.platform.evidence.EvidenceContentCanonicalizer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class HttpInvestigationToolGatewayFailureTaxonomyTest {

    @Test
    void mapsDocumentedDecisionFailureWithoutExposingGatewayBody() throws Exception {
        assertDecisionFailure(503, "FAILED", "connector.cancelled",
            "dependency.tool-gateway-unavailable", 503);
        assertDecisionFailure(504, "FAILED", "connector.timeout",
            "dependency.tool-gateway-timeout", 504);
        assertDecisionFailure(409, "DENIED", "execution.in-progress",
            "dependency.tool-gateway-conflict", 409);
        assertDecisionFailure(400, "DENIED", "arguments.invalid",
            "dependency.tool-gateway-request-rejected", 502);
    }

    @Test
    void acceptsOnlyExactSecurityProblemContract() throws Exception {
        assertProblemFailure(
            401,
            "{\"type\":\"urn:opsmind:problem:caller.unauthenticated\","
                + "\"title\":\"authentication required\",\"status\":401,"
                + "\"code\":\"caller.unauthenticated\","
                + "\"instance\":\"urn:opsmind:error:11111111-1111-4111-8111-111111111111\"}",
            "dependency.tool-gateway-workload-unauthenticated"
        );
        assertProblemFailure(
            403,
            "{\"type\":\"urn:opsmind:problem:capability.invalid\","
                + "\"title\":\"denied\",\"status\":403,\"code\":\"capability.invalid\","
                + "\"instance\":\"urn:opsmind:error:11111111-1111-4111-8111-111111111111\"}",
            "dependency.tool-gateway-request-rejected"
        );
        assertProblemFailure(
            403,
            "{\"type\":\"urn:opsmind:problem:unknown\",\"title\":\"denied\"," +
                "\"status\":403,\"code\":\"unknown\",\"instance\":\"urn:opsmind:error:x\"}",
            "dependency.tool-gateway-invalid-response"
        );
    }

    private void assertDecisionFailure(
        int status,
        String outcome,
        String denialCode,
        String expectedCode,
        int expectedStatus
    ) throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            Map<String, Object> response = successResponse(objectMapper, requestBody, false);
            response.put("status", outcome);
            response.put("evidence", new ArrayList<>());
            response.put("denial_code", denialCode);
            response.put("redaction_count", 0);
            ToolGatewayHttpTestServer.respond(
                exchange, status, "application/json", objectMapper.writeValueAsBytes(response)
            );
        })) {
            assertThatThrownBy(() -> client(server, objectMapper, catalog)
                .execute(intent(catalog), context()))
                .isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(expectedCode);
                    assertThat(exception.status().value()).isEqualTo(expectedStatus);
                });
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    private void assertProblemFailure(int status, String body, String expectedCode) throws Exception {
        ObjectMapper objectMapper = mapper();
        InvestigationToolIntentCatalog catalog = catalog(objectMapper);
        try (ToolGatewayHttpTestServer server = new ToolGatewayHttpTestServer(exchange ->
            ToolGatewayHttpTestServer.respond(exchange, status, "application/problem+json", body))) {
            assertThatThrownBy(() -> client(server, objectMapper, catalog)
                .execute(intent(catalog), context()))
                .isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                    assertThat(exception.code()).isEqualTo(expectedCode));
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    private HttpInvestigationToolGatewayClient client(
        ToolGatewayHttpTestServer server,
        ObjectMapper objectMapper,
        InvestigationToolIntentCatalog catalog
    ) {
        return new HttpInvestigationToolGatewayClient(
            properties(server.endpoint(), 65_536), () -> "workload.jwt.token",
            ignored -> "capability.jwt.token", catalog,
            new EvidenceContentCanonicalizer(objectMapper), objectMapper, Duration.ofMinutes(2),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
