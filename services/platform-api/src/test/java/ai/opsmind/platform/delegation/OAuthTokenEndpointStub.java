package ai.opsmind.platform.delegation;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class OAuthTokenEndpointStub implements AutoCloseable {

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<ResponseSpec> response = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpServer server;

    OAuthTokenEndpointStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/oauth/token", this::handle);
        server.start();
    }

    void respond(int status, String contentType, String body, long delayMillis) {
        response.set(new ResponseSpec(status, contentType, body, delayMillis));
    }

    void respondJson(int status, String body) {
        respond(status, "application/json", body, 0);
    }

    int requestCount() {
        return requests.get();
    }

    String authorization() {
        return authorization.get();
    }

    String requestBody() {
        return requestBody.get();
    }

    URI issuer() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/issuer");
    }

    URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.US_ASCII));
        ResponseSpec spec = response.get();
        pause(spec.delayMillis());
        byte[] bytes = spec.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", spec.contentType());
        try {
            exchange.sendResponseHeaders(spec.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        finally {
            exchange.close();
        }
    }

    private void pause(long delayMillis) {
        if (delayMillis <= 0) return;
        try {
            Thread.sleep(delayMillis);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record ResponseSpec(int status, String contentType, String body, long delayMillis) { }
}
