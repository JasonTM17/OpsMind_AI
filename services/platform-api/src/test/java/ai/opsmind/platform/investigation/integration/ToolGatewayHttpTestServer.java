package ai.opsmind.platform.investigation.integration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class ToolGatewayHttpTestServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    ToolGatewayHttpTestServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/tools/execute", exchange -> {
            requests.incrementAndGet();
            try {
                handler.handle(exchange);
            }
            catch (Exception exception) {
                exchange.close();
            }
        });
        server.start();
    }

    URI endpoint() {
        return URI.create(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/v1/tools/execute"
        );
    }

    int requestCount() {
        return requests.get();
    }

    static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
        throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    static void respond(HttpExchange exchange, int status, String contentType, String body)
        throws IOException {
        respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    @FunctionalInterface
    interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
