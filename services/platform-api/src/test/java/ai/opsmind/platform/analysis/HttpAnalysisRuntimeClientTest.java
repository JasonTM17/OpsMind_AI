package ai.opsmind.platform.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ai.opsmind.platform.common.api.PlatformProblemException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class HttpAnalysisRuntimeClientTest {

    private static final UUID RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void sendsExactBytesAndRawCapabilityThenValidatesResponse() throws Exception {
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedCapability = new AtomicReference<>();
        AtomicReference<String> receivedCorrelation = new AtomicReference<>();
        try (TestServer server = new TestServer(exchange -> {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            receivedCapability.set(exchange.getRequestHeaders().getFirst(
                "X-OpsMind-Delegated-Capability"
            ));
            receivedCorrelation.set(exchange.getRequestHeaders().getFirst("X-Correlation-ID"));
            exchange.getResponseHeaders().set("X-Correlation-ID", "trace_contract_001");
            respond(exchange, 200, "application/json", validResponse());
        })) {
            HttpAnalysisRuntimeClient client = client(server.endpoint(), 65_536);
            PreparedAnalysisRequest request = request("{\"exact\":true}".getBytes(StandardCharsets.UTF_8));

            AnalysisRuntimeResponse response = client.analyze(
                request,
                "header.payload.signature",
                "trace_contract_001"
            );

            assertThat(response.runId()).isEqualTo(RUN_ID);
            assertThat(receivedBody.get()).isEqualTo(request.body());
            assertThat(receivedCapability.get()).isEqualTo("header.payload.signature");
            assertThat(receivedCorrelation.get()).isEqualTo("trace_contract_001");
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void failsClosedOnUnavailableOrOversizedResponseWithoutRetry() throws Exception {
        try (TestServer unavailable = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("X-Correlation-ID", "trace_contract_002");
            respond(exchange, 503, "application/problem+json", problem(
                503, "provider.unavailable", "trace_contract_002"
            ));
        })) {
            assertThatThrownBy(() -> client(unavailable.endpoint(), 1_024).analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_002"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("dependency.ai-runtime-unavailable");
                assertThat(exception.status().value()).isEqualTo(503);
            });
            assertThat(unavailable.requestCount()).isEqualTo(1);
        }

        try (TestServer oversized = new TestServer(exchange -> respond(
            exchange,
            200,
            "application/json",
            "x".repeat(1_025)
        ))) {
            assertThatThrownBy(() -> client(oversized.endpoint(), 1_024).analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_003"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("dependency.ai-runtime-invalid-response"));
            assertThat(oversized.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void rejectsUnknownResponseFieldsAndMissingCorrelationWithoutRetry() throws Exception {
        try (TestServer unknownField = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("X-Correlation-ID", "trace_contract_004");
            String response = validResponse();
            respond(
                exchange,
                200,
                "application/json",
                response.substring(0, response.length() - 1) + ",\"unexpected\":true}"
            );
        })) {
            assertThatThrownBy(() -> client(unknownField.endpoint(), 65_536).analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_004"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("dependency.ai-runtime-invalid-response"));
            assertThat(unknownField.requestCount()).isEqualTo(1);
        }

        try (TestServer missingCorrelation = new TestServer(exchange ->
            respond(exchange, 200, "application/json", validResponse()))) {
            assertThatThrownBy(() -> client(missingCorrelation.endpoint(), 65_536).analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_005"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception ->
                assertThat(exception.code()).isEqualTo("dependency.ai-runtime-invalid-response"));
            assertThat(missingCorrelation.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void enforcesDeadlineWhileResponseBodyIsTrickled() throws Exception {
        try (TestServer trickle = new TestServer(exchange -> {
            byte[] body = validResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("X-Correlation-ID", "trace_contract_006");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body, 0, 1);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(2_000);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        })) {
            HttpAnalysisRuntimeClient runtimeClient = client(
                trickle.endpoint(), 65_536, Duration.ofMillis(200)
            );
            Instant startedAt = Instant.now();
            assertThatThrownBy(() -> runtimeClient.analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_006"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("dependency.ai-runtime-timeout");
                assertThat(exception.status().value()).isEqualTo(504);
            });
            assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(1));
            assertThat(trickle.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void preservesPolicyDenialButRejectsDelegationFailureAsInvalidDependencyResponse()
        throws Exception {
        try (TestServer policyDenied = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("X-Correlation-ID", "trace_contract_007");
            respond(exchange, 403, "application/problem+json", problem(
                403, "egress.denied", "trace_contract_007"
            ));
        })) {
            assertThatThrownBy(() -> client(policyDenied.endpoint(), 65_536).analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_007"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("analysis.egress-denied");
                assertThat(exception.status().value()).isEqualTo(403);
            });
        }

        try (TestServer delegationDenied = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("X-Correlation-ID", "trace_contract_008");
            respond(exchange, 403, "application/problem+json", problem(
                403, "delegation.invalid", "trace_contract_008"
            ));
        })) {
            assertThatThrownBy(() -> client(delegationDenied.endpoint(), 65_536).analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_008"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("dependency.ai-runtime-invalid-response");
                assertThat(exception.status().value()).isEqualTo(502);
            });
        }
    }

    @Test
    void preservesPermanentProviderFailureTaxonomyAsBadGateway() throws Exception {
        try (TestServer providerRejected = new TestServer(exchange -> {
            exchange.getResponseHeaders().set("X-Correlation-ID", "trace_contract_009");
            respond(exchange, 502, "application/problem+json", problem(
                502, "provider.unauthorized", "trace_contract_009"
            ));
        })) {
            assertThatThrownBy(() -> client(providerRejected.endpoint(), 65_536).analyze(
                request("{}".getBytes(StandardCharsets.UTF_8)),
                "header.payload.signature",
                "trace_contract_009"
            )).isInstanceOfSatisfying(PlatformProblemException.class, exception -> {
                assertThat(exception.code()).isEqualTo("dependency.ai-runtime-unauthorized");
                assertThat(exception.status().value()).isEqualTo(502);
            });
        }
    }

    private HttpAnalysisRuntimeClient client(URI endpoint, int responseLimit) {
        return client(endpoint, responseLimit, Duration.ofSeconds(10));
    }

    private HttpAnalysisRuntimeClient client(
        URI endpoint,
        int responseLimit,
        Duration requestTimeout
    ) {
        AnalysisRuntimeClientProperties properties = new AnalysisRuntimeClientProperties(
            true,
            endpoint,
            false,
            Duration.ofSeconds(1),
            requestTimeout,
            responseLimit
        );
        return new HttpAnalysisRuntimeClient(
            properties,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
            JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
        );
    }

    private PreparedAnalysisRequest request(byte[] body) {
        return new PreparedAnalysisRequest(
            body,
            "sha256:" + "a".repeat(64),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            RUN_ID,
            "prompt-incident-v1",
            "incident_investigation",
            Set.of("redacted_metrics"),
            Instant.now().plusSeconds(30)
        );
    }

    private static String validResponse() {
        return "{\"status\":\"abstain\",\"run_id\":\"" + RUN_ID + "\","
            + "\"model_id\":\"deepseek-v4-flash\",\"prompt_version\":\"prompt-incident-v1\","
            + "\"schema_version\":\"analysis-v1\",\"hypotheses\":[],"
            + "\"counter_evidence\":[],\"missing_evidence\":[],\"citations\":[],"
            + "\"confidence\":0.0,\"usage\":{\"prompt_tokens\":1,"
            + "\"completion_tokens\":0,\"total_tokens\":1},"
            + "\"cost_estimate\":{\"currency\":\"USD\",\"amount\":0.0},"
            + "\"requested_tool_calls\":[]}";
    }

    private static String problem(int status, String code, String correlationId) {
        return "{\"type\":\"about:blank\",\"title\":\"Analysis request rejected\","
            + "\"status\":" + status + ",\"code\":\"" + code + "\","
            + "\"correlation_id\":\"" + correlationId + "\"}";
    }

    private static void respond(
        HttpExchange exchange,
        int status,
        String contentType,
        String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();

        private TestServer(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/analysis", exchange -> {
                requests.incrementAndGet();
                handler.handle(exchange);
            });
            server.start();
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/analysis");
        }

        private int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
