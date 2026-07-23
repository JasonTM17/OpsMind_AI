package ai.opsmind.toolgateway.connectors.prometheus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import ai.opsmind.toolgateway.domain.DenialCode;
import ai.opsmind.toolgateway.domain.ToolDeniedException;

import org.junit.jupiter.api.Test;

class PrometheusHttpExchangeTest {

    @Test
    void acceptsOnlyBoundedJsonSuccess() throws Exception {
        byte[] response = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
        try (TestServer server = new TestServer(exchange ->
            respond(exchange, 200, "application/json; charset=utf-8", response))) {
            PrometheusHttpExchange client = client(server.endpoint(), 1_024);

            assertThat(client.getJson(server.endpoint(), Duration.ofSeconds(1)))
                .isEqualTo(response);
            assertThat(server.requests()).isEqualTo(1);
        }
    }

    @Test
    void neverFollowsRedirectAndNeverRetriesFailure() throws Exception {
        try (TestServer redirectTarget = new TestServer(exchange ->
            respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));
             TestServer redirect = new TestServer(exchange -> {
                 exchange.getResponseHeaders().set("Location", redirectTarget.endpoint().toString());
                 respond(exchange, 302, "application/json", new byte[0]);
             });
             TestServer unavailable = new TestServer(exchange ->
                 respond(exchange, 503, "application/json", "{}".getBytes(StandardCharsets.UTF_8)))) {
            PrometheusHttpExchange redirectClient = client(redirect.endpoint(), 1_024);
            PrometheusHttpExchange unavailableClient = client(unavailable.endpoint(), 1_024);

            assertConnectorFailure(() ->
                redirectClient.getJson(redirect.endpoint(), Duration.ofSeconds(1))
            );
            assertConnectorFailure(() ->
                unavailableClient.getJson(unavailable.endpoint(), Duration.ofSeconds(1))
            );
            assertThat(redirect.requests()).isEqualTo(1);
            assertThat(redirectTarget.requests()).isZero();
            assertThat(unavailable.requests()).isEqualTo(1);
        }
    }

    @Test
    void cancelsOversizeAndTimedOutResponses() throws Exception {
        try (TestServer oversized = new TestServer(exchange ->
                 respond(exchange, 200, "application/json", new byte[2_048]));
             TestServer slow = new TestServer(exchange -> {
                 try {
                     Thread.sleep(250);
                     respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                 }
                 catch (InterruptedException exception) {
                     Thread.currentThread().interrupt();
                 }
                 catch (IOException ignored) {
                     // Client cancellation closes the exchange before the delayed response.
                 }
             })) {
            assertThatThrownBy(() -> client(oversized.endpoint(), 128)
                .getJson(oversized.endpoint(), Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                    assertThat(exception.code()).isEqualTo(DenialCode.RESULT_OVERSIZE));
            assertThatThrownBy(() -> client(slow.endpoint(), 1_024)
                .getJson(slow.endpoint(), Duration.ofMillis(50)))
                .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                    assertThat(exception.code()).isEqualTo(DenialCode.CONNECTOR_TIMEOUT));
            assertThat(oversized.requests()).isEqualTo(1);
            assertThat(slow.requests()).isEqualTo(1);
        }
    }

    private PrometheusHttpExchange client(URI endpoint, int maximumBytes) {
        PrometheusConnectorProperties properties = new PrometheusConnectorProperties(
            true,
            URI.create("http://127.0.0.1:" + endpoint.getPort()),
            true,
            Duration.ofMillis(250),
            Duration.ofSeconds(1),
            65_536,
            1,
            10,
            Duration.ofMinutes(2),
            Duration.ofMinutes(1)
        );
        return new PrometheusHttpExchange(
            PrometheusHttpClientFactory.create(properties),
            maximumBytes
        );
    }

    private void assertConnectorFailure(ThrowingCall call) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ToolDeniedException.class, exception ->
                assertThat(exception.code()).isEqualTo(DenialCode.CONNECTOR_FAILED));
    }

    private static void respond(
        HttpExchange exchange,
        int status,
        String contentType,
        byte[] body
    ) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor;
        private final AtomicInteger requests = new AtomicInteger();

        private TestServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                handler.handle(exchange);
            });
            server.start();
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        private int requests() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
